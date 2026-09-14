# CrossClip AI 开发交接契约

> **接手本项目的 AI: 这是你必读的第一份、也是最重要的一份文档。**
>
> 本仓库由多轮 AI 协作开发而成。代码能运行不代表设计能被安全修改 —— 大量实现细节
> 是「为了绕开某个具体的系统限制」而存在的, 一旦被不了解背景的改动覆盖, 会以
> 「偶发失效」「夜间掉线」「某些机型不能用」这类难以复现的形式暴露出来。
>
> 读完本文, 你应该能回答: **这段代码为什么长这样? 我改它会不会踩到别人的坑?**

---

## 0. 阅读顺序与硬性规则

### 0.1 必读顺序

| 顺序 | 文档 | 作用 |
| :--- | :--- | :--- |
| 1 | **本文 (`docs/ai-handover.md`)** | 全局设计意图、业务背景、改动禁区 |
| 2 | [`docs/protocol.md`](protocol.md) | 双端通信协议、端点与加密细节 |
| 3 | [`docs/engineering-rules.md`](engineering-rules.md) | 具体工程约束(保活/心跳/生命周期) |
| 4 | [`README.md`](../README.md) | 用户视角的功能与目录结构 |

另有**专题文档**, 按需查阅(不是必读, 但碰到对应主题时必须看):

| 文档 | 什么时候看 |
| :--- | :--- |
| [`docs/app-icons.md`](app-icons.md) | 要改应用图标 / 通知图标 / 托盘图标时 —— 双端共 7 处呈现, 极易漏改 |

### 0.2 改动前的硬性检查清单

任何修改都必须先过一遍这张表, 有任一项命中就必须同步修改另一端或对应资源:

- [ ] **碰了通信协议?** → 必须同时改 Windows 端与 Android 端, 且考虑老版本兼容(见 §5.4)
- [ ] **碰了加密/哈希/分块?** → 两端算法参数必须逐字节一致, 否则表现为「静默校验失败」
- [ ] **碰了 Android 端任何 `Service` / 广播 / Shizuku 逻辑?** → 检查是否会打断保活链(见 §3.2)
- [ ] **碰了 Windows 端托盘窗口过程?** → 检查是否仍能接收系统关机广播(见 §3.6)
- [ ] **新增了后台轮询/定时任务?** → 评估待机功耗, 并优先复用已有的节流入口(见 §3.7)
- [ ] **新增了文件/网络资源?** → 确认在所有退出路径上都被释放(见 §6.5)
- [ ] **改了 `SyncForegroundService` 的公开可见状态?** → 检查 `MainActivity` 的轮询 UI 是否需要同步
- [ ] **新增了通知 / 换了应用图标?** → 小图标必须复用 `NOTIFICATION_SMALL_ICON`(见 §3.14 与 [`app-icons.md`](app-icons.md))
- [ ] **碰了连接状态或 Peer 表?** → 两端各有一处「唯一真相来源」, 见 §3.13

---

## 1. 这个项目要解决什么业务问题

### 1.1 用户场景

用户同时使用一台 Windows 电脑和一台 Android 手机。他经常需要:

- 把电脑上看到的一段文字/链接/代码片段弄到手机上(或反过来);
- 把手机拍的照片、下载的文件传到电脑(或反过来)。

现有方案都不理想:

| 方案 | 问题 |
| :--- | :--- |
| 微信/QQ 文件传输助手 | 必须联网、经过第三方服务器、有大小限制、需登录 |
| 数据线 / U 盘 | 物理连接麻烦, 手机端文件管理复杂 |
| 蓝牙 | 慢、配对繁琐 |
| 云盘 | 要联网、要上传下载两趟 |

**CrossClip 的答案: 同一 Wi-Fi 下, 点一下就走局域网直连, 数据不出内网。**

### 1.2 不可动摇的产品约束

这几条是项目的立足点, 任何新功能都不能违反:

1. **纯局域网点对点** —— 不允许引入任何公网服务器中转。一旦引入, 项目就失去了与微信的差异。
2. **零配置** —— 用户不应被要求输入 IP、开端口、装驱动。理想体验是「打开 App → 输 6 位 PIN → 完毕」。
3. **轻量常驻** —— Windows 端约 8 MB 内存的单文件 exe; Android 端不应显著耗电。
4. **数据不出内网** —— 所有内容端到端加密, 局域网内嗅探也无法还原明文。

### 1.3 功能边界(明确不做的事)

写下这些是为了避免未来 AI 把精力投到错误方向:

- ❌ 不做公网/跨网段传输;
- ❌ 不做音视频流媒体;
- ❌ 不引入账号体系 / 云同步;
- ❌ 不做多用户/权限管理(这是两台设备之间的工具, 不是团队协作平台)。

---

## 2. 整体架构: 两端如何协作

### 2.1 角色分工

| | Windows 端 (Rust) | Android 端 (Kotlin) |
| :--- | :--- | :--- |
| 角色 | 服务端 + 主动方 | 客户端 + 常驻方 |
| 监听 | HTTP(18236) + SSE + UDP 广播应答(18234) + mDNS | HTTP(18237) |
| 主动连接 | 向手机 HTTP POST 推送 | 向电脑发起 SSE 长连接 |
| 配对凭据 | 生成并展示 6 位 PIN | 输入 PIN |

**关键点: 手机是「出站方」。** 这不是随意的选择 —— 见 §3.1。

### 2.2 三条通道

