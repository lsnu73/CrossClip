# 「退出软件」必须同时封堵三条复活路径，且用持久退出态兜底竞态
- 日期：2026-10-19
- 状态：已采纳
- 关联约束：INV-006
- 关联坑点：无（实现前完整测绘，未踩坑）

## 背景
用户要求设置页新增「退出软件」：彻底退出，等同 Windows 的结束进程。但本 App 为了
「长期免维护运行」积累了三层保活/复活机制，任何一条没堵死，退出后都会在数秒内复活：

1. **START_STICKY**（`SyncForegroundService.onStartCommand` 返回值）：服务仍标记为
   running 时进程被杀，系统会重新拉起服务；
2. **onTaskRemoved 闹钟**：用户划掉任务卡片时通过 AlarmManager 调度 1 秒后
   `PendingIntent.getService` 拉起服务（仅服务存活时回调）；
3. **Shizuku Shell 守护循环**（`ShizukuPrivilegeHelper.startBackgroundWakeupPulse`）：
   `nohup` 脱离本进程、以 UID 2000 常驻，每 10 秒 `pidof` 探测，进程一死立即
   `am start-foreground-service` 拉起——这条**能跨进程死亡存活**，是最硬的一条。

## 候选方案
| 方案 | 优点 | 缺点 |
|---|---|---|
| A（最终采用）：置退出态标志 → pkill 守护 → stopService → 延迟 800ms 杀进程 | 三条路径全部封堵；标志持久化可兜住在途竞态；手动打开 App 自然复活 | 杀进程前需等待异步 stopService 落地，有 ~800ms 退出延迟 |
| B：只杀进程（`killProcess`） | 实现一行 | Shizuku 守护 10 秒内复活，等于功能无效 |
| C：退出时清掉 Shizuku 授权/白名单 | 无守护可活 | 破坏用户授权状态，下次打开要重新配置，代价过大 |
| D：用 ActivityManager.killBackgroundProcesses / 系统「强行停止」 | 语义最干净 | 普通应用无权 force-stop 自己之外的包；对自己调用也拦不住 UID 2000 的守护 |

## 结论与理由
选 A。四个动作的顺序不能反：
1. 先写 `user_exited=true`（SharedPreferences，持久）——此后任何路径把服务拉活，
   `onStartCommand` 入口自查标志立即 `stopSelf()` 并返回 `START_NOT_STICKY`，
   覆盖「pkill 之前守护最后一拍已发出 am start」这类竞态；
2. `ShizukuPrivilegeHelper.stopBackgroundWakeupPulse()`——pkill 守护循环，
   同时复位 `pulseStarted`，下次手动打开时可重新拉起守护；
3. `stopService(SyncForegroundService)`——服务进入销毁流程后 STICKY 失效，
   onTaskRemoved 也不再回调；
4. `handler.postDelayed(800ms)` 后 `finishAndRemoveTask()` + `killProcess`——
   stopService 走主线程消息队列异步生效，不等它落地就杀进程，AMS 记录里服务
   仍是 running，STICKY 立刻复活。

唯一复活口：`MainActivity.onCreate` 清除 `user_exited`——即「下次使用需手动打开」，
与设计稿文案一致。

**重新评估条件**：若未来新增第四条保活机制（如厂商推送拉起、JobScheduler、
WorkManager、第二守护进程），必须让它也走「先查 user_exited 再决定是否启动」的
入口闸门，否则本方案失效——见 INV-006。
