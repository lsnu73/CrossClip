#![windows_subsystem = "windows"]

//! CrossClip Windows 端主程序。
//!
//! 接手开发前请先阅读 `docs/ai-handover.md`(AI 开发交接契约), 其中记录了
//! 托盘窗口生命周期、单实例 + WM_COPYDATA 转发、资源管理器右键菜单注册
//! 等设计背后的原因与改动禁区。

mod clipboard;
mod config;
mod crypto;
mod file_transfer;
mod ip_util;
mod mdns;
mod server;
mod udp_discovery;

use std::ffi::OsStr;
use std::os::windows::ffi::OsStrExt;
use std::ptr::null_mut;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use windows_sys::Win32::Foundation::*;
use windows_sys::Win32::Graphics::Gdi::*;
use windows_sys::Win32::System::DataExchange::*;
use windows_sys::Win32::System::Registry::*;
use windows_sys::Win32::System::Threading::*;
use windows_sys::Win32::UI::Controls::Dialogs::*;
use windows_sys::Win32::UI::Shell::*;
use windows_sys::Win32::UI::WindowsAndMessaging::*;

const WM_TRAYICON: u32 = WM_USER + 1;
const WM_CLIPBOARDUPDATE: u32 = 0x031D;
const WM_QUERYENDSESSION: u32 = 0x0011;
const WM_ENDSESSION: u32 = 0x0016;

/// 跨进程传递数据（用于把「右键菜单选中的文件路径」交给已运行的实例）
const WM_COPYDATA: u32 = 0x004A;

/// WM_COPYDATA 的载荷标识：表示数据是「待发送的文件路径」（ASCII "CPTH"）
const COPYDATA_FILE_PATH: usize = 0x4350_5448;

const ID_TRAY_STATUS: usize = 1001;
const ID_TRAY_IP: usize = 1002;
const ID_TRAY_PIN: usize = 1003;
const ID_TRAY_REGEN_PIN: usize = 1004;
const ID_TRAY_TOGGLE_AUTO: usize = 1005;
const ID_TRAY_AUTO_START: usize = 1006;
const ID_TRAY_SEND_MANUAL: usize = 1007;
const ID_TRAY_SEND_FILE: usize = 1009;
const ID_TRAY_PHONE: usize = 1010;
const ID_TRAY_QUIT: usize = 1008;

/// 托盘主窗口的窗口类名。
/// 右键菜单启动的新进程需要通过 FindWindowW 找到已运行的实例并投递文件路径。
const TRAY_WINDOW_CLASS: &str = "CrossClipTrayWndMainV2";

/// Win32 的 COPYDATASTRUCT，用于 WM_COPYDATA 跨进程传参。
/// 这里手工定义而不用 windows-sys 的版本，避免因 feature 开关导致不可用。
#[repr(C)]
struct CopyDataStruct {
    dw_data: usize,
    cb_data: u32,
    lp_data: *mut std::ffi::c_void,
}

static GLOBAL_STATE: Mutex<Option<AppState>> = Mutex::new(None);

#[derive(Clone)]
pub struct AppState {
    pub pin_code: Arc<RwLock<String>>,
    pub device_name: String,
    pub device_id: String,
    pub http_port: u16,
    pub main_ip: String,
    pub auto_sync: Arc<AtomicBool>,
    pub broadcaster: server::Broadcaster,
    pub hwnd: isize,
    pub file_manager: Arc<file_transfer::FileTransferManager>,
}

impl AppState {
    pub fn regen_pin(&self) -> String {
        let new_pin = config::generate_random_pin();
        {
            let mut pin_lock = self.pin_code.write().unwrap();
            *pin_lock = new_pin.clone();
        }
        // 同步持久化写入 config.json
        config::update_persisted_pin(&new_pin);
        // 断开旧客户端长连接，强制手机端使用新 PIN 重新握手
        self.broadcaster.disconnect_all();
        update_tray_tooltip(
            self.hwnd as HWND,
            &new_pin,
            &self.main_ip,
            self.http_port,
            self.auto_sync.load(Ordering::SeqCst),
        );
        crate::clipboard::set_clipboard_text(&new_pin);
        new_pin
    }

    pub fn toggle_auto_sync(&self) -> bool {
        let current = self.auto_sync.load(Ordering::SeqCst);
        let new_val = !current;
        self.auto_sync.store(new_val, Ordering::SeqCst);
        config::update_persisted_auto_sync(new_val);
        let pin = self.pin_code.read().unwrap().clone();
        update_tray_tooltip(self.hwnd as HWND, &pin, &self.main_ip, self.http_port, new_val);
        new_val
    }

    pub fn send_manual(&self) {
        if let Some(txt) = crate::clipboard::get_clipboard_text() {
            if !txt.trim().is_empty() {
                self.broadcaster.broadcast_text(&txt);
            }
        }
    }

