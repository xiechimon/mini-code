package dev.minicode.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简 .env 加载器 — 从 user.dir 向上查找 .env，解析 KEY=VALUE。
 * 无外部依赖，对齐 dotenv 行为，供 LlmConfig 使用。
 * 优先级：System.getenv() 覆盖 .env（在 LlmConfig 中合并）。
 */
public final class Dotenv {

    private Dotenv() {}

    /**
     * 从 user.dir 向上查找并加载 .env，未找到返回空 Map
     */
    public static Map<String, String> load() {
        Path cwd = Path.of(System.getProperty("user.dir", "."));
        Path file = findEnvFile(cwd);
        if (file == null) return Collections.emptyMap();
        return load(file);
    }

    /**
     * 加载指定文件（包内可见，供测试用）
     */
    static Map<String, String> load(Path file) {
        try {
            if (!Files.exists(file) || !Files.isRegularFile(file)) return Collections.emptyMap();
            String content = Files.readString(file);
            return parse(content);
        } catch (IOException e) {
            return Collections.emptyMap();
        }
    }

    /**
     * 向上遍历目录查找 .env，未找到返回 null
     */
    static Path findEnvFile(Path start) {
        Path cur = start.toAbsolutePath().normalize();
        while (cur != null) {
            Path candidate = cur.resolve(".env");
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                return candidate;
            }
            Path parent = cur.getParent();
            if (parent == null || parent.equals(cur)) break;
            cur = parent;
        }
        return null;
    }

    /**
     * 解析 .env 文本为 Map，支持：
     * - 注释（#）与空行
     * - export 前缀
     * - 单/双引号包裹的值
     * - 未加引号值后的行内注释（" #..."）
     */
    public static Map<String, String> parse(String content) {
        Map<String, String> map = new LinkedHashMap<>();
        String[] lines = content.split("\\r?\\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            // 处理 export 前缀
            if (line.startsWith("export ")) {
                line = line.substring(7).trim();
                if (line.isEmpty()) continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();

            if (key.isEmpty()) continue;
            // 去掉引号包裹
            if (value.length() >= 2) {
                char first = value.charAt(0);
                char last = value.charAt(value.length() - 1);
                if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                    value = value.substring(1, value.length() - 1);
                    // 双引号需处理转义
                    if (first == '"') {
                        value = value.replace("\\n", "\n")
                                     .replace("\\r", "\r")
                                     .replace("\\t", "\t")
                                     .replace("\\\"", "\"")
                                     .replace("\\\\", "\\");
                    }
                } else {
                    // 未加引号：截掉行内注释 " #"
                    int commentIdx = value.indexOf(" #");
                    if (commentIdx >= 0) {
                        value = value.substring(0, commentIdx).trim();
                    }
                }
            }
            map.put(key, value);
        }
        return map;
    }
}
