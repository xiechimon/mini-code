package dev.minicode.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Mirrors pi write.ts
 */
public class WriteTool implements ToolDefinition {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path workdir;

    public WriteTool(Path workdir) { this.workdir = workdir; }

    @Override public String name() { return "write"; }
    @Override public String description() { return "Write content to a file. Creates the file if it doesn't exist, overwrites if it does. Automatically creates parent directories."; }
    @Override public com.fasterxml.jackson.databind.JsonNode parameters() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = MAPPER.createObjectNode();
        ObjectNode p = MAPPER.createObjectNode(); p.put("type","string"); p.put("description","Path to the file to write");
        props.set("path", p);
        ObjectNode c = MAPPER.createObjectNode(); c.put("type","string"); c.put("description","Content to write to the file");
        props.set("content", c);
        schema.set("properties", props);
        var req = MAPPER.createArrayNode(); req.add("path"); req.add("content"); schema.set("required", req);
        return schema;
    }

    @Override
    public ToolResult execute(String callId, Map<String, Object> args) throws Exception {
        String pathStr = (String) args.get("path");
        String content = (String) args.get("content");
        if (pathStr == null) return ToolResult.error("missing path");
        if (content == null) content = "";
        Path file = resolve(pathStr);
        Files.createDirectories(file.getParent() != null ? file.getParent() : workdir);
        Files.writeString(file, content);
        return ToolResult.ok("Wrote " + content.length() + " chars to " + file);
    }

    private Path resolve(String p) {
        Path path = Path.of(p);
        if (path.isAbsolute()) return path.normalize();
        return workdir.resolve(p).normalize();
    }
}
