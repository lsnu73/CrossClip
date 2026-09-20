//! 文件传输浮窗 —— 电脑端呈现传输状态的唯一方式。
//!
//! ## 为什么必须自绘
//! Windows 的托盘气泡通知（`NOTIFYICONDATAW` 的 `NIF_INFO`）**只能显示标题 + 正文两行文本，
//! 没有任何进度条能力**。传输又是个持续几十秒的过程，用户需要看到「还在动」。
//! 与其「系统气泡 + 自绘浮窗」两套并存互相重复，这里统一为一套浮窗：
//! 进行中显示进度条，结束显示终态并在几秒后自动淡出。
//!
//! ## 线程模型
//! 传输跑在后台线程，窗口却必须由创建它的线程（主线程，也就是消息循环所在线程）来更新。
//! 所以这里的做法是：后台线程只写共享状态并 `PostMessage` 一个通知，
//! 真正的窗口创建 / 重绘 / 销毁都发生在主线程的 [`on_ui_tick`] 里。
//!
//! ## 不变量
//! - 浮窗**永远不可以抢占焦点**（`WS_EX_NOACTIVATE`）：用户在传文件时通常正在做别的事，
//!   弹窗抢焦点是极其讨厌的行为。
//! - 所有失败路径都必须静默降级：浮窗画不出来无所谓，绝不能影响传输本身。
//! - 终态必须自动消失：不能让「发送完成」这种一次性信息永久占着屏幕。
//! - 点击终态浮窗 = 打开接收文件的所在目录（走系统默认的文件夹处理程序，
//!   见 [`open_saved_dir`]）；进行中 / 失败态的点击无动作。

use std::ptr::null_mut;
use std::sync::atomic::{AtomicBool, AtomicIsize, Ordering};
use std::sync::Mutex;

use windows_sys::Win32::Foundation::*;
use windows_sys::Win32::Graphics::Gdi::*;
use windows_sys::Win32::UI::Shell::ShellExecuteW;
use windows_sys::Win32::UI::WindowsAndMessaging::*;

/// 主线程收到这个消息后刷新浮窗（由工作线程投递）
pub const WM_PROGRESS_TICK: u32 = WM_USER + 2;

const WINDOW_CLASS: &str = "CrossClipToastWnd";
const WINDOW_TITLE: &str = "CrossClip 文件传输";

const WIN_W: i32 = 380;
const WIN_H: i32 = 104;

/// 终态浮窗的停留时长（毫秒），到点自动关闭
const AUTO_CLOSE_MS: u32 = 3_500;
/// 自动关闭定时器 ID
const TIMER_AUTO_CLOSE: usize = 1;

/// 主窗口句柄（工作线程用它投递消息）；0 表示窗口尚未就绪
static MAIN_HWND: AtomicIsize = AtomicIsize::new(0);
/// 浮窗自身的句柄（仅在主线程读写）
static UI_HWND: AtomicIsize = AtomicIsize::new(0);
/// 浮窗当前是否已显示
static VISIBLE: AtomicBool = AtomicBool::new(false);
/// 窗口类只允许注册一次
static CLASS_REGISTERED: AtomicBool = AtomicBool::new(false);
/// 终态自动关闭定时器是否已启动
static TIMER_ARMED: AtomicBool = AtomicBool::new(false);

/// 当前要展示的快照；`None` 表示应当关闭浮窗
static STATE: Mutex<Option<ToastState>> = Mutex::new(None);

/// 当前终态浮窗对应的「可打开」路径（接收完成的落盘文件）。
///
/// 与 `STATE` 同生命周期：每次 show/finish 都会重置，保证点击时打开的目录
/// 一定对应用户眼前这条浮窗，而不是上一次传输的陈旧路径。
static OPEN_PATH: Mutex<Option<String>> = Mutex::new(None);

/// 浮窗处于哪个阶段
#[derive(Clone, Copy, PartialEq, Eq)]
enum Phase {
    /// 传输进行中：显示进度条与百分比
    Progress,
    /// 成功结束
    Done,
    /// 失败结束
    Failed,
}

#[derive(Clone)]
struct ToastState {
    title: String,
    detail: String,
    done: u64,
    total: u64,
    phase: Phase,
}

