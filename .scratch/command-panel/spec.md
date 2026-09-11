# Spec: 命令面板 — 输 / 呼出、方向键选择、Esc 取消

Status: ready-for-agent

## Problem Statement

REPL 里输入 `/` 虽然会浮现命令列表（命令自动提示，MVP4 已做），但它只是 JLine 内建建议列表的渲染贴片——**没有任何按键循环**：方向键会直接关掉列表（按键被回吐给主循环触发历史搜索）、Enter 直接提交原始行、Esc 无任何响应（emacs 键位表里裸 ESC 只是转义序列前缀）。用户想要的是一个可控的面板：呼出后能用方向键选、Esc 取消、选中即走，而不是「碰一下就消失还乱动历史」的列表。

## Solution

自绘状态栏命令面板（术语表：命令面板），对齐 pi 的下拉补全面板语义，但锚定在终端状态栏（JLine 内抛光路线的 c1 方案，可行性已用 JLine 3.27.1 源码验证）。行为：

- 输入 `/` 呼出面板（首 token 以 `/` 开头时），候选 = 命令注册表 + `/exit` `/quit`，每项显示命令名 + 一行说明
- ↑↓ 移动高亮；继续输入实时过滤；删光 `/` 自动关闭
- Enter 选中：无参命令（/help /session /compact /new /exit /quit）立即执行；带参命令（/model /export）填入行尾加空格、面板关闭、等待参数
- Esc：只关面板，输入行内容保留
- 无匹配时面板自动消失

## User Stories

1. As a REPL 用户, I want 输入 `/` 立即看到命令面板, so that 不用记忆也能发现全部命令
2. As a REPL 用户, I want ↑↓ 在面板里移动高亮, so that 可以浏览并选择命令而不是背名字
3. As a REPL 用户, I want 每个候选显示命令名加一行说明, so that 不熟的命令也知道干什么
4. As a REPL 用户, I want 继续输入时面板实时过滤, so that 快速缩小到目标命令
5. As a REPL 用户, I want 把 `/` 删掉后面板自动关闭, so that 反悔时界面干净
6. As a REPL 用户, I want 面板里 Enter 选中无参命令立即执行, so that 少敲一次回车
7. As a REPL 用户, I want 带参命令选中后填入并等待我输参数, so that 不会带空参数误执行
8. As a REPL 用户, I want Esc 只关面板不动我输入行, so that 已敲的内容不丢
9. As a REPL 用户, I want 输入没有匹配的命令后面板自动消失, so that 屏幕不留死界面
10. As a REPL 用户, I want 面板出现时不再叠着旧的建议列表, so that 不双显不闪烁
11. As a REPL 用户, I want 面板只在等我输入时出现、回合执行期间不出现, so that 不干扰流式渲染
12. As a dumb 终端用户, I want 面板不出现且其余行为不变, so that 降级环境可用
13. As a REPL 用户, I want 粘贴含 `/` 的多行文本不触发面板异常, so that 括号粘贴行为不退化
14. As a REPL 用户, I want 普通文本输入（非 `/` 开头）不出现面板, so that 面板不喧宾夺主
15. As a REPL 用户, I want 装了面板后方向键延迟不可感知, so that 编辑体验不退化
16. As a REPL 用户, I want `/exit` `/quit` 也出现在面板候选里, so that 退出方式可发现

## Implementation Decisions

- **新模块：命令面板 = 纯状态机 + widget 壳两层**。
  - `PanelModel`（纯状态机，零 JLine 依赖）：状态 = 候选列表 + 过滤词 + 高亮索引 + 开关；转移 = open/filter/move（环绕）/select/dismiss（Esc/删光/无匹配）；select 返回分流结果（直接执行命令名 / 填入命令名）。这是本特性的测试缝，与术语表「纯函数渲染缝」同款取舍。
  - `SlashCommandPanel`（widget 壳）：继承 JLine `Widgets`，启用时按 TailTipWidgets 先例别名接管 MAIN 键表（self-insert / backward-delete-char / accept-line / 方向键 / Esc），渲染走 `Status.update`（状态栏面板，屏幕底部锚定）。面板打开期间 `setSuggestionType(NONE)` 抑制内建建议列表，关闭恢复 COMPLETER。壳只做「键事件 ↔ PanelModel 转移 ↔ Status 渲染」的翻译，不含业务判断。
