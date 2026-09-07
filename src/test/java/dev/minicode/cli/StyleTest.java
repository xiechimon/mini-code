package dev.minicode.cli;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 样式探测单测：覆盖 NO_COLOR / 非 tty / TERM=dumb 三分支去色，默认 tty 有色。
 */
class StyleTest {

    @Test
    void detect_noColorNonEmptyDisablesColor() {
        Map<String, String> env = Map.of("NO_COLOR", "1");
        Style style = Style.detect(env, true);
        assertFalse(style.colorEnabled(), "NO_COLOR 非空应去色");
        assertFalse(Style.detect(Map.of("NO_COLOR", "true"), true).colorEnabled());
        assertFalse(Style.detect(Map.of("NO_COLOR", "0"), true).colorEnabled());
        assertFalse(Style.detect(Map.of("NO_COLOR", " "), true).colorEnabled());
    }

    @Test
    void detect_noColorEmptyKeepsColorWhenTty() {
        Map<String, String> env = Map.of("NO_COLOR", "");
        Style style = Style.detect(env, true);
        assertTrue(style.colorEnabled(), "NO_COLOR 空字符串不应去色（仅非空触发）");
    }

    @Test
    void detect_noColorAbsentKeepsColorWhenTtyAndTermNotDumb() {
        Map<String, String> env = Map.of();
        assertTrue(Style.detect(env, true).colorEnabled());
        assertTrue(Style.detect(Map.of("TERM", "xterm-256color"), true).colorEnabled());
    }

    @Test
    void detect_nonTtyDisablesColor() {
        Map<String, String> env = Map.of();
        assertFalse(Style.detect(env, false).colorEnabled(), "非 tty 应去色");
        // 即使 TERM 正常，非 tty 仍去色
        assertFalse(Style.detect(Map.of("TERM", "xterm-256color"), false).colorEnabled());
        // NO_COLOR 优先级也去色
        assertFalse(Style.detect(Map.of("NO_COLOR", "1"), false).colorEnabled());
    }

    @Test
    void detect_termDumbDisablesColor() {
        Map<String, String> env = Map.of("TERM", "dumb");
        assertFalse(Style.detect(env, true).colorEnabled(), "TERM=dumb 应去色");
        // TERM 大小写敏感：DUMB 不触发
        assertTrue(Style.detect(Map.of("TERM", "DUMB"), true).colorEnabled());
        assertTrue(Style.detect(Map.of("TERM", "Dumb"), true).colorEnabled());
    }

    @Test
    void detect_defaultTtyColored() {
        Map<String, String> env = new HashMap<>();
        // 空环境 + tty = 有色
        assertTrue(Style.detect(env, true).colorEnabled(), "默认 tty 应有色");
        // 带 TERM xterm 时仍有色
        env.put("TERM", "xterm");
        assertTrue(Style.detect(env, true).colorEnabled());
    }

    @Test
    void detect_priorityNoColorOverridesOthers() {
        // NO_COLOR 非空时，无论 tty/TERM 如何都去色
        assertFalse(Style.detect(Map.of("NO_COLOR", "1", "TERM", "xterm-256color"), true).colorEnabled());
        assertFalse(Style.detect(Map.of("NO_COLOR", "1"), false).colorEnabled());
        assertFalse(Style.detect(Map.of("NO_COLOR", "1", "TERM", "dumb"), true).colorEnabled());
    }

    @Test
    void styleRecordEquality() {
        assertEquals(Style.COLOR, Style.colored());
        assertEquals(Style.PLAIN, Style.plain());
        assertNotEquals(Style.COLOR, Style.PLAIN);
        assertTrue(new Style(true).colorEnabled());
        assertFalse(new Style(false).colorEnabled());
    }
}
