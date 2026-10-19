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

- **INV-005** 任何「执行动作的判定」（按钮是否可用、是否弹确认、阈值比较）必须使用
  原始数据值，**禁止解析 UI 展示文案反推**；展示层的格式化（取整、单位换算、截断）
  一律视为有损。
  - 违反代价：格式化吞掉真值后判定跟着失真——缓存残留 < 1 MB 被「0 MB」显示
    连带判成「清洁」，清理按钮永久失效（pitfalls/2026-10-19-cache-button-dead-by-display-rounding.md）。
  - 强制点：`MainActivity.lastCacheBytes` 字段范式。
    ⚠️ 通用约定无编译期强制，靠 code review 兜底。

- **INV-006** 任何「把 App 拉活」的入口（服务启动、广播唤醒、守护拉起、未来的
  JobScheduler/推送等）必须尊重 `user_exited` 退出态：先查标志，置位则放弃启动。
  彻底退出的四步顺序（写标志 → pkill 守护 → stopService → 延迟杀进程）不可调换。
  - 违反代价：新增保活机制绕过退出态，「退出软件」被某条路径复活，功能名存实亡；
    顺序调换则 STICKY/守护在杀进程瞬间把服务拉回。
  - 强制点：`SyncForegroundService.onStartCommand` 入口的 user_exited 闸门（唯一
    服务入口）；`MainActivity.exitAppCompletely()` 的四步顺序。
    ⚠️ 新增的拉活入口不在编译期覆盖范围内，靠本条约与 code review 兜底。
