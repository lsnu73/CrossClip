# CrossClip 跨端极速剪贴板互传

CrossClip 是一套面向个人多设备协同场景的局域网剪贴板双向同步工具:Windows 电脑与 Android 手机在同一局域网内实现纯文本(文字、代码、链接)的秒级无感互传。

**设计要点**

- 去中心化点对点:无公网服务器中转,数据只在本机局域网内传输;
- 极轻量:Windows 端为 Rust + Win32 原生托盘程序(单文件、无 Electron、常驻内存约 8 MB);Android 端为 Kotlin 原生应用;
- 端到端加密:基于 6 位动态 PIN 码经 SHA-256 派生 256 位密钥,AES-256-GCM 认证加密 + 随机 Nonce,防局域网重放与窃听;
- 后台常驻优先:Android 端深度接入 Shizuku,直接调用系统剪贴板底层 Binder 接口,实现无弹窗、无焦点切换的后台静默读写,并兼容 Android 10-14 后台剪贴板限制;
- 明确边界:只同步文本,**不支持图片、音视频与文件传输**。

## 目录结构

```text
CrossClip/
├── rust_desktop/   # Windows 电脑端(Rust 原生托盘程序)
│   ├── Cargo.toml
│   └── src/
│       ├── main.rs            # 入口、Win32 托盘、单实例互斥、关机广播放行
│       ├── server.rs          # HTTP / SSE 局域网服务端、对等推送与 Peer 管理
│       ├── clipboard.rs       # 原生剪贴板监听、内容提取/注入与防回环
│       ├── crypto.rs          # AES-256-GCM 加解密与 SHA-256 哈希
│       ├── config.rs          # 本地配置加载与动态 6 位 PIN 码生成
│       ├── ip_util.rs         # 本机局域网 IP 提取(过滤虚拟网卡)
│       ├── udp_discovery.rs   # UDP 广播自发现应答
│       └── mdns.rs            # mDNS 服务发布
└── android/        # Android 手机端(Kotlin 原生应用)
    └── app/src/main/
        ├── AndroidManifest.xml
        ├── java/com/crossclip/app/
        │   ├── service/SyncForegroundService.kt     # 前台常驻守护、通信调度、降级写入
        │   ├── shizuku/ShizukuClipboardManager.kt   # Shizuku Binder 剪贴板静默读写引擎
        │   ├── shizuku/ShizukuPrivilegeHelper.kt    # 权限检查与后台保活辅助
        │   ├── network/LocalHttpServer.kt           # 手机端本地 HTTP 服务(18237)
        │   ├── network/HttpUploader.kt              # 向电脑端提交密文
        │   ├── network/SseClient.kt                 # 出站 SSE 长连接客户端
        │   ├── network/LanDiscovery.kt              # UDP 发现与网段并发探测
        │   ├── network/NsdHelper.kt                 # mDNS/NSD 服务发现
        │   ├── crypto/CryptoUtil.kt                 # AES-256-GCM / SHA-256
        │   ├── ui/MainActivity.kt                   # 主界面、配对与厂商保活指引
        │   ├── ui/ClipWriteActivity.kt              # 1 像素透明穿透降级写入
        │   └── util/{DebugLogger,PermissionHelper}.kt
        └── res/…
```

> `docs/protocol.md` 描述局域网协议与加密细节;`docs/engineering-rules.md` 记录双端工程约束规范。

## 快速上手

### 1. Windows 电脑端(Rust)

```bash
cd rust_desktop
cargo build --release
```

运行 `rust_desktop/target/release/cross_clip.exe`,程序驻留系统托盘:

- 左键点击托盘图标复制当前 6 位配对 PIN 码;
- 右键菜单可查看本机 IP、重新生成 PIN、配置开机自启与退出。

首次运行会在程序所在目录自动生成本地 `config.json`(设备 ID、随机 PIN、端口与偏好),请勿提交或外泄该文件。

### 2. Android 手机端(Kotlin)

```bash
cd android
./gradlew assembleDebug
```

将 `app/build/outputs/apk/debug/app-debug.apk` 安装到手机,保持与电脑在同一局域网,打开 App 会自动发现电脑;若未发现可手动输入电脑 IP。输入电脑托盘显示的 PIN 码即可连接。

在国产定制系统(小米 HyperOS/MIUI、OPPO ColorOS、vivo OriginOS、华为 HarmonyOS 等)上,建议在多任务卡片下拉加锁、开启自启动、将电池策略设为无限制;配合 [Shizuku](https://shizuku.rikka.app/) 可实现真正无感的后台静默读写。

## 网络与安全

| 项目 | 说明 |
| :--- | :--- |
| UDP `18234` | 局域网自发现广播/应答 |
| HTTP/SSE `18236` | Windows 端服务端口(鉴权、密文同步、SSE 下发) |
| HTTP `18237` | 手机端本地服务端口(接收电脑对等推送) |
| 密钥派生 | `AES_256_KEY = SHA-256(PIN)` |
| 加密 | AES-256-GCM,12 字节随机 Nonce,`base64(nonce ‖ ciphertext ‖ tag)` |
| 防回环 | 双端维护近期内容哈希环形队列,过滤自身写入引发的反射同步 |

完整端点与消息格式见 [docs/protocol.md](docs/protocol.md)。

## 已知边界与免责声明

- 仅限同一局域网内相互可达的设备;不支持跨公网传输;
- 仅同步文本内容;
- Android 端依赖 Shizuku 获得最佳体验;未授予 Shizuku 时自动降级为前台透明写入方案,部分厂商系统上后台能力受限;
- 本项目仅提供源代码,请在自行构建与安全审查后使用,开发者不对使用后果承担责任。

## License

[MIT](LICENSE) © CrossClip Project Authors