```text
┌─────────────── Windows (Rust) ───────────────┐        ┌────────── Android (Kotlin) ──────────┐
│                                               │        │                                       │
│  ① UDP 18234  广播应答 OFFER(设备名/IP/端口)  │◄───────┤  UDP 广播 DISCOVER + 子网并发探测     │
│                                               │        │                                       │
│  ② HTTP 18236 /ping /auth /sync /file/*       │◄───────┤  HttpUploader / FileUploader (POST)   │
│                                               │        │                                       │
│  ③ SSE 18236 /events?pin=xxx  (下行推送)      │───push─►  SseClient 长连接, 秒级接收       │
│                                               │        │                                       │
│  ④ HTTP 18237 (对等兜底推送)                   │───POST─►  LocalHttpServer                    │
└───────────────────────────────────────────────┘        └───────────────────────────────────────┘
```

- **①** 解决「怎么找到对方」;
- **②** 解决「手机怎么主动发数据」;
- **③** 解决「电脑怎么实时把数据送到手机」(手机主动建立的长连接);
- **④** 是 ③ 的降级兜底(SSE 断了也能推)。

### 2.3 一次典型同步的完整链路(手机复制文本)

```text
用户复制 → PrimaryClipChangedListener / Shizuku 监听触发
        → SyncForegroundService.onLocalClipboardChanged()
        → 计算 SHA-256 查 recentHashes(60 秒时间窗去重) 命中则跳过
        → HttpUploader.sendClipboard() → POST /sync (密文)
        → 电脑端 server.rs 解密 → 校验 hash → 写入系统剪贴板 + 记录 LAST_TEXT_HASH
        → 电脑端 SSE 广播 FILE_PROGRESS / 直接回推? (不回推, 由电脑端 IS_UPDATING_SELF 抑制)
```

---

## 3. 设计决策与「为什么」(最重要的一节)

> 下面每一条都是**某个具体问题**的解法。删掉它们, 问题会回来。

### 3.1 为什么由手机主动发起 SSE 长连接

**问题**: 国产定制 ROM(小米 HyperOS、OPPO ColorOS、vivo OriginOS、华为 HarmonyOS)在息屏待机时,
会限制/节流**入站** TCP 连接与后台进程的网络活动。如果电脑端主动连手机, 手机经常收不到。

**解法**: 配对成功后, 手机主动向电脑发起一条长连接(`GET /events?pin=xxx`, Server-Sent Events)。
长连接建立后是「已存在的连接」, 系统不会像拦截新入站握手那样拦截它。

**推论(改代码时必须注意)**:
- 电脑端 `server.rs` 的 SSE 处理跑在**独立线程**上(不是 16 线程的 WorkerPool), 因为它会长期阻塞。
  别把它挪进线程池, 否则 16 个长连接就能耗尽整个服务。
- 手机端 `SseClient` 断开后 2 秒自动重连, 403(PIN 错误)则停止重试 —— 这个区别很重要,
  否则 PIN 错了会无限重连打爆电脑端。

### 3.2 为什么要接入 Shizuku

**问题**: Android 10+ 起, 后台应用**无法读取剪贴板**(只有获得焦点的前台应用可以)。
这直接掐死了「后台静默同步」这个核心体验。

