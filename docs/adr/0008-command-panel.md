---
status: accepted
---

# 命令面板：Status 状态栏锚定 + widget 键接管（c1 路线）

## Background

MVP4 的命令自动提示用 JLine 内建 `SuggestionType.COMPLETER`：输 `/` 出列表，但列表只是渲染贴片、没有按键循环——方向键会关列表并误触历史搜索、Esc 无响应、Enter 提交原始行。用户要的是可控面板：呼出/导航/取消/选中闭环。JLine 3.27.1 源码调查结论：建议列表无按键循环（`LineReaderImpl.java:4511-4514` 直接返回、`5076-5078` 跳过循环）、Esc 在 emacs 键表只是前缀（`BindingReader` 无超时离散化）、doList 循环键行为硬编码三例（`5082-5113`），**配置内无解**；3.27.1 另有 `clearChoices` 不清 post 的残留 bug（3.30.9 已修，故先升级）。规格：`.scratch/command-panel/spec.md`。

## Decision

- **两层**：`PanelModel` 纯状态机（零 JLine，唯一测试缝）+ `SlashCommandPanel` widget 壳（键接管 + Status 渲染的薄翻译层）。
- **键接管**：TailTipWidgets 同款别名机制（`addWidget` 注册 `_panel-*` 变体 + `aliasWidget` 重指内建名 + 壳内 `.` 前缀回调真内建），接管 self-insert/delete/backspace/accept-line/↑/↓/Esc；disable 全量还原。
- **渲染**：`Status` 状态栏（屏幕底部锚定），高亮反白；不支持的终端 Status 为 null/无操作，面板自动不出现（降级 = 现状）。
- **Esc 离散化**：裸 ESC 绑进 MAIN 键表 + `AMBIGUOUS_BINDING` 1000ms→100ms；转义序列整帧到达，方向键延迟不可感知，仅裸 Esc 关面板等 100ms。
- **Enter 分流**：注册表 `Entry.takesArg`——无参命令选中即执行（填入 + acceptLine），带参命令填入 + 空格等参数。
- **Tab 接管**：面板打开时 Tab = 上屏（选中项填入输入行，不执行；pi 语义：确认即填充），面板关闭；关闭时透传内建 Tab 补全。不接 Tab 会与内建补全列表双显（用户实测）。导航仅 ↑↓。
- **固定高度面板区**：状态栏恒定 候选数+1 行（末行恒为底栏提示），开闭只换内容不换行数。根因：JLine `Status.update` 的滚动区只在行数变化时重算，且「增长上推内容、收缩不回滚」——开闭一次净上推一段（用户实测漂移的源码级根因）。代价：底部常驻 N 行面板区（关闭态显示「输 / 打开命令面板」提示，兼作发现入口）。
- **面板取代内建建议列表**：打开期间 `setAutosuggestion(NONE)`，关闭恢复；Tab 补全独立不受影响。
- **前置**：JLine 3.27.1 → 3.30.9（clearChoices 修复；dumb 反射补丁复核后保留）。

为何选此：c1 全公开 API、无版本漂移面；PanelModel 把行为面收敛成纯函数，壳的接线风险（别名还原）用 ExternalTerminal 冒烟锁定。

## Considered Options

- **配置内方案（方向键导航/Esc 取消）**：源码调查证实不存在——建议列表无循环可配、Esc 无绑定可救、无内建 widget 清 post。否。
- **c2：LineReaderImpl 子类接管 post 区（pi 同款光标下浮层）**：位置更对齐 pi，但碰 protected API（`post` 字段），JLine 升级有漂移面（3.27.1→3.30.9 已实测改过 `clearChoices`），工作量 2-5 天 vs c1 的 1-3 天。多花约 2 天只买位置差异，否。
- **TailTipWidgets 直接复用**：它是 tailtip/ghost-text 语义（参数提示条），不是可选择列表，给不了「方向键选择」。否。

## 有意偏离（对齐 pi X，但有意简化为 Y）

- pi 补全下拉是光标下浮层 → mini-code 锚定终端状态栏（底部）。理由：c2 的 protected API 漂移风险不值位置差异。
- pi 补全确认 = 填入（执行绑 Enter 提交）→ mini-code 无参命令选中即执行（少一次回车），带参命令维持填入语义。
- pi 的下拉由编辑器组件驱动 → mini-code 用 widget 别名接管 MAIN 键表，Esc 依赖 100ms 消歧延迟（方向键为转义序列所固有的歧义）。

## Consequences

- 新命令注册即自动出现在面板（候选与注册表同源，含 /exit /quit）；`/help`、Tab 补全、面板三处共享一份清单。
- 方向键在面板关闭时行为不变（别名透传内建）；面板打开时 ↑↓ 被面板消费（原「历史搜索」在面板语境无意义）。
- 裸 Esc 多了 100ms 消歧延迟——仅影响「无面板时按 Esc」这一原本无操作的路径，无可感知回归。
- JLine 升级面被 304 全量测试锁定；后续 JLine 升级只需回归该套件。
- 后续加项：参数补全（/model 的模型候选）、面板样式主题化、c2 浮层（若状态栏位置被证明不可接受）。
