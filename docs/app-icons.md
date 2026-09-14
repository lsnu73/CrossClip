# CrossClip 图标体系(应用图标 / 通知图标 / 托盘图标)

> 本文是 [`docs/ai-handover.md`](ai-handover.md) 的**专题补充**, 只讲一件事: **图标**。
>
> 图标是一个横跨双端的主题 —— 它同时牵扯 Android 资源目录、Windows 资源节、
> 以及「启动器 / 通知栏 / 快捷磁贴 / 托盘 / exe 文件」五种呈现方式。
> 在模块职责表里它被拆散在好几行, 改的时候极容易漏掉某一处, 于是留下
> 「App 图标换了, 通知栏还是旧图标」这类看起来莫名其妙的问题。换图标前请通读本文。

---

## 0. 先记住三件事

1. **一张源图, 多种派生物。** 源图是仓库根目录的 `软件图标.png`(2048×2048 RGBA)。
   各端真正使用的图标资源都是它的派生物 —— **构建过程完全不读源图**, 只读
   `android/.../res/mipmap-*/` 与 `rust_desktop/assets/app.ico`。
2. **派生物是手工导出的一次性产物, 仓库里没有生成脚本。** 换图必须按 §2.4 / §3.1
   的规格逐个重新导出, 漏掉哪个, 哪个位置就停在旧图标上。
3. **通知小图标与启动图标是两套渲染规则, 不能互相替代。** 详见 §2.2 —— 这是本项目
   已经踩过的坑。

---

## 1. 呈现清单: 图标到底在哪些地方出现

| # | 呈现位置 | 平台 | 读取的资源 |
| :--- | :--- | :--- | :--- |
| 1 | 启动器 / 最近任务 / 应用信息页 | Android | `mipmap-*/ic_launcher.png`(`ic_launcher_round.png`) |
| 2 | Android 8.0+ 自适应图标 | Android | `mipmap-anydpi-v26/ic_launcher.xml` → 前景层 + 背景色 |
| 3 | **通知栏**(常驻守护 / 文件传输 / 分享面板) | Android | `SyncForegroundService.NOTIFICATION_SMALL_ICON` |
| 4 | 控制中心快捷磁贴 | Android | `AndroidManifest.xml` 里 `SendClipTileService` 的 `android:icon` |
| 5 | 「分享到」面板中的应用图标 | Android | 同 #1 |
| 6 | Windows 托盘 | Windows | `icon.rs` 从 `assets/app.ico` 取最接近 32×32 的条目 |
| 7 | exe 文件自身(资源管理器 / 任务栏) | Windows | `build.rs` 把 `assets/app.ico` 嵌进资源节 |

对照这张表就能明白: **只改 `软件图标.png` 不会有任何效果**, 它不参与构建。

---

## 2. Android 侧

### 2.1 启动图标: 自适应图标的两层结构

Android 8.0+ 使用**自适应图标**, 由系统按启动器的形状(圆形 / 方形 / 水滴)自行裁切,
因此图标必须拆成两层:

| 资源 | 作用 | 规格 |
| :--- | :--- | :--- |
| `values/ic_launcher_background.xml` | 背景层(纯色) | 当前 `#FFFFFF` |
| `drawable-xxxhdpi/ic_launcher_foreground.png` | 前景图案层 | 432×432 RGBA, **四周留安全边距** |

**安全边距是硬要求**: 系统会裁掉前景层的外圈, 图案画满整张图会被切掉边缘。
惯例是图案只占中间约 2/3(自适应图标画布 108dp, 安全区 72dp)。

`mipmap-*/ic_launcher.png` 与 `ic_launcher_round.png` 是**降级路径**: Android 8.0 以下,
或某些不支持自适应图标的启动器会直接读这两组位图。

> **现有债务**: `ic_launcher_foreground.png` 目前**只有 xxxhdpi 一份**, 其他密度靠系统降采样;
> 且 `ic_launcher_round.png` 与 `ic_launcher.png` 是**完全相同的图**(round 并未做圆形裁切),
> 圆角由启动器自行处理。后续补图标时建议一并补齐 `drawable-*dpi/` 各密度。

### 2.2 通知小图标必须用「图案层」, 不是「完整图标」 ⚠️

**这是最容易忘、后果又最明显的一处。**

Android 把通知小图标当作 **alpha 蒙版**渲染: 只取形状、丢弃颜色, 再用系统颜色填充。于是:

- 用 `@mipmap/ic_launcher`(完整图标, 含**不透明**的白色背景层)→ 整张图都不透明 →
  套上蒙版就是一个**实心方块**, 通知栏里看起来是个白块;
- 用 `@drawable/ic_launcher_foreground`(透明底 + 图案)→ 蒙版后才是应用图标本身的**形状剪影**。

所以全部通知的小图标都走 `SyncForegroundService.NOTIFICATION_SMALL_ICON` 这一个常量:

```kotlin
val NOTIFICATION_SMALL_ICON = R.drawable.ic_launcher_foreground
```

- **新增通知时请复用该常量**, 不要写字面量, 更不要改回 `@mipmap/ic_launcher`;
- 分享面板的通知(`receiver/ShareReceiveActivity.kt`)也复用它 —— 通知栏里所有 CrossClip
  通知必须看起来来自同一个 App, 所以图标来源只允许存在一处;
