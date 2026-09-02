package dev.minicode.tools;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 统一截断策略，深模块：ReadTool 保留头部、BashTool 保留尾部，
 * 阈值与字符边界处理收敛到一处，避免两处各自为政导致的阈值发散与多字节乱码。
 */
public final class Truncate {

    private Truncate() {
    }

    /**
     * 保留头部（Read 语义）：超 2000 行截行、超 50KB 截字节
     */
    public static Result head(String s, int maxLines, int maxBytes) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        int original = bytes.length;
        String[] lines = s.split("\n", -1);
        boolean byLines = lines.length > maxLines;
        boolean byBytes = bytes.length > maxBytes;
        if (!byLines && !byBytes) return new Result(false, s, original, original);

        String truncatedText;
        if (byLines) {
            truncatedText = String.join("\n", Arrays.copyOfRange(lines, 0, maxLines))
                    + "\n... [已截断至 " + maxLines + " 行]";
        } else {
            truncatedText = s;
        }
        byte[] tb = truncatedText.getBytes(StandardCharsets.UTF_8);
        if (tb.length > maxBytes) {
            byte[] cut = Arrays.copyOf(tb, maxBytes);
            int validLen = validUtf8Length(cut);
            truncatedText = new String(Arrays.copyOf(cut, validLen), StandardCharsets.UTF_8)
                    + "\n... [已截断至 50KB]";
            tb = truncatedText.getBytes(StandardCharsets.UTF_8);
        }
        return new Result(true, truncatedText, original, tb.length);
    }

    /**
     * 保留尾部（Bash 语义）：超长只保留最后 2000 行或 50KB
     */
    public static String tail(String s, int maxLines, int maxBytes) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        // 先按字节截
        if (b.length > maxBytes) {
            byte[] tail = Arrays.copyOfRange(b, Math.max(0, b.length - maxBytes), b.length);
            // 尾部截断同样避免切断多字节首字节，跳过开头的残缺字符
            int skip = skipBrokenUtf8Head(tail);
            String tailStr = new String(Arrays.copyOfRange(tail, skip, tail.length), StandardCharsets.UTF_8);
            return "... [已截断，保留最后 50KB]\n" + tailStr;
        }
        // 再按行截
        String[] lines = s.split("\n", -1);
        if (lines.length > maxLines) {
            String[] tail = Arrays.copyOfRange(lines, lines.length - maxLines, lines.length);
            return "... [已截断，保留最后 2000 行]\n" + String.join("\n", tail);
        }
        return s;
    }

    /**
     * 计算字节数组中最后一个完整 UTF-8 字符边界
     */
    static int validUtf8Length(byte[] bytes) {
        int len = bytes.length;
        if (len == 0) return 0;
        int i = len - 1;
        while (i >= 0 && (bytes[i] & 0xC0) == 0x80) i--;
        if (i < 0) return len;
        int start = bytes[i] & 0xFF;
        int expected;
        if ((start & 0x80) == 0) expected = 1;
        else if ((start & 0xE0) == 0xC0) expected = 2;
        else if ((start & 0xF0) == 0xE0) expected = 3;
        else if ((start & 0xF8) == 0xF0) expected = 4;
        else return len;
        int actual = len - i;
        return actual == expected ? len : i;
    }

    /**
     * 跳过尾部截断后开头的残缺多字节字符
     */
    static int skipBrokenUtf8Head(byte[] bytes) {
        if (bytes.length == 0) return 0;
        // 从开头找第一个合法起始字节；跳过开头的续字节
        int i = 0;
        while (i < bytes.length && (bytes[i] & 0xC0) == 0x80) i++;
        // 再检查第一个字符是否完整：若长度不足 expected，则也跳过
        if (i < bytes.length) {
            int start = bytes[i] & 0xFF;
            int expected;
            if ((start & 0x80) == 0) expected = 1;
            else if ((start & 0xE0) == 0xC0) expected = 2;
            else if ((start & 0xF0) == 0xE0) expected = 3;
            else if ((start & 0xF8) == 0xF0) expected = 4;
            else expected = 1;
            if (bytes.length - i < expected) return bytes.length; // 残缺，全部跳过
        }
        return i;
    }

    public static class Result {
        public final boolean truncated;
        public final String text;
        public final int originalBytes;
        public final int truncatedBytes;

        Result(boolean truncated, String text, int originalBytes, int truncatedBytes) {
            this.truncated = truncated;
            this.text = text;
            this.originalBytes = originalBytes;
            this.truncatedBytes = truncatedBytes;
        }
    }
}