/// 记录主窗口句柄（程序启动、托盘窗口建好后调用一次）。
///
/// 有了它，传输线程与 HTTP 处理线程都能在不持有窗口句柄的前提下刷新浮窗。
pub fn init(main_hwnd: HWND) {
    MAIN_HWND.store(main_hwnd as isize, Ordering::SeqCst);
}

/// 进入「进行中」状态（`total` 为 0 时进度条保持空白）
pub fn show_progress(title: &str, detail: &str, total: u64) {
    // 进行中没有「打开目录」语义，顺带清掉上一轮可能残留的路径
    *OPEN_PATH.lock().unwrap() = None;
    set_state(ToastState {
        title: title.to_string(),
        detail: detail.to_string(),
        done: 0,
        total,
        phase: Phase::Progress,
    });
}

/// 刷新传输进度
pub fn update_progress(done: u64, total: u64) {
    {
        let mut guard = STATE.lock().unwrap();
        // 已进入终态就不再接受迟到的进度刷新，否则会把「已完成」覆盖回百分比
        if let Some(state) = guard.as_mut() {
            if state.phase != Phase::Progress {
                return;
            }
            state.done = done;
            if total > 0 {
                state.total = total;
            }
        }
    }
    poke_main_thread();
}

/// 进入「终态」：展示结果，并在 [`AUTO_CLOSE_MS`] 后自动关闭。
///
/// `open_path` 不为空时，终态浮窗可点击跳转到该路径所在目录
/// （接收完成场景传入落盘文件路径；发送/失败场景传 `None`，点击无动作）。
pub fn finish(title: &str, detail: &str, success: bool) {
    finish_inner(title, detail, success, None);
}

/// 同 [`finish`]，但携带「点击打开所在目录」的目标路径
pub fn finish_openable(title: &str, detail: &str, success: bool, open_path: Option<&str>) {
    finish_inner(title, detail, success, open_path);
}

fn finish_inner(title: &str, detail: &str, success: bool, open_path: Option<&str>) {
    *OPEN_PATH.lock().unwrap() = open_path.map(|p| p.to_string());
    set_state(ToastState {
        title: title.to_string(),
        detail: detail.to_string(),
        done: 0,
        total: 0,
        phase: if success { Phase::Done } else { Phase::Failed },
    });
}

/// 点击终态浮窗：用系统默认方式打开文件所在目录。
///
/// **必须用 `ShellExecuteW` 的 `open` 动词而不是硬编码 `explorer.exe /select`**：
/// ShellExecute 会解析注册表里 `Directory` 类注册的默认处理程序，第三方资源管理器
/// （Total Commander / Directory Opus 等接管了文件夹打开方式的工具）因此也能被正确
/// 调起；硬编码 explorer 会绕开用户的默认管理器。代价是无法预选中文件本身，
/// 这里选择「尊重默认管理器」而非「选中文件」。
fn open_saved_dir() {
    let path = OPEN_PATH.lock().unwrap().clone();
    let Some(path) = path else { return };
    let dir = match std::path::Path::new(&path).parent() {
        Some(parent) if parent.as_os_str().is_empty() => std::path::Path::new(&path),
        Some(parent) => parent,
        None => return,
    };
    let dir_str = dir.to_string_lossy().to_string();
    let verb = to_wide("open");
    let dir_wide = to_wide(&dir_str);
    unsafe {
        let result = ShellExecuteW(
            null_mut(),
            null_mut(),
            dir_wide.as_ptr(),
            null_mut(),
            null_mut(),
            SW_SHOWNORMAL as i32,
        );
        // 返回值 ≤ 32 表示失败（句柄 > 32 才是成功）；失败时仅打日志即可，
        // 浮窗本身是尽力而为的辅助功能，不能反过来打扰传输主流程
        if result as isize <= 32 {
            log_warn!("ProgressWindow", "打开保存目录失败: {}", dir_str);
        }
    }
}

