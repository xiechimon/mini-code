package dev.minicode.session;

/**
 * Context 压缩预算常量——上下文窗口 / 预留 / 保留段三元组。对齐 pi 默认值（reserve=16384、keepRecent=20000）。
 * <p>
 * 偏离 pi：未走 pi 的 {@code settings.json}，随常量子提供，env 覆盖。原因：仓库无 settings.json infra，
 * 见 {@code docs/adr/0004}。若未来要加配置文件，仅需在 {@link #resolve()} 中加一层加载即可，调用缝不变。
 * </p>
 */
public final class CompactionConfig {

    private CompactionConfig() {
    }

    /** 上下文窗口（按字符 4 = 1 token 估算的字符数），默认 128k chars。可被 env {@code MINICODE_CONTEXT_WINDOW_CHARS} 覆盖。 */
    public static long defaultContextWindowChars() {
        String v = System.getenv("MINICODE_CONTEXT_WINDOW_CHARS");
        if (v == null || v.isBlank()) return 128_000L;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException ignore) {
            return 128_000L;
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

    /** 保留段 token，压缩时仍原样保留的最末 token 数，默认 20000。可被 env {@code MINICODE_KEEP_RECENT_TOKENS} 覆盖。 */
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

    /** 触发判定：history 当前 token > window − reserve。 */
    public static boolean exceedsThreshold(int historyTokens) {
        int reserve = defaultReserveTokens();
        long window = defaultContextWindowChars(); // chars 作为粗略窗口值（与启发式对齐）
        return historyTokens > Math.max(0, window - reserve);
    }

    /** 解析（带 env 覆盖）的运行时配置。 */
    public static Resolved resolve() {
        return new Resolved(defaultContextWindowChars(), defaultReserveTokens(), defaultKeepRecentTokens());
    }

    /** 解析后的不可变配置。 */
    public record Resolved(long contextWindowChars, int reserveTokens, int keepRecentTokens) {
    }
}
