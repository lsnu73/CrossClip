# 全局俯瞰：模块拓扑与关键依赖

只收录经代码确认的事实；缺信息留空。箭头方向 = 调用/数据流向。

## 连接与发现栈（本次测绘范围）

```
用户操作(配对页/设置页/分享页)
  → SyncForegroundService(状态机枢纽)
      ├→ SseClient ──HTTP/SSE──→ rust server /events
      ├→ HttpUploader ──/auth、/sync、/heartbeat、/disconnect──→ rust server
      └→ LanDiscovery(UDP+子网探测) / NsdHelper(mDNS) ──发现──→ onDeviceDiscovered
rust server: /auth、/heartbeat → register_peer(真实身份)
             /events、/sync   → touch_peer_or_register_placeholder(INV-004)
```

### SyncForegroundService  `android/app/src/main/java/com/crossclip/app/service/SyncForegroundService.kt`
- 职责：手机端核心枢纽——配对与连接状态机、SSE 出站调度、剪贴板监听转发、心跳节流、文件收发。
- 对外暴露：`connectionState`、`currentPcIp`、`connectWithPin()`、`selectTargetDevice()`、
  `disconnectCurrentPc()`、`triggerRescan()`、`setAutoSearchEnabled()`、
  `beginPairingSearch()` / `endPairingSearch(userStopped)`。
- 关键依赖：[SseClient] → [LanDiscovery]（重连门控与省电窗口同源）；→ [HttpUploader]（握手/心跳/同步）。
- 易错点：见 pitfalls/2026-10-19-manual-pairing-deadlock-after-auto-search-off.md；
  配对页交互契约见 ai-handover.md §3.17；状态分裂补握手见 §3.16。
- 决策依据：见 decisions/2026-10-19-sse-gate-vs-explicit-connect.md。

### SseClient  `android/app/src/main/java/com/crossclip/app/network/SseClient.kt`
- 职责：手机→电脑的出站 SSE 长连接，接收电脑下发的剪贴板与文件事件；代际（generation）
  机制保证旧连接被顶替后静默退出。
- 对外暴露：`connect(url)`、`disconnect()`、`activeUrl`（补握手时反解目标地址用）。
- 关键依赖：→ `nextRetryDelayMs` 回调（由服务层注入，与 LanDiscovery 省电窗口同表）。
- 易错点：connect() 换代必须静默（INV-002）；首轮尝试豁免门控（INV-001）；
  `disconnect()` 靠 cancel 底层 socket 杀读线程，interrupt 叫不醒阻塞读。
- 决策依据：decisions/2026-10-19-sse-gate-vs-explicit-connect.md。

### LanDiscovery  `android/app/src/main/java/com/crossclip/app/network/LanDiscovery.kt`
- 职责：UDP 广播 + 子网并发探测的发现通道，兼作省电时间窗的唯一驱动者
  （`searchWindowStartMs` / `currentAutoRetryIntervalMs()` / `restartAutoSearchWindow()`）。
- 对外暴露：`isSearching`、`isConnected`、`continuousSearchMode`（配对页连续搜索）、
  `setAutoSearchEnabled()`、`startDiscovery(manualScan=)`、`stopDiscovery()`。
- 关键依赖：被 [SyncForegroundService] 与 [SseClient]（门控回调）读取。
- 易错点：内部 `autoSearchEnabledState` 与服务层开关是两个变量，写路径必须成对（INV-003）；
  `startDiscovery` 不会复位内部开关。
- 决策依据：decisions/2026-10-19-sse-gate-vs-explicit-connect.md。

### Broadcaster / Peer 表  `rust_desktop/src/server.rs`
- 职责：电脑端对等节点注册表（托盘「已连接手机」的唯一来源）+ SSE 广播。
- 对外暴露：`register_peer()`（真实身份写入）、`touch_peer_or_register_placeholder()`
  （在线证明）、`unregister_peer()`、`get_peers()`。
- 关键依赖：被 `/auth`、`/heartbeat`、`/events`、`/sync`、`/disconnect` 各端点调用。
- 易错点：`register_peer` 对已注册节点会用非空字段覆盖——传占位身份即降级（INV-004）；
  SSE 结束即摘除 peer（与连接同生共死，600s 心跳超时只是兜底）。
- 决策依据：docs/protocol.md「Peer 身份登记的分层语义」。

## 尚未测绘的区域

剪贴板去重与防回环（`clipboard.rs` / 服务层同步记录，见 ai-handover.md §3.4）、
文件传输分块与落盘去重（`FileUploader`/`FileReceiver`/`file_transfer.rs`/`SaveDirManager`）、
Shizuku 保活与后台读取、Windows 托盘与右键菜单——本次未触碰，待后续会话补测。
