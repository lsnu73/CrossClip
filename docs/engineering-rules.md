# 双端工程约束规范

> 本文档是**具体工程细节**的约束清单。若你是第一次接手本仓库, 请先阅读
> [`docs/ai-handover.md`](ai-handover.md)(AI 开发交接契约) —— 它会告诉你这些约束
> **为什么**存在、以及改动时的连带影响。

本文档记录 CrossClip 双端开发、打包与系统生命周期相关的关键规范,供后续维护参考。

## Android 构建与保活

- Release 构建必须显式配置 `signingConfig`,禁止交付未签名 `app-release-unsigned.apk`;产出后使用 `apksigner verify --verbose` 校验 V1/V2 签名;
- 移动端在网络配对成功后主动发起出站长连接(SSE/WebSocket),不依赖电脑端入站连接,规避国产系统待机时对入站 TCP 握手的节流与丢包;
- 权限引导只引导真实依赖的核心权限(自启动),避免无关关联启动提示。

## Android 省电与心跳/保活频率约定

- 心跳上报统一走 `SyncForegroundService.sendHeartbeatThrottled()`:定时心跳线程(30s)、Shell(UID 2000) 唤醒脉冲、亮屏事件共用这一个入口,靠 `HEARTBEAT_MIN_GAP_MS`(25s) 去重,避免同一时刻多路重复发 HTTP;进程被 ROM 冻结时定时线程停摆,脉冲解冻后会立即补发一次(此时距上次上报已超过节流窗口);
- 电脑端在线判定余量:`rust_desktop/src/server.rs` 中 peers 超时为 600s,心跳周期 ≤30s 即有余量;手机端感知电脑掉线靠 SSE 断开(秒级)与局域网探测,**不要**用提高心跳频率来解决;
- Shell 唤醒脉冲周期 `ShizukuPrivilegeHelper.PULSE_INTERVAL_SEC`(当前 10s) 只决定「进程被冷冻时的最坏同步延迟」,进程活跃时同步由原生/Shizuku 监听实时触发,调整该值需同步评估待机功耗;
- Shizuku 剪贴板轮询仅在底层 Binder 监听注册失败时启用(息屏自动暂停),监听就绪时不得再叠加高频轮询。

## Windows 托盘程序生命周期

- 托盘消息窗口必须使用**顶层隐藏窗口**(父句柄为 `NULL`),不能使用 `HWND_MESSAGE`,否则无法接收系统关机广播;
- 收到 `WM_QUERYENDSESSION` 立即返回 `TRUE` 放行关机;
- 收到 `WM_ENDSESSION` 后快速释放网络资源并 `exit(0)`,严禁阻塞系统关机;
- 通过 `SetConsoleCtrlHandler` 兜底捕获 `CTRL_SHUTDOWN_EVENT` / `CTRL_LOGOFF_EVENT`;
- 入口绑定系统命名互斥体实现单实例,重复启动静默退出,避免托盘图标堆叠与端口冲突。
