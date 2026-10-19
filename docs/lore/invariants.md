# 不可违反的硬约束（invariants）

每条约束只在本文件定义一次；`docs/engineering-rules.md` 的「连接状态与断开语义」一节
是这些约束在规范文档中的对应表述，两处如将来出现出入，以代码强制点为准并就地修订本文件。

- **INV-001** 省电门控（`nextRetryDelayMs` 返回 null）只拦「失败后的自动重试」，
  每次 `SseClient.connect()` 的**首次 TCP 尝试必须豁免**。
  - 违反代价：自动搜索关闭时，手动 PIN 配对成功后的首次连接也被闸死——
    提示配对成功但永远未连接（pitfalls/2026-10-19-manual-pairing-deadlock-after-auto-search-off.md）。
  - 强制点：`android/.../network/SseClient.kt` connect 循环内的 `isFirstAttempt` 豁免分支。

- **INV-002** `SseClient.connect()` 换代必须静默，禁止向服务层回调
  `onConnectionChanged(false)`；只有显式断开（手动断开/重新扫描/清历史/onDestroy）才回调。
  - 违反代价：「先破后立」的复合操作里，同步假回调把调用方刚置位的 `connectionState=1`
    当场冲回 0，并误触发 `onPcDisconnected()` 重置搜索窗口（同一坑点条目）。
  - 强制点：`SseClient.kt` 的 `teardown(notify: Boolean)`；`connect()` 传 `notify = false`。

- **INV-003** 「自动搜索」开关在服务层（`SyncForegroundService.autoSearchEnabled`）与
  `LanDiscovery.autoSearchEnabledState` 是**两个变量**，开启/关闭路径必须成对写入。
  已连接时重开开关只重置时间窗 + 确保扫描在跑，禁止走 `triggerRescan()` 切断健康连接。
  - 违反代价：内部标志残留 false → 扫描一轮即停、`currentAutoRetryIntervalMs()` 恒为 null，
    「开关关闭过一次再打开」必现断开并假死（同一坑点条目）。
  - 强制点：`SyncForegroundService.setAutoSearchEnabled()`。
    ⚠️ 无编译期强制，靠本条约与 code review 兜底。

- **INV-004** 电脑端「连接即在线」类端点（`/events`、`/sync`）登记对等节点时，
  已注册节点只刷新存活时间，未注册节点才以占位身份登记；
  真实身份只由 `/auth`、`/heartbeat` 写入/刷新。
  - 违反代价：占位身份（`android_phone`/「安卓手机」）冲掉真实身份，托盘在每次剪贴板
    同步 / SSE 建连后退化显示，最长 30 秒才恢复。
  - 强制点：`rust_desktop/src/server.rs` 的 `touch_peer_or_register_placeholder()`。
