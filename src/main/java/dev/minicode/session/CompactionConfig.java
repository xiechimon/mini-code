package dev.minicode.session;

/**
 * Context 压缩预算常量——统一单位为 token（对齐 pi）。默认窗口 200k 对齐 Claude Sonnet 现实值（pi 默认 200k 类窗口）。
 * <p>
 * 偏离 pi：未走 pi 的 {@code settings.json}，随常量子提供，env 覆盖。原因：仓库无 settings.json infra，
 * 见 {@code docs/adr/0004}。若未来加配置，仅需在 {@link #resolve()} 加一层加载即可，调用缝不变。
 * </p>
 */
public final class CompactionConfig {

    private CompactionConfig() {
    }

    /** 上下文窗口（token 数），默认 200_000。可被 env {@code MINICODE_CONTEXT_WINDOW_TOKENS} 覆盖。 */
    public static long defaultContextWindowTokens() {
        String v = System.getenv("MINICODE_CONTEXT_WINDOW_TOKENS");
        if (v == null || v.isBlank()) return 200_000L;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException ignore) {
            return 200_000L;
        }
    }

    /** 预留 token 预算（生成响应空间），默认 16384。可被 env {@code MINICODE_RESERVE_TOKENS} 覆盖。 */
    public static int defaultReserveTokens() {
        String v = System.getenv("MINICODE_RESERVE_TOKENS");
        if (v == null || v.isBlank()) return 16_384;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException ignore) {
            return 16_384;
        }
    }

    /** 保留段 token：压缩时仍原样保留的最末 token 数，默认 20000。可被 env {@code MINICODE_KEEP_RECENT_TOKENS} 覆盖。 */
    public static int defaultKeepRecentTokens() {
        String v = System.getenv("MINICODE_KEEP_RECENT_TOKENS");
        if (v == null || v.isBlank()) return 20_000;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException ignore) {
            return 20_000;
        }
    }

    /** 估算 token 数：启发式 chars/4（对齐 pi，图片按 4800 字符估算在本节保守不做）。 */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, (text.length() + 3) / 4);
    }

    /** 解析（带 env 覆盖）的运行时配置（单位：token）。 */
    public static Resolved resolve() {
        return new Resolved(defaultContextWindowTokens(), defaultReserveTokens(), defaultKeepRecentTokens());
    }

    /** 解析后的不可变配置（单位：token）。 */
    public record Resolved(long contextWindowTokens, int reserveTokens, int keepRecentTokens) {
    }
}