    /// 托盘菜单入口：弹出文件选择器，选择后发送到手机
    pub fn send_file_to_phone(&self) {
        let file_path = match open_file_picker(self.hwnd as HWND) {
            Some(p) => p,
            None => return,
        };
        self.send_file_path(&file_path);
    }

    /// 发送指定路径的文件到手机。
    /// 资源管理器右键菜单（`--send-file` 参数）与托盘菜单共用此入口。
    pub fn send_file_path(&self, path: &str) {
        let file_path = path.to_string();
        let file_manager = self.file_manager.clone();
        let broadcaster = self.broadcaster.clone();
        let pin_code = self.pin_code.clone();
        let hwnd_isize = self.hwnd; // 保存为 isize 以便线程安全传递

        // 在后台线程中执行文件发送
        std::thread::spawn(move || {
            // 1. 读取文件并创建发送任务
            let transfer = match file_manager.prepare_outgoing(&file_path) {
                Ok(t) => t,
                Err(e) => {
                    show_balloon_tip(hwnd_isize as HWND, "文件发送失败", &format!("读取文件失败: {}", e));
                    return;
                }
            };

            let file_id = transfer.file_id.clone();
            let filename = transfer.filename.clone();
            let file_size = transfer.file_size;
            let total_chunks = transfer.total_chunks;
            let file_hash = file_manager.get_file_hash(&file_id).unwrap_or_default();

            // 2. 获取已连接手机的 IP 和端口
            let peers: Vec<server::ClientPeer> = broadcaster.get_peers();

            let phone_peer = peers.first();
            let (phone_ip, phone_port) = match phone_peer {
                Some(p) => (p.ip.clone(), p.port),
                None => {
                    show_balloon_tip(hwnd_isize as HWND, "文件发送失败", "未找到已连接的手机设备");
                    file_manager.cleanup_outgoing(&file_id);
                    return;
                }
            };

            let current_pin = pin_code.read().unwrap().clone();

            // 显示开始发送提示
            show_balloon_tip(
                hwnd_isize as HWND,
                "开始发送文件",
                &format!("{} ({} bytes) -> 手机", filename, file_size),
            );

            // 3. 通过**单条复用连接**发送整个文件（prepare → 分块×N → complete）
            let sender_id = {
                let state = GLOBAL_STATE.lock().unwrap();
                state.as_ref().map(|s| s.device_id.clone()).unwrap_or_default()
            };

            let send_result = server::send_file_to_phone(
                &phone_ip,
                phone_port,
                &file_manager,
                &transfer,
                &current_pin,
                &file_hash,
                &sender_id,
                |sent, total| {
                    // 每块完成时刷新托盘提示的进度百分比
                    file_manager.update_send_progress(&file_id, sent);
                    let progress = if total > 0 { sent * 100 / total } else { 100 };
                    update_tray_tooltip(
                        hwnd_isize as HWND,
                        &format!("📤 发送中 {}%", progress),
                        &filename,
                        0,
                        false,
                    );
                },
            );

            match send_result {
                Ok(()) => {
                    show_balloon_tip(
                        hwnd_isize as HWND,
                        "文件发送完成",
                        &format!("{} 已成功发送到手机", filename),
                    );
                }
                Err(e) => {
                    show_balloon_tip(hwnd_isize as HWND, "文件发送失败", &e);
                }
            }

            // 恢复托盘提示
            let pin = pin_code.read().unwrap().clone();
            let main_ip = {
                let state = GLOBAL_STATE.lock().unwrap();
                state.as_ref().map(|s| s.main_ip.clone()).unwrap_or_else(|| "127.0.0.1".to_string())
            };
            let port = {
                let state = GLOBAL_STATE.lock().unwrap();
                state.as_ref().map(|s| s.http_port).unwrap_or(18236)
            };
            let auto = {
                let state = GLOBAL_STATE.lock().unwrap();
                state.as_ref().map(|s| s.auto_sync.load(Ordering::SeqCst)).unwrap_or(true)
            };
            update_tray_tooltip(hwnd_isize as HWND, &pin, &main_ip, port, auto);

            file_manager.cleanup_outgoing(&file_id);
        });
    }
}

fn to_wide(s: &str) -> Vec<u16> {
    OsStr::new(s).encode_wide().chain(Some(0)).collect()
}

