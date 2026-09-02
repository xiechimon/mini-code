package dev.minicode.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 编辑文件工具，对应 pi 的 edit.ts
 * 精确文本替换，oldText 必须在文件中唯一匹配。
 */
public class EditTool implements ToolDefinition {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Workspace workspace;

    public EditTool(Path workdir) {
        this.workspace = new Workspace(workdir);
    }

    @Override
    public String name() {
        return "edit";
    }

    @Override
    public String description() {
        return "精确文本替换编辑文件，oldText 必须在文件中唯一且完全匹配。";
    }

    @Override
    public com.fasterxml.jackson.databind.JsonNode parameters() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = MAPPER.createObjectNode();
        for (String f : new String[]{"path", "oldText", "newText"}) {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("type", "string");
            if ("path".equals(f)) n.put("description", "要编辑的文件路径");
            if ("oldText".equals(f)) n.put("description", "要被替换的精确文本（必须唯一）");
            if ("newText".equals(f)) n.put("description", "替换后的文本");
            props.set(f, n);
        }
        schema.set("properties", props);
        var req = MAPPER.createArrayNode();
        req.add("path");
        req.add("oldText");
        req.add("newText");
        schema.set("required", req);
        return schema;
    }

    @Override
    public ToolResult execute(String callId, Map<String, Object> args) throws Exception {
        String pathStr = (String) args.get("path");
        String oldText = (String) args.get("oldText");
        String newText = (String) args.get("newText");
        if (pathStr == null || oldText == null || newText == null)
            return ToolResult.error("缺少必填参数 path/oldText/newText");
        Path file;
        try {
            file = workspace.resolve(pathStr);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        if (!Files.exists(file)) return ToolResult.error("文件不存在: " + file);
        String content = Files.readString(file);
        int first = content.indexOf(oldText);
        if (first < 0) return ToolResult.error("未找到 oldText");
        int last = content.lastIndexOf(oldText);
        if (first != last) return ToolResult.error("oldText 在文件中不唯一（找到多处匹配）");
        String updated = content.substring(0, first) + newText + content.substring(first + oldText.length());
        Files.writeString(file, updated);
        return ToolResult.ok("已编辑 " + file + "（替换 1 处）");
    }

    private Path resolve(String p) {
        return workspace.resolve(p);
    }
}