/// 人类可读的文件大小。
///
/// 按 MB 起步展示：文件传输场景下 KB 的位数太多、不便一眼估算传输时间。
pub fn format_size(bytes: u64) -> String {
    const KB: f64 = 1024.0;
    const MB: f64 = 1024.0 * 1024.0;
    const GB: f64 = 1024.0 * 1024.0 * 1024.0;
    let value = bytes as f64;
    if value >= GB {
        format!("{:.2} GB", value / GB)
    } else if value >= MB {
        format!("{:.2} MB", value / MB)
    } else if value >= KB {
        format!("{:.0} KB", value / KB)
    } else {
        format!("{} B", bytes)
    }
}

fn set_state(state: ToastState) {
    *STATE.lock().unwrap() = Some(state);
    poke_main_thread();
}

fn poke_main_thread() {
    let hwnd = MAIN_HWND.load(Ordering::SeqCst);
    if hwnd != 0 {
        unsafe {
            PostMessageW(hwnd as HWND, WM_PROGRESS_TICK, 0, 0);
        }
    }
}

/// 主线程处理消息循环时调用：按当前状态创建 / 刷新 / 销毁浮窗。
///
/// 这是唯一会碰窗口句柄的地方，因此不存在跨线程的窗口操作竞态。
pub fn on_ui_tick() {
    let snapshot = STATE.lock().unwrap().clone();

    let (hwnd, phase) = match snapshot {
        None => {
            TIMER_ARMED.store(false, Ordering::SeqCst);
            let old = UI_HWND.swap(0, Ordering::SeqCst);
            VISIBLE.store(false, Ordering::SeqCst);
            if old != 0 {
                unsafe {
                    DestroyWindow(old as HWND);
                }
            }
            return;
        }
        Some(state) => {
            let mut hwnd = UI_HWND.load(Ordering::SeqCst);
            if hwnd == 0 {
                hwnd = unsafe { create_window() } as isize;
                UI_HWND.store(hwnd, Ordering::SeqCst);
            }
            if hwnd == 0 {
                return;
            }
            (hwnd as HWND, state.phase)
        }
    };

    unsafe {
        // 终态挂自动关闭定时器；重新回到进行中则撤销它
        let is_final = phase != Phase::Progress;
        if is_final && !TIMER_ARMED.swap(true, Ordering::SeqCst) {
            SetTimer(hwnd, TIMER_AUTO_CLOSE, AUTO_CLOSE_MS, None);
        } else if !is_final && TIMER_ARMED.swap(false, Ordering::SeqCst) {
            KillTimer(hwnd, TIMER_AUTO_CLOSE);
        }

        if !VISIBLE.swap(true, Ordering::SeqCst) {
            ShowWindow(hwnd, SW_SHOWNOACTIVATE);
        }
        InvalidateRect(hwnd, null_mut(), 0);
        UpdateWindow(hwnd);
    }
}

unsafe fn create_window() -> HWND {
    // hInstance 传 NULL：与主窗口一致，由系统回退到当前进程模块句柄
    let hinstance = null_mut();

    if !CLASS_REGISTERED.swap(true, Ordering::SeqCst) {
        let class_name = to_wide(WINDOW_CLASS);
        let wc = WNDCLASSEXW {
            cbSize: std::mem::size_of::<WNDCLASSEXW>() as u32,
            style: 0,
            lpfnWndProc: Some(wnd_proc),
            cbClsExtra: 0,
            cbWndExtra: 0,
            hInstance: hinstance,
            hIcon: null_mut(),
            hCursor: LoadCursorW(null_mut(), IDC_ARROW),
            hbrBackground: null_mut(),
            lpszMenuName: null_mut(),
            lpszClassName: class_name.as_ptr(),
            hIconSm: null_mut(),
        };
        RegisterClassExW(&wc);
    }

    // 贴屏幕工作区右下角，避开任务栏
    let mut work_area: RECT = std::mem::zeroed();
    SystemParametersInfoW(
        SPI_GETWORKAREA,
        0,
        &mut work_area as *mut RECT as *mut std::ffi::c_void,
        0,
    );
    let x = work_area.right - WIN_W - 16;
    let y = work_area.bottom - WIN_H - 16;

    let class_name = to_wide(WINDOW_CLASS);
    let window_title = to_wide(WINDOW_TITLE);

    // WS_EX_NOACTIVATE + WS_EX_TOOLWINDOW：不抢焦点、不进任务栏与 Alt-Tab
    let hwnd = CreateWindowExW(
        WS_EX_TOPMOST | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE,
        class_name.as_ptr(),
        window_title.as_ptr(),
        WS_POPUP,
        x,
        y,
        WIN_W,
        WIN_H,
        null_mut(),
        null_mut(),
        hinstance,
        null_mut(),
    );

    if !hwnd.is_null() {
        // 圆角：直接给窗口设置圆角 Region，比自绘抗锯齿简单得多
        let rgn = CreateRoundRectRgn(0, 0, WIN_W + 1, WIN_H + 1, 16, 16);
        SetWindowRgn(hwnd, rgn, 1);
    }

    hwnd
}