- 历史上这里用的是 Android 系统内置图标(`android.R.drawable.ic_menu_share` /
  `ic_menu_save` / `ic_dialog_info` / `ic_dialog_alert` / `ic_menu_upload`), 与应用图标毫无关系 ——
  现象就是「通知栏里显示的是另一个图标」。已全部替换, **不要改回去**。

### 2.3 快捷磁贴

`AndroidManifest.xml` 中 `SendClipTileService` 的 `android:icon` 用 `@mipmap/ic_launcher`。

磁贴显示的是**彩色**图标, 不走 alpha 蒙版 —— 与通知小图标是两条不同的渲染路径, 别混用。
(顺带一提: 该属性原先也是系统内置的 `@android:drawable/ic_menu_send`。)

### 2.4 资源清单(换图标时需要逐个替换)

| 文件 | 当前规格 |
| :--- | :--- |
| `mipmap-mdpi/ic_launcher.png` + `ic_launcher_round.png` | 48×48 |
| `mipmap-hdpi/...` | 72×72 |
| `mipmap-xhdpi/...` | 96×96 |
| `mipmap-xxhdpi/...` | 144×144 |
| `mipmap-xxxhdpi/...` | 192×192 |
| `drawable-xxxhdpi/ic_launcher_foreground.png` | 432×432, RGBA, 含安全边距 |
| `values/ic_launcher_background.xml` | 背景色, 当前 `#FFFFFF` |

密度比例 1× / 1.5× / 2× / 3× / 4×, 基数 48px。

> 导出建议: Android Studio 的 **Res → Image Asset** 向导可以一次生成上述全部 mipmap 密度
> 以及自适应图标的前景 / 背景层, 比手工切图可靠。当前仓库没有对应的脚本。

---

## 3. Windows 侧

### 3.1 单一来源: `rust_desktop/assets/app.ico`

Windows 侧只有**一个**图标文件 `assets/app.ico`, 内含 7 个尺寸条目:
**16 / 24 / 32 / 48 / 64 / 128 / 256**(全部 32bpp)。它被用在两个地方:

1. **exe 文件自身** —— `build.rs` 调 Windows SDK 的 `rc.exe` 把 `app.rc`
   (`1 ICON "assets/app.ico"`)编译成 `.res`, 再经 `cargo:rustc-link-arg` 交给链接器。
   资源 ID 取 **1**: Explorer 与任务栏把 ID 最小的 ICON 资源当作程序主图标;
2. **托盘图标** —— `icon.rs` 用 `include_bytes!` 把同一份 ICO 编进二进制,
   运行时 `CreateIconFromResourceEx` 挑最接近 32×32 的条目建 HICON。
   用内嵌而不是运行时读文件, 是因为 CrossClip 是**单文件绿色版**,
   用户会把 exe 复制到任意位置, 图标不能依赖旁边还有别的文件。

**为什么托盘不再是手绘的**: 旧实现用 GDI 逐块 `FillRect` 画 32×32 剪贴板图案,
只能做出硬边色块、没有抗锯齿。现在托盘 / 任务栏 / exe 属性页看到的是同一张图。

### 3.2 构建脚本的容错(别误判成「图标没生效」)

`build.rs` 找不到 `rc.exe`(未安装 Windows SDK)时**只打印 warning、不让构建失败** ——
缺个图标只是外观问题, 不该让任何人在 clone 之后连 `cargo build` 都跑不通。

所以出现「exe 图标没变」时, 先翻 cargo 输出里有没有这一行:
`warning: 未找到 rc.exe（Windows SDK），已跳过嵌入 exe 图标`。

⚠️ `cargo:rustc-link-arg` 的值**不能加引号** —— 会报 `LNK1104`
(详见 [`ai-handover.md`](ai-handover.md) §7 第 13 条)。

---

## 4. 换图标的完整步骤

1. 用新的 `软件图标.png` 重新导出 §2.4 的**全部** Android 资源(前景层记得留安全边距);
2. 重新生成 `rust_desktop/assets/app.ico`(必须是多尺寸 ICO, 只放一张 256×256 会让小尺寸发虚);
3. `cd android && ./gradlew assembleDebug`, 装到手机上逐项确认:
   - 启动器图标;
   - **通知栏**: 下拉通知栏, 常驻守护通知的小图标应是应用图标的**剪影**
     —— 不是白块、也不是系统图标;
   - 控制中心快捷磁贴;
4. `cd rust_desktop && cargo build --release`, 确认托盘图标与 exe 图标都已更新
   (资源管理器里右键 exe → 属性 → 图标);
5. 提交时注意: `软件图标.png` 是唯一设计源, 请一并提交 —— 丢了就没法重新派生。

---

## 5. 相关文档

- [`ai-handover.md`](ai-handover.md) §4.1 / §4.2 —— 图标相关文件在模块职责表里的位置;
- [`ai-handover.md`](ai-handover.md) §7 —— 历史坑与雷区(`LNK1104` 引号坑);
- [`engineering-rules.md`](engineering-rules.md) —— 图标与通知相关的硬性约束。
