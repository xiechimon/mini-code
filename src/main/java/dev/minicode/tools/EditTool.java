package dev.minicode.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Mirrors pi edit.ts — exact text replacement, oldText must be unique.
 */
public class EditTool implements ToolDefinition {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path workdir;

    public EditTool(Path workdir) { this.workdir = workdir; }

    @Override public String name() { return "edit"; }
    @Override public String description() { return "Edit a single file using exact text replacement. oldText must match exactly and be unique in the file."; }
    @Override public com.fasterxml.jackson.databind.JsonNode parameters() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type","object");
        ObjectNode props = MAPPER.createObjectNode();
        for (String f : new String[]{"path","oldText","newText"}) {
            ObjectNode n = MAPPER.createObjectNode(); n.put("type","string");
            if ("path".equals(f)) n.put("description","Path to the file to edit");
            if ("oldText".equals(f)) n.put("description","Exact text to replace, must be unique");
            if ("newText".equals(f)) n.put("description","Replacement text");
            props.set(f, n);
        }
        schema.set("properties", props);
        var req = MAPPER.createArrayNode(); req.add("path"); req.add("oldText"); req.add("newText");
        schema.set("required", req);
        return schema;
    }

    @Override
    public ToolResult execute(String callId, Map<String, Object> args) throws Exception {
        String pathStr = (String) args.get("path");
        String oldText = (String) args.get("oldText");
        String newText = (String) args.get("newText");
        if (pathStr == null || oldText == null || newText == null) return ToolResult.error("missing required arguments path/oldText/newText");
        Path file = resolve(pathStr);
        if (!Files.exists(file)) return ToolResult.error("File not found: " + file);
        String content = Files.readString(file);
        int first = content.indexOf(oldText);
        if (first < 0) return ToolResult.error("oldText not found in file");
        int last = content.lastIndexOf(oldText);
        if (first != last) return ToolResult.error("oldText is not unique in file (found multiple occurrences)");
        String updated = content.substring(0, first) + newText + content.substring(first + oldText.length());
        Files.writeString(file, updated);
        return ToolResult.ok("Edited " + file + " (replaced 1 occurrence)");
    }

    private Path resolve(String p) {
        Path path = Path.of(p);
        if (path.isAbsolute()) return path.normalize();
        return workdir.resolve(p).normalize();
    }
}