unsafe extern "system" fn wnd_proc(
    hwnd: HWND,
    msg: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    match msg {
        WM_PAINT => {
            paint(hwnd);
            0
        }
        WM_SETCURSOR => {
            // 命中客户区时换成手型光标，提示「可点击」；非客户区交回默认处理
            if (lparam as usize & 0xFFFF) as u32 == HTCLIENT {
                SetCursor(LoadCursorW(null_mut(), IDC_HAND));
                1
            } else {
                DefWindowProcW(hwnd, msg, wparam, lparam)
            }
        }
        WM_LBUTTONUP => {
            // 仅接收成功的终态可点击跳目录；进行中/失败态的点击没有语义
            let clickable = STATE
                .lock()
                .unwrap()
                .as_ref()
                .is_some_and(|s| s.phase == Phase::Done)
                && OPEN_PATH.lock().unwrap().is_some();
            if clickable {
                open_saved_dir();
            }
            0
        }
        WM_TIMER => {
            if wparam == TIMER_AUTO_CLOSE {
                // 终态停留结束：清空状态并立刻走一次 tick 把窗口销毁
                *STATE.lock().unwrap() = None;
                on_ui_tick();
            }
            0
        }
        _ => DefWindowProcW(hwnd, msg, wparam, lparam),
    }
}

