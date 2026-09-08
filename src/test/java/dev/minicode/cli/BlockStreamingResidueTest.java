package dev.minicode.cli;

import dev.minicode.agent.AgentEvent;
import dev.minicode.ai.Message;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 块级流式渲染的终端模拟器回归测试。
 * <p>
 * 背景：逐字直出 + 事后重绘的方案在真实终端上失败——滚出视口的内容进入回滚缓冲区不可擦除，
 * CUU 无法到达，导致回复出现两遍（裸 markdown 残留 + 渲染版）。块级策略从结构上消除该缺陷：
 * 渲染块完成即打印（一次成型），进度行单行可原位擦除。
 * 本测试用 1:1 忠实终端模拟器（光标 r/c、\r、CUU/EL/ED、按显示宽度折行、CJK 计 2、FE0F 计 0）
 * 回放 runReplTurn 的完整 emit 序列，断言终屏无裸 markdown 残留、无进度行残留、渲染正文恰一次。
 * </p>
 */
class BlockStreamingResidueTest {

    private static final int COLS = 106;

    /** 忠实终端模拟器。 */
    static class TermSim {
        final int cols;
        final List<StringBuilder> rows = new ArrayList<>();
        int r = 0, c = 0;

        TermSim(int cols) {
            this.cols = cols;
            rows.add(new StringBuilder());
        }

        void print(String s) {
            int i = 0;
            while (i < s.length()) {
                char ch = s.charAt(i);
                if (ch == 0x1B) {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '[') {
                        int j = i + 2;
                        StringBuilder num = new StringBuilder();
                        while (j < s.length() && Character.isDigit(s.charAt(j))) num.append(s.charAt(j++));
                        char cmd = s.charAt(j);
                        int n = num.length() == 0 ? 1 : Integer.parseInt(num.toString());
                        switch (cmd) {
                            case 'A' -> r = Math.max(0, r - n);
                            case 'K' -> truncateRow(r, c);
                            case 'J' -> {
                                truncateRow(r, c);
                                while (rows.size() > r + 1) rows.remove(rows.size() - 1);
                            }
                            default -> { }
                        }
                        i = j + 1;
                        continue;
                    }
                    i++;
                    continue;
                }
                if (ch == '\r') {
                    c = 0;
                    i++;
                    continue;
                }
                if (ch == '\n') {
                    r++;
                    c = 0;
                    ensure(r);
                    i++;
                    continue;
                }
                int cp = s.codePointAt(i);
                int w = cpWidth(cp);
                if (w > 0) {
                    if (c + w > cols) {
                        r++;
                        c = 0;
                    }
                    ensure(r);
                    StringBuilder row = rows.get(r);
                    while (row.length() < c) row.append(' ');
                    row.append(new String(Character.toChars(cp)));
                    for (int pad = 1; pad < w; pad++) row.append(' ');
                    c += w;
                    if (c >= cols) {
                        r++;
                        c = 0;
                    }
                }
                i += Character.charCount(cp);
            }
        }

        private void ensure(int idx) {
            while (rows.size() <= idx) rows.add(new StringBuilder());
        }

        private void truncateRow(int idx, int col) {
            ensure(idx);
            StringBuilder row = rows.get(idx);
            if (row.length() > col) row.setLength(col);
            while (row.length() < col) row.append(' ');
        }

