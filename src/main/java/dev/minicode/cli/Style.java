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
 * <p>
 * 对齐 pi 源：pi-tui 渲染层样式探测与 provider-attribution 的 ANSI 角色色约定；
 * 本类为 ANSI 常量唯一定义处，供 {@link EventRenderer} 与 {@link Main} 复用，避免两处重复定义。
 * </p>
 */
public record Style(boolean colorEnabled) {

    // —— ANSI 常量唯一定义处（零依赖手写，供 EventRenderer/Main 复用） ——
    /** ANSI：重置 */
    public static final String ANSI_RESET = "\u001B[0m";
    /** ANSI：工具名青 */
    public static final String ANSI_CYAN = "\u001B[36m";
    /** ANSI：参数/辅助信息暗灰（bright black） */
    public static final String ANSI_GRAY = "\u001B[90m";
    /** ANSI：成功绿 */
    public static final String ANSI_GREEN = "\u001B[32m";
    /** ANSI：失败红 */
    public static final String ANSI_RED = "\u001B[31m";
    /** ANSI：粗体（用于横幅名称） */
    public static final String ANSI_BOLD = "\u001B[1m";
    /** ANSI：暗灰（用于横幅其余部分，与 ANSI_GRAY 同值语义一致） */
    public static final String ANSI_DIM = "\u001B[90m";
    // 便捷别名（对应任务描述 Style.CYAN/RESET 等）
    /** @see #ANSI_RESET */ public static final String RESET = ANSI_RESET;
    /** @see #ANSI_CYAN */ public static final String CYAN = ANSI_CYAN;
    /** @see #ANSI_GRAY */ public static final String GRAY = ANSI_GRAY;
    /** @see #ANSI_GREEN */ public static final String GREEN = ANSI_GREEN;
    /** @see #ANSI_RED */ public static final String RED = ANSI_RED;
    /** @see #ANSI_BOLD */ public static final String BOLD = ANSI_BOLD;
    /** @see #ANSI_DIM */ public static final String DIM = ANSI_DIM;

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
