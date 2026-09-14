# CrossClip — Windows ⇄ Android 局域网剪贴板 & 文件互传（Fork 增强版）

> 本仓库基于 [zxzc114514/CrossClip](https://github.com/zxzc114514/CrossClip) Fork 并新增了多项功能增强，详见下方"本 Fork 新增功能"。原始仓库保留了完整的剪贴板同步基础架构与协议设计，特此致谢。

> [!IMPORTANT]
> **开发者 / AI 接手必读：[`docs/ai-handover.md`](docs/ai-handover.md)**
> 该文档记录了本项目的整体设计意图、每个「反直觉写法」背后的系统限制、改动禁区与历史踩坑清单。
> 直接上手改代码前请先通读，可避免破坏保活链、协议一致性等难以复现的问题。

CrossClip 是一套**纯局域网、点对点**的剪贴板双向同步 & 文件互传工具：Windows 电脑与 Android 手机连接同一 Wi-Fi/局域网后，文本、代码、链接可在两端**秒级无感互传**，还支持**双向文件传输**。全程无需公网服务器，数据不出局域网。

正式安装包可从 [Releases](https://github.com/zxzc114514/CrossClip/releases) 下载，也可按下方说明自行构建。

## 功能特性

- **去中心化点对点**：无云服务器中转，传输只发生在局域网内部；
- **极轻量**：Windows 端为 Rust + Win32 原生单文件托盘程序（常驻内存约 8 MB），Android 端为 Kotlin 原生应用；
- **端到端加密**：6 位动态 PIN 码经 SHA-256 派生 256 位密钥，AES-256-GCM 认证加密 + 随机 Nonce，防止局域网窃听与重放；
- **后台静默读写**：Android 端接入 Shizuku 系统特权 API，直接操作系统剪贴板 Binder 接口，实现无弹窗、无焦点切换的后台同步，兼容 Android 10+ 的后台剪贴板限制；未授权 Shizuku 时自动降级为透明 Activity 方案；
- **出站长连接**：配对成功后由手机主动向电脑发起 SSE 出站长连接，规避国产定制系统息屏待机对入站 TCP 握手的拦截与节流；
- **多电脑管理（v1.1.0）**：自动按 `device_id` 记忆已配对电脑并为每台电脑独立保存 PIN 码；支持局域网内多台电脑在线列表、手动切换目标、心跳感知与离线自动重连；
- **可靠自发现**：UDP 广播 + mDNS/NSD 双通道发现，辅以 HTTP `/ping` 探活过滤系统幽灵缓存，支持多网卡/多 IP 候选及手动输入电脑 IP；
- **防回环**：双端维护近期内容哈希环形队列，过滤自身写入引发的反射同步；
- **双向文件传输（Fork 新增）**：Windows ⇄ Android 支持文件发送/接收，分块传输（1MB/chunk）+ AES-256-GCM 加密 + 流式哈希校验，Android 端支持系统分享面板直接发送；
- **自动搜索省电降频（Fork 新增）**：Android 端自动发现机制支持空闲降频扫描，手动扫描支持有限轮次控制，降低电量消耗；
- **明确边界**：仅同步文本与文件，不支持音视频流媒体传输。

## 本 Fork 新增功能

以下功能均在原版 [zxzc114514/CrossClip](https://github.com/zxzc114514/CrossClip) 基础上新增：

- **双向文件传输**：Windows ⇄ Android 支持文件发送/接收，Android 端可通过系统分享面板直接发送文件到电脑，电脑端右键托盘可快速发送文件到手机；分块传输（1MB/chunk）+ 进度通知 + 取消支持；
- **文件加解密与校验**：文件传输过程采用 AES-256-GCM 加密（与剪贴板同步共用密钥派生），支持流式哈希校验确保完整性；
- **自动搜索省电降频**：Android 端自动发现支持空闲降频扫描，手动扫描支持有限轮次，减少电量消耗；
- **HTTP Keep-alive 长连接**：手机端 HTTP 服务改用 Keep-alive 连接复用，减少频繁建连开销；
- **过滤 VMware 虚拟网卡**：Windows 端 IP 提取自动过滤 VMware 虚拟网卡，避免多虚拟网卡环境下广播错误地址；
- **新设置界面**：Android 端新增自动搜索开关、通知权限引导与文件保存目录设置；
- **文件保存目录管理**：支持自定义文件保存路径，可通过设置界面修改；
- **调试日志系统**：Android 端新增 `DebugLogger`，便于排查问题。

## 目录结构

```text
CrossClip/
├── rust_desktop/              # Windows 电脑端（Rust 原生托盘程序）
│   ├── Cargo.toml
│   └── src/
│       ├── main.rs            # 入口、Win32 托盘、单实例互斥、关机广播放行
│       ├── server.rs          # HTTP / SSE 局域网服务、鉴权与 Peer 管理
│       ├── clipboard.rs       # 原生剪贴板监听与内容注入
│       ├── crypto.rs          # AES-256-GCM 加解密与 SHA-256（含文件流式加密）
│       ├── config.rs          # 本地配置与动态 6 位 PIN 生成
│       ├── file_transfer.rs   # 文件传输核心：分块传输、进度跟踪、取消与哈希校验
│       ├── ip_util.rs         # 局域网 IP 提取（过滤虚拟网卡、VMware）
│       ├── udp_discovery.rs   # UDP 自发现广播应答
│       └── mdns.rs            # mDNS 服务发布
├── android/                   # Android 手机端（Kotlin 原生工程）
│   └── app/src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/crossclip/app/
│       │   ├── service/SyncForegroundService.kt     # 前台守护、配对/重连、SSE 调度与文件传输通知
│       │   ├── shizuku/ShizukuClipboardManager.kt   # Shizuku Binder 剪贴板静默读写
│       │   ├── shizuku/ShizukuPrivilegeHelper.kt    # Shizuku 权限辅助
│       │   ├── network/LocalHttpServer.kt           # 手机端本地接收服务（18237，Keep-alive）
│       │   ├── network/HttpUploader.kt              # 向电脑端提交密文/心跳
│       │   ├── network/FileUploader.kt              # 文件上传至电脑（分块 + 加密）
│       │   ├── network/FileReceiver.kt              # 从电脑接收文件（分块 + 解密）
│       │   ├── network/SseClient.kt                 # 出站 SSE 长连接客户端
│       │   ├── network/LanDiscovery.kt              # UDP 发现、多 IP 候选、网段探测与省电降频
│       │   ├── network/NsdHelper.kt                 # mDNS/NSD 发现（HTTP 探活过滤）
│       │   ├── crypto/CryptoUtil.kt                 # AES-256-GCM / SHA-256（含文件流式加密）
│       │   ├── receiver/ShareReceiveActivity.kt     # 系统分享面板入口（文件发送至电脑）
│       │   ├── ui/MainActivity.kt                   # 主界面、多电脑切换、设置与厂商保活指引
│       │   ├── ui/ClipWriteActivity.kt              # 透明穿透降级写入
│       │   └── util/{DebugLogger,SaveDirManager,PermissionHelper}.kt
│       └── res/…
└── docs/
    ├── ai-handover.md         # ★ AI 开发交接契约（接手开发前必读）
    ├── protocol.md            # 局域网协议与加密细节
    ├── engineering-rules.md   # 双端工程约束规范
    └── app-icons.md           # 应用图标 / 通知图标 / 托盘图标体系
```

## 快速上手

### Windows 电脑端（Rust）

```bash
cd rust_desktop
cargo build --release
```

运行 `rust_desktop/target/release/cross_clip.exe`，程序驻留系统托盘：

- 左键点击托盘图标：复制当前 6 位配对 PIN 码；
- 右键菜单：查看本机 IP、重新生成 PIN、发送文件到手机、配置开机自启、退出。

首次运行会在程序所在目录生成 `config.json`（设备 ID、随机 PIN、端口与偏好），请勿提交或外泄该文件。

### Android 手机端（Kotlin）

方式一：直接安装 [Releases](https://github.com/zxzc114514/CrossClip/releases) 中的 `CrossClip.apk`。

方式二：自行构建：

```bash
cd android
./gradlew assembleRelease
```

产物位于 `app/build/outputs/apk/release/app-release.apk`。安装后：

1. 手机与电脑连接同一局域网；
2. 打开 App，自动扫描电脑；未发现时可手动输入电脑 IP；
3. 输入电脑托盘显示的 6 位 PIN 码连接；
4. 可同时配对多台电脑，连接后在 App 内随时切换目标。

国产定制系统（小米 HyperOS/MIUI、OPPO ColorOS、vivo OriginOS、华为 HarmonyOS 等）建议：多任务卡片下拉加锁、开启自启动、电池策略设为无限制；配合 [Shizuku](https://shizuku.rikka.app/) 可获得真正的后台静默读写体验。

## 网络与安全

| 项目 | 说明 |
| :--- | :--- |
| UDP `18234` | Windows 端自发现广播/应答（OFFER 携带 `device_id`、候选 IP 列表） |
| HTTP/SSE `18236` | Windows 端服务：`/ping`、`/auth`、`/sync`、SSE `/events` |
| HTTP `18237` | 手机端本地接收端口（电脑对等推送） |
| mDNS | 服务类型 `_crossclip._tcp.local.`，TXT 携带 `device_id` |
| 密钥派生 | `AES_256_KEY = SHA-256(PIN)` |
| 加密 | AES-256-GCM，12 字节随机 Nonce，`base64(nonce ‖ ciphertext ‖ tag)` |
| 防回环 | 双端维护近期内容哈希环形队列，过滤自反射同步 |

完整端点与消息格式见 [docs/protocol.md](docs/protocol.md)。

## 已知边界与免责声明

- 仅支持同一局域网内相互可达的设备，不支持跨公网传输；
- Android 端接入 Shizuku 体验最佳；未授权时自动降级为透明写入方案，部分厂商系统上后台能力受限；
- 本项目仅提供源代码与构建产物，请在自行安全审查后使用，开发者不对使用后果承担责任。

## 致谢

本 Fork 基于 [zxzc114514/CrossClip](https://github.com/zxzc114514/CrossClip) 开发，感谢原作者提供的优秀基础架构。

## License

MIT © CrossClip Project Authors
