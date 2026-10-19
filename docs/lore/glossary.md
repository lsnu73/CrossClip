# 术语表（领域术语 ↔ 代码实体）

- `连接状态`：`SyncForegroundService.connectionState`（0 未连接 / 1 已连接 / -1 验证中 / 2 PIN 错误）。
  只反映「SSE 长连接是否存活」，**不等于**握手是否完成；易与 `LanDiscovery.isConnected`
  （驱动扫描循环的低频保活分支）混淆——两者由服务层成对维护，读代码时注意是谁在读。
- `握手派生状态`：`currentPcIp` / `currentPcName` / `currentTargetDeviceId`，只在 `/auth`
  握手成功的回调里赋值。与 `连接状态` 脱节即为「状态分裂」（见 ai-handover.md §3.16）。
- `省电时间窗 / 搜索窗口`：`LanDiscovery.searchWindowStartMs` 起计的
  「0–5 分钟高频 / 5–9 分钟每分钟 / 9–15 分钟每 2 分钟 / 15 分钟停止」降频表；
  `currentAutoRetryIntervalMs()` 是它给 SSE 重连共用的读取口，返回 null 即「窗口已关闭」。
- `重连门控`：`SseClient` 构造参数 `nextRetryDelayMs` 回调；每一轮重试开头查一次窗口，
  null 则退出循环。受 INV-001 约束：首轮尝试豁免。
- `配对模式`：`SyncForegroundService.pairingModeActive`，「电脑配对」页打开期间置位，
  是自动连接的第四道闸门（发现设备只登记不连接，见 ai-handover.md §3.17）。
- `手动断开抑制`：`SyncForegroundService.manualDisconnected`，用户手动断开后置位，
  只在用户主动发起连接（重新扫描/输 PIN/选设备/重开自动搜索）时清除。
- `占位身份`：电脑端 Peer 表里的 `android_phone` / 「安卓手机」，仅表示「有个手机在线但
  还没上报真实身份」；真实身份（如 `android_V2507A` / `vivo`）只来自 `/auth` 与 `/heartbeat`。
- `touch 登记`：`server.rs` 的 `touch_peer_or_register_placeholder()`——已注册只刷新
  存活时间，未注册才占位登记（INV-004）。`/events`、`/sync` 专用。
- `状态分裂`：特指 `connectionState=1` 但 `currentPcIp` 为空（或反向）的半连接态，
  表现为单通：电脑→手机正常、手机→电脑全灭。两类历史入口：SSE 自动重连跳过握手
  （§7 #24）、省电门控误杀手动首连（§7 #28）。
- `同步记录 / 回声窗口 / lamport_clock`：剪贴板去重三件套（电脑端为 hub 统一分配时钟），
  见 ai-handover.md §3.4 与 docs/protocol.md「剪贴板同步算法」。
- `退出态（user_exited）`：`cross_clip_config` SharedPreferences 的持久布尔标志，
  「退出软件」置位、`MainActivity.onCreate`（手动打开）清除。所有把 App 拉活的入口
  须先查它（INV-006）；服务入口闸门在 `SyncForegroundService.onStartCommand`。
- `Shizuku 守护循环`：`ShizukuPrivilegeHelper.startBackgroundWakeupPulse()` 拉起的
  `nohup` Shell 循环（UID 2000），每 10 秒 `pidof` 探测并 `am start-foreground-service`
  复活进程；是「退出软件」必须 pkill 的最硬复活路径
  （decisions/2026-10-19-exit-app-must-defeat-three-revival-paths.md）。
