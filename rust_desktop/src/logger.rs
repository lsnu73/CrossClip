//! CrossClip 桌面端日志系统。
//!
//! 参考手机端 DebugLogger 设计：
//! - 日志文件位于 exe 所在目录，文件名 `crossclip_debug.log`
//! - 每行格式 `[yyyy-MM-dd HH:mm:ss.SSS] [LEVEL] [TAG] message`
//! - 等级：INFO / OK / WARN / ERR
//! - 超过 5MB 时自动删除重建（简单轮转）
//! - 线程安全，通过静态单例 + Mutex 写入

use std::fs;
use std::os::windows::ffi::OsStrExt;
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};

/// 日志等级，与手机端 DebugLogger 保持一致。
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum LogLevel {
    Info,
    Ok,
    Warn,
    Err,
}

impl LogLevel {
    pub fn label(&self) -> &'static str {
        match self {
            LogLevel::Info => "INFO",
            LogLevel::Ok => "OK",
            LogLevel::Warn => "WARN",
            LogLevel::Err => "ERR",
        }
    }
}

fn local_timestamp() -> String {
    use windows_sys::Win32::Foundation::SYSTEMTIME;
    use windows_sys::Win32::System::SystemInformation::GetLocalTime;
    unsafe {
        let mut st: SYSTEMTIME = std::mem::zeroed();
        GetLocalTime(&mut st);
        format!(
            "{:04}-{:02}-{:02} {:02}:{:02}:{:02}.{:03}",
            st.wYear, st.wMonth, st.wDay, st.wHour, st.wMinute, st.wSecond, st.wMilliseconds
        )
    }
}

pub struct Logger {
    file_path: PathBuf,
}

impl Logger {
    fn new() -> Self {
        let exe_dir = std::env::current_exe()
            .ok()
            .and_then(|p| p.parent().map(|d| d.to_path_buf()))
            .unwrap_or_else(|| std::env::current_dir().unwrap_or_default());
        Logger {
            file_path: exe_dir.join("crossclip_debug.log"),
        }
    }

    fn write(&self, level: LogLevel, tag: &str, message: &str) {
        let line = format!(
            "[{}] [{}] [{}] {}\n",
            local_timestamp(),
            level.label(),
            tag,
            message
        );

        // 简单轮转：超过 5MB 时删除旧文件再重建
        if let Ok(metadata) = fs::metadata(&self.file_path) {
            if metadata.len() > 5 * 1024 * 1024 {
                let _ = fs::remove_file(&self.file_path);
            }
        }

        if let Ok(mut file) = fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&self.file_path)
        {
            use std::io::Write;
            let _ = file.write_all(line.as_bytes());
        }

        // 调试构建时同时输出到控制台，便于开发
        #[cfg(debug_assertions)]
        {
            print!("{}", line);
        }
    }
}

static LOGGER: OnceLock<Mutex<Logger>> = OnceLock::new();

/// 初始化日志系统。应在进程启动后尽早调用。
pub fn init() {
    let _ = LOGGER.set(Mutex::new(Logger::new()));
}

/// 写入一条日志。
pub fn log(level: LogLevel, tag: &str, message: &str) {
    if let Some(logger) = LOGGER.get() {
        if let Ok(l) = logger.lock() {
            l.write(level, tag, message);
        }
    }
}

/// 用系统默认程序打开日志文件（等价手机端的打开日志）。
pub fn open_log_file() {
    let path = LOGGER
        .get()
        .and_then(|l| l.lock().ok().map(|g| g.file_path.clone()));

    if let Some(p) = path {
        let wide: Vec<u16> = std::ffi::OsStr::new(&p)
            .encode_wide()
            .chain(Some(0))
            .collect();
        let operation: Vec<u16> = std::ffi::OsStr::new("open")
            .encode_wide()
            .chain(Some(0))
            .collect();
        unsafe {
            windows_sys::Win32::UI::Shell::ShellExecuteW(
                std::ptr::null_mut(),
                operation.as_ptr(),
                wide.as_ptr(),
                std::ptr::null(),
                std::ptr::null(),
                windows_sys::Win32::UI::WindowsAndMessaging::SW_SHOWNORMAL,
            );
        }
    }
}

#[macro_export]
macro_rules! log_info {
    ($tag:expr, $($arg:tt)*) => {
        $crate::logger::log($crate::logger::LogLevel::Info, $tag, &format!($($arg)*))
    };
}

#[macro_export]
macro_rules! log_ok {
    ($tag:expr, $($arg:tt)*) => {
        $crate::logger::log($crate::logger::LogLevel::Ok, $tag, &format!($($arg)*))
    };
}

#[macro_export]
macro_rules! log_warn {
    ($tag:expr, $($arg:tt)*) => {
        $crate::logger::log($crate::logger::LogLevel::Warn, $tag, &format!($($arg)*))
    };
}

#[macro_export]
macro_rules! log_err {
    ($tag:expr, $($arg:tt)*) => {
        $crate::logger::log($crate::logger::LogLevel::Err, $tag, &format!($($arg)*))
    };
}
