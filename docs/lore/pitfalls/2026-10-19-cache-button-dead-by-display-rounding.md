# 「本地缓存」永远显示 0 MB、清除按钮永远不可用
- 首次出现：2026-10-19（用户反馈「使用以来一直显示 0MB，清理按钮从没用过」）
- 影响范围：Android 主界面「本地缓存」行（`MainActivity`）；所有缓存残留 < 1 MB 的用户
- 复现条件：cacheDir 存在任意 1 B ~ 1 MB 的残留（如传输中断的分块）

## 表象

主界面缓存体积恒为「0 MB」；点「清除」只弹「缓存已是清洁状态」，即使目录里实际有残留。

## 根因

两个互相掩护的 bug：

1. **显示取整**：`refreshCacheSize` 用 `${bytes / (1024 * 1024)} MB` 整数除法，
   不足 1 MB 一律显示「0 MB」——残留被格式化吞掉；
2. **判定解析展示文案**：`confirmClearCache` 用 `tvCacheSize.text.removeSuffix(" MB").toLongOrNull()`
   反推字节数——显示的「0 MB」被当成真实的 0，按钮对任何 sub-MB 残留拒绝工作。

注意背景：「启动时自动清理」早已在 93960da 移除（改手动），本次 0 MB 的主因不是
「自动清理偷跑」，而是这两个 bug 让残留既看不见也清不掉（叠加「成功传输的中转文件
按协议即删」，见 decisions/2026-10-19-cache-is-transfer-residue-only.md）。

## 已采用的修复

`MainActivity.kt`：实测字节数存入 `lastCacheBytes` 字段作为清理判定的唯一依据；
展示改 `formatCacheSize()`（<1 KB 显示 0 KB、<1 MB 显示 KB、以上保留一位小数 MB）。

## 复发防护

- 规则入库：INV-005（docs/lore/invariants.md）——**任何「执行动作的判定」禁止使用
  UI 展示文案反推数据**，展示格式化必须是有损的、判定必须用原始值；
- 验证路径：向 cacheDir 手工放一个 500 KB 文件 → 主界面应显示「488 KB」，
  点「清除」应弹确认框而非「已是清洁状态」。
