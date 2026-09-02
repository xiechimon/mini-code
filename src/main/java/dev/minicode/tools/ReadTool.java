package dev.minicode.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Mirrors pi packages/agent/src/harness/tools/read.ts
 */
public class ReadTool implements ToolDefinition {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path workdir;

    public ReadTool(Path workdir) {
        this.workdir = workdir;
    }

    @Override public String name() { return "read"; }
    @Override public String description() {
        return "Read the contents of a file. For text files, output is truncated to 2000 lines or 50KB (whichever is hit first). Use offset/limit for large files.";
    }
    @Override public com.fasterxml.jackson.databind.JsonNode parameters() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = MAPPER.createObjectNode();
        ObjectNode pathProp = MAPPER.createObjectNode();
        pathProp.put("type", "string"); pathProp.put("description", "Path to the file to read (relative or absolute)");
        props.set("path", pathProp);
        ObjectNode offsetProp = MAPPER.createObjectNode();
        offsetProp.put("type", "number"); offsetProp.put("description", "Line number to start reading from (1-indexed)");
        props.set("offset", offsetProp);
        ObjectNode limitProp = MAPPER.createObjectNode();
        limitProp.put("type", "number"); limitProp.put("description", "Maximum number of lines to read");
        props.set("limit", limitProp);
        schema.set("properties", props);
        var req = MAPPER.createArrayNode(); req.add("path"); schema.set("required", req);
        return schema;
    }

    @Override
    public ToolResult execute(String callId, Map<String, Object> args) throws Exception {
        String pathStr = (String) args.get("path");
        if (pathStr == null) return ToolResult.error("missing required argument: path");
        Number offsetN = (Number) args.get("offset");
        Number limitN = (Number) args.get("limit");
        int offset = offsetN != null ? offsetN.intValue() : 1;
        Integer limit = limitN != null ? limitN.intValue() : null;

        Path file = resolve(pathStr);
        if (!Files.exists(file)) return ToolResult.error("File not found: " + file);
        if (Files.isDirectory(file)) return ToolResult.error("Path is a directory: " + file);

        byte[] bytes = Files.readAllBytes(file);
        // try detect binary? for MVP treat as text
        String text = new String(bytes);
        List<String> allLines = List.of(text.split("\n", -1));
        int totalLines = allLines.size();
        int start = Math.max(0, offset - 1);
        if (start >= totalLines) return ToolResult.error("Offset " + offset + " is beyond end of file (" + totalLines + " lines total)");

        List<String> selected;
        if (limit != null) {
            int end = Math.min(start + limit, totalLines);
            selected = allLines.subList(start, end);
        } else {
            selected = allLines.subList(start, totalLines);
        }
        String selectedText = String.join("\n", selected);

        // truncate like pi
        Truncation t = truncate(selectedText);
        String header = "Read " + file + " (" + totalLines + " lines total, showing " + (start+1) + "-" + (start + selected.size()) + ")";
        if (t.truncated) {
            header += " [truncated " + t.originalBytes + " -> " + t.truncatedBytes + " bytes]";
            return ToolResult.ok(header + "\n" + t.text);
        } else {
            return ToolResult.ok(header + "\n" + selectedText);
        }
    }

    private Path resolve(String p) {
        Path path = Path.of(p);
        if (path.isAbsolute()) return path.normalize();
        return workdir.resolve(p).normalize();
    }

    static class Truncation { boolean truncated; String text; int originalBytes; int truncatedBytes; }

    static Truncation truncate(String s) {
        Truncation r = new Truncation();
        byte[] bytes = s.getBytes();
        r.originalBytes = bytes.length;
        String[] lines = s.split("\n", -1);
        boolean byLines = lines.length > DEFAULT_MAX_LINES;
        boolean byBytes = bytes.length > DEFAULT_MAX_BYTES;
        if (!byLines && !byBytes) { r.truncated = false; r.text = s; r.truncatedBytes = bytes.length; return r; }
        r.truncated = true;
        // truncate to limits
        String truncatedText;
        if (byLines) {
            truncatedText = String.join("\n", java.util.Arrays.copyOfRange(lines, 0, DEFAULT_MAX_LINES));
            truncatedText += "\n... [truncated to " + DEFAULT_MAX_LINES + " lines]";
        } else {
            truncatedText = s;
        }
        byte[] tb = truncatedText.getBytes();
        if (tb.length > DEFAULT_MAX_BYTES) {
            truncatedText = new String(java.util.Arrays.copyOf(tb, DEFAULT_MAX_BYTES)) + "\n... [truncated to 50KB]";
            tb = truncatedText.getBytes();
        }
        r.text = truncatedText;
        r.truncatedBytes = tb.length;
        return r;
    }
}
