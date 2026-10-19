# 关闭「自动搜索电脑」后手动配对：提示成功却永未连接，文件发不出但剪贴板照通
- 首次出现：2026-10-19（v2.6.0，双端日志对时定位）
- 影响范围：Android 端所有「自动搜索已关闭」或「开关关闭过一次再打开」场景下的手动连接；
  文件发送（分享页）与主页连接态显示直接受害
- 复现条件：① 已连接状态下关闭「自动搜索电脑」→ 手动断开 → 配对页点「连接」输 PIN；
  或 ② 开关关闭过一次后重新打开（已连接会被当场切断，随后进入同一假死态）

## 表象

- 手机日志：`PIN 码配对成功` 后紧跟 `SSE 连接状态变更: connected=false, state=1`，
  随后 `开始发起 SSE 长连接` → `搜索窗口已关闭且未连接，停止 SSE 自动重连` →
  `SSE 接收循环退出`——从发起到退出不到 1 毫秒，**一次 TCP 尝试都没发**。
- 用户侧：主页永显示「未连接设备」；电脑→手机文件/剪贴板正常，手机→电脑剪贴板正常，
  唯独文件发送被 `ShareReceiveActivity` 的 `connectionState != 1` 拦截。
- 电脑侧日志：`/auth` 握手注册成功，之后心跳/剪贴板照常——单看电脑端一切健康。

## 根因

三个缺陷连锁，缺一个都不至此：

1. `SseClient.connect()` 内部先调 `disconnect()`，其**同步回调** `onConnectionChanged(false)`
   把配对成功刚置位的 `connectionState=1` 当场打回 0（并误触发 `onPcDisconnected()`）；
2. 省电门控（§7 #25 引入）在**首轮尝试之前**检查：状态 0 + 自动搜索已关 →
   `LanDiscovery.currentAutoRetryIntervalMs()` 返回 null → 新连接零尝试退出，
   `connectionState` 永远停在未连接。心跳/剪贴板只要求 `currentPcIp` 非空故照常，
   形成「半通」假象；
3. `setAutoSearchEnabled(true)` 只写服务层变量、**从不复位 `LanDiscovery` 内部开关**
   （只关不开），还无条件 `triggerRescan()` 切断健康连接——内部标志残留 false →
   扫描一轮即停、重试调度恒为 null，解释了「开关再打开必断开并假死」。

真第一因是 2 与 1 的顺序叠加：即使门控保留，只要换代不冲掉状态，首轮检查就会看到
`connectionState==1` 而放行；但 1 单独存在时，任何「配对成功即 connect」的路径都在
裸奔。3 是独立缺陷，只是症状恰好同族。

## 已采用的修复

- `SseClient.kt`：`connect()` 换代改走 `teardown(notify = false)`（INV-002）；
  重连门控后移到首轮尝试之后，`isFirstAttempt` 豁免（INV-001）；
- `SyncForegroundService.setAutoSearchEnabled()`：开启路径先
  `lanDiscovery.setAutoSearchEnabled(true)` 双向同步；已连接只重置窗口 + 确保扫描在跑，
  未连接才 `triggerRescan()`（INV-003）；
- 附带（同日志暴露）：`server.rs` 的 `/sync`、`/events` 占位身份登记改
  `touch_peer_or_register_placeholder()`，不再把托盘真实身份周期性冲成「安卓手机」（INV-004）。

## 复发防护

- 约束入库：docs/lore/invariants.md INV-001~INV-004；规范同步于
  docs/engineering-rules.md「连接状态与断开语义」；教训总表登记于 ai-handover.md §7 #28；
- 验证路径（改动门控/开关/连接流程后必跑）：关闭自动搜索 → 手动断开 → 配对页重连，
  主页应变「已连接」且手机→电脑发文件成功；已连接时关→开自动搜索，连接不得掉线。
