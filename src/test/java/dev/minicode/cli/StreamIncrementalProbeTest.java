package dev.minicode.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 诊断探针（throwaway）：把「流式增量应当直接上屏」固化为断言，用于证明当前
 * 块级流式渲染在流式期间只出计数器、不出正文——即用户所述「治标不治本，想要流式效果」。
 * <p>
 * 流式增量（StreamDelta）语义：模型逐片段返回文本，理应在到达时立即可见。
 * 当前 BlockStreamer 把增量压进块缓冲、单行进度指示「▌ 已生成 N 字」原位刷新，
 * 只有等块完成（空行/围栏闭合/终态 flush）才一次性打印渲染正文。
 * 本探针在子块完成前切一刀，断言增量文本已上屏——真实实现应通过，当前实现应失败。
 * </p>
 */
class StreamIncrementalProbeTest {

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