/// 在 `HKCU\Software\Classes\*\shell` 下注册资源管理器右键菜单项。
///
/// 注册后，用户在资源管理器中右键任意文件即可看到「发送文件到手机 (CrossClip)」；
/// 点击后系统会以 `CrossClip.exe --send-file "<文件路径>"` 启动本程序。
///
/// 选择 HKCU 而非 HKLM：无需管理员权限，且随用户配置卸载时自动清理。
fn register_shell_context_menu() -> bool {
    unsafe {
        let exe_path = match std::env::current_exe() {
            Ok(p) => p,
            Err(_) => return false,
        };
        let exe_str = exe_path.to_string_lossy().to_string();

        // ---------- 1. 菜单项主键（显示名 + 图标） ----------
        let menu_key = to_wide(r"Software\Classes\*\shell\CrossClipSendFile");
        let mut hkey: HKEY = null_mut();
        if RegCreateKeyExW(
            HKEY_CURRENT_USER,
            menu_key.as_ptr(),
            0,
            std::ptr::null(),
            0,
            KEY_WRITE,
            std::ptr::null(),
            &mut hkey,
            null_mut(),
        ) != 0
        {
            return false;
        }

        // 菜单显示文本（键的默认值）
        let label = to_wide("发送文件到手机 (CrossClip)");
        RegSetValueExW(
            hkey,
            std::ptr::null(),
            0,
            REG_SZ,
            label.as_ptr() as *const u8,
            (label.len() * 2) as u32,
        );

        // 菜单图标复用程序自身图标
        let icon_value = to_wide(&exe_str);
        let icon_name = to_wide("Icon");
        RegSetValueExW(
            hkey,
            icon_name.as_ptr(),
            0,
            REG_SZ,
            icon_value.as_ptr() as *const u8,
            (icon_value.len() * 2) as u32,
        );
        RegCloseKey(hkey);

        // ---------- 2. command 子键（实际执行的命令行） ----------
        let cmd_key = to_wide(r"Software\Classes\*\shell\CrossClipSendFile\command");
        let mut hkey2: HKEY = null_mut();
        if RegCreateKeyExW(
            HKEY_CURRENT_USER,
            cmd_key.as_ptr(),
            0,
            std::ptr::null(),
            0,
            KEY_WRITE,
            std::ptr::null(),
            &mut hkey2,
            null_mut(),
        ) != 0
        {
            return false;
        }

        // %1 会被资源管理器替换成被右键选中的文件完整路径
        let cmd = to_wide(&format!("\"{}\" --send-file \"%1\"", exe_str));
        RegSetValueExW(
            hkey2,
            std::ptr::null(),
            0,
            REG_SZ,
            cmd.as_ptr() as *const u8,
            (cmd.len() * 2) as u32,
        );
        RegCloseKey(hkey2);

        true
    }
}

/// 从命令行参数中解析 `--send-file <路径>`。
/// 返回 None 表示本次启动并非由资源管理器右键菜单触发。
fn parse_send_file_arg() -> Option<String> {
    let args: Vec<String> = std::env::args().collect();
    let mut iter = args.iter().skip(1);
    while let Some(arg) = iter.next() {
        if arg == "--send-file" {
            return iter.next().cloned();
        }
    }
    None
}

/// 把「右键菜单选中的文件路径」投递给已在运行的 CrossClip 实例。
///
/// 程序是单实例的：右键菜单拉起的新进程不能自己处理文件（它会立刻退出），
/// 因此必须通过 WM_COPYDATA 把路径发给已运行的实例，由后者弹出托盘气泡并开始传输。
///
/// @return 是否成功完成投递（false 表示无参数或找不到主窗口）
fn forward_file_to_existing_instance() -> bool {
    let path = match parse_send_file_arg() {
        Some(p) => p,
        None => return false,
    };

    unsafe {
        let class_name = to_wide(TRAY_WINDOW_CLASS);
        let hwnd = FindWindowW(class_name.as_ptr(), std::ptr::null());
        if hwnd.is_null() {
            return false;
        }

        // WM_COPYDATA 的载荷约定以 NUL 结尾；
        // SendMessageW 是同步调用，返回前 payload 不会被释放，指针始终有效。
        let mut payload = path.into_bytes();
        payload.push(0);

        let mut cds = CopyDataStruct {
            dw_data: COPYDATA_FILE_PATH,
            cb_data: payload.len() as u32,
            lp_data: payload.as_mut_ptr() as *mut std::ffi::c_void,
        };

        SendMessageW(
            hwnd,
            WM_COPYDATA,
            0,
            &mut cds as *mut CopyDataStruct as isize,
        );
        true
    }
}

