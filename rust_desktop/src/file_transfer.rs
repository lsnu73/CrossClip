use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

/// 文件传输分块大小。
///
/// **必须与手机端 `FileUploader.CHUNK_SIZE` / `FileReceiver.CHUNK_SIZE` 保持一致（1MB）**。
/// 旧版为 256KB，请求数为现在的 4 倍，是传输慢的原因之一。
const CHUNK_SIZE: usize = 1024 * 1024;

/// 文件传输阶段
#[derive(Debug, Clone, PartialEq)]
pub enum TransferState {
    Preparing,
    Transferring,
    Completed,
    Failed(String),
    Cancelled,
}

/// 传入文件元数据（来自发送方）
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FilePrepare {
    #[serde(rename = "type")]
    pub msg_type: String,
    pub file_id: String,
    pub filename: String,
    pub file_size: u64,
    pub mime_type: Option<String>,
    pub sender_id: Option<String>,
    /// 发送方给出的整文件 SHA-256（可选，老版本发送端不带该字段）。
    ///
    /// 有了它，接收端才能在**传输开始前**判断目标目录里是否已经有同一个文件，
    /// 从而跳过整轮分块传输；缺失时退化为「照常传输」。
    /// 作为可选字段新增是刻意的：协议只增不改，老客户端仍能正常互传。
    #[serde(default)]
    pub file_hash: Option<String>,
}

/// 文件传输完成信号
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FileComplete {
    #[serde(rename = "type")]
    pub msg_type: String,
    pub file_id: String,
    pub file_hash: String,
}

/// 传入文件的接收状态
struct IncomingTransfer {
    filename: String,
    received_bytes: u64,
    total_chunks: u32,
    chunks_received: u32,
    /// 临时文件路径（接收期间不断追加写入）
    temp_path: PathBuf,
    /// 落盘后的目标路径（尚未去重）
    final_path: PathBuf,
    /// 去重命中：目标目录已存在「同名 + 同大小 + 同哈希」的文件，直接复用它的路径。
    ///
    /// 命中时仍然照常登记传输并留一个空的临时文件，这样即便发送端没识别 `already_exists`
    /// 而继续推送分块，每个分块也能被正常接收、不会中途报错；完成时直接丢弃临时文件。
    dedup_hit: Option<PathBuf>,
}

/// 发出文件的发送状态。
///
/// 注意：这里**只保存文件路径**，不缓存文件内容 —— 大文件（GB 级）才不会撑爆内存，
/// 分块数据在发送时按需从磁盘 seek + read 读取。
#[derive(Debug, Clone)]
pub struct OutgoingTransfer {
    pub file_id: String,
    pub filename: String,
    pub file_size: u64,
    pub sent_bytes: u64,
    pub total_chunks: u32,
    pub chunks_sent: u32,
    pub state: TransferState,
    /// 源文件绝对路径
    pub file_path: PathBuf,
}

/// 文件传输管理器
pub struct FileTransferManager {
    incoming: Arc<Mutex<HashMap<String, IncomingTransfer>>>,
    outgoing: Arc<Mutex<HashMap<String, OutgoingTransfer>>>,
    downloads_dir: PathBuf,
}

impl FileTransferManager {
    pub fn new() -> Self {
        // 用环境变量手动定位下载目录，避免引入 dirs 依赖。
        // 文件统一落在「下载目录/CrossClip」：与手机端默认的 Download/CrossClip 同名，
        // 也避免收到的文件散落在下载根目录里跟别的东西混在一起。
        let downloads_root = if let Ok(userprofile) = std::env::var("USERPROFILE") {
            PathBuf::from(userprofile).join("Downloads")
        } else if let Ok(homedrive) = std::env::var("HOMEDRIVE") {
            let homepath = std::env::var("HOMEPATH").unwrap_or_default();
            PathBuf::from(format!("{}{}", homedrive, homepath)).join("Downloads")
        } else {
            PathBuf::from(".").join("Downloads")
        };
        let downloads_dir = downloads_root.join("CrossClip");

        Self {
            incoming: Arc::new(Mutex::new(HashMap::new())),
            outgoing: Arc::new(Mutex::new(HashMap::new())),
            downloads_dir,
        }
    }

    /// 获取下载目录
    pub fn downloads_dir(&self) -> &PathBuf {
        &self.downloads_dir
    }

