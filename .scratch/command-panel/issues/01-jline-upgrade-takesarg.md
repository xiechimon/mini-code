# 01: JLine 升级 3.30.9 + 注册表 takesArg 标记

**What to build:** 为命令面板铺路的两项 prefactor,用户侧无可见行为变化:REPL 的补全/历史/括号粘贴/命令自动提示行为与升级前完全一致(回归即验收);命令注册表的每条命令新增「是否需要参数」声明(为面板的 Enter 分流服务),`/help` 与面板候选展示不受影响。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [ ] JLine 3.27.1 → 3.30.9(修 3.27.1 `clearChoices` 不清理 post 导致建议列表残留的 bug);`mvn test` 全量绿——补全/历史/多行/括号粘贴路径零回归
- [ ] dumb 终端 bracketed-paste 反射补丁在 3.30.9 下复核:若 JLine 新版已原生绑定则删补丁,未删则注释注明仍需要
- [ ] 命令注册表 Entry 增加 `takesArg` 布尔声明:/model、/export 为 true,其余(含 /exit /quit)为 false;`/help` 输出格式不变
- [ ] 注册表测试补 takesArg 断言(并入既有 SlashDispatcherTest 风格)
