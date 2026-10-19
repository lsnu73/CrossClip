# 地图修改时间线

- 2026-10-19 首次建图：新增 README / glossary（11 条术语）/ map（连接与发现栈 4 个模块，其余区域标注未测绘）/ invariants（INV-001~004）/ decisions 1 条（SSE 闸门 vs 显式连接）/ pitfalls 1 条（关闭自动搜索后手动配对假死）。来源：v2.6.0「手动配对假死」bug 双端日志对时定位与修复全过程。
- 2026-10-19 新增 invariants 1 条（INV-005 判定禁用展示文案反推）、decisions 1 条（缓存=传输残留，协议收尾即删不累积）、pitfalls 1 条（缓存按钮因显示取整+文案解析失效）。来源：用户反馈「缓存恒 0MB、清理按钮没用过」的排查与修复；同日「退出软件」功能完成 index.html / crossclip-静态设计.html 设计稿（设置页底部 danger-row + 二次确认 + 已退出遮罩），待用户评审后再动 Android 界面。
- 2026-10-19 新增 invariants 1 条（INV-006 拉活入口必须尊重 user_exited 退出态）、decisions 1 条（退出软件须封堵 STICKY/onTaskRemoved 闹钟/Shizuku 守护三条复活路径，四步顺序不可调换）、glossary 2 条（退出态、Shizuku 守护循环）。来源：「退出软件」Android 端落地——设计师重做设计稿（exit-block 独立区块 + 描边危险按钮）后按稿复刻，实现前完整测绘三层保活机制。