    // ==================== 接收文件（手机 → 电脑） ====================

    /// 处理文件准备消息：登记传输状态，并顺带做一次「接收端去重」预检。
    ///
    /// ## 去重的开销模型（为什么是低开销版）
    /// 只做「同名 + 同大小」两个近乎零成本的判定，两者都命中才为**这一个**文件流式算一次
    /// SHA-256。绝不遍历整个目录逐个算哈希 —— 那样在大目录下会不可控地卡住请求线程。
    /// 于是重复发送同一个文件可以瞬间完成，而误判的代价仅是一次单文件哈希。
    pub fn handle_prepare(&self, prepare: &FilePrepare, _current_pin: &str) -> Result<(), String> {
        let safe_filename = sanitize_filename(&prepare.filename);
        let temp_path = self.downloads_dir.join(format!("{}.part", prepare.file_id));
        let final_path = self.downloads_dir.join(&safe_filename);

        fs::create_dir_all(&self.downloads_dir).map_err(|e| format!("创建下载目录失败: {}", e))?;
        // 清空可能残留的同名临时文件
        let _ = fs::remove_file(&temp_path);
        fs::write(&temp_path, b"").map_err(|e| format!("创建临时文件失败: {}", e))?;

        // 发送端没带哈希（老版本客户端）时放弃去重，退化为正常传输
        let dedup_hit = prepare
            .file_hash
            .as_deref()
            .filter(|expected| !expected.is_empty())
            .and_then(|expected| {
                self.find_existing_duplicate(&final_path, prepare.file_size, expected)
            });

        if let Some(existing) = &dedup_hit {
            log_info!(
                "FileTransfer",
                "目标目录已存在相同文件，接收完成后将直接复用: {}",
                existing.display()
            );
        }

        let transfer = IncomingTransfer {
            filename: safe_filename,
            received_bytes: 0,
            total_chunks: ((prepare.file_size + CHUNK_SIZE as u64 - 1) / CHUNK_SIZE as u64) as u32,
            chunks_received: 0,
            temp_path,
            final_path,
            dedup_hit,
        };

        self.incoming
            .lock()
            .unwrap()
            .insert(prepare.file_id.clone(), transfer);

        log_info!(
            "FileTransfer",
            "准备接收文件: {} ({} bytes)",
            prepare.filename,
            prepare.file_size
        );
        Ok(())
    }

    /// 目标路径上是否已有「大小相同且内容哈希一致」的文件；命中则返回该路径。
    ///
    /// 先比大小（读元数据，几乎零成本），只有大小一致才值得付出一次哈希。
    fn find_existing_duplicate(
        &self,
        candidate: &PathBuf,
        expected_size: u64,
        expected_hash: &str,
    ) -> Option<PathBuf> {
        let metadata = fs::metadata(candidate).ok()?;
        if !metadata.is_file() || metadata.len() != expected_size {
            return None;
        }
        let actual_hash = crate::crypto::compute_file_hash(candidate).ok()?;
        if actual_hash.eq_ignore_ascii_case(expected_hash) {
            Some(candidate.clone())
        } else {
            None
        }
    }

    /// 本次接收是否命中了去重。
    /// 供 HTTP 层在 `prepare` 响应里告诉发送端「别再传分块了」。
    pub fn is_dedup_hit(&self, file_id: &str) -> bool {
        self.incoming
            .lock()
            .unwrap()
            .get(file_id)
            .map(|transfer| transfer.dedup_hit.is_some())
            .unwrap_or(false)
    }

    /// 当前接收进度：`(已接收字节数, 总字节数估算)`，供 UI 绘制进度条。
    ///
    /// 总字节数按「分块数 × 分块大小」估算，最多比真实值多出一个分块 ——
    /// 对进度条而言这点误差没有意义，却能省下在每个传输记录里再存一份精确大小。
    pub fn incoming_progress(&self, file_id: &str) -> Option<(u64, u64)> {
        self.incoming.lock().unwrap().get(file_id).map(|transfer| {
            (
                transfer.received_bytes,
                transfer.total_chunks as u64 * CHUNK_SIZE as u64,
            )
        })
    }