- **Esc 离散化**：裸 ESC 绑进 MAIN 键表（emacs 里 ESC 只是前缀）；`AMBIGUOUS_BINDING` 消歧超时从默认 1000ms 调至约 100ms——方向键转义序列整帧到达，延迟感知低风险。此取舍记入 ADR-0008。
- **Enter 分流**：注册表 `Entry` 增加 `takesArg` 标记（/model /export 为 true）；无参命令选中 = 填入行 + acceptLine（执行）；带参命令选中 = 填入行尾加空格，面板关闭。
- **/exit /quit 入面板候选**：takesArg=false，选中执行 = 走既有 isExitCommand 退出路径（命令表面板与注册表同源，不出现第三份清单）。
- **JLine 升级 3.27.1 → 3.30.9**：修 `clearChoices` 不清理 post 的残留 bug（3.27.1 实测存在，3.30.9 已修）；升级作为独立小步先落地，全量测试回归绿后面板再进场。
- **降级**：dumb 终端 Status no-op，面板自动不出现；内建建议列表在 dumb 下本就不生效——降级行为 = 现状，零特判代码。
- **面板生命周期边界**：只在 readLine 等待输入期间存在；回合执行（流式渲染）期间 readLine 不活跃，面板不可能出现，与 BlockStreamer 渲染层无交叠。
- **括号粘贴兼容**：别名接管走 TailTipWidgets 同款别名机制，begin-paste 是独立绑定不受影响；自定义逻辑不直接消费原始字节流。

## Testing Decisions

- **唯一新缝：PanelModel 纯状态机**，不碰终端不读环境，单测直接断言转移结果（延续「纯函数渲染缝」先例：SlashDispatcher 纯分发表、渲染器纯函数）。
  - `PanelModelTest`：呼出条件（`/`、`/x`、非首 token 不呼出）、过滤收窄、move 环绕、select 无参/带参分流、dismiss 三分支（Esc/删光/无匹配）、候选与注册表同源（含 /exit /quit）
- **widget 壳接线冒烟**：ExternalTerminal 上 enable/disable 往返后键表别名还原（先例：MainReplTest 的 ExternalTerminal 用法）；dumb 终端下零面板（Status no-op 锁定）。
- **零回归**：MainReplTest、SlashDispatcherTest、SlashCommandsLifecycleTest 既有用例不动；JLine 升级后 `mvn test` 全量绿（升级与面板分两次提交）。
- **Esc 延迟**：不自动化测量；实现注释标注 AMBIGUOUS_BINDING 取值与理由，人工验证手感。

## Out of Scope

- 面板视觉美化（边框/主题色；首期 = Status 默认样式 + 高亮反白）
- c2 路线：输入行下浮层（LineReaderImpl 子类碰 protected API，JLine 升级漂移风险）——已否决，理由记 ADR-0008
- 命令参数补全（如 `/model <id>` 的模型候选列表）
- 鼠标交互、多列布局
- 管道/one-shot 模式（无交互层，维持透传）

## Further Notes

- 偏离记录进 `docs/adr/0008-command-panel.md`：面板锚定状态栏（pi 是光标下浮层，c2 因 protected API 漂移风险否决）；无参命令选中即执行（pi 是确认即填充、Enter 才提交）；Esc 消歧延迟取舍；JLine 内建建议列表不可配置的结论（附源码行号证据）。
- 可行性依据：JLine 3.27.1 源码调查（建议列表无按键循环、Esc 无前缀绑定、Status/Widgets API 先例），证据链见调查记录。
- 与 ADR-0007（MVP5 起语义标的切换 Claude Code）无冲突：命令面板是交互层能力，pi/Claude Code 均有此体验，对标语义不受影响。
