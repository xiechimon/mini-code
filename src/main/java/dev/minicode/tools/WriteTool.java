package dev.minicode.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 写文件工具，对应 pi 的 write.ts
 * 不存在则创建，存在则覆盖，自动创建父目录。
 */
public class WriteTool implements ToolDefinition {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Workspace workspace;

    public WriteTool(Path workdir) {
        this.workspace = new Workspace(workdir);
    }

    @Override
    public String name() {
        return "write";
    }

    @Override
    public String description() {
        return "写入内容到文件。文件不存在则创建，存在则覆盖，自动创建父目录。";
    }

    @Override
    public com.fasterxml.jackson.databind.JsonNode parameters() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = MAPPER.createObjectNode();
        ObjectNode p = MAPPER.createObjectNode();
        p.put("type", "string");
        p.put("description", "要写入的文件路径");
        props.set("path", p);
        ObjectNode c = MAPPER.createObjectNode();
        c.put("type", "string");
        c.put("description", "要写入的文件内容");
        props.set("content", c);
        schema.set("properties", props);
        var req = MAPPER.createArrayNode();
        req.add("path");
        req.add("content");
        schema.set("required", req);
        return schema;
    }

    @Override
    public ToolResult execute(String callId, Map<String, Object> args) throws Exception {
        String pathStr = (String) args.get("path");
        String content = (String) args.get("content");
        if (pathStr == null) return ToolResult.error("缺少 path");
        if (content == null) content = "";
        Path file;
        try {
            file = workspace.resolve(pathStr);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        Files.createDirectories(file.getParent() != null ? file.getParent() : workspace.root());
        Files.writeString(file, content);
        return ToolResult.ok("已写入 " + content.length() + " 字符到 " + file);
    }

    private Path resolve(String p) {
        return workspace.resolve(p);
    }
}