    /// 处理文件分块（二进制密文，已解出为原始明文的字节由本函数解密后追加写入）
    ///
    /// @param payload `nonce||ciphertext||tag` 形式的 AES-GCM 密文
    /// @return (已接收块数, 总块数)
    pub fn handle_chunk(
        &self,
        file_id: &str,
        chunk_index: u32,
        total_chunks: u32,
        payload: &[u8],
        current_pin: &str,
    ) -> Result<(u32, u32), String> {
        // 解密在锁外进行，避免长时间持锁阻塞其它请求
        let plain = crate::crypto::decrypt_bytes_raw(payload, current_pin)
            .map_err(|e| format!("解密文件块失败: {}", e))?;

        let mut incoming = self.incoming.lock().unwrap();
        let transfer = incoming
            .get_mut(file_id)
            .ok_or_else(|| format!("未知的文件传输 ID: {}", file_id))?;

        let mut file = fs::OpenOptions::new()
            .append(true)
            .open(&transfer.temp_path)
            .map_err(|e| format!("打开临时文件失败: {}", e))?;
        file.write_all(&plain)
            .map_err(|e| format!("写入文件块失败: {}", e))?;

        transfer.received_bytes += plain.len() as u64;
        transfer.chunks_received = chunk_index + 1;
        if total_chunks > 0 {
            transfer.total_chunks = total_chunks;
        }

        Ok((transfer.chunks_received, transfer.total_chunks))
    }

    /// 处理传输完成：流式校验哈希 → 重命名为最终文件（自动规避重名）
    /// 处理传输完成：命中过去重则直接复用已有文件，否则流式校验哈希后落盘。
    ///
    /// @return `(最终文件路径, 是否命中去重)`
    pub fn handle_complete(
        &self,
        complete: &FileComplete,
        _current_pin: &str,
    ) -> Result<(PathBuf, bool), String> {
        let transfer = self
            .incoming
            .lock()
            .unwrap()
            .remove(&complete.file_id)
            .ok_or_else(|| format!("未知的文件传输 ID: {}", complete.file_id))?;

        // 去重命中：目标目录里已经有同名、同大小、同内容的文件。
        // 这里把收到的临时数据直接丢掉 —— 无论发送端是否识别了 `already_exists`
        // 而跳过分块，这条路径都成立。
        if let Some(existing) = transfer.dedup_hit {
            let _ = fs::remove_file(&transfer.temp_path);
            log_info!(
                "FileTransfer",
                "文件已存在，跳过落盘并复用: {}",
                existing.display()
            );
            return Ok((existing, true));
        }

        // 流式计算哈希：不把整个文件读进内存，GB 级文件也安全
        let actual_hash = crate::crypto::compute_file_hash(&transfer.temp_path)?;
        if actual_hash != complete.file_hash {
            let _ = fs::remove_file(&transfer.temp_path);
            return Err(format!(
                "文件哈希不匹配: 期望={}, 实际={}",
                complete.file_hash, actual_hash
            ));
        }

        let final_path = unique_path(&transfer.final_path);
        fs::rename(&transfer.temp_path, &final_path)
            .map_err(|e| format!("重命名文件失败: {}", e))?;

        log_ok!(
            "FileTransfer",
            "文件接收完成: {} -> {}",
            transfer.filename,
            final_path.display()
        );
        Ok((final_path, false))
    }

    /// 取消接收并清理临时文件
    pub fn cancel_incoming(&self, file_id: &str) {
        let mut incoming = self.incoming.lock().unwrap();
        if let Some(transfer) = incoming.remove(file_id) {
            let _ = fs::remove_file(&transfer.temp_path);
        }
    }

    // ==================== 发送文件（电脑 → 手机） ====================

    /// 登记一个待发送的文件（只记录路径与大小，不加载内容）
    pub fn prepare_outgoing(&self, file_path: &str) -> Result<OutgoingTransfer, String> {
        let path = PathBuf::from(file_path);
        if !path.exists() {
            return Err(format!("文件不存在: {}", file_path));
        }

        let metadata = fs::metadata(&path).map_err(|e| format!("读取文件信息失败: {}", e))?;
        let file_size = metadata.len();
        let filename = path
            .file_name()
            .unwrap_or_default()
            .to_string_lossy()
            .to_string();

        let total_chunks = ((file_size + CHUNK_SIZE as u64 - 1) / CHUNK_SIZE as u64) as u32;
        let file_id = format!(
            "{:016x}_{:08x}",
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis(),
            rand::random::<u32>()
        );

        let transfer = OutgoingTransfer {
            file_id: file_id.clone(),
            filename,
            file_size,
            sent_bytes: 0,
            total_chunks,
            chunks_sent: 0,
            state: TransferState::Preparing,
            file_path: path,
        };

        self.outgoing
            .lock()
            .unwrap()
            .insert(file_id, transfer.clone());
        Ok(transfer)
    }