        String screen() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < rows.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(rows.get(i));
            }
            return sb.toString();
        }
    }

    private static int cpWidth(int cp) {
        if (cp >= 0xFE00 && cp <= 0xFE0F) return 0;
        if ((cp >= 0x1100 && cp <= 0x115F) || (cp >= 0x2E80 && cp <= 0x9FFF)
                || (cp >= 0xAC00 && cp <= 0xD7A3) || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFF00 && cp <= 0xFF60) || (cp >= 0x1F300 && cp <= 0x1FAFF)) return 2;
        return 1;
    }

    /** 长文 CJK markdown fixture（模型真实回复形态：标题/粗体/分隔线/多段）。 */
    private static final String ARTICLE = """
            # Java 17 新特性详解

            **一、密封类（Sealed Classes）——精细化的继承控制**

            Java 17 正式将密封类（Sealed Classes）从预览特性转为正式特性，这是 Java 语言在面向对象设计方面的一次重大飞跃。在传统的 Java 中，一个类只要没有被声明为 final，任何类都可以继承它，这在大型项目中往往导致继承层次失控，给维护和推理代码行为带来了极大困难。密封类通过 sealed 关键字修饰类或接口，并配合 permits 子句明确列出允许继承的子类，从而实现了对继承树的精确控制。例如，你可以声明 sealed interface Shape permits Circle, Rectangle, Triangle，这样只有这三个类可以实现该接口。这一特性与模式匹配和 switch 表达式结合时威力尤为强大，编译器能够基于密封类的完整子类列表进行穷举检查，确保 switch 语句覆盖了所有可能的情况，从而在编译期就消除了遗漏分支的风险。

            ---

            **二、模式匹配增强——instanceof 模式匹配与 switch 模式匹配**

            Java 17 延续并巩固了模式匹配这一重要演进方向。instanceof 模式匹配早在 Java 16 中就已经正式发布，它允许开发者在进行类型检查的同时直接完成类型转换和变量绑定，彻底告别了过去繁琐的先检查再强转的两步写法。而在 Java 17 的后续预览中，模式匹配进一步扩展到了 switch 表达式和语句中，使得 switch 不仅可以匹配常量值，还可以匹配类型、解构复杂对象。结合密封类的穷举性，开发者可以构建出编译器级别的安全性保证——如果未来新增了一个子类，所有相关的 switch 语句都会在编译期报错提醒开发者处理新情况，这在大型系统的重构和演进中价值巨大。

            ---

            **三、ZGC 与 Shenandoah GC 的正式支持——低延迟垃圾回收**

            Java 17 将 ZGC 和 Shenandoah GC 两大低延迟垃圾收集器正式标记为生产就绪，这对于追求极致响应时间的应用来说是一个里程碑式的改进。ZGC 最初在 Java 11 中引入，经过数个版本的迭代优化，在 Java 17 中终于获得了官方的稳定承诺。ZGC 的设计目标是在 TB 级别的堆内存上实现亚毫秒级的最大暂停时间。它采用了着色指针、读屏障和多重映射内存等先进技术，实现了几乎完全并发的垃圾回收。Shenandoah GC 则是 Red Hat 主导开发的另一款低延迟收集器，同样致力于将 GC 暂停时间控制在极低的水平。

            ---

            **四、新版本发布节奏与 LTS 策略——三年周期的承诺**

            Java 17 作为一个长期支持版本，其意义远不止于技术特性本身。Oracle 从 Java 10 开始引入了每六个月发布一个新版本的快速迭代节奏，但并非每个版本都会获得长期支持。Java 11 是上一个 LTS 版本，而 Java 17 则是下一个 LTS 版本，承诺提供至少八年的公共更新支持。这种快速迭代加锚点的策略在保持语言活力的同时，也兼顾了企业对稳定性和可预测性的核心需求。

            ---

            **五、其他重要改进与整体评价——语言、API 与 JVM 的全面进化**

            除了上述重大特性外，Java 17 还包含了大量的语言和 API 改进。增强型 switch 表达式允许 switch 作为表达式使用，支持箭头语法，不再需要 break，并且可以返回值。文本块使得多行字符串的编写变得优雅自然。Record 类提供了一种简洁的方式来声明不可变的数据载体类。总体而言，Java 17 是一个承上启下的集大成版本。
            """;

    @Test
    void blockStreamingLeavesNoRawResidueAndNoDuplicate() {
        TermSim sim = new TermSim(COLS);
        Style style = Style.COLOR;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        BlockStreamer streamer = new BlockStreamer(style, COLS,
                new PrintStream(captured, true, StandardCharsets.UTF_8));

        // TurnStart（println 语义）
        sim.print(EventRenderer.render(new AgentEvent.TurnStart(1), style, COLS) + "\n");

        // 流式 deltas → BlockStreamer（进度行/渲染块）→ 模拟器
        for (String d : splitFragments(ARTICLE, 12)) {
            streamer.delta(d);
            sim.print(captured.toString(StandardCharsets.UTF_8));
            captured.reset();
        }

        // MessageEnd 终态冲刷 + AgentEnd 统计
        Message msg = Message.assistant(List.of(Message.Content.text(ARTICLE)), "end");
        streamer.flush(false);
        sim.print(captured.toString(StandardCharsets.UTF_8) + "\n");
        captured.reset();
        sim.print(EventRenderer.render(new AgentEvent.AgentEnd(List.of(msg)), style,
                TurnStats.of(Duration.ofSeconds(69), 1, 0), COLS) + "\n");

        String screen = sim.screen();
        // 单元格空格与 \r 不影响内容判断
        String flat = screen.replace("\r", "").replace(" ", "");

        // 终屏不应残留裸 markdown 标记
        assertFalse(flat.contains("**一、密封类"), "不应残留裸粗体标记");
        assertFalse(flat.contains("**二、"), "不应残留裸粗体标记");
        assertFalse(flat.contains("#Java17新特性详解"), "不应残留裸标题标记");
        // 进度行应全部被擦除
        assertFalse(flat.contains("▌已生成"), "进度行应被擦除，不应残留");
        // 渲染正文恰一次（无重复）
        long heading = Arrays.stream(flat.split("\n")).filter(l -> l.contains("Java17新特性详解")).count();
        assertEquals(1, heading, "渲染标题应恰好出现一次，实际 " + heading + " 次\n==== 终屏 ====\n" + screen);
        // 统计行恰一次
        long stats = Arrays.stream(flat.split("\n")).filter(l -> l.contains("轮·0次工具")).count();
        assertEquals(1, stats, "统计行应恰好出现一次");
    }

    @Test
    void interruptedStreamKeepsPartialAndMarker() {
        TermSim sim = new TermSim(COLS);
        Style style = Style.COLOR;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        BlockStreamer streamer = new BlockStreamer(style, COLS,
                new PrintStream(captured, true, StandardCharsets.UTF_8));

        sim.print(EventRenderer.render(new AgentEvent.TurnStart(1), style, COLS) + "\n");
        // 只流出一部分就中断（测试自行累积已投喂的 partial，不依赖生产 API）
        StringBuilder fed = new StringBuilder();
        for (String d : splitFragments(ARTICLE, 12)) {
            streamer.delta(d);
            fed.append(d);
            sim.print(captured.toString(StandardCharsets.UTF_8));
            captured.reset();
            if (d.contains("二、")) break;
        }
        Message partial = Message.assistant(List.of(Message.Content.text(fed.toString())), "aborted");
        streamer.flush(true);
        sim.print(captured.toString(StandardCharsets.UTF_8) + "\n");
        captured.reset();

        String screen = sim.screen();
        String flat = screen.replace("\r", "").replace(" ", "");
        assertTrue(flat.contains("⏹已中断"), "中断应保留 partial 并追加标记");
        assertFalse(flat.contains("**二、模式匹配"), "未完成块的裸标记不应以渲染前形态残留");
    }

    private static List<String> splitFragments(String text, int size) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            out.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return out;
    }

    /** 统计屏幕行内包含某子串的行数（CJK 空格剥离后）。 */
    private static long countLines(String flat, String needle) {
        return Arrays.stream(flat.split("\n")).filter(l -> l.contains(needle)).count();
    }

    @Test
    void tableSealsOnCloseNoDuplicate() {
        TermSim sim = new TermSim(COLS);
        Style style = Style.PLAIN;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        BlockStreamer streamer = new BlockStreamer(style, COLS,
                new PrintStream(captured, true, StandardCharsets.UTF_8));
        sim.print(EventRenderer.render(new AgentEvent.TurnStart(1), style, COLS) + "\n");

        String table = "| Name | Age |\n|------|-----|\n| Alice | 30 |\n| Bob | 25 |\n";
        for (String d : splitFragments(table, 5)) {
            streamer.delta(d);
            sim.print(captured.toString(StandardCharsets.UTF_8));
            captured.reset();
        }
        streamer.flush(false);
        sim.print(captured.toString(StandardCharsets.UTF_8) + "\n");
        captured.reset();

        String flat = sim.screen().replace("\r", "").replace(" ", "");
        // 结构性表格整体闭合才成表：表头/数据行各恰一次，不因开放期碎片累积而重复
        assertEquals(1, countLines(flat, "Name"), "表头应恰一次\n" + sim.screen());
        assertEquals(1, countLines(flat, "Alice"), "数据行应恰一次\n" + sim.screen());
        assertEquals(1, countLines(flat, "Bob"), "数据行应恰一次\n" + sim.screen());
    }

    @Test
    void indentedCodeSealsOnCloseNoDuplicate() {
        TermSim sim = new TermSim(COLS);
        Style style = Style.PLAIN;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        BlockStreamer streamer = new BlockStreamer(style, COLS,
                new PrintStream(captured, true, StandardCharsets.UTF_8));
        sim.print(EventRenderer.render(new AgentEvent.TurnStart(1), style, COLS) + "\n");

        String code = "    int a = 1;\n    int b = 2;\n";
        for (String d : splitFragments(code, 4)) {
            streamer.delta(d);
            sim.print(captured.toString(StandardCharsets.UTF_8));
            captured.reset();
        }
        streamer.flush(false);
        sim.print(captured.toString(StandardCharsets.UTF_8) + "\n");
        captured.reset();

        String flat = sim.screen().replace("\r", "").replace(" ", "");
        // 缩进代码作为结构性块：开放期不碎片重绘，闭合后整块成盒且无重复
        assertEquals(1, countLines(flat, "inta=1;"), "代码行应恰一次\n" + sim.screen());
        assertFalse(flat.contains("inta=1;inta=1;"), "不应重复\n" + sim.screen());
    }
}