unsafe fn paint(hwnd: HWND) {
    let mut ps: PAINTSTRUCT = std::mem::zeroed();
    let hdc = BeginPaint(hwnd, &mut ps);
    if hdc.is_null() {
        return;
    }

    let snapshot = STATE.lock().unwrap().clone();

    // 卡片背景
    let bg_brush = CreateSolidBrush(0x00FF_FFFF);
    let full = RECT {
        left: 0,
        top: 0,
        right: WIN_W,
        bottom: WIN_H,
    };
    FillRect(hdc, &full, bg_brush);
    DeleteObject(bg_brush);

    if let Some(state) = snapshot {
        // 阶段主色：进行中=品牌蓝，成功=绿，失败=红
        let accent_color: u32 = match state.phase {
            Phase::Progress => 0x00EB_6325, // #2563EB
            Phase::Done => 0x0081_B910,     // #10B981
            Phase::Failed => 0x0044_44EF,   // #EF4444
        };

        // 顶部色条
        let accent = CreateSolidBrush(accent_color);
        let top_bar = RECT {
            left: 0,
            top: 0,
            right: WIN_W,
            bottom: 4,
        };
        FillRect(hdc, &top_bar, accent);

        SetBkMode(hdc, TRANSPARENT as i32);
        let face = to_wide("Microsoft YaHei UI");

        // 标题
        let title_font = CreateFontW(
            -16, 0, 0, 0, 600, 0, 0, 0, DEFAULT_CHARSET as u32, OUT_DEFAULT_PRECIS as u32,
            CLIP_DEFAULT_PRECIS as u32, CLEARTYPE_QUALITY as u32, DEFAULT_PITCH as u32,
            face.as_ptr(),
        );
        let old_font = SelectObject(hdc, title_font);
        SetTextColor(hdc, 0x002A_170F); // #0F172A
        let mut title_rect = RECT {
            left: 16,
            top: 14,
            right: WIN_W - 16,
            bottom: 36,
        };
        let title = to_wide(&state.title);
        DrawTextW(
            hdc,
            title.as_ptr(),
            -1,
            &mut title_rect,
            DT_SINGLELINE | DT_VCENTER | DT_LEFT | DT_END_ELLIPSIS,
        );

        // 副标题：进行中显示「文件名 已传/总量」，终态只显示详细信息
        let sub_font = CreateFontW(
            -12, 0, 0, 0, 400, 0, 0, 0, DEFAULT_CHARSET as u32, OUT_DEFAULT_PRECIS as u32,
            CLIP_DEFAULT_PRECIS as u32, CLEARTYPE_QUALITY as u32, DEFAULT_PITCH as u32,
            face.as_ptr(),
        );
        let old_sub = SelectObject(hdc, sub_font);
        SetTextColor(hdc, 0x008B_7464); // #64748B
        let mut sub_rect = RECT {
            left: 16,
            top: 38,
            right: WIN_W - 16,
            bottom: 58,
        };
        let subtitle_text = match state.phase {
            Phase::Progress => format!(
                "{}   {} / {}",
                state.detail,
                format_size(state.done),
                format_size(state.total)
            ),
            _ => state.detail.clone(),
        };
        let subtitle = to_wide(&subtitle_text);
        DrawTextW(
            hdc,
            subtitle.as_ptr(),
            -1,
            &mut sub_rect,
            DT_SINGLELINE | DT_VCENTER | DT_LEFT | DT_END_ELLIPSIS,
        );

        // 进度条轨道
        let track_left = 16;
        let track_right = WIN_W - 16;
        let track_brush = CreateSolidBrush(0x00F0_F5F1); // #F1F5F9
        let track = RECT {
            left: track_left,
            top: 68,
            right: track_right,
            bottom: 80,
        };
        FillRect(hdc, &track, track_brush);
        DeleteObject(track_brush);

        // 已填充部分：终态一律铺满，用颜色表达结果
        let ratio = match state.phase {
            Phase::Progress => {
                if state.total > 0 {
                    (state.done as f64 / state.total as f64).clamp(0.0, 1.0)
                } else {
                    0.0
                }
            }
            _ => 1.0,
        };
        let filled_width = ((track_right - track_left) as f64 * ratio) as i32;
        if filled_width > 0 {
            let fill_brush = CreateSolidBrush(accent_color);
            let fill = RECT {
                left: track_left,
                top: 68,
                right: track_left + filled_width,
                bottom: 80,
            };
            FillRect(hdc, &fill, fill_brush);
            DeleteObject(fill_brush);
        }

        // 百分比 / 终态标记；接收完成的终态在左侧给出「点击打开所在目录」的点击提示
        SetTextColor(hdc, accent_color);
        let mut pct_rect = RECT {
            left: track_left,
            top: 82,
            right: track_right,
            bottom: WIN_H - 2,
        };
        let pct_text = match state.phase {
            Phase::Progress => format!("{:.0}%", ratio * 100.0),
            Phase::Done => "已完成".to_string(),
            Phase::Failed => "已中断".to_string(),
        };
        let pct = to_wide(&pct_text);
        DrawTextW(
            hdc,
            pct.as_ptr(),
            -1,
            &mut pct_rect,
            DT_SINGLELINE | DT_VCENTER | DT_RIGHT,
        );

        if state.phase == Phase::Done && OPEN_PATH.lock().unwrap().is_some() {
            SetTextColor(hdc, 0x00EB_6325); // #2563EB, 与品牌蓝一致的可点击提示色
            let hint = to_wide("🖱 点击打开所在目录");
            DrawTextW(
                hdc,
                hint.as_ptr(),
                -1,
                &mut pct_rect,
                DT_SINGLELINE | DT_VCENTER | DT_LEFT,
            );
        }

        SelectObject(hdc, old_sub);
        SelectObject(hdc, old_font);
        DeleteObject(sub_font);
        DeleteObject(title_font);
        DeleteObject(accent);
    }

    EndPaint(hwnd, &ps);
}

fn to_wide(s: &str) -> Vec<u16> {
    use std::os::windows::ffi::OsStrExt;
    std::ffi::OsStr::new(s).encode_wide().chain(Some(0)).collect()
}