/// 打开 Windows 原生文件选择对话框
fn open_file_picker(hwnd: HWND) -> Option<String> {
    unsafe {
        let mut filename_buf = [0u16; 32768]; // 支持长路径
        let filter = to_wide("所有文件\0*.*\0图片文件\0*.jpg;*.jpeg;*.png;*.gif;*.bmp;*.webp\0文档\0*.txt;*.doc;*.docx;*.pdf\0视频\0*.mp4;*.avi;*.mkv;*.mov\0音频\0*.mp3;*.wav;*.flac;*.aac\0");
        let title = to_wide("选择要发送到手机的文件");

        let mut ofn: OPENFILENAMEW = std::mem::zeroed();
        ofn.lStructSize = std::mem::size_of::<OPENFILENAMEW>() as u32;
        ofn.hwndOwner = hwnd;
        ofn.lpstrFilter = filter.as_ptr();
        ofn.lpstrFile = filename_buf.as_mut_ptr();
        ofn.nMaxFile = 32768;
        ofn.lpstrTitle = title.as_ptr();
        ofn.Flags = OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST | OFN_NOCHANGEDIR;

        if GetOpenFileNameW(&mut ofn) != 0 {
            let len = filename_buf.iter().position(|&c| c == 0).unwrap_or(0);
            let path = String::from_utf16_lossy(&filename_buf[..len]);
            if !path.is_empty() {
                Some(path)
            } else {
                None
            }
        } else {
            None
        }
    }
}

/// 显示系统托盘气球通知
fn show_balloon_tip(hwnd: HWND, title: &str, message: &str) {
    unsafe {
        let title_wide = to_wide(title);
        let msg_wide = to_wide(message);

        let mut nid: NOTIFYICONDATAW = std::mem::zeroed();
        nid.cbSize = std::mem::size_of::<NOTIFYICONDATAW>() as u32;
        nid.hWnd = hwnd;
        nid.uID = 1;
        nid.uFlags = NIF_INFO;

        let title_len = title_wide.len().min(nid.szInfoTitle.len() - 1);
        std::ptr::copy_nonoverlapping(title_wide.as_ptr(), nid.szInfoTitle.as_mut_ptr(), title_len);

        let msg_len = msg_wide.len().min(nid.szInfo.len() - 1);
        std::ptr::copy_nonoverlapping(msg_wide.as_ptr(), nid.szInfo.as_mut_ptr(), msg_len);

        nid.dwInfoFlags = NIIF_INFO;
        Shell_NotifyIconW(NIM_MODIFY, &nid);
    }
}

fn is_auto_start_enabled() -> bool {
    unsafe {
        let subkey = to_wide(r"Software\Microsoft\Windows\CurrentVersion\Run");
        let mut hkey: HKEY = null_mut();
        let res = RegOpenKeyExW(
            HKEY_CURRENT_USER,
            subkey.as_ptr(),
            0,
            KEY_QUERY_VALUE,
            &mut hkey,
        );
        if res != 0 {
            return false;
        }

        let val_name = to_wide("CrossClip");
        let mut val_type: u32 = 0;
        let mut cb_data: u32 = 0;
        let query_res = RegQueryValueExW(
            hkey,
            val_name.as_ptr(),
            null_mut(),
            &mut val_type,
            null_mut(),
            &mut cb_data,
        );
        RegCloseKey(hkey);
        query_res == 0
    }
}

fn set_auto_start(enabled: bool) -> bool {
    unsafe {
        let subkey = to_wide(r"Software\Microsoft\Windows\CurrentVersion\Run");
        let val_name = to_wide("CrossClip");

        if enabled {
            let exe_path = match std::env::current_exe() {
                Ok(p) => p,
                Err(_) => return false,
            };
            let path_str = exe_path.to_string_lossy().to_string();
            let quoted_path = format!("\"{}\"", path_str);
            let wide_val = to_wide(&quoted_path);
            let byte_len = (wide_val.len() * std::mem::size_of::<u16>()) as u32;

            let mut hkey: HKEY = null_mut();
            let res = RegOpenKeyExW(
                HKEY_CURRENT_USER,
                subkey.as_ptr(),
                0,
                KEY_SET_VALUE,
                &mut hkey,
            );
            if res != 0 {
                return false;
            }

            let set_res = RegSetValueExW(
                hkey,
                val_name.as_ptr(),
                0,
                REG_SZ,
                wide_val.as_ptr() as *const u8,
                byte_len,
            );
            RegCloseKey(hkey);
            set_res == 0
        } else {
            let mut hkey: HKEY = null_mut();
            let res = RegOpenKeyExW(
                HKEY_CURRENT_USER,
                subkey.as_ptr(),
                0,
                KEY_SET_VALUE,
                &mut hkey,
            );
            if res != 0 {
                return false;
            }

            let del_res = RegDeleteValueW(hkey, val_name.as_ptr());
            RegCloseKey(hkey);
            del_res == 0 || del_res == 2
        }
    }
}


