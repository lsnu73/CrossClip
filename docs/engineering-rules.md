# 双端工程约束规范

本文档记录 CrossClip 双端开发、打包与系统生命周期相关的关键规范,供后续维护参考。

## Android 构建与保活

- Release 构建必须显式配置 `signingConfig`,禁止交付未签名 `app-release-unsigned.apk`;产出后使用 `apksigner verify --verbose` 校验 V1/V2 签名;
- 移动端在网络配对成功后主动发起出站长连接(SSE/WebSocket),不依赖电脑端入站连接,规避国产系统待机时对入站 TCP 握手的节流与丢包;
- 权限引导只引导真实依赖的核心权限(自启动),避免无关关联启动提示。

## Windows 托盘程序生命周期

- 托盘消息窗口必须使用**顶层隐藏窗口**(父句柄为 `NULL`),不能使用 `HWND_MESSAGE`,否则无法接收系统关机广播;
- 收到 `WM_QUERYENDSESSION` 立即返回 `TRUE` 放行关机;
- 收到 `WM_ENDSESSION` 后快速释放网络资源并 `exit(0)`,严禁阻塞系统关机;
- 通过 `SetConsoleCtrlHandler` 兜底捕获 `CTRL_SHUTDOWN_EVENT` / `CTRL_LOGOFF_EVENT`;
- 入口绑定系统命名互斥体实现单实例,重复启动静默退出,避免托盘图标堆叠与端口冲突。