**解法**: 通过 [Shizuku](https://shizuku.rikka.app/) 拿到系统 Binder 的 `IClipboard` 接口,
以特权身份直接读写剪贴板, 绕过焦点限制。未授权时自动降级为三层兜底(见下)。

**降级链**(写 `onNetworkTextReceived` 这类代码时必须保留顺序):
1. Shizuku 特权写入(优先, 零弹窗零焦点);
2. 普通 `setPrimaryClip`(前台时有效);
3. 拉起透明 `ClipWriteActivity` 抢一次前台焦点再写(最差但兜底)。

**相关文件**: `shizuku/ShizukuClipboardManager.kt`(1000 行, 最复杂的文件)、
`shizuku/ShizukuPrivilegeHelper.kt`。

**雷区**: `ShizukuPrivilegeHelper` 注入的系统命令(`cmd appops set ... 10008 allow` 等)
是 MIUI 私有权限码, 换 ROM 可能失败 —— 所以每一步都记录了日志但**不抛异常**。
不要为了让「日志干净」而把失败改成中止。

### 3.3 为什么用 6 位 PIN + 派生密钥

**问题**: 局域网内其他设备可以嗅探流量。不能让内容明文裸奔, 但也不该给普通用户
增加「生成证书/导入密钥」的门槛。

**解法**: 电脑端生成 6 位数字 PIN 并显示在托盘, 用户手动输入到手机。
两端都执行 `AES_256_KEY = SHA-256(PIN)`, 用 AES-256-GCM 认证加密。

**细节**:
- 握手 `POST /auth` 时, 手机发送的是**用 PIN 加密的时间戳挑战**, 而非明文 PIN
  (但服务端仍保留了明文 PIN 与 hash 两条兼容路径, 见 `server.rs` 的 `AuthRequest`);
- 每次加密使用**随机 12 字节 nonce**, 杜绝重放;
- PIN 可以随时「重新生成」, 生成后会主动断开所有旧连接强制重新握手。

**为什么不做中间人防护**: 局域网工具, 威胁模型是「防邻居偷看」而不是「防国家级攻击」。
过度设计会牺牲零配置体验。**如果你要加证书体系, 请先确认用户真的需要。**

### 3.4 为什么要防回环(两端各自的哈希队列)

**问题**: A 写入剪贴板 → 同步给 B → B 写入剪贴板 → B 的监听器又触发 → 同步回 A → 死循环。

**解法**: 双端各自维护一个「最近发送内容哈希」的环形队列:
- Windows: `clipboard.rs` 的 `LAST_TEXT_HASH` + `IS_UPDATING_SELF` 原子标志
  (自己写剪贴板前置位, 写完清除, 监听回调看到置位就直接返回);
- Android: `SyncForegroundService.recentHashes`(`LinkedHashMap<String, Long>`, 最多 50 条,
  **每条只保留 60 秒**) —— 判重只看时间窗内: 回环反射/监听双发都在秒级, 而用户几分钟后
  重新复制同一段内容是合法诉求(电脑端剪贴板可能早已改变), 必须放行;
  被去重跳过时会打 `CLIP_DETECT` 日志, 不是无声丢弃。

**新增任何「写入剪贴板」的代码路径时, 都必须把内容哈希塞进这个队列**, 否则回环会立刻出现。

### 3.5 为什么文件传输要分块 + 二进制 body

**问题**: 第一版文件传输(256 KB 分块 + `base64(JSON)` + 每块新建 TCP 连接)实测很慢,
对比 LocalSend 的 26 MB/s 差得远。

**根因(实测定位)**:
1. `ConnectionPool(0, 1, SECONDS)` —— 空闲连接数为 0, 每块都要重新 TCP 三次握手;
2. 整个文件被 `readBytes()` 读进内存 **3 次**(分块 + 两次算哈希, 其中一次 `readText` 对二进制还是错的);
3. `base64` 让传输量膨胀 33%;
4. 分块只有 256 KB, 请求数偏多。

**现状设计(不要退回旧实现)**:
- 分块 **1 MB**, 两端常量必须一致
  (`FileUploader.CHUNK_SIZE` / `FileReceiver.CHUNK_SIZE` / `rust file_transfer.rs` 的 `CHUNK_SIZE`);
- 分块密文**直接作为 HTTP body** 传输(`application/octet-stream`), 元数据走 URL query
  (`?file_id=..&index=..&total=..`), **不做 Base64、不套 JSON**;
- 加解密走 `encrypt_to_bytes` / `decrypt_bytes_raw`, 产物是 `nonce(12B)||ciphertext||tag(16B)`;
- 发送端**所有分块复用同一条 TCP 连接**(HTTP keep-alive);
  电脑端接收服务(`LocalHttpServer`)也为 keep-alive 重写过, 注意它现在是「同一 socket 循环处理多个请求」;
- 哈希一律**流式计算**(`computeHashFile` / `compute_file_hash`), 不把整个文件读进内存;
- 电脑端发送只保存**文件路径**, 按需 `seek + read` 取分块, 不缓存文件内容。

**推论**: 若要改分块大小或帧格式, **两端必须同批修改**, 否则表现为「传输到 100% 后校验失败」。
这是最容易出错的地方, 因为它不会在编译期报错。

### 3.6 为什么 Windows 端要用「顶层隐藏窗口」而不是消息窗口

**问题**: 托盘程序需要在系统关机时优雅退出(否则会阻塞用户关机)。

**解法**: 用 `CreateWindowExW` 创建 **父句柄为 `NULL` 的顶层隐藏窗口**。
如果图省事用 `HWND_MESSAGE`(仅消息窗口), 它**收不到 `WM_QUERYENDSESSION` / `WM_ENDSESSION`**,
会导致关机被拖住 —— 这是一个非常隐蔽的坑。

**相关**: `main.rs` 的 `wnd_proc` 中 `WM_QUERYENDSESSION` 直接返回 1 放行、
`WM_ENDSESSION` 里释放网络资源并 `exit(0)`。

### 3.7 为什么心跳是 30 秒 + 25 秒节流

**问题**: 早期心跳 15 秒一次, 叠加 Shell 唤醒脉冲, 待机功耗偏高。

**解法**:
- 电脑端判定手机离线要 **600 秒**(`server.rs` 中 peers 超时), 所以 30 秒上报一次余量极大;
- 手机端感知电脑掉线**主要靠 SSE 断开(秒级)**, 不依赖心跳频率 ——
  **所以「感知变慢」时不要用提高心跳频率来治, 要去看 SSE**;
- 三路触发源(定时线程 30s / Shell 唤醒脉冲 10s / 亮屏事件)全部收敛到**同一个**
  `sendHeartbeatThrottled()`, 用 `HEARTBEAT_MIN_GAP_MS = 25s` 去重。
  进程被 ROM 冻结后定时线程停摆, 脉冲解冻时会立刻补发(此时已超过节流窗口)。

**新增任何「需要上报」的场景时, 请复用这个入口, 不要新增独立的 HTTP 定时器。**

### 3.8 为什么自动搜索要分阶段降频

**问题**: 电脑关机后, 手机端会持续在局域网高频搜索 8 小时以上, 夜间耗电严重。

**解法**(`LanDiscovery.kt`):
| 已搜索时长 | 行为 |
| :--- | :--- |
| 0–5 分钟 | 保持高频(2.5 s 一轮 + 全网段并发探测) |
| 5–15 分钟 | 降频到每 60 s 一轮 |
| > 15 分钟 | **停止搜索**, 等待用户手动点「重新扫描」 |

另有「自动搜索」总开关: 关闭后**立即**中断循环并释放 UDP socket 与 MulticastLock;
此时手动点「重新扫描」仍会执行**有限轮次**(`MANUAL_SCAN_ROUNDS = 3`)的扫描。

**掉线重连也要重置计时**(`onPcDisconnected() → restartAutoSearchWindow()`),
否则「连上过又掉线」会沿用旧的时间窗直接放弃搜索。

**推论**: 新增后台轮询任务时, 请照这个模式设计「能停、能降频」, 不要写死循环。

### 3.9 为什么文件要落盘到 SAF 目录 / 为什么临时文件在 cacheDir

**问题 A**: 用户希望自定义接收文件的保存位置, 但 Android 10+ 分区存储不允许
应用随便往任意路径写文件。

**解法 A**: 用 SAF(`ACTION_OPEN_DOCUMENT_TREE`)让用户选目录, 拿到 `content://` URI 后
调 `takePersistableUriPermission` 持久化授权 —— 这样进程重启后仍可写入。
默认目录仍是 `Download/CrossClip`(走 File API, Android 11+ 对 Download 目录放行)。

**问题 B**: 接收过程中需要边收边写, 但如果直接往 SAF 目录写, 中途失败会留下半截文件。

**解法 B**: 临时文件一律写在 **App 私有 `cacheDir/file_transfer/`**,
`/file/complete` 时校验哈希通过后才交给 `SaveDirManager` 落盘到目标目录。

**相关**: `util/SaveDirManager.kt`(目录配置 + 落盘 + 打开目录)、`network/FileReceiver.kt`。

### 3.10 为什么 Windows 右键菜单要用注册表 + WM_COPYDATA

**问题**: 用户希望在资源管理器里右键文件 → 「发送文件到手机」。
但本程序是**单实例**的(命名互斥体), 右键菜单拉起的新进程会直接退出。

**解法**:
1. 启动时把菜单项写进 `HKCU\Software\Classes\*\shell\CrossClipSendFile`(含图标与 `command`),
   命令行形如 `"CrossClip.exe" --send-file "%1"`;
2. 新进程启动时, 若互斥体已存在 → 用 `FindWindowW` 找到主窗口 →
   通过 **`WM_COPYDATA`** 把文件路径投递给它 → 自己立刻退出;
3. 主窗口的 `wnd_proc` 收到 `WM_COPYDATA` 后转为发送任务。

**为什么用 HKCU 而不是 HKLM**: 不需要管理员权限。

**雷区**: `WM_COPYDATA` 的载荷必须以 NUL 结尾, 且必须是**同步** `SendMessageW`
(返回前发送方的缓冲区必须保持有效)。改成 `PostMessageW` 会读到已释放内存。

### 3.11 为什么 Android 端要「多任务卡片加锁 / 隐藏」

**问题**: 用户一键清理全部任务时, 后台服务被杀, 同步中断。

**解法**: `MainActivity` 的 `applyHideFromRecentsPreference()` 调用
`ActivityManager.appTasks.setExcludeFromRecents()` 让应用在多任务视图隐身;
UI 上提供各厂商的「加锁」操作指引。

**这不是可选的 UI 装饰, 而是保活链的一环**, 改动时不要删。

### 3.12 为什么「手动断开」要停搜索、还要抑制自动重连

**问题**: 用户在手机上点「断开连接」, 几秒后连接自己又回来了 —— 断开形同虚设。

**根因**(三个因素叠加):
1. `disconnectCurrentPc()` 原先只切断了 SSE, **没有停扫描线程**;
2. 扫描线程一发现目标电脑就会触发自动握手, 而判定条件「是记忆中的设备 + 内存里有 PIN」
   此时全部成立(`currentTargetDeviceId` 与 `pinCode` 都还在);
3. 于是断开动作刚做完, 扫描线程的下一轮就把连接接了回来。

**解法**(顺序有讲究, 不要重排):
1. **先落状态, 再断连接**: 先把 `connectionState = 0`, 再调 `sseClient.disconnect()`。
   后者会**同步**回调 `onConnectionChanged(false)`, 而那里用 `connectionState == 1` 判断
   是否属于「已连接 → 断开」跳变 —— 若此刻仍是 1, 就会走 `onPcDisconnected()`
   把刚停掉的扫描线程重新拉起来;
2. 置 `manualDisconnected = true`, 让 `onDeviceDiscovered()` 直接忽略扫描结果。
   这是**兜底**: 即使时序上扫描多跑了一轮、或 mDNS 恰好回调了一次, 也绝不自动接回;
3. 清空 `currentPcIp`, 让心跳线程自然停发 —— 否则它仍会持续向电脑端注册
   (见 §3.13), 断开等于没断;
4. `nsdHelper.stopDiscovery()` + `lanDiscovery.stopDiscovery()`, 使终态与
   「自动搜索 15 分钟超时」**完全一致** —— 通知栏与页面都显示「搜索已暂停」。

**抑制标志的解除时机**: 只在用户**主动发起连接**时清除 —— 重新扫描 / 输 PIN / 选设备 /
重开自动搜索。断开是「用户说别连」, 这四种动作是「用户说要连」, 二者必须区分开。

### 3.13 连接状态的「唯一真相来源」

**问题**: 手机端已经断开, 电脑端托盘却继续显示「已连接手机: XXX (ip)」长达 10 分钟。

**根因**: 电脑端 `server.rs` 的 Peer 表只在 **600 秒无心跳**后才被 `retain` 修剪,
而 `/events` 的 SSE 写循环结束后**从不摘除** peer。于是「是否已连接」这个状态实际上变成了
「最近 10 分钟内是否收到过心跳」, 与真实连接无关。

**解法**: 让 Peer 的**注册与摘除与连接同生共死**:

| 时机 | 动作 |
| :--- | :--- |
| `/auth` 握手成功、`/heartbeat`、`/sync`、`/events` 建连 | `register_peer()` |
| 手机端主动断开 → `POST /disconnect` | `unregister_peer()` |
| `/events` 长连接结束(含响应头写入失败) | `unregister_peer()` |
| 600 秒无任何联系 | `retain` 超时兜底 |

为什么三条路都要留:
- `/disconnect` 是**即时**路径(POST 到达即生效), 覆盖用户正常点「断开连接」的场景;
- SSE 连接结束是**兜底**路径, 覆盖「手机进程被杀 / 网络断开 / 用户没走正常断开流程」。
  注意它有延迟: 电脑端要等下一次写入(keepalive 每 30 秒发一次)失败, 才知道对端没了;
- 600 秒超时退化为「连 TCP 都不通知一声就消失」的极端场景兜底。

**代价(已知且可接受)**: 手机侧网络抖动导致 SSE 瞬断时, 电脑端托盘可能短暂显示
「已连接手机: 无」, 手机端 2 秒后自动重连即恢复。这比「断开后还挂 10 分钟」正确得多。

**推论**: 新增任何「能表明手机端在线 / 离线」的端点时, 请让它在建立时注册、结束时摘除,
不要只往 Peer 表里写而不管销账。

### 3.14 为什么通知小图标必须用「图案层」而不是「完整图标」

**问题**: 通知栏里显示的是一个与 App 图标无关的图标(早期用的是 Android 系统内置图标),
后来想直接换成应用图标, 结果整条通知变成一坨实心方块。

**根因**: Android 把通知小图标当作 **alpha 蒙版**渲染 —— 只取形状、丢弃颜色。
`@mipmap/ic_launcher` 是自适应图标的**完整图**(前景层 + **不透明**背景层),
整张都不透明, 套上蒙版就是一个实心方块; `@drawable/ic_launcher_foreground` 虽是
前景层, 但它承载的是整张彩色圆角方块图(供启动器叠加白色底色), 蒙版后同样只是
一个实心圆角方块, 看不出应用图案。

**解法**: 通知使用**专用剪影层** `ic_notification_foreground`(从源图抠出的
「手机+电脑+WiFi」图案主体, 透明底), 并让所有通知的小图标统一走一个常量:

```kotlin
val NOTIFICATION_SMALL_ICON = R.drawable.ic_notification_foreground
```

- 新增通知时**复用该常量**, 不要写字面量、不要改回 `@mipmap/ic_launcher`;
- 分享面板的通知也复用它 —— 通知栏里所有 CrossClip 通知必须看起来来自同一个 App;
- 快捷磁贴**相反**, 用的是 `@mipmap/ic_launcher`(完整彩色图标, 不走蒙版) ——
  两条渲染路径别混。

**完整规格、双端 7 处呈现与换图标步骤见 [`docs/app-icons.md`](app-icons.md)。**

---

## 4. 模块职责地图

> 「改这里会影响什么」—— 改任何文件前先看这一列。

### 4.1 Windows 端 (`rust_desktop/src/`)

| 文件 | 职责 | 改它会牵连 |
| :--- | :--- | :--- |
| `main.rs` | 入口、Win32 托盘图标与菜单、单实例互斥、关机广播、右键菜单注册、WM_COPYDATA 接收、文件选择器、文件发送入口(状态反馈走自绘浮窗, 已无气泡通知) | 托盘菜单 ID 常量、`AppState` 字段、`wnd_proc` 分支 |
| `server.rs` | HTTP 服务(18236)、SSE、鉴权、**Peer 表的注册与摘除**、`/disconnect` 断开握手、文件收发端点、**向手机发送文件**、接收端去重协商 | 协议、Peer 生命周期(§3.13)、`Broadcaster` |
| `crypto.rs` | SHA-256 派生、AES-GCM 加密(字符串 / 原始字节 / 流式文件哈希) | **两端必须一致**, 改任一函数都要核对 Android 的 `CryptoUtil.kt` |
| `file_transfer.rs` | 文件传输状态机、分块读写、临时文件、重名规避、**同名+同大小+同哈希去重** | `CHUNK_SIZE` 必须与手机端一致 |
| `clipboard.rs` | Win32 剪贴板读写、`IS_UPDATING_SELF` 防回环 | 回环控制 |
| `config.rs` | `config.json` 读写(设备 ID / PIN / 端口 / 偏好) | 首次运行行为、PIN 持久化 |
| `ip_util.rs` | 局域网 IP 提取(过滤 VPN / 虚拟网卡 / VMware) | 发现成功率 |
| `udp_discovery.rs` | UDP 18234 广播应答(OFFER 报文) | 两端发现协议字段 |
| `mdns.rs` | mDNS 服务发布(`_crossclip._tcp.local.`) | 与 Android `NsdHelper` 配对 |
| `progress_window.rs` | **自绘文件传输进度浮窗**(置顶 / 无边框 / 不抢焦点) | 托盘气泡通知没有进度条能力, 这是唯一能显示进度的途径 |
| `icon.rs` | 从内嵌 `assets/app.ico` 里挑尺寸最接近的条目建 HICON | 必须与 `build.rs` 嵌进 exe 资源的是同一张图 |
| `build.rs` + `app.rc` | 用 Windows SDK 的 `rc.exe` 把图标嵌进 exe 资源节(找不到只告警、不中断构建) | 影响 exe 文件自身显示的图标 |

### 4.2 Android 端 (`android/app/src/main/java/com/crossclip/app/`)

| 文件 | 职责 | 改它会牵连 |
| :--- | :--- | :--- |
| `service/SyncForegroundService.kt` (1450 行) | **核心枢纽**: 前台服务、连接状态机、配对、SSE 调度、心跳、文件回调、通知、**手动断开语义(§3.12)**、**通知小图标常量(§3.14)** | 几乎一切。改前务必通读一遍 |
| `ui/MainActivity.kt` (896 行) | 主界面、设备列表与切换、各项设置、日志工具; 「断开连接」按钮入口 | 布局 `activity_main.xml` 的 id 必须一一对应; 轮询 UI 的文案必须与服务端 `statusTextForNotification()` 一致 |
| `shizuku/ShizukuClipboardManager.kt` (1001 行) | Shizuku Binder 剪贴板读写、底层监听、轮询兜底 | 降级链、自检工具 |
| `shizuku/ShizukuPrivilegeHelper.kt` | Shell 特权白名单注入、UID 2000 唤醒脉冲 | 保活能力(`PULSE_INTERVAL_SEC`) |
| `network/LocalHttpServer.kt` | 手机侧 HTTP 服务(18237), **keep-alive 循环处理请求**, prepare 响应回告 `already_exists` | 与 `server.rs` 的发送端配对 |
| `network/FileUploader.kt` | 手机→电脑文件发送(分块+加密+进度), 带 `file_hash`, 电脑端已有该文件时整个跳过上传 | `CHUNK_SIZE`、二进制帧格式 |
| `network/FileReceiver.kt` | 电脑→手机文件接收(临时文件+校验+落盘), **同名+同大小+同哈希去重** | `SaveDirManager` |
| `network/HttpUploader.kt` | 剪贴板密文、心跳上报、**主动断开时向电脑端发的 `POST /disconnect` 告别请求** | 协议字段; 该请求是「尽力而为」, 失败静默, 由 §3.13 的两条兜底路径接管 |
| `network/SseClient.kt` | 出站长连接客户端 | 重连策略 |
| `network/LanDiscovery.kt` | UDP 广播、子网并发探测、**自动搜索降频状态机** | 耗电表现 |
| `network/NsdHelper.kt` | mDNS 发现 + HTTP 探活过滤幽灵缓存 | 发现成功率 |
| `crypto/CryptoUtil.kt` | 与 Rust `crypto.rs` **逐字节对称** | 改一端必须改另一端 |
| `receiver/ShareReceiveActivity.kt` | 系统分享面板入口(SEND / SEND_MULTIPLE); **「直接分享」/长按拖拽浮层目标**(`res/xml/shortcuts.xml` 的 share-target + `CrossClipApp` 发布的同 category 动态快捷方式, 两者缺一不可); 通知图标复用 `NOTIFICATION_SMALL_ICON` | `AndroidManifest.xml` 的 intent-filter 与 shortcuts meta-data |
| `receiver/ProcessTextActivity.kt` | 文本选择菜单「发送至电脑」 | 同上 |
| `ui/ClipWriteActivity.kt` | 透明 Activity, 抢焦点写剪贴板(降级链第 3 层) | 降级链 |
| `ui/OpenSaveDirActivity.kt` | 无界面跳板: 「文件接收完成」通知点击后打开保存目录, 随即 `finish()` | 打开目录必须由 Activity 上下文发起 |
| `ui/ScanRefreshController.kt` | 「重新扫描」的旋转箭头动画与「正在扫描中」文案 | 依赖 `activity_main.xml` 的 `iv_refresh_icon` / `tv_refresh_label` |
| `tile/SendClipTileService.kt` | 控制中心快捷磁贴 | 手动发送入口 |
| `util/SaveDirManager.kt` | 保存目录配置(SAF)、落盘、打开目录 | `file_paths.xml`(FileProvider) |
| `util/DebugLogger.kt` | 双文件日志(内部 + 外部)、5 MB 轮转、崩溃捕获 | 排查问题的唯一手段 |
| `util/PermissionHelper.kt` | 各厂商权限/电池/自启动页面跳转 | 保活引导 |
| `CrossClipApp.kt` | Application 入口: 日志初始化、崩溃捕获、系统状态快照 | 诊断能力 |

---

## 5. 双端协议契约

### 5.1 端口与服务

| 端口 | 类型 | 归属 | 用途 |
| :--- | :--- | :--- | :--- |
| 18234 | UDP | Windows | 自发现广播应答(OFFER) |
| 18236 | HTTP/SSE | Windows | `/ping` `/auth` `/sync` `/heartbeat` `/disconnect` `/file/*` `/events` |
| 18237 | HTTP | Android | `/ping` `/sync` `/file/*`(对等兜底推送 + 文件接收) |
| — | mDNS | 双端 | `_crossclip._tcp.local.`, TXT 携带 `device_id` |

### 5.2 加密总纲

```text
key         = SHA-256(pin_code)                       # 6 位数字, 两端各自派生
nonce       = random(12 bytes)
密文(文本)   = base64( nonce || AES-256-GCM(key, nonce, utf8(text)) )   # 剪贴板/控制类
密文(文件块) =        nonce || AES-256-GCM(key, nonce, chunk_bytes)      # 二进制直传, 无 Base64
hash        = SHA-256(plaintext)  或  SHA-256(整个文件, 流式)
```

> **注意**: 剪贴板走 Base64(因为要放进 JSON), 文件块**不走 Base64**(直接作为 HTTP body)。
> 两者字节布局相同(`nonce||ciphertext||tag`), 只是编码方式不同。

### 5.3 文件传输端点

| 路径 | 方法 | Body | 说明 |
| :--- | :--- | :--- | :--- |
| `/file/prepare` | POST | JSON `{type,file_id,filename,file_size,mime_type,sender_id,file_hash?}` | 建临时文件; 带 `file_hash` 时做接收端去重(响应回 `already_exists`) |
| `/file/chunk?file_id=&index=&total=` | POST | **二进制密文** | 元数据在 query, 密文在 body |
| `/file/complete` | POST | JSON `{type,file_id,file_hash}` | 校验整文件哈希并落盘 |

进度通过 SSE 事件广播: `FILE_PROGRESS`(接收进度) / `FILE_RECEIVED`(落盘完成)。

### 5.4 改协议时的兼容性规则

- 端点表与字段名一旦发布, **只能增不能改**(老版本 App 仍在用户手机上);
- 若要改语义, 请**新增端点或新增可选字段**, 两端各自做能力探测;
- 分块大小这类「隐式契约」没有版本协商机制, **改它等于要求用户同时升级两端**,
  发版说明里必须写清楚。

---

## 6. 新功能开发规则

### 6.1 不能破坏的不变量(Invariants)

1. 任何写入剪贴板的路径都必须登记哈希去重(§3.4);
2. 任何后台网络任务都必须「可停、可降频」(§3.8);
3. SSE 长连接必须跑在独立线程, 不能占用工作线程池(§3.1);
4. Windows 托盘窗口必须是顶层隐藏窗口(§3.6);
5. 文件临时数据不得直接写用户可见目录(§3.9);
6. 单实例语义必须保留(§3.10);
7. 所有通知的小图标必须复用 `SyncForegroundService.NOTIFICATION_SMALL_ICON`(§3.14);
8. Peer 的注册与摘除必须与连接同生共死 —— 新增端点时不要只写不销账(§3.13)。

### 6.2 新增一个「手机 → 电脑」的能力

1. 电脑端在 `server.rs::handle_client_request` 里加端点分支(注意: 用 `path ==` 精确匹配, 不要前缀匹配, 否则会吃掉 `/file/*`);
2. 若是长耗时操作, 交给 `WorkerPool`(普通请求)而不是主循环;
3. 手机端在对应 `network/` 类里加调用;
4. 加日志(两端都用 `DebugLogger.log` / `println!`) —— **没有日志的新功能在用户那边等于不可调试**。

### 6.3 新增一个「电脑 → 手机」的能力

1. 优先走 **SSE 事件**(实时、省电), 因为长连接已经建好了;
2. 若数据量大(如文件), 用**手机端 18237 端点**承接, 通过 SSE 只发「准备工作」指令;
3. 手机端在 `SyncForegroundService` 的 `SseClient(onFileEvent=…)` 里加事件分支。

### 6.4 新增一个 UI 设置项(Android)

改动是**四处联动**, 漏一处就会崩或无效:
1. `res/layout/activity_main.xml` 加控件(带唯一 `android:id`);
2. `MainActivity` 加 `lateinit var` 字段;
3. `initViews()` 里 `findViewById` + 绑定监听;
4. 若需持久化 → 用 `cross_clip_config` SharedPreferences(key 命名保持蛇形小写)。

**注意**: `loadConfig()` 回填开关状态时会触发 `OnCheckedChangeListener`,
务必用 `isInitializingUi` 标志位跳过真实逻辑(现有代码已这么做)。

### 6.5 资源释放约定

项目的资源分四类, **每一项都要在 `onDestroy` / 退出路径上释放**:

| 资源 | 位置 | 释放 |
| :--- | :--- | :--- |
| 剪贴板监听器 | `SyncForegroundService.clipListener` | `removePrimaryClipChangedListener` |
| 广播接收器 | `screenStateReceiver` | `unregisterReceiver` |
| 网络对象 | `nsdHelper` / `lanDiscovery` / `sseClient` / `localHttpServer` | 各自的 `stop()` / `disconnect()` |
| 定时器 | `heartbeatExecutor` | `shutdownNow()` |
| Socket / MulticastLock | `LanDiscovery` | `stopDiscovery()` |

### 6.6 注释与日志约定

- **注释写「为什么」不写「是什么」**。例:
  ✅ `// 避让 15ms 允许复制源应用完成写操作并安全关闭剪贴板句柄`
  ❌ `// 睡眠 15 毫秒`
- 涉及厂商 ROM / 系统限制的代码, 注释里必须写清**是哪个系统、哪个版本、什么现象**;
- 日志 tag 已形成惯例, 新增请沿用:
  `SVC_LIFECYCLE` `CLIP_DETECT` `CLIP_WRITE` `CLIP_RECV` `SVC_SEND` `DISCOVERY`
  `SSE` `HEARTBEAT` `LOCAL_HTTP` `FILE_RECEIVE` `SHIZUKU_PRIV` `EXIT_REASON`。

---

## 7. 历史坑与雷区(血泪教训)

| # | 现象 | 根因 | 现状 |
| :--- | :--- | :--- | :--- |
| 1 | 大文件传输慢 | 连接池空闲连接数为 0 + 文件读 3 次 + Base64 膨胀 | 已改二进制 body + keep-alive + 流式读写 |
| 2 | 传输到 100% 后通知卡在「正在发送」 | `onError` 只弹 Toast、从不更新通知 | 失败/完成都发终态通知 |
| 3 | 电脑关机后夜间耗电 | 无上限的持续高频搜索 | 分阶段降频, 15 分钟后停止 |
| 4 | 部分机型后台收不到同步 | Android 10+ 后台剪贴板限制 | Shizuku + 三层降级 |
| 5 | 手机息屏后电脑推不动 | 国产 ROM 拦截入站 TCP | 改为手机主动出站 SSE |
| 6 | 两台设备互相同步刷屏 | 缺防回环 | 双端哈希队列 + `IS_UPDATING_SELF` |
| 7 | 关机被程序拖住 | 误用 `HWND_MESSAGE` 消息窗口 | 顶层隐藏窗口 |
| 8 | `readText()` 算二进制文件哈希 | 把二进制当 UTF-8 读 | 改用流式字节哈希 |
| 9 | 重启后无法写入自定义目录 | 忘记 `takePersistableUriPermission` | 已在 `SaveDirManager.setCustomDir` 调用 |
| 10 | 多虚拟网卡环境广播错误地址 | 未过滤 VMware / VPN 网卡 | `ip_util.rs` 白名单私有网段 |
| 11 | 大文件(.apk / .rar)收完后通知栏卡在「正在接收 100%」 | 完成通知与 `setOngoing(true)` 的进度通知都要经 `mainHandler.post` 异步投递, 存在竞态; 而 ongoing 通知不会自动消失 | `SyncForegroundService.fileTransferSettled` 在终态后丢弃一切迟到的进度刷新 |
| 12 | Windows 端无法显示文件传输进度 | `NOTIFYICONDATAW` 的 `NIF_INFO` 气泡只支持标题 + 正文两行文本, 没有进度条能力 | `progress_window.rs` 自绘置顶无边框浮窗(收发双向共用) |
| 13 | 嵌入 exe 图标后链接报 `LNK1104` | `cargo:rustc-link-arg` 的值**不能加引号**, 引号会被原样带进 link 命令行, 路径被判为非法文件名 | `build.rs` 输出裸路径(项目路径含空格时需另行处理) |
| 14 | 通知栏里显示的是「另一个图标」, 与应用图标无关 | 所有通知的 `setSmallIcon` 用的都是 Android 系统内置图标(`ic_menu_share` / `ic_menu_save` / `ic_dialog_*` / `ic_menu_upload`) | 统一走 `SyncForegroundService.NOTIFICATION_SMALL_ICON`(§3.14) |
| 15 | 点「断开连接」后几秒又自己连上 | `disconnectCurrentPc()` 只断了 SSE 没停扫描线程; 扫描发现「记忆中的设备 + 内存里还有 PIN」即重新握手 | 断开即停 UDP/mDNS 搜索并置 `manualDisconnected` 抑制重连(§3.12) |
| 16 | 手机端已断开, 电脑端托盘仍显示「已连接手机」 | Peer 表只在 600 秒无心跳后才修剪, SSE 连接结束也不摘除, 状态退化成「最近 10 分钟是否收到过心跳」 | 新增 `POST /disconnect` 即时摘除, SSE 长连接结束时也摘除(§3.13) |
| 17 | 中等大小文件(实测 5/10/20/50MB)收完后通知栏卡在「正在接收 100%」, 而 1/2/100MB 反而正常 | 终态「完成」通知与进行中卡片共用同一通知 ID: 进行中卡片在同一 ID 上高频更新过若干次, 个别 ROM 的通知优化会合并/限流「同 ID 的快速连续更新」, 最后一条终态更新被系统吞掉; 触发与否取决于传输时长落在哪个窗口, 故呈现「个别大小必现」的假象 | 终态(完成/失败)通知改用**全新 ID** `NOTIFICATION_ID_FILE_SETTLED`(2003)发布, 并显式 `cancel` 进行中卡片 —— 新 ID 是「新通知」而非「第 N 次更新」, 从根上绕开同 ID 更新合并; 另桌面端补上 chunk/complete 响应状态码检查, 手机端拒绝时浮窗报「发送失败」而非假成功 |

---

## 8. 构建、调试与验证

### 8.1 构建

```bash
# Windows 端
cd rust_desktop && cargo build --release
# 产物: rust_desktop/target/release/cross_clip.exe

# Android 端
cd android && ./gradlew assembleRelease
# 产物: android/app/build/outputs/apk/release/app-release.apk
```

> Release 构建必须配置 `signingConfig`, 禁止交付 `app-release-unsigned.apk`。

### 8.2 调试

- **Android**: `DebugLogger` 会同时写内部(`filesDir`)与外部(`getExternalFilesDir`)两份日志,
  5 MB 轮转。App 内「诊断与排查工具」可查看/复制/导出。点击日志路径可打开所在目录。
- **Windows**: 崩溃写 `crossclip_crash.log`(工作目录); 运行时状态见托盘 tooltip 与托盘菜单;
  文件收发过程有气泡通知。

### 8.3 验证清单(改动后至少跑一遍)

- [ ] 两端都能编译;
- [ ] 配对成功(PIN 正确) / 失败(PIN 错误应提示而非静默重连);
- [ ] 剪贴板双向同步(含中文);
- [ ] 文件双向传输(>100 MB 大文件, 观察速度与内存占用);
- [ ] 息屏 5 分钟后仍能收到同步(考验保活);
- [ ] 通知栏里的小图标与应用图标一致(是图案剪影, 不是白块、也不是系统图标);
- [ ] **手动点「断开连接」后, 手机不再自动连回**(至少等 1 分钟), 且通知栏显示「搜索已暂停」;
- [ ] **手机端断开后, 电脑端托盘菜单立即变为「已连接手机: 无」**(不必等 10 分钟);
- [ ] 断开电脑后, 手机在 15 分钟内降频、之后停止搜索;
- [ ] 关机时 Windows 端能立即退出(不拖住关机);
- [ ] 资源管理器右键文件 → 「发送文件到手机」可用(程序已在运行时);
- [ ] 自定义保存目录后重启 App 仍能写入。

---

## 9. 术语表

| 术语 | 含义 |
| :--- | :--- |
| **Peer** | 已注册的对等设备记录(`server.rs::ClientPeer`), 含 ip / port / device_id / last_seen |
| **OFFER** | UDP 发现应答报文, 携带设备名、device_id、候选 IP 列表、http_port |
| **出站长连接** | 手机主动向电脑发起的 SSE 连接(相对「入站」) |
| **防回环** | 通过内容哈希拦截自己写入引发的反射同步 |
| **降级链** | Shizuku → 普通写入 → 透明 Activity 的三层剪贴板写入策略 |
| **唤醒脉冲** | UID 2000 Shell 进程每 10 秒发的广播, 用于解冻被 ROM 冷冻的应用进程 |
| **时间窗** | 自动搜索的分阶段降频计时(0–5 / 5–15 / >15 分钟) |
| **手动断开抑制** | `manualDisconnected` 标志: 用户点过断开后, 扫描结果不再触发自动重连, 直到用户主动发起连接(§3.12) |
| **Peer 摘除** | `Broadcaster::unregister_peer()`: 连接结束时把该 IP 从 Peer 表移除, 让托盘状态与真实连接同步(§3.13) |
| **通知小图标** | 通知栏左侧的图标, 被系统按 alpha 蒙版渲染, 因此必须用图案层而非完整图标(§3.14) |

---

## 10. 给未来 AI 的最后三句话

1. **先读代码, 再改代码**。本文档的表格只是索引, 真正的约束在代码注释里。
2. **改动越「理所当然」, 越可能踩坑**。这个项目里几乎所有奇特写法都是被某个真实问题逼出来的。
3. **改完必须两端一起验证**。单端能编译通过, 不代表功能可用 —— 协议错误只在运行时暴露。

*本文档随代码演进而更新。当你解决了一个新的坑, 请把它加进 §7。*
