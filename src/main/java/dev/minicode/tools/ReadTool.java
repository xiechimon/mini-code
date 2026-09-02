package dev.minicode.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 读文件工具，对应 pi 的 packages/agent/src/harness/tools/read.ts
 * 支持按行偏移与截断（2000 行 / 50KB）。
 */
public class ReadTool implements ToolDefinition {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Workspace workspace;

    public ReadTool(Path workdir) {
        this.workspace = new Workspace(workdir);
    }

    /**
     * 按行数与字节数截断（UTF-8 计量，避免平台编码差异与多字节截断乱码）—— 委托深模块 Truncate
     */
    static Truncation truncate(String s) {
        Truncate.Result res = Truncate.head(s, DEFAULT_MAX_LINES, DEFAULT_MAX_BYTES);
        Truncation r = new Truncation();
        r.truncated = res.truncated;
        r.text = res.text;
        r.originalBytes = res.originalBytes;
        r.truncatedBytes = res.truncatedBytes;
        return r;
    }

    // validUtf8Length 已收敛到 Truncate 深模块，保留静态转发以兼容测试
    static int validUtf8Length(byte[] bytes) {
        return Truncate.validUtf8Length(bytes);
    }

    @Override
    public String name() {
        return "read";
    }

    @Override
    public String description() {
        return "读取文件内容。文本文件默认截断为 2000 行或 50KB（先到为准），可用 offset/limit 分页读取大文件。";
    }

    @Override
    public com.fasterxml.jackson.databind.JsonNode parameters() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = MAPPER.createObjectNode();
        ObjectNode pathProp = MAPPER.createObjectNode();
        pathProp.put("type", "string");
        pathProp.put("description", "要读取的文件路径（相对或绝对）");
        props.set("path", pathProp);
        ObjectNode offsetProp = MAPPER.createObjectNode();
        offsetProp.put("type", "number");
        offsetProp.put("description", "起始行号（1 开始）");
        props.set("offset", offsetProp);
        ObjectNode limitProp = MAPPER.createObjectNode();
        limitProp.put("type", "number");
        limitProp.put("description", "最多读取行数");
        props.set("limit", limitProp);
        schema.set("properties", props);
        var req = MAPPER.createArrayNode();
        req.add("path");
        schema.set("required", req);
        return schema;
    }

    @Override
    public ToolResult execute(String callId, Map<String, Object> args) throws Exception {
        String pathStr = (String) args.get("path");
        if (pathStr == null) return ToolResult.error("缺少必填参数: path");
        Number offsetN = (Number) args.get("offset");
        Number limitN = (Number) args.get("limit");
        int offset = offsetN != null ? offsetN.intValue() : 1;
        Integer limit = limitN != null ? limitN.intValue() : null;

        Path file;
        try {
            file = workspace.resolve(pathStr);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        if (!Files.exists(file)) return ToolResult.error("文件不存在: " + file);
        if (Files.isDirectory(file)) return ToolResult.error("路径是目录: " + file);

        byte[] bytes = Files.readAllBytes(file);
        // MVP 阶段按文本处理，二进制后续可扩展 —— 显式 UTF-8，避免平台编码差异
        String text = new String(bytes, StandardCharsets.UTF_8);
        List<String> allLines = List.of(text.split("\n", -1));
        int totalLines = allLines.size();
        int start = Math.max(0, offset - 1);
        if (start >= totalLines)
            return ToolResult.error("offset " + offset + " 超出文件末尾（共 " + totalLines + " 行）");

        List<String> selected;
        if (limit != null) {
            int end = Math.min(start + limit, totalLines);
            selected = allLines.subList(start, end);
        } else {
            selected = allLines.subList(start, totalLines);
        }
        String selectedText = String.join("\n", selected);

        // 按 pi 的策略截断
        Truncation t = truncate(selectedText);
        String header = "读取 " + file + "（共 " + totalLines + " 行，展示 " + (start + 1) + "-" + (start + selected.size()) + " 行）";
        if (t.truncated) {
            header += " [已截断 " + t.originalBytes + " -> " + t.truncatedBytes + " 字节]";
            return ToolResult.ok(header + "\n" + t.text);
        } else {
            return ToolResult.ok(header + "\n" + selectedText);
        }
    }

    /**
     * 解析相对/绝对路径 —— 已收敛到 Workspace 深模块，保留以兼容内部调用
     */
    private Path resolve(String p) {
        return workspace.resolve(p);
    }

    static class Truncation {
        boolean truncated;
        String text;
        int originalBytes;
        int truncatedBytes;
    }
}
