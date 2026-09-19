//! 剪贴板同步状态机（纯文本）。
//!
//! ## 算法（重构后，取代旧的「单哈希 + 自写标志」）
//!
//! 全局状态收敛为**一条记录** `SYNC_RECORD = { device_id, clock, text, updated_at }`
//! （方案一「服务端一条记录」），去重收敛为**一次字段比对**：
//!
//! 1. **非文本源头过滤**：剪贴板带 `CF_HDROP`（复制文件）时一律不同步，
//!    排除「复制文件时同时携带文件名文本」的歧义场景；无 `CF_UNICODETEXT`
//!    （复制图片等）时 `get_clipboard_text` 天然返回 None，同样不同步。
//! 2. **变化判定**：读到的文本与记录中的 `text` 不同 → 是新内容，推送；
//!    相同 → 仅当「真实复制事件」触发且距上次登记超过回声窗口
//!    （[`ECHO_SUPPRESS_WINDOW`]）才作为「用户重申」放行 —— 覆盖
//!    「Win+V 清空后重推旧内容」「重复复制同内容」两类旧痛点；
//!    轮询/亮屏脉冲等观察型触发对未变化内容一律静默（不重推旧内容打扰对端）。
//! 3. **回声抑制**：收到远端文本写入本机剪贴板前，记录先行更新为该文本
//!    （[`begin_remote_push`] + [`set_clipboard_text`]），随后剪贴板变化事件
//!    读到相同文本且新鲜，命中记录直接跳过 —— 不需要旧版 `IS_UPDATING_SELF`
//!    全局标志，也不会像旧版 60 秒哈希窗口那样把合法的重复复制吞掉。
//! 4. **时钟裁决**：`clock` 由本机（hub）统一分配、单调递增，随消息下发；
//!    手机端据此丢弃重复投递（SSE + HTTP 兜底双通道各送一次同一条内容）。
//!    时钟持久化进 config.json，电脑端重启后不回退（手机端无时钟状态，
//!    重启零成本）。响应里回带 `lamport_clock`，手机端追赶后可丢弃在途旧事件。
//!
//! 旧版问题对照：`LAST_TEXT_HASH`（单哈希）导致「PC 刚同步入的内容用户再复制一次
//! 被吞」；手机端 60 秒 `recentHashes` 窗口导致「清空/重复制同内容被吞」；
//! `IS_UPDATING_SELF` 与轮询线程存在竞态 —— 三者均被上述记录模型取代。

use std::ffi::OsStr;
use std::os::windows::ffi::OsStrExt;
use std::ptr::null_mut;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::Instant;
use windows_sys::Win32::Foundation::*;
use windows_sys::Win32::System::DataExchange::*;
use windows_sys::Win32::System::Memory::*;

const CF_UNICODETEXT: u32 = 13;
/// 剪贴板文件列表格式（复制文件时出现）。出现即判定为非文本场景，不同步。
const CF_HDROP: u32 = 15;

/// 同文本「重申」放行窗口（毫秒）。
///
/// 用户重申同一段内容与「我们刚写入的内容触发的回声/重复触发」在内容上不可区分，
/// 唯一可观测差异是时间：回声与重复触发发生在写入后的毫秒~2 秒内（监听器 + 2 秒轮询
/// 兜底各一次），而用户重申是主动事件。窗口只需盖住触发链爆发期，取 3 秒。
/// 取代旧版手机端 60 秒哈希窗口 —— 后者会把几分钟后合法的重复复制静默吞掉。
const ECHO_SUPPRESS_WINDOW_MS: u64 = 3_000;

/// 当前已知的剪贴板同步记录（方案一：服务端全局唯一一条）。
///
/// `None` 表示启动后尚未观察到任何内容；首条内容必然判定为「新」。
static SYNC_RECORD: Mutex<Option<SyncRecord>> = Mutex::new(None);

/// 本机（hub）统一分配的同步时钟，随消息下发。
static SYNC_CLOCK: AtomicU64 = AtomicU64::new(0);

struct SyncRecord {
    /// 该内容的来源设备（本机推送为自身 device_id，远端来文为 sender_id）。
    /// 当前判定逻辑不依赖该字段，保留用于日志排查与方案一对齐。
    #[allow(dead_code)]
    device_id: String,
    #[allow(dead_code)]
    clock: u64,
    text: String,
    updated_at: Instant,
}

/// 启动时用持久化的时钟初始化（main 在加载 config 后调用一次）。
pub fn init_sync_state(persisted_clock: u64) {
    SYNC_CLOCK.store(persisted_clock, Ordering::SeqCst);
}

/// 分配下一个时钟并持久化。
///
/// 时钟回退会让手机端把新推送判为旧事件丢弃，因此每次递增都落盘。
/// 复制是人为低频事件，每次一次 JSON 文件重写的开销可忽略。
fn next_clock() -> u64 {
    let clock = SYNC_CLOCK.load(Ordering::SeqCst) + 1;
    SYNC_CLOCK.store(clock, Ordering::SeqCst);
    crate::config::update_persisted_clock(clock);
    clock
}

