# CrossClip 局域网协议说明

## 端口与服务

| 端口 | 协议 | 用途 |
| :--- | :--- | :--- |
| 18234 | UDP | Windows 端广播自发现(应答设备名、IP、HTTP 端口) |
| 18236 | HTTP/SSE | Windows 端服务:鉴权、密文同步、SSE 剪贴板下发 |
| 18237 | HTTP | Android 端本地服务:接收电脑端对等推送 |
| - | mDNS | 服务类型 `_crossclip._tcp.local.`,TXT 携带 `device_id` |

## 端到端加密

所有剪贴板明文在发送前加密:

```text
key       = SHA-256(pin_code)              # pin_code 为 6 位动态 PIN
nonce     = random(12 bytes)
payload   = base64(nonce || AES-256-GCM(key, nonce, text))
hash      = SHA-256(text)                  # 防回环与完整性校验
```

双端各自维护近期内容哈希环形队列:收到远端内容写入本地剪贴板后,若哈希命中本机近期的发送记录,则判定为回环并过滤,避免两台设备互相触发无限反射同步。

## Windows 端 HTTP 端点(端口 18236)

| 路径 | 方法 | 说明 | 请求 | 响应 |
| :--- | :--- | :--- | :--- | :--- |
| `/ping` | GET | 心跳与设备探测 | - | `{"status":"ok","device_name":…}` |
| `/auth` | POST | PIN 码握手 | `{"pin":"123456","pin_hash":…}` | 匹配 `200`,失败 `403` |
| `/events` | GET | SSE 长连接(手机主动出站) | 头 `Accept: text/event-stream` | `data: {"type":"CLIP_SYNC","encrypted":…,"hash":…}` |
| `/sync` | POST | 手机向电脑提交密文 | `{"encrypted":…,"hash":…,"sender_id":…,"sender_name":…}` | `{"status":"ok"}` |
| `/heartbeat` | POST | 手机端保活并刷新设备表 | `{"pin_hash":…,"device_id":…,"device_name":…}` | `{"status":"ok"}` |
| `/disconnect` | POST | 手机端主动断开,立即摘除该设备在电脑端的连接状态 | `{"pin_hash":…,"device_id":…}` | `{"status":"ok"}` |

## 文件传输端点

分块大小 **1 MB**,两端常量必须一致(`FileUploader.CHUNK_SIZE` / `FileReceiver.CHUNK_SIZE` /
`rust_desktop/src/file_transfer.rs` 的 `CHUNK_SIZE`)。

| 路径 | 方法 | Body | 说明 |
| :--- | :--- | :--- | :--- |
| `/file/prepare` | POST | JSON `{type, file_id, filename, file_size, mime_type, sender_id, file_hash?}` | 建临时文件并登记传输 |
| `/file/chunk?file_id=&index=&total=` | POST | **二进制密文**(`nonce + ciphertext + tag`) | 元数据走 query,不做 Base64 |
| `/file/complete` | POST | JSON `{type, file_id, file_hash}` | 校验整文件哈希并落盘 |

进度通过 SSE 事件广播:`FILE_PROGRESS`(接收进度)与 `FILE_RECEIVED`(落盘完成)。

### 接收端去重(可选字段,向后兼容)

在传输开始前先判断「接收方是否已经有同一文件」,避免白跑一整轮分块:

1. 发送端在 `/file/prepare` 里附带整文件 SHA-256 的 `file_hash` 字段;
2. 接收端**只做「同名 + 同大小」这两个零成本判定**,两者都命中才为这一个文件流式算一次
   SHA-256 —— 不遍历目录,避免大目录下开销不可控;
3. 命中时 `/file/prepare` 响应带 `"already_exists": true`,发送端据此**一个分块都不发**,
   但**仍然必须发送 `/file/complete`**;
4. 接收端收到 `complete` 后复用已有文件、回收本次传输记录,并在 `/file/complete` 的响应与
   `FILE_RECEIVED` 事件里带上 `deduplicated` 字段。

> **易踩的坑**:去重命中时如果把 `complete` 一并省掉,发送端与接收端都会把这次传输的记录
> 一直挂在内存里,而且接收端的进度提示没有任何信号来收尾 —— 会永久停在「正在接收」
> (不定进度条还会 0→100 无限循环)。`complete` 是「本轮传输结束」的唯一信号,
> 与是否真的传过数据无关。

老版本客户端不带 `file_hash`,接收端会自动跳过该判定,行为与从前完全一致。

Android 端配对成功后主动向电脑发起出站 SSE 长连接,避免国产定制 ROM 在息屏待机时拦截入站 TCP 握手。

## 剪贴板流转方向

1. **手机 → 电脑**:手机捕获剪贴板变化 → `POST http://<pc-ip>:18236/sync`(密文)→ 电脑解密写入本地剪贴板;
2. **电脑 → 手机**:电脑捕获剪贴板变化 → 推送到已注册的 Peer `http://<phone-ip>:18237/sync` → 手机 Shizuku 静默写入系统剪贴板;
3. 任一端写入后均按“防回环”规则校验哈希,过滤自反射。
