//! 应用图标：从随程序编译进来的 .ico 创建 HICON。
//!
//! 图标用 `include_bytes!` 编入二进制而不是运行时读文件 —— CrossClip 是单文件绿色版，
//! 用户会把 exe 复制到任意位置，图标不能依赖旁边还有别的文件。
//!
//! 同一份 ICO 也会被 `build.rs` 嵌进 exe 的资源节，作为「文件本身」的图标
//! （资源管理器 / 任务栏显示的那个），两者必须是同一张图。

use std::ptr::null_mut;

use windows_sys::Win32::UI::WindowsAndMessaging::{CreateIconFromResourceEx, HICON};

/// 多尺寸 ICO（16 / 24 / 32 / 48 / 64 / 128 / 256），由 `build.rs` 一起嵌入 exe 资源
static ICO_BYTES: &[u8] = include_bytes!("../assets/app.ico");

/// 从嵌入的 ICO 中挑一个最接近 `size` 的条目并创建 HICON。
///
/// 任何解析异常都返回空句柄（调用方回退到系统默认图标），**绝不 panic**：
/// 图标只是外观，不该因为图标读不出来就让整个程序起不来。
pub unsafe fn load_app_icon(size: i32) -> HICON {
    let data: &[u8] = ICO_BYTES;

    // ICONDIR: reserved(2) | type(2, 1=icon) | count(2)
    if data.len() < 6 || data[0] != 0 || data[1] != 0 || data[2] != 1 || data[3] != 0 {
        return null_mut();
    }
    let count = u16::from_le_bytes([data[4], data[5]]) as usize;
    if count == 0 || data.len() < 6 + count * 16 {
        return null_mut();
    }

    // 在目录里挑尺寸最接近目标的一条
    let mut best_diff = i32::MAX;
    let mut best_offset = 0usize;
    let mut best_len = 0usize;
    for index in 0..count {
        let base = 6 + index * 16;
        // 目录中的宽高字段为 0 表示 256
        let width = if data[base] == 0 { 256 } else { data[base] as i32 };
        let bytes = u32::from_le_bytes([
            data[base + 8],
            data[base + 9],
            data[base + 10],
            data[base + 11],
        ]) as usize;
        let offset = u32::from_le_bytes([
            data[base + 12],
            data[base + 13],
            data[base + 14],
            data[base + 15],
        ]) as usize;

        if bytes == 0 || offset + bytes > data.len() {
            continue;
        }
        let diff = (width - size).abs();
        if diff < best_diff {
            best_diff = diff;
            best_offset = offset;
            best_len = bytes;
        }
    }

    if best_len == 0 {
        return null_mut();
    }

    CreateIconFromResourceEx(
        data[best_offset..best_offset + best_len].as_ptr(),
        best_len as u32,
        1,          // fIcon = TRUE
        0x0003_0000, // dwVersion = 3.0
        size,
        size,
        0, // LR_DEFAULTCOLOR
    )
}
