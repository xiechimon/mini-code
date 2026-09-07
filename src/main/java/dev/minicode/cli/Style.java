package dev.minicode.cli;

import java.util.Map;

/**
 * 终端样式开关：决定是否输出 ANSI 颜色。
 * <p>
 * 探测规则（与 spec 一致）：
 * 1) NO_COLOR 非空 → 去色
 * 2) 非 tty → 去色
 * 3) TERM=dumb → 去色
 * 任一命中即去色，否则有色。
 * </p>
 * 纯数据对象，不读环境；探测由调用方注入 env 与 isTty。
 */
public record Style(boolean colorEnabled) {

    /** 有色样式 */
    public static final Style COLOR = new Style(true);
    /** 去色样式 */
    public static final Style PLAIN = new Style(false);

    /**
     * 样式探测。
     *
     * @param env   环境变量表（通常为 System.getenv() 的拷贝，避免直接读环境以保持纯函数可测）
     * @param isTty 是否为交互式 tty（System.console()!=null 或 Terminal.isTty）
     * @return 去色或有色样式
     */
    public static Style detect(Map<String, String> env, boolean isTty) {
        if (env == null) env = Map.of();
        // 1) NO_COLOR 非空即去色，参考 https://no-color.org
        String noColor = env.get("NO_COLOR");
        if (noColor != null && !noColor.isEmpty()) {
            return PLAIN;
        }
        // 2) 非 tty 去色（管道/重定向）
        if (!isTty) {
            return PLAIN;
        }
        // 3) TERM=dumb 去色（最小终端）
        String term = env.get("TERM");
        if ("dumb".equals(term)) {
            return PLAIN;
        }
        return COLOR;
    }

    /**
     * 是否启用颜色。
     */
    public boolean isColorEnabled() {
        return colorEnabled;
    }

    /** 快捷：有色 */
    public static Style colored() {
        return COLOR;
    }

    /** 快捷：去色 */
    public static Style plain() {
        return PLAIN;
    }
}