pub fn set_clipboard_text(text: &str) -> bool {
    let wide: Vec<u16> = OsStr::new(text).encode_wide().chain(Some(0)).collect();
    let bytes_len = wide.len() * 2;

    let mut opened = false;
    for retry in 0..4 {
        unsafe {
            if OpenClipboard(null_mut()) != 0 {
                opened = true;
                break;
            }
        }
        std::thread::sleep(std::time::Duration::from_millis(30 + retry * 20));
    }

    if !opened {
        return false;
    }

    let written = unsafe {
        EmptyClipboard();

        let h_mem = GlobalAlloc(GMEM_MOVEABLE, bytes_len);
        if h_mem.is_null() {
            CloseClipboard();
            false
        } else {
            let ptr = GlobalLock(h_mem) as *mut u16;
            if !ptr.is_null() {
                std::ptr::copy_nonoverlapping(wide.as_ptr(), ptr, wide.len());
                GlobalUnlock(h_mem);
                SetClipboardData(CF_UNICODETEXT, h_mem as HANDLE);
                true
            } else {
                // 锁失败同样必须关剪贴板，否则句柄泄漏且后续打开全部阻塞
                CloseClipboard();
                false
            }
        }
    };

    // 写入成功后刷新记录（只动 text/updated_at，不碰时钟/来源）。
    // 这一步是回声抑制的关键：随后的 WM_CLIPBOARDUPDATE 读到相同文本且
    // 「新鲜」，命中记录被跳过，不会把刚写入的内容再推回网络。
    if written {
        let mut record = SYNC_RECORD.lock().unwrap();
        match record.as_mut() {
            Some(r) => {
                r.text = text.to_string();
                r.updated_at = Instant::now();
            }
            None => {
                // 程序化写入先于任何观察（理论罕见），补一条自身来源的记录
                *record = Some(SyncRecord {
                    device_id: String::new(),
                    clock: SYNC_CLOCK.load(Ordering::SeqCst),
                    text: text.to_string(),
                    updated_at: Instant::now(),
                });
            }
        }
    }

    written
}

pub fn get_clipboard_text() -> Option<String> {
    let mut opened = false;
    for retry in 0..5 {
        unsafe {
            if OpenClipboard(null_mut()) != 0 {
                opened = true;
                break;
            }
        }
        std::thread::sleep(std::time::Duration::from_millis(10 + retry * 10));
    }

    if !opened {
        return None;
    }

    unsafe {
        let h_data = GetClipboardData(CF_UNICODETEXT);
        if h_data.is_null() {
            CloseClipboard();
            return None;
        }

        let max_bytes = GlobalSize(h_data as HGLOBAL);
        if max_bytes == 0 {
            CloseClipboard();
            return None;
        }
        let max_words = (max_bytes as usize) / 2;

        let ptr = GlobalLock(h_data as HGLOBAL) as *const u16;
        if ptr.is_null() {
            CloseClipboard();
            return None;
        }

        let mut len = 0;
        while len < max_words && *ptr.add(len) != 0 {
            len += 1;
        }

        let slice = std::slice::from_raw_parts(ptr, len);
        let result = String::from_utf16_lossy(slice);

        GlobalUnlock(h_data as HGLOBAL);
        CloseClipboard();
        Some(result)
    }
}

/// 观察一次剪贴板变化，返回 `(文本, 时钟)` 表示需要向网络推送。
///
/// @param event_triggered true = 真实复制事件（WM_CLIPBOARDUPDATE 监听器）；
///                        false = 轮询兜底等观察型触发。
/// 观察型触发只补漏（内容确实变了才推），不会因窗口过期把未变化的旧内容重推给对端。
pub fn observe_clipboard_change(event_triggered: bool) -> Option<(String, u64)> {
    unsafe {
        // 非文本源头过滤：复制文件时部分应用会同时放置文件名文本，
        // 带 CF_HDROP 即视为文件场景，整条不同步（图片等无文本格式时
        // get_clipboard_text 返回 None，同样在此被过滤）
        if IsClipboardFormatAvailable(CF_HDROP) != 0 {
            return None;
        }
    }

    let text = get_clipboard_text()?;
    if text.trim().is_empty() {
        return None;
    }

    let mut record = SYNC_RECORD.lock().unwrap();
    if let Some(r) = record.as_ref() {
        if r.text == text {
            // 内容与已知记录相同：
            let elapsed_ms = r.updated_at.elapsed().as_millis() as u64;
            let reassert = event_triggered && elapsed_ms >= ECHO_SUPPRESS_WINDOW_MS;
            if !reassert {
                return None;
            }
            // 用户在清空等操作后主动重申同一段内容：放行，走全新时钟，
            // 对端按新事件处理（这正是旧版哈希窗口吞掉的场景）
        }
    }

    let clock = next_clock();
    *record = Some(SyncRecord {
        device_id: String::new(),
        clock,
        text: text.clone(),
        updated_at: Instant::now(),
    });
    Some((text, clock))
}

/// 接收远端文本（手机 → 电脑）：分配时钟、登记记录，返回分配的时钟。
///
/// hub 对来文按到达顺序无条件受理（到达序即用户意图序），登记先行于写入，
/// 随后 [`set_clipboard_text`] 触发的本地变化事件会命中记录被跳过。
pub fn begin_remote_push(sender_id: &str, text: &str) -> u64 {
    let clock = next_clock();
    *SYNC_RECORD.lock().unwrap() = Some(SyncRecord {
        device_id: sender_id.to_string(),
        clock,
        text: text.to_string(),
        updated_at: Instant::now(),
    });
    clock
}

/// 手动发送当前剪贴板（托盘「手动发送」菜单）：用户显式意图，绕过全部去重。
pub fn begin_local_manual_push(text: &str) -> u64 {
    let clock = next_clock();
    *SYNC_RECORD.lock().unwrap() = Some(SyncRecord {
        device_id: String::new(),
        clock,
        text: text.to_string(),
        updated_at: Instant::now(),
    });
    clock
}
