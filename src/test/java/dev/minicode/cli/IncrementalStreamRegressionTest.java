package dev.minicode.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：流式增量应在到达时立即可见（「行级流式」取代「块级计数器」的钉）。
 * <p>
 * 演进历史：块级流式把增量压进块缓冲、只显示单行进度指示「▌ 已生成 N 字」，块完成才整块倾销，
 * 用户看不到回复在流动（"治标不治本"）。行级流式承诺正文随增量可见增长。本测试在子块完成前
 * 切一刀，断言增量文本已上屏且不退化回计数器。先前在块级实现上为红，行级实现落地后转绿。
 * </p>
 */
class IncrementalStreamRegressionTest {

    private ByteArrayOutputStream captured;
    private PrintStream out;

    private BlockStreamer newStreamer(Style style, int width) {
        captured = new ByteArrayOutputStream();
        out = new PrintStream(captured, true, StandardCharsets.UTF_8);
        return new BlockStreamer(style, width, out);
    }

    private String out() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void streamedTextShouldBeVisibleBeforeBlockCompletes() {
        BlockStreamer bs = newStreamer(Style.PLAIN, 80);
        // 一个未完成的段落增量（既非空行分界，也无围栏闭合 → 块未完成）
        bs.delta("你好，这是流式正文");
        String o = out();
        // 用户症状：流式期间应能「看到」正文在流动，而不是只看到一个计数器
        assertTrue(o.contains("你好，这是流式正文"),
                "流式增量应直接上屏，不应被块缓冲藏起来\n实际输出=>>>[" + o + "]<<<");
    }

    @Test
    void subsequentIncrementsShouldAppendVisibly() {
        BlockStreamer bs = newStreamer(Style.PLAIN, 80);
        bs.delta("第一段正在流动");
        captured.reset();
        bs.delta("，继续追加");
        String o = out();
        assertTrue(o.contains("第一段正在流动，继续追加"),
                "后续增量应追加到已上屏文本，而不是仅更新计数\n实际输出=>>>[" + o + "]<<<");
    }
}