fn update_tray_tooltip(hwnd: HWND, pin: &str, ip: &str, port: u16, auto_sync: bool) {
    unsafe {
        let mode_str = if auto_sync { "自动互传" } else { "手动模式" };
        let tip: Vec<u16> = OsStr::new(&format!(
            "CrossClip 互传 ({})\nIP: {}:{}\nPIN 码: {}",
            mode_str, ip, port, pin
        ))
        .encode_wide()
        .chain(Some(0))
        .collect();

        let mut nid: NOTIFYICONDATAW = std::mem::zeroed();
        nid.cbSize = std::mem::size_of::<NOTIFYICONDATAW>() as u32;
        nid.hWnd = hwnd;
        nid.uID = 1;
        nid.uFlags = NIF_TIP;

        let tip_len = tip.len().min(nid.szTip.len() - 1);
        std::ptr::copy_nonoverlapping(tip.as_ptr(), nid.szTip.as_mut_ptr(), tip_len);

        Shell_NotifyIconW(NIM_MODIFY, &nid);
    }
}

/// 动态创建精致美观的剪贴板互传图标 (天蓝板夹 + 白色互传线，适配 Windows 任务栏)
unsafe fn create_crossclip_tray_icon() -> HICON {
    let hdc_screen = GetDC(null_mut());
    let hdc_mem = CreateCompatibleDC(hdc_screen);
    let hdc_mask = CreateCompatibleDC(hdc_screen);

    let hbm_color = CreateCompatibleBitmap(hdc_screen, 32, 32);
    let hbm_mask = CreateBitmap(32, 32, 1, 1, null_mut());

    let old_color = SelectObject(hdc_mem, hbm_color);
    let old_mask = SelectObject(hdc_mask, hbm_mask);

    let black_brush = CreateSolidBrush(0x000000);
    let white_brush = CreateSolidBrush(0xFFFFFF);

    // 1. 初始化背景透明
    let full_rect = RECT {
        left: 0,
        top: 0,
        right: 32,
        bottom: 32,
    };
    FillRect(hdc_mask, &full_rect, white_brush);
    FillRect(hdc_mem, &full_rect, black_brush);

    // 2. 剪贴板主体板身 (天蓝色 #0284C7 -> BGR 0xC78402)
    let clip_brush = CreateSolidBrush(0xC78402);
    let clip_rect = RECT {
        left: 5,
        top: 6,
        right: 27,
        bottom: 30,
    };
    FillRect(hdc_mem, &clip_rect, clip_brush);
    FillRect(hdc_mask, &clip_rect, black_brush);

    // 3. 顶部金属夹扣
    let top_clip_brush = CreateSolidBrush(0xE2E8F0); // 银白色
    let top_clip_rect = RECT {
        left: 10,
        top: 2,
        right: 22,
        bottom: 8,
    };
    FillRect(hdc_mem, &top_clip_rect, top_clip_brush);
    FillRect(hdc_mask, &top_clip_rect, black_brush);

    // 4. 白色横线与互传标识
    let line_brush = CreateSolidBrush(0xFFFFFF);
    let line1 = RECT {
        left: 9,
        top: 12,
        right: 23,
        bottom: 14,
    };
    let line2 = RECT {
        left: 9,
        top: 17,
        right: 23,
        bottom: 19,
    };
    let line3 = RECT {
        left: 9,
        top: 22,
        right: 18,
        bottom: 24,
    };
    FillRect(hdc_mem, &line1, line_brush);
    FillRect(hdc_mem, &line2, line_brush);
    FillRect(hdc_mem, &line3, line_brush);

    SelectObject(hdc_mem, old_color);
    SelectObject(hdc_mask, old_mask);
    DeleteDC(hdc_mem);
    DeleteDC(hdc_mask);
    ReleaseDC(null_mut(), hdc_screen);

    DeleteObject(black_brush);
    DeleteObject(white_brush);
    DeleteObject(clip_brush);
    DeleteObject(top_clip_brush);
    DeleteObject(line_brush);

    let icon_info = ICONINFO {
        fIcon: 1,
        xHotspot: 0,
        yHotspot: 0,
        hbmMask: hbm_mask,
        hbmColor: hbm_color,
    };

    let icon = CreateIconIndirect(&icon_info);
    DeleteObject(hbm_color);
    DeleteObject(hbm_mask);
    icon
}