    /// 读取指定分块并加密为**二进制密文**（`nonce||ciphertext||tag`）。
    ///
    /// 采用「先取元信息 → 释放锁 → 读盘加密」的顺序，避免大分块的磁盘 IO 阻塞其它线程。
    pub fn get_chunk_encrypted_bytes(
        &self,
        file_id: &str,
        chunk_index: u32,
        current_pin: &str,
    ) -> Result<Vec<u8>, String> {
        let (path, offset, len, file_size) = {
            let outgoing = self.outgoing.lock().unwrap();
            let transfer = outgoing
                .get(file_id)
                .ok_or_else(|| format!("未知的文件传输 ID: {}", file_id))?;

            let offset = chunk_index as u64 * CHUNK_SIZE as u64;
            if offset >= transfer.file_size {
                return Err("分块索引越界".to_string());
            }
            let len = std::cmp::min(CHUNK_SIZE as u64, transfer.file_size - offset) as usize;
            (transfer.file_path.clone(), offset, len, transfer.file_size)
        };
        let _ = file_size;

        // 按需 seek + read，只把当前分块载入内存
        let mut file = fs::File::open(&path).map_err(|e| format!("打开源文件失败: {}", e))?;
        file.seek(SeekFrom::Start(offset))
            .map_err(|e| format!("定位文件偏移失败: {}", e))?;
        let mut buffer = vec![0u8; len];
        file.read_exact(&mut buffer)
            .map_err(|e| format!("读取文件分块失败: {}", e))?;

        crate::crypto::encrypt_bytes_raw(&buffer, current_pin)
    }

    /// 更新发送进度
    pub fn update_send_progress(&self, file_id: &str, chunk_sent: u32) {
        let mut outgoing = self.outgoing.lock().unwrap();
        if let Some(transfer) = outgoing.get_mut(file_id) {
            transfer.chunks_sent = chunk_sent;
            transfer.sent_bytes =
                std::cmp::min(chunk_sent as u64 * CHUNK_SIZE as u64, transfer.file_size);
            transfer.state = TransferState::Transferring;
        }
    }

    /// 获取整文件 SHA-256（流式，用于发送完成后的校验）
    pub fn get_file_hash(&self, file_id: &str) -> Option<String> {
        let path = {
            let outgoing = self.outgoing.lock().unwrap();
            outgoing.get(file_id).map(|t| t.file_path.clone())
        }?;
        crate::crypto::compute_file_hash(&path).ok()
    }

    /// 清理已完成的发送任务
    pub fn cleanup_outgoing(&self, file_id: &str) {
        self.outgoing.lock().unwrap().remove(file_id);
    }
}

/// 文件名安全化（移除路径分隔符和危险字符）
fn sanitize_filename(name: &str) -> String {
    let safe: String = name
        .chars()
        .map(|c| match c {
            '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|' => '_',
            c if c.is_control() => '_',
            c => c,
        })
        .collect();

    if safe.is_empty() {
        "unnamed_file".to_string()
    } else if safe.len() > 200 {
        // 截断过长文件名，避免超出文件系统限制
        let mut end = 200;
        while end > 0 && !safe.is_char_boundary(end) {
            end -= 1;
        }
        format!("{}...", &safe[..end])
    } else {
        safe
    }
}

/// 生成不冲突的文件路径（同名时追加 ` (1)`、` (2)` …）
fn unique_path(path: &PathBuf) -> PathBuf {
    if !path.exists() {
        return path.clone();
    }

    let stem = path
        .file_stem()
        .unwrap_or_default()
        .to_string_lossy()
        .to_string();
    let ext = path
        .extension()
        .map(|e| format!(".{}", e.to_string_lossy()))
        .unwrap_or_default();
    let parent = path.parent().unwrap_or(std::path::Path::new("."));

    for i in 1..10000 {
        let candidate = parent.join(format!("{} ({}){}", stem, i, ext));
        if !candidate.exists() {
            return candidate;
        }
    }

    let ts = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    parent.join(format!("{}_{}{}", stem, ts, ext))
}
