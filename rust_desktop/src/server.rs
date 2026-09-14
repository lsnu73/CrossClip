use serde::{Deserialize, Serialize};
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::sync::mpsc::{channel, Sender};
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tiny_http::{Header, Method, Request, Response, Server, StatusCode};

use crate::file_transfer::FileTransferManager;

struct WorkerPool {
    sender: Sender<Box<dyn FnOnce() + Send + 'static>>,
}

impl WorkerPool {
    fn new(size: usize) -> Self {
        let (tx, rx) = channel::<Box<dyn FnOnce() + Send + 'static>>();
        let rx = Arc::new(Mutex::new(rx));
        for _ in 0..size {
            let rx = rx.clone();
            std::thread::spawn(move || {
                while let Ok(job) = {
                    let lock = rx.lock().unwrap();
                    lock.recv()
                } {
                    job();
                }
            });
        }
        Self { sender: tx }
    }

    fn execute<F>(&self, f: F)
    where
        F: FnOnce() + Send + 'static,
    {
        let _ = self.sender.send(Box::new(f));
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ClientPeer {
    pub ip: String,
    pub port: u16,
    pub device_id: String,
    pub device_name: String,
    pub last_seen: u64,
}

#[derive(Deserialize)]
#[allow(dead_code)]
struct AuthRequest {
    auth_cipher: Option<String>,
    pin: Option<String>,
    pin_hash: Option<String>,
    client_port: Option<u16>,
    device_id: Option<String>,
    device_name: Option<String>,
    timestamp: Option<u64>,
}

#[derive(Deserialize)]
struct AuthChallengePayload {
    timestamp: u64,
    device_id: Option<String>,
    device_name: Option<String>,
    client_port: Option<u16>,
}

#[derive(Deserialize)]
struct HeartbeatRequest {
    pin_hash: Option<String>,
    client_port: Option<u16>,
    device_id: Option<String>,
    device_name: Option<String>,
}

#[derive(Deserialize)]
#[allow(dead_code)]
struct SyncRequest {
    encrypted: String,
    hash: String,
    sender_id: Option<String>,
    timestamp: Option<u64>,
}

fn extract_pin_from_query(url: &str) -> Option<String> {
    let query = url.split_once('?')?.1;
    for pair in query.split('&') {
        let mut parts = pair.splitn(2, '=');
        if parts.next() == Some("pin") {
            return parts.next().map(|v| v.to_string());
        }
    }
    None
}

/// 解析 URL 的全部 query 参数为键值对。
///
/// 文件分块的元数据（file_id / index / total）通过 query 传递，
/// 其中 file_id 由时间戳+随机数构成、不含需要转义的字符，因此这里不做 URL 解码。
fn parse_query_params(url: &str) -> std::collections::HashMap<String, String> {
    let mut map = std::collections::HashMap::new();
    if let Some((_, query)) = url.split_once('?') {
        for pair in query.split('&') {
            if let Some((key, value)) = pair.split_once('=') {
                map.insert(key.to_string(), value.to_string());
            }
        }
    }
    map
}

#[derive(Serialize)]
struct StatusResponse {
    status: String,
    auth: Option<bool>,
    device_name: Option<String>,
    device_id: Option<String>,
    message: Option<String>,
}

#[derive(Clone)]
pub struct Broadcaster {
    clients: Arc<Mutex<Vec<Sender<String>>>>,
    peers: Arc<RwLock<Vec<ClientPeer>>>,
    pin_code: Arc<RwLock<String>>,
    device_id: String,
}

impl Broadcaster {
    pub fn device_id(&self) -> &str {
        &self.device_id
    }

    pub fn new(pin_code: Arc<RwLock<String>>, device_id: String) -> Self {
        let broadcaster = Self {
            clients: Arc::new(Mutex::new(Vec::new())),
            peers: Arc::new(RwLock::new(Vec::new())),
            pin_code,
            device_id,
        };

        let peers_clone = broadcaster.peers.clone();
        let clients_clone = broadcaster.clients.clone();
        std::thread::spawn(move || loop {
            std::thread::sleep(Duration::from_secs(30));
            let now = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs();

            {
                let mut peers = peers_clone.write().unwrap();
                peers.retain(|p| now.saturating_sub(p.last_seen) < 600);
            }
            {
                let mut clients = clients_clone.lock().unwrap();
                clients.retain(|client| client.send(": keepalive\n\n".to_string()).is_ok());
            }
        });

        broadcaster
    }

    pub fn register_peer(&self, ip: String, port: u16, device_id: String, device_name: String) {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
        let mut peers = self.peers.write().unwrap();
        if let Some(existing) = peers.iter_mut().find(|p| p.ip == ip) {
            existing.last_seen = now;
            existing.port = port;
            if !device_id.is_empty() {
                existing.device_id = device_id;
            }
            if !device_name.is_empty() {
                existing.device_name = device_name;
            }
        } else {
            peers.push(ClientPeer {
                ip,
                port,
                device_id,
                device_name,
                last_seen: now,
            });
        }
    }

    pub fn broadcast_text(&self, text: &str) {
        if text.trim().is_empty() {
            return;
        }
        let current_pin = self.pin_code.read().unwrap().clone();
        if let Ok(encrypted) = crate::crypto::encrypt(text, &current_pin) {
            let hash = crate::crypto::compute_hash(text);
            let now_ms = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as u64;

            let payload = serde_json::json!({
                "type": "CLIP_SYNC",
                "encrypted": encrypted,
                "hash": hash,
                "sender_id": self.device_id,
                "timestamp": now_ms
            })
            .to_string();

            // 1. 毫秒级优先推送到所有活跃的 SSE 长连接（手机端直达通道）
            let msg = format!("data: {}\n\n", payload);
            {
                let mut clients = self.clients.lock().unwrap();
                clients.retain(|client| client.send(msg.clone()).is_ok());
            }

            // 2. 异步向局域网已注册的对等节点做 HTTP POST 兜底通知，不阻塞主流程
            let peers: Vec<ClientPeer> = self.peers.read().unwrap().clone();
            if !peers.is_empty() {
                std::thread::spawn(move || {
                    for peer in peers {
                        let ip = peer.ip.clone();
                        let port = peer.port;
                        let payload_clone = payload.clone();
                        let _ = send_raw_http_post(&ip, port, "/sync", &payload_clone);
                    }
                });
            }
        }
    }

    pub fn disconnect_all(&self) {
        let mut clients = self.clients.lock().unwrap();
        clients.clear();
        let mut peers = self.peers.write().unwrap();
        peers.clear();
    }

    /// 获取当前已注册的对等节点列表（供文件传输等模块使用）
    pub fn get_peers(&self) -> Vec<ClientPeer> {
        self.peers.read().unwrap().clone()
    }

    /// 摘除指定 IP 的对等节点（手机端主动断开、或 SSE 长连接结束时调用）。
    ///
    /// peers 表原本只在 600 秒无心跳后才被修剪，于是手机端早已断开、托盘菜单却还挂着
    /// 「已连接手机」整整 10 分钟 —— 这正是要修的状态判断错误。连接真正结束时就地摘除，
    /// 600 秒心跳超时退化为「连 TCP 都不通知一声就消失」场景的兜底。
    pub fn unregister_peer(&self, ip: &str) {
        let mut peers = self.peers.write().unwrap();
        let before = peers.len();
        peers.retain(|p| p.ip != ip);
        if peers.len() != before {
            println!("[Server] 已摘除对等节点: {}", ip);
        }
    }
}

fn send_raw_http_post(ip: &str, port: u16, path: &str, json_body: &str) -> bool {
    let addr_str = format!("{}:{}", ip, port);
    let socket_addr: SocketAddr = match addr_str.parse() {
        Ok(addr) => addr,
        Err(_) => return false,
    };

    if let Ok(mut stream) = TcpStream::connect_timeout(&socket_addr, Duration::from_millis(2000)) {
        let _ = stream.set_write_timeout(Some(Duration::from_millis(2000)));
        let _ = stream.set_read_timeout(Some(Duration::from_millis(2000)));
        let body_bytes = json_body.as_bytes();
        let request = format!(
            "POST {} HTTP/1.1\r\nHost: {}:{}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            path, ip, port, body_bytes.len()
        );
        if stream.write_all(request.as_bytes()).is_ok() && stream.write_all(body_bytes).is_ok() {
            let _ = stream.flush();
            let mut buf = [0u8; 128];
            let _ = stream.read(&mut buf);
            return true;
        }
    }
    false
}

/// 在一个已建立的连接上写出一个 HTTP 请求（HTTP/1.1 keep-alive）
fn write_http_request(
    writer: &mut TcpStream,
    host: &str,
    path: &str,
    content_type: &str,
    body: &[u8],
) -> Result<(), String> {
    let header = format!(
        "POST {} HTTP/1.1\r\nHost: {}\r\nContent-Type: {}\r\nContent-Length: {}\r\nConnection: keep-alive\r\n\r\n",
        path,
        host,
        content_type,
        body.len()
    );
    writer
        .write_all(header.as_bytes())
        .map_err(|e| format!("写请求头失败: {}", e))?;
    writer
        .write_all(body)
        .map_err(|e| format!("写请求体失败: {}", e))?;
    writer.flush().map_err(|e| format!("刷新连接失败: {}", e))?;
    Ok(())
}

/// 读取并完整消费一个 HTTP 响应，返回响应体文本。
///
/// 必须把响应体读干净，否则残留字节会与下一个请求的响应「串包」，
/// 这是 keep-alive 复用连接时最容易踩的坑。
fn read_http_response(reader: &mut BufReader<TcpStream>) -> Result<String, String> {
    // 1. 状态行
    let mut status_line = String::new();
    reader
        .read_line(&mut status_line)
        .map_err(|e| format!("读取状态行失败: {}", e))?;
    if status_line.is_empty() {
        return Err("连接已被对端关闭".to_string());
    }

    // 2. 响应头：找到 Content-Length
    let mut content_length = 0usize;
    loop {
        let mut header_line = String::new();
        let n = reader
            .read_line(&mut header_line)
            .map_err(|e| format!("读取响应头失败: {}", e))?;
        if n == 0 {
            return Err("连接已被对端关闭".to_string());
        }
        if header_line == "\r\n" || header_line == "\n" {
            break;
        }
        let lower = header_line.to_ascii_lowercase();
        if let Some(rest) = lower.strip_prefix("content-length:") {
            content_length = rest.trim().parse::<usize>().unwrap_or(0);
        }
    }

    // 3. 响应体
    if content_length > 0 {
        let mut body = vec![0u8; content_length];
        reader
            .read_exact(&mut body)
            .map_err(|e| format!("读取响应体失败: {}", e))?;
        Ok(String::from_utf8_lossy(&body).to_string())
    } else {
        Ok(String::new())
    }
}

/// 一次「电脑 → 手机」文件发送的结果。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SendOutcome {
    /// 文件已完整传输并在手机端落盘
    Sent,
    /// 手机端已存在同名同内容的文件，本次一个分块都没传
    SkippedExisting,
}

/// 把文件完整发送到手机端（prepare → chunk×N → complete）。
///
/// ## 关键性能优化
/// 整个文件的**所有分块复用同一条 TCP 连接**（HTTP keep-alive）。
/// 旧实现每个分块都 `TcpStream::connect_timeout` 新建连接并以 `Connection: close` 收尾，
/// 传输 100MB 文件会产生上千次 TCP 三次握手，是速度慢的主因之一。
///
/// ## 接收端去重
/// `prepare` 请求里带上整文件哈希。手机端若发现目标目录已有「同名 + 同大小 + 同哈希」
/// 的文件，会在响应里回 `already_exists: true`；此时**一个分块都不发**，直接返回
/// [`SendOutcome::SkippedExisting`]。老版本手机端不认识这个字段，行为与从前完全一致。
///
/// @param on_progress 每完成一个分块回调一次 `(已完成块数, 总块数)`
pub fn send_file_to_phone(
    ip: &str,
    port: u16,
    file_manager: &crate::file_transfer::FileTransferManager,
    transfer: &crate::file_transfer::OutgoingTransfer,
    pin: &str,
    file_hash: &str,
    sender_id: &str,
    mut on_progress: impl FnMut(u32, u32),
) -> Result<SendOutcome, String> {
    let host = format!("{}:{}", ip, port);
    let socket_addr: SocketAddr = host
        .parse()
        .map_err(|_| format!("非法的目标地址: {}", host))?;

    let stream = TcpStream::connect_timeout(&socket_addr, Duration::from_secs(5))
        .map_err(|e| format!("连接手机失败: {}", e))?;
    // 关闭 Nagle：每个分块都是一次独立的请求-响应往返，攒包只会徒增延迟
    let _ = stream.set_nodelay(true);
    let _ = stream.set_write_timeout(Some(Duration::from_secs(60)));
    let _ = stream.set_read_timeout(Some(Duration::from_secs(60)));

    let mut writer = stream
        .try_clone()
        .map_err(|e| format!("复制连接句柄失败: {}", e))?;
    let mut reader = BufReader::new(stream);

    // ---------- 1. 发送文件元数据（含整文件哈希，供接收端做去重判定） ----------
    let prepare_body = serde_json::json!({
        "type": "FILE_PREPARE",
        "file_id": transfer.file_id,
        "filename": transfer.filename,
        "file_size": transfer.file_size,
        "mime_type": "application/octet-stream",
        "sender_id": sender_id,
        "file_hash": file_hash,
    })
    .to_string();
    write_http_request(
        &mut writer,
        &host,
        "/file/prepare",
        "application/json; charset=utf-8",
        prepare_body.as_bytes(),
    )?;

    let prepare_response = read_http_response(&mut reader)?;
    // 接收端已有同一文件：一个分块都不用发
    let skipped_existing = prepare_response.contains("\"already_exists\":true");

    if skipped_existing {
        println!(
            "[FileTransfer] 手机端已存在相同文件，跳过全部分块: {}",
            transfer.filename
        );
    } else {
        // ---------- 2. 逐块发送（复用同一连接） ----------
        for idx in 0..transfer.total_chunks {
            let encrypted = file_manager.get_chunk_encrypted_bytes(&transfer.file_id, idx, pin)?;
            let path = format!(
                "/file/chunk?file_id={}&index={}&total={}",
                transfer.file_id, idx, transfer.total_chunks
            );
            write_http_request(&mut writer, &host, &path, "application/octet-stream", &encrypted)?;
            let _ = read_http_response(&mut reader)?;
            on_progress(idx + 1, transfer.total_chunks);
        }
    }

    // ---------- 3. 发送完成信号（携带整文件哈希供接收端校验） ----------
    //
    // **即使一个分块都没发，也必须走完这一步。** 它是接收端「本轮传输结束」的唯一信号：
    // 接收端据此收起进行中的提示、完成校验与落盘（或复用已有文件）、回收传输记录。
    // 早先的实现在去重命中时直接 return，后果有两个：
    //   - 手机端通知永远停在「正在接收文件」（不定进度条还会一直循环）；
    //   - 两端的传输记录都不会被回收，电脑端 incoming 表会持续增长。
    let complete_body = serde_json::json!({
        "type": "FILE_COMPLETE",
        "file_id": transfer.file_id,
        "file_hash": file_hash,
    })
    .to_string();
    write_http_request(
        &mut writer,
        &host,
        "/file/complete",
        "application/json; charset=utf-8",
        complete_body.as_bytes(),
    )?;
    let _ = read_http_response(&mut reader)?;

    Ok(if skipped_existing {
        SendOutcome::SkippedExisting
    } else {
        SendOutcome::Sent
    })
}

pub fn start_http_server(
    port: u16,
    pin_code: Arc<RwLock<String>>,
    device_name: String,
    broadcaster: Broadcaster,
    on_text_received: Arc<dyn Fn(String, String) + Send + Sync + 'static>,
    file_manager: Arc<FileTransferManager>,
) {
    std::thread::spawn(move || {
        let addr = format!("0.0.0.0:{}", port);
        let server = match Server::http(&addr) {
            Ok(s) => Arc::new(s),
            Err(e) => {
                eprintln!("[HTTP] 无法绑定端口 {}: {}", port, e);
                return;
            }
        };

        let pool = Arc::new(WorkerPool::new(16));

        for request in server.incoming_requests() {
            let pin_code_clone = pin_code.clone();
            let device_name_clone = device_name.clone();
            let broadcaster_clone = broadcaster.clone();
            let on_received_clone = on_text_received.clone();
            let file_manager_clone = file_manager.clone();

            if request.url().starts_with("/events") {
                // SSE 长连接会持久阻塞在读取循环，使用独立线程处理，绝不耗尽普通 API 线程池
                std::thread::spawn(move || {
                    handle_client_request(
                        request,
                        pin_code_clone,
                        device_name_clone,
                        broadcaster_clone,
                        on_received_clone,
                        file_manager_clone,
                    );
                });
            } else {
                pool.execute(move || {
                    handle_client_request(
                        request,
                        pin_code_clone,
                        device_name_clone,
                        broadcaster_clone,
                        on_received_clone,
                        file_manager_clone,
                    );
                });
            }
        }
    });
}

fn handle_client_request(
    mut request: Request,
    pin_code: Arc<RwLock<String>>,
    device_name: String,
    broadcaster: Broadcaster,
    on_text_received: Arc<dyn Fn(String, String) + Send + Sync + 'static>,
    file_manager: Arc<FileTransferManager>,
) {
    let cors_header = Header::from_bytes(&b"Access-Control-Allow-Origin"[..], &b"*"[..]).unwrap();
    let cors_headers_allow = Header::from_bytes(
        &b"Access-Control-Allow-Headers"[..],
        &b"Content-Type, Accept, Authorization"[..],
    )
    .unwrap();
    let content_type = Header::from_bytes(
        &b"Content-Type"[..],
        &b"application/json; charset=utf-8"[..],
    )
    .unwrap();

    let client_ip = request
        .remote_addr()
        .map(|a| a.ip().to_string())
        .unwrap_or_else(|| "127.0.0.1".to_string());

    let url = request.url().to_string();
    let path = url.split('?').next().unwrap_or("").to_string();
    let method = request.method().clone();

    if method == Method::Options {
        let resp = Response::empty(StatusCode(200))
            .with_header(cors_header)
            .with_header(cors_headers_allow);
        let _ = request.respond(resp);
        return;
    }

    if path == "/ping" && method == Method::Get {
        let resp_data = serde_json::to_string(&StatusResponse {
            status: "ok".to_string(),
            auth: None,
            device_name: Some(device_name),
            device_id: Some(broadcaster.device_id().to_string()),
            message: None,
        })
        .unwrap();

        let resp = Response::from_string(resp_data)
            .with_header(cors_header)
            .with_header(content_type);
        let _ = request.respond(resp);
        return;
    }

    if path == "/heartbeat" && method == Method::Post {
        let mut body = String::new();
        if request.as_reader().read_to_string(&mut body).is_ok() {
            if let Ok(req) = serde_json::from_str::<HeartbeatRequest>(&body) {
                let current_pin = pin_code.read().unwrap().clone();
                let my_hash = crate::crypto::compute_hash(&current_pin);
                let my_hash_prefix = &my_hash[..16.min(my_hash.len())];
                if req.pin_hash.as_deref() == Some(my_hash_prefix) {
                    let port = req.client_port.unwrap_or(18237);
                    let dev_id = req.device_id.unwrap_or_default();
                    let dev_name = req.device_name.unwrap_or_default();
                    broadcaster.register_peer(client_ip, port, dev_id, dev_name);
                    let resp = Response::from_string(r#"{"status":"ok"}"#)
                        .with_header(cors_header)
                        .with_header(content_type);
                    let _ = request.respond(resp);
                    return;
                }
            }
        }
        let resp = Response::from_string(r#"{"status":"error"}"#)
            .with_status_code(StatusCode(403))
            .with_header(cors_header);
        let _ = request.respond(resp);
        return;
    }

    // 手机端点了「断开连接」时主动打招呼：立即摘掉它的对等节点，
    // 不必等 SSE 写失败（最长 30 秒）或心跳超时（600 秒）。
    // 与别的端点一样用 pin_hash 校验，避免任意设备把别人的连接记录踢掉。
    if path == "/disconnect" && method == Method::Post {
        let mut body = String::new();
        if request.as_reader().read_to_string(&mut body).is_ok() {
            if let Ok(req) = serde_json::from_str::<HeartbeatRequest>(&body) {
                let current_pin = pin_code.read().unwrap().clone();
                let my_hash = crate::crypto::compute_hash(&current_pin);
                let my_hash_prefix = &my_hash[..16.min(my_hash.len())];
                if req.pin_hash.as_deref() == Some(my_hash_prefix) {
                    broadcaster.unregister_peer(&client_ip);
                    let resp = Response::from_string(r#"{"status":"ok","message":"disconnected"}"#)
                        .with_header(cors_header)
                        .with_header(content_type);
                    let _ = request.respond(resp);
                    return;
                }
            }
        }
        let resp = Response::from_string(r#"{"status":"error"}"#)
            .with_status_code(StatusCode(403))
            .with_header(cors_header);
        let _ = request.respond(resp);
        return;
    }

    if path == "/events" && method == Method::Get {
        let current_pin = pin_code.read().unwrap().clone();
        let query_pin = extract_pin_from_query(&url).unwrap_or_default();
        if query_pin.trim() != current_pin.trim() {
            let resp_data = serde_json::to_string(&StatusResponse {
                status: "error".to_string(),
                auth: Some(false),
                device_name: None,
                device_id: None,
                message: Some("pin_mismatch".to_string()),
            })
            .unwrap();
            let resp = Response::from_string(resp_data)
                .with_status_code(StatusCode(403))
                .with_header(cors_header)
                .with_header(content_type);
            let _ = request.respond(resp);
            return;
        }

        // clone 而非 move：这个连接结束时还要用同一个 IP 把自己从 peers 里摘掉
        broadcaster.register_peer(client_ip.clone(), 18237, "android_phone".to_string(), "安卓手机".to_string());

        let (tx, rx) = channel::<String>();
        {
            let mut clients = broadcaster.clients.lock().unwrap();
            clients.push(tx);
        }

        let mut writer = request.into_writer();
        let response_line = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-cache, no-transform\r\nConnection: keep-alive\r\nAccess-Control-Allow-Origin: *\r\n\r\n";
        if writer.write_all(response_line.as_bytes()).is_err() {
            // 响应头都没能写出去，这条 SSE 对手机端等于不存在 ——
            // 回滚掉上面刚做的注册，别让托盘凭一个假的连接显示「已连接手机」
            broadcaster.unregister_peer(&client_ip);
            return;
        }
        let _ = writer.flush();

        let _ = writer.write_all(b": connected\n\n");
        let _ = writer.flush();

        while let Ok(msg) = rx.recv() {
            if writer.write_all(msg.as_bytes()).is_err() {
                break;
            }
            if writer.flush().is_err() {
                break;
            }
        }
        // SSE 长连接结束 = 手机端不再与电脑相连，就地摘掉对等节点，
        // 让托盘菜单的「已连接手机」与真实状态同步（而不是继续挂 600 秒）。
        broadcaster.unregister_peer(&client_ip);
        return;
    }

    if path == "/auth" && method == Method::Post {
        let mut body = String::new();
        if request.as_reader().read_to_string(&mut body).is_ok() {
            let auth_req: Result<AuthRequest, _> = serde_json::from_str(&body);
            let mut is_valid = false;
            let current_pin = pin_code.read().unwrap().clone();

            let mut client_port = 18237;
            let mut dev_id = "android_phone".to_string();
            let mut dev_name = "安卓手机".to_string();

            if let Ok(req) = auth_req {
                let now_ms = SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .unwrap_or_default()
                    .as_millis() as u64;

                if let Some(cipher) = req.auth_cipher {
                    if let Ok(decrypted) = crate::crypto::decrypt(&cipher, &current_pin) {
                        if let Ok(payload) = serde_json::from_str::<AuthChallengePayload>(&decrypted) {
                            let time_diff = now_ms.abs_diff(payload.timestamp);
                            if time_diff < 60_000 {
                                is_valid = true;
                                if let Some(p) = payload.client_port {
                                    client_port = p;
                                }
                                if let Some(id) = payload.device_id {
                                    dev_id = id;
                                }
                                if let Some(name) = payload.device_name {
                                    dev_name = name;
                                }
                            }
                        }
                    }
                }

                if !is_valid {
                    let my_hash = crate::crypto::compute_hash(&current_pin);
                    let my_hash_prefix = &my_hash[..16.min(my_hash.len())];

                    if let Some(pin) = req.pin {
                        if pin.trim() == current_pin.trim() {
                            is_valid = true;
                        }
                    }
                    if let Some(hash) = req.pin_hash {
                        if hash == my_hash_prefix {
                            is_valid = true;
                        }
                    }
                    if let Some(p) = req.client_port {
                        client_port = p;
                    }
                    if let Some(id) = req.device_id {
                        dev_id = id;
                    }
                    if let Some(name) = req.device_name {
                        dev_name = name;
                    }
                }
            }

            if is_valid {
                broadcaster.register_peer(client_ip, client_port, dev_id, dev_name);

                let resp_data = serde_json::to_string(&StatusResponse {
                    status: "ok".to_string(),
                    auth: Some(true),
                    device_name: Some(device_name),
                    device_id: Some(broadcaster.device_id().to_string()),
                    message: None,
                })
                .unwrap();
                let resp = Response::from_string(resp_data)
                    .with_header(cors_header)
                    .with_header(content_type);
                let _ = request.respond(resp);
            } else {
                let resp_data = serde_json::to_string(&StatusResponse {
                    status: "error".to_string(),
                    auth: Some(false),
                    device_name: None,
                    device_id: None,
                    message: Some("pin_mismatch".to_string()),
                })
                .unwrap();
                let resp = Response::from_string(resp_data)
                    .with_status_code(StatusCode(403))
                    .with_header(cors_header)
                    .with_header(content_type);
                let _ = request.respond(resp);
            }
        }
        return;
    }

    if path == "/sync" && method == Method::Post {
        let mut body = String::new();
        if request.as_reader().read_to_string(&mut body).is_ok() {
            if let Ok(sync_req) = serde_json::from_str::<SyncRequest>(&body) {
                let current_pin = pin_code.read().unwrap().clone();
                if let Ok(decrypted) = crate::crypto::decrypt(&sync_req.encrypted, &current_pin) {
                    if crate::crypto::compute_hash(&decrypted) == sync_req.hash {
                        broadcaster.register_peer(client_ip, 18237, "android_phone".to_string(), "安卓手机".to_string());

                        crate::clipboard::set_clipboard_text(&decrypted);

                        let sender = sync_req.sender_id.unwrap_or_else(|| "手机端".to_string());
                        on_text_received(decrypted, sender);

                        let resp = Response::from_string(r#"{"status":"ok","message":"synced"}"#)
                            .with_header(cors_header)
                            .with_header(content_type);
                        let _ = request.respond(resp);
                        return;
                    }
                }
            }
        }

        let resp = Response::from_string(r#"{"status":"error","message":"invalid_payload"}"#)
            .with_status_code(StatusCode(400))
            .with_header(cors_header)
            .with_header(content_type);
        let _ = request.respond(resp);
        return;
    }

    // ==================== 文件传输端点 ====================

    if path == "/file/prepare" && method == Method::Post {
        let mut body = String::new();
        if request.as_reader().read_to_string(&mut body).is_ok() {
            if let Ok(prepare) = serde_json::from_str::<crate::file_transfer::FilePrepare>(&body) {
                let current_pin = pin_code.read().unwrap().clone();
                match file_manager.handle_prepare(&prepare, &current_pin) {
                    Ok(()) => {
                        // 去重命中时明确告知发送端「别再传分块了」；
                        // 老版本发送端不认识这个字段，会照常传完 —— 只是白跑一趟，不会出错。
                        let already_exists = file_manager.is_dedup_hit(&prepare.file_id);
                        // 接收侧同样走自绘浮窗（系统气泡没有进度条，且与浮窗重复）。
                        // 已判定目标目录存在同一文件时不弹窗 —— 马上就结束了，闪一下反而干扰。
                        if !already_exists {
                            crate::progress_window::show_progress(
                                "正在接收文件",
                                &prepare.filename,
                                prepare.file_size,
                            );
                        }
                        let resp = serde_json::json!({
                            "status": "ok",
                            "message": "准备接收文件",
                            "file_id": prepare.file_id,
                            "already_exists": already_exists
                        });
                        let resp_str = resp.to_string();
                        let resp = Response::from_string(resp_str)
                            .with_header(cors_header)
                            .with_header(content_type);
                        let _ = request.respond(resp);
                        return;
                    }
                    Err(e) => {
                        let resp = serde_json::json!({
                            "status": "error",
                            "message": e
                        });
                        let resp = Response::from_string(resp.to_string())
                            .with_status_code(StatusCode(500))
                            .with_header(cors_header)
                            .with_header(content_type);
                        let _ = request.respond(resp);
                        return;
                    }
                }
            }
        }
        let resp = Response::from_string(r#"{"status":"error","message":"invalid_payload"}"#)
            .with_status_code(StatusCode(400))
            .with_header(cors_header)
            .with_header(content_type);
        let _ = request.respond(resp);
        return;
    }

    if path == "/file/chunk" && method == Method::Post {
        // 二进制协议：分块密文直接作为请求体（省去 Base64/JSON 开销），元数据走 query 参数
        let params = parse_query_params(&url);
        let file_id = params.get("file_id").cloned().unwrap_or_default();
        let chunk_index = params.get("index").and_then(|v| v.parse::<u32>().ok()).unwrap_or(0);
        let total_chunks = params.get("total").and_then(|v| v.parse::<u32>().ok()).unwrap_or(0);

        if file_id.is_empty() {
            let resp = Response::from_string(r#"{"status":"error","message":"missing_file_id"}"#)
                .with_status_code(StatusCode(400))
                .with_header(cors_header)
                .with_header(content_type);
            let _ = request.respond(resp);
            return;
        }

        // 一次性读入二进制密文 body
        let mut payload: Vec<u8> = Vec::new();
        if request.as_reader().read_to_end(&mut payload).is_err() {
            let resp = Response::from_string(r#"{"status":"error","message":"read_body_failed"}"#)
                .with_status_code(StatusCode(400))
                .with_header(cors_header)
                .with_header(content_type);
            let _ = request.respond(resp);
            return;
        }

        let current_pin = pin_code.read().unwrap().clone();
        match file_manager.handle_chunk(&file_id, chunk_index, total_chunks, &payload, &current_pin) {
            Ok((received, total)) => {
                // 通过 SSE 广播接收进度给所有长连接客户端
                let progress_event = serde_json::json!({
                    "type": "FILE_PROGRESS",
                    "file_id": file_id,
                    "chunk_index": chunk_index,
                    "total_chunks": total,
                    "received_chunks": received
                });
                let msg = format!("data: {}\n\n", progress_event);
                {
                    let mut clients = broadcaster.clients.lock().unwrap();
                    clients.retain(|client| client.send(msg.clone()).is_ok());
                }

                // 同步刷新本机进度浮窗
                if let Some((done, total_bytes)) = file_manager.incoming_progress(&file_id) {
                    crate::progress_window::update_progress(done, total_bytes);
                }

                let resp = serde_json::json!({
                    "status": "ok",
                    "received": received,
                    "total": total
                });
                let resp = Response::from_string(resp.to_string())
                    .with_header(cors_header)
                    .with_header(content_type);
                let _ = request.respond(resp);
            }
            Err(e) => {
                let resp = serde_json::json!({
                    "status": "error",
                    "message": e
                });
                let resp = Response::from_string(resp.to_string())
                    .with_status_code(StatusCode(500))
                    .with_header(cors_header)
                    .with_header(content_type);
                let _ = request.respond(resp);
            }
        }
        return;
    }

    if path == "/file/complete" && method == Method::Post {
        let mut body = String::new();
        if request.as_reader().read_to_string(&mut body).is_ok() {
            if let Ok(complete) = serde_json::from_str::<crate::file_transfer::FileComplete>(&body) {
                let current_pin = pin_code.read().unwrap().clone();
                match file_manager.handle_complete(&complete, &current_pin) {
                    Ok((final_path, deduplicated)) => {
                        let path_str = final_path.to_string_lossy().to_string();
                        // 接收结束：浮窗切到终态（几秒后自动消失），与发送侧表现一致
                        crate::progress_window::finish(
                            if deduplicated {
                                "文件已存在，未重复写入"
                            } else {
                                "文件接收完成"
                            },
                            &path_str,
                            true,
                        );
                        // 通过 SSE 广播文件接收完成事件
                        let complete_event = serde_json::json!({
                            "type": "FILE_RECEIVED",
                            "file_id": complete.file_id,
                            "path": path_str,
                            "deduplicated": deduplicated
                        });
                        let msg = format!("data: {}\n\n", complete_event);
                        {
                            let mut clients = broadcaster.clients.lock().unwrap();
                            clients.retain(|client| client.send(msg.clone()).is_ok());
                        }

                        let resp = serde_json::json!({
                            "status": "ok",
                            "message": if deduplicated {
                                "电脑端已存在相同文件，未重复写入"
                            } else {
                                "文件接收完成"
                            },
                            "path": path_str,
                            "deduplicated": deduplicated
                        });
                        let resp = Response::from_string(resp.to_string())
                            .with_header(cors_header)
                            .with_header(content_type);
                        let _ = request.respond(resp);
                        return;
                    }
                    Err(e) => {
                        // 失败同样要给出终态，否则浮窗会一直停在半途
                        crate::progress_window::finish("文件接收失败", &e, false);
                        let resp = serde_json::json!({
                            "status": "error",
                            "message": e
                        });
                        let resp = Response::from_string(resp.to_string())
                            .with_status_code(StatusCode(500))
                            .with_header(cors_header)
                            .with_header(content_type);
                        let _ = request.respond(resp);
                        return;
                    }
                }
            }
        }
        let resp = Response::from_string(r#"{"status":"error","message":"invalid_payload"}"#)
            .with_status_code(StatusCode(400))
            .with_header(cors_header)
            .with_header(content_type);
        let _ = request.respond(resp);
        return;
    }

    let resp = Response::from_string("404 Not Found")
        .with_status_code(StatusCode(404))
        .with_header(cors_header);
    let _ = request.respond(resp);
}
