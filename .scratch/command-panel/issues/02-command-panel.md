# 02: 命令面板 — 输 / 呼出、方向键选择、Esc 取消

**What to build:** REPL 输入行首敲 `/` 立即呼出屏幕底部的命令面板:候选 = 命令注册表 + /exit /quit,每项显示命令名与一行说明;↑↓ 移动高亮,继续输入实时过滤,删光 `/` 自动关闭;Enter 选中无参命令(/help /session /compact /new /exit /quit)立即执行、带参命令(/model /export)填入行尾加空格等待参数;Esc 只关面板、输入行内容保留;无匹配时面板自动消失。dumb 终端不出现面板、其余行为不变。

**Blocked by:** 01(JLine 升级 3.30.9 + 注册表 takesArg 标记)—— 面板依赖 3.30.9 的 clearChoices 修复与 takesArg 分流标记

**Status:** resolved

- [ ] `PanelModel` 纯状态机(零 JLine 依赖):open(首 token 以 / 开头)/filter/move(环绕)/select(按 takesArg 分流)/dismiss(Esc、删光 /、无匹配);候选与命令注册表同源
- [ ] `SlashCommandPanel` widget 壳:继承 Widgets,启用时按 TailTipWidgets 先例别名接管 MAIN 键表(self-insert/backward-delete-char/accept-line/方向键/Esc),渲染走 Status(底部状态栏,高亮反白);面板打开期间 setSuggestionType(NONE) 抑制内建建议列表,关闭恢复 COMPLETER;disable 时键表全部还原
- [ ] 无参命令选中 = 填入行 + acceptLine(直接执行);带参命令选中 = 填入 + 空格 + 面板关闭
- [ ] Esc 离散化:裸 ESC 绑进 MAIN 键表,AMBIGUOUS_BINDING 调至约 100ms,代码注释注明理由
- [ ] 接线进 REPL:createReader/循环处启用面板;管道与 one-shot 路径不启用
- [ ] 括号粘贴兼容:粘贴含 / 的多行文本不触发面板异常(begin-paste 独立绑定不受影响)
- [ ] 测试:PanelModelTest 覆盖全部分支(呼出条件/过滤/环绕/分流/dismiss 三分支/候选同源);widget 壳 ExternalTerminal 冒烟(enable/disable 往返键表还原、dumb 无面板);既有 MainReplTest/SlashDispatcherTest/SlashCommandsLifecycleTest 零改动全绿
- [ ] `mvn test` 全绿;`mvn package` 后人工验证:输 `/` 出面板、方向键、Esc、Enter 分流、删除行为
- [ ] 收尾:`docs/adr/0008-command-panel.md`(状态栏锚定 vs pi 光标浮层、无参即执行 vs pi 填入、Esc 消歧延迟、内建列表不可配置结论附源码证据);README「已完成」补命令面板行