unsafe extern "system" fn wnd_proc(
    hwnd: HWND,
    msg: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    match msg {
        WM_COPYDATA => {
            // 收到来自「右键菜单新进程」的文件路径投递，转交发送流程
            unsafe {
                let cds = &*(lparam as *const CopyDataStruct);
                if cds.dw_data == COPYDATA_FILE_PATH && !cds.lp_data.is_null() {
                    let bytes = std::slice::from_raw_parts(cds.lp_data as *const u8, cds.cb_data as usize);
                    let path = String::from_utf8_lossy(bytes).trim_end_matches('\0').to_string();
                    if !path.is_empty() {
                        let state_opt = GLOBAL_STATE.lock().unwrap().clone();
                        if let Some(state) = state_opt {
                            state.send_file_path(&path);
                        }
                    }
                }
            }
            1
        }
        WM_CLIPBOARDUPDATE => {
            let state_opt = GLOBAL_STATE.lock().unwrap().clone();
            if let Some(state) = state_opt {
                if state.auto_sync.load(Ordering::SeqCst) {
                    // 避让 15ms 允许复制源应用程序完成写操作并安全关闭剪贴板句柄
                    std::thread::sleep(std::time::Duration::from_millis(15));
                    if let Some(text) = crate::clipboard::on_clipboard_updated() {
                        state.broadcaster.broadcast_text(&text);
                    }
                }
            }
            0
        }
        WM_TRAYICON => {
            if lparam as u32 == WM_RBUTTONUP || lparam as u32 == WM_LBUTTONUP {
                let state_opt = GLOBAL_STATE.lock().unwrap().clone();
                if let Some(state) = state_opt {
                    let mut pt: POINT = std::mem::zeroed();
                    GetCursorPos(&mut pt);

                    let hmenu = CreatePopupMenu();
                    let current_pin = state.pin_code.read().unwrap().clone();
                    let is_auto = state.auto_sync.load(Ordering::SeqCst);

                    let status_text: Vec<u16> =
                        OsStr::new(&format!("🟢 状态: 运行中 (端口: {})", state.http_port))
                            .encode_wide()
                            .chain(Some(0))
                            .collect();
                    let ip_text: Vec<u16> = OsStr::new(&format!("🌐 本机 IP: {}", state.main_ip))
                        .encode_wide()
                        .chain(Some(0))
                        .collect();

                    // p8: 展示当前已连接的手机设备名与 IP（取最近注册的对等节点）
                    let peers = state.broadcaster.get_peers();
                    let phone_str = match peers.first() {
                        Some(p) => {
                            let name = if p.device_name.trim().is_empty() {
                                "安卓手机".to_string()
                            } else {
                                p.device_name.clone()
                            };
                            format!("📱 已连接手机: {} ({})", name, p.ip)
                        }
                        None => "📱 已连接手机: 无".to_string(),
                    };
                    let phone_text: Vec<u16> = OsStr::new(&phone_str)
                        .encode_wide()
                        .chain(Some(0))
                        .collect();
                    let pin_text: Vec<u16> =
                        OsStr::new(&format!("🔑 随机 PIN: {} (点击复制)", current_pin))
                            .encode_wide()
                            .chain(Some(0))
                            .collect();
                    let regen_text: Vec<u16> = OsStr::new("🔄 重新生成随机 PIN 码")
                        .encode_wide()
                        .chain(Some(0))
                        .collect();

                    let auto_str = if is_auto {
                        "✅ 自动互传模式: 已开启 (点击切换)"
                    } else {
                        "⚪ 自动互传模式: 已关闭 (点击切换)"
                    };
                    let toggle_auto_text: Vec<u16> = OsStr::new(auto_str)
                        .encode_wide()
                        .chain(Some(0))
                        .collect();

                    let is_autostart = is_auto_start_enabled();
                    let autostart_str = if is_autostart {
                        "✅ 开机自启动: 已开启 (点击切换)"
                    } else {
                        "⚪ 开机自启动: 已关闭 (点击切换)"
                    };
                    let autostart_text: Vec<u16> = OsStr::new(autostart_str)
                        .encode_wide()
                        .chain(Some(0))
                        .collect();

                    let manual_send_text: Vec<u16> =
                        OsStr::new("📤 手动发送当前剪贴板到手机")
                            .encode_wide()
                            .chain(Some(0))
                            .collect();

                    let send_file_text: Vec<u16> =
                        OsStr::new("📁 发送文件到手机...")
                            .encode_wide()
                            .chain(Some(0))
                            .collect();

                    let quit_text: Vec<u16> = OsStr::new("❌ 退出 CrossClip")
                        .encode_wide()
                        .chain(Some(0))
                        .collect();

                    AppendMenuW(hmenu, MF_STRING | MF_GRAYED, ID_TRAY_STATUS, status_text.as_ptr());
                    AppendMenuW(hmenu, MF_STRING | MF_GRAYED, ID_TRAY_IP, ip_text.as_ptr());
                    AppendMenuW(hmenu, MF_STRING | MF_GRAYED, ID_TRAY_PHONE, phone_text.as_ptr());
                    AppendMenuW(hmenu, MF_SEPARATOR, 0, null_mut());
                    AppendMenuW(hmenu, MF_STRING, ID_TRAY_PIN, pin_text.as_ptr());
                    AppendMenuW(hmenu, MF_STRING, ID_TRAY_REGEN_PIN, regen_text.as_ptr());
                    AppendMenuW(hmenu, MF_SEPARATOR, 0, null_mut());
                    AppendMenuW(hmenu, MF_STRING, ID_TRAY_TOGGLE_AUTO, toggle_auto_text.as_ptr());
                    AppendMenuW(hmenu, MF_STRING, ID_TRAY_AUTO_START, autostart_text.as_ptr());
                    if !is_auto {
                        AppendMenuW(
                            hmenu,
                            MF_STRING,
                            ID_TRAY_SEND_MANUAL,
                            manual_send_text.as_ptr(),
                        );
                    }
                    AppendMenuW(hmenu, MF_SEPARATOR, 0, null_mut());
                    AppendMenuW(hmenu, MF_STRING, ID_TRAY_SEND_FILE, send_file_text.as_ptr());
                    AppendMenuW(hmenu, MF_SEPARATOR, 0, null_mut());
                    AppendMenuW(hmenu, MF_STRING, ID_TRAY_QUIT, quit_text.as_ptr());

                    SetForegroundWindow(hwnd);
                    let cmd = TrackPopupMenu(
                        hmenu,
                        TPM_RETURNCMD | TPM_NONOTIFY,
                        pt.x,
                        pt.y,
                        0,
                        hwnd,
                        null_mut(),
                    );
                    DestroyMenu(hmenu);
                    PostMessageW(hwnd, WM_NULL, 0, 0);

                    if cmd == ID_TRAY_PIN as i32 {
                        crate::clipboard::set_clipboard_text(&current_pin);
                    } else if cmd == ID_TRAY_REGEN_PIN as i32 {
                        state.regen_pin();
                    } else if cmd == ID_TRAY_TOGGLE_AUTO as i32 {
                        state.toggle_auto_sync();
                    } else if cmd == ID_TRAY_AUTO_START as i32 {
                        let cur = is_auto_start_enabled();
                        set_auto_start(!cur);
                    } else if cmd == ID_TRAY_SEND_MANUAL as i32 {
                        state.send_manual();
                    } else if cmd == ID_TRAY_SEND_FILE as i32 {
                        state.send_file_to_phone();
                    } else if cmd == ID_TRAY_QUIT as i32 {
                        state.broadcaster.disconnect_all();
                        PostQuitMessage(0);
                    }
                }
            }
            0
        }
        WM_QUERYENDSESSION => {
            // 系统正在查询是否可以关机，返回 1 (TRUE) 明确同意关机，绝不阻止
            1
        }
        WM_ENDSESSION => {
            if wparam != 0 {
                // 系统已确定关机或注销，立即断开连接并极速退出进程
                let state_opt = GLOBAL_STATE.lock().unwrap().clone();
                if let Some(state) = state_opt {
                    state.broadcaster.disconnect_all();
                }
                RemoveClipboardFormatListener(hwnd);
                std::process::exit(0);
            }
            0
        }
        WM_DESTROY => {
            RemoveClipboardFormatListener(hwnd);
            0
        }
        _ => DefWindowProcW(hwnd, msg, wparam, lparam),
    }
}

