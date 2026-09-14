//! 构建脚本：把多尺寸应用图标嵌入 exe 的资源节。
//!
//! 嵌入资源依赖 Windows SDK 里的 `rc.exe`。**找不到时只打印警告、绝不让构建失败** ——
//! 缺一个图标只是外观问题，不该让任何人在 clone 之后连 `cargo build` 都跑不通。

use std::path::PathBuf;
use std::process::Command;

fn main() {
    println!("cargo:rerun-if-changed=app.rc");
    println!("cargo:rerun-if-changed=assets/app.ico");

    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("windows") {
        return;
    }

    let manifest_dir = match std::env::var("CARGO_MANIFEST_DIR") {
        Ok(dir) => PathBuf::from(dir),
        Err(_) => return,
    };
    let out_dir = match std::env::var("OUT_DIR") {
        Ok(dir) => PathBuf::from(dir),
        Err(_) => return,
    };

    let rc_exe = match find_rc() {
        Some(path) => path,
        None => {
            println!("cargo:warning=未找到 rc.exe（Windows SDK），已跳过嵌入 exe 图标");
            return;
        }
    };

    let res_path = out_dir.join("crossclip.res");
    let rc_file = manifest_dir.join("app.rc");

    let status = Command::new(&rc_exe)
        .arg("/nologo")
        .arg("/fo")
        .arg(&res_path)
        .arg(&rc_file)
        .status();

    match status {
        Ok(code) if code.success() => {
            // 注意：link-arg 的值**不能加引号** —— 引号会被原样带进链接器命令行，
            // 让 link.exe 把整个路径当成非法文件名（表现为 LNK1104 打不开 `\C:\...\.obj`）。
            println!("cargo:rustc-link-arg={}", res_path.display());
        }
        Ok(code) => println!(
            "cargo:warning=rc.exe 退出码 {:?}，已跳过嵌入 exe 图标",
            code.code()
        ),
        Err(err) => println!("cargo:warning=调用 rc.exe 失败（{err}），已跳过嵌入 exe 图标"),
    }
}

/// 定位 rc.exe：优先 PATH，其次 Windows Kits 目录里版本号最高的那一份。
fn find_rc() -> Option<PathBuf> {
    if let Ok(output) = Command::new("where").arg("rc.exe").output() {
        if output.status.success() {
            let text = String::from_utf8_lossy(&output.stdout);
            if let Some(first_line) = text.lines().next() {
                let candidate = PathBuf::from(first_line.trim());
                if candidate.exists() {
                    return Some(candidate);
                }
            }
        }
    }

    let kits_bin = PathBuf::from(r"C:\Program Files (x86)\Windows Kits\10\bin");
    let mut versions: Vec<PathBuf> = std::fs::read_dir(kits_bin)
        .ok()?
        .flatten()
        .map(|entry| entry.path())
        .collect();
    // 版本号目录按名称排序后从高到低找（10.0.26100.0 > 10.0.22621.0 …）
    versions.sort();
    for version_dir in versions.into_iter().rev() {
        for arch in ["x64", "x86", "arm64"] {
            let candidate = version_dir.join(arch).join("rc.exe");
            if candidate.exists() {
                return Some(candidate);
            }
        }
    }
    None
}