fn main() {
    std::panic::set_hook(Box::new(|info| {
        let msg = format!("CrossClip 崩溃: {:?}", info);
        let _ = std::fs::write("crossclip_crash.log", msg);
    }));

    // 单实例互斥检查：通过系统命名互斥体彻底防止复数启动
    let mutex_name = to_wide("Local\\CrossClipSingleInstanceMutex");
    let _h_single_instance = unsafe {
        let h = CreateMutexW(null_mut(), 1, mutex_name.as_ptr());
        if !h.is_null() && GetLastError() == ERROR_ALREADY_EXISTS {
            // 已有实例在运行。若本次是被「资源管理器右键菜单」拉起的，
            // 则把选中的文件路径通过 WM_COPYDATA 转交给那个实例，然后本进程立即退出。
            forward_file_to_existing_instance();
            std::process::exit(0);
        }
        h
    };

    // 注册资源管理器右键菜单「发送文件到手机 (CrossClip)」。
    // 幂等操作，每次启动都会刷新一遍（例如程序被移动了目录时自动更新路径）。
    register_shell_context_menu();

    let cfg = config::load_or_init_config();
    let initial_pin = cfg.pin_code.clone();
    let pin_code_arc = Arc::new(RwLock::new(initial_pin.clone()));
    let lan_ips = ip_util::get_local_lan_ips();
    let main_ip = lan_ips
        .first()
        .cloned()
        .unwrap_or_else(|| "127.0.0.1".to_string());
    let auto_sync_arc = Arc::new(AtomicBool::new(cfg.auto_sync));

    // 1. 广播器 (用于将电脑剪贴板并发推送到手机)
    let broadcaster =
        server::Broadcaster::new(pin_code_arc.clone(), cfg.device_id.clone());

    // 1.5 文件传输管理器
    let file_manager = Arc::new(file_transfer::FileTransferManager::new());

    // 2. 启动 UDP 广播自动应答服务 (附带物理 IP)
    let pin_for_udp = pin_code_arc.clone();
    udp_discovery::start_udp_discovery_responder(
        cfg.device_name.clone(),
        cfg.device_id.clone(),
        cfg.http_port,
        pin_for_udp,
    );

    // 3. 启动 mDNS 广播
    let _mdns =
        mdns::start_mdns_broadcast(&cfg.device_name, cfg.http_port, &cfg.device_id);

    // 4. 启动 HTTP API 服务与 SSE 下发服务 (非阻塞多线程模型)
    let pin_for_http = pin_code_arc.clone();
    let broadcaster_for_server = broadcaster.clone();
    let file_manager_for_server = file_manager.clone();
    let on_received = Arc::new(move |_text: String, _sender: String| {
        // 完全静默写入系统剪贴板
    });
    server::start_http_server(
        cfg.http_port,
        pin_for_http,
        cfg.device_name.clone(),
        broadcaster_for_server,
        on_received,
        file_manager_for_server,
    );

    // 5. 独立后台剪贴板兜底监控线程 (Win32 原生事件为主，2000ms 轮询超低频防漏)
    let auto_sync_for_poll = auto_sync_arc.clone();
    let broadcaster_for_poll = broadcaster.clone();
    std::thread::spawn(move || {
        loop {
            std::thread::sleep(std::time::Duration::from_millis(2000));
            if auto_sync_for_poll.load(Ordering::SeqCst) {
                if let Some(text) = crate::clipboard::on_clipboard_updated() {
                    broadcaster_for_poll.broadcast_text(&text);
                }
            }
        }
    });

    // 6. 纯后台托盘图标与消息循环 (Win32 原生剪贴板监听由主窗口过程捕获)
    unsafe {
        let class_name: Vec<u16> = OsStr::new("CrossClipTrayWndMainV2")
            .encode_wide()
            .chain(Some(0))
            .collect();

        let wnd_class = WNDCLASSEXW {
            cbSize: std::mem::size_of::<WNDCLASSEXW>() as u32,
            style: 0,
            lpfnWndProc: Some(wnd_proc),
            cbClsExtra: 0,
            cbWndExtra: 0,
            hInstance: null_mut(),
            hIcon: null_mut(),
            hCursor: null_mut(),
            hbrBackground: null_mut(),
            lpszMenuName: null_mut(),
            lpszClassName: class_name.as_ptr(),
            hIconSm: null_mut(),
        };
        RegisterClassExW(&wnd_class);

        let hwnd = CreateWindowExW(
            0,
            class_name.as_ptr(),
            class_name.as_ptr(),
            0,
            0,
            0,
            0,
            0,
            null_mut(),
            null_mut(),
            null_mut(),
            null_mut(),
        );

        let state = AppState {
            pin_code: pin_code_arc,
            device_name: cfg.device_name.clone(),
            device_id: cfg.device_id.clone(),
            http_port: cfg.http_port,
            main_ip: main_ip.clone(),
            auto_sync: auto_sync_arc,
            broadcaster,
            hwnd: hwnd as isize,
            file_manager,
        };
        {
            let mut g = GLOBAL_STATE.lock().unwrap();
            *g = Some(state);
        }

        // 注册 Win32 系统剪贴板格式监听器至主窗口
        AddClipboardFormatListener(hwnd);

        // 创建现代高清剪贴板互传图标
        let h_icon = create_crossclip_tray_icon();

        let mut nid: NOTIFYICONDATAW = std::mem::zeroed();
        nid.cbSize = std::mem::size_of::<NOTIFYICONDATAW>() as u32;
        nid.hWnd = hwnd;
        nid.uID = 1;
        nid.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
        nid.uCallbackMessage = WM_TRAYICON;
        nid.hIcon = if !h_icon.is_null() {
            h_icon
        } else {
            LoadIconW(null_mut(), IDI_APPLICATION)
        };

        let tip: Vec<u16> = OsStr::new(&format!(
            "CrossClip 互传 (自动互传)\nIP: {}:{}\nPIN 码: {}",
            main_ip, cfg.http_port, initial_pin
        ))
        .encode_wide()
        .chain(Some(0))
        .collect();
        let tip_len = tip.len().min(nid.szTip.len() - 1);
        std::ptr::copy_nonoverlapping(tip.as_ptr(), nid.szTip.as_mut_ptr(), tip_len);

        Shell_NotifyIconW(NIM_ADD, &nid);

        let mut msg: MSG = std::mem::zeroed();
        while GetMessageW(&mut msg, null_mut(), 0, 0) > 0 {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }

        Shell_NotifyIconW(NIM_DELETE, &nid);
        if !h_icon.is_null() {
            DestroyIcon(h_icon);
        }
    }
}
