package dev.minicode.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 执行 Bash 命令的工具，对应 pi 的 harness/tools/bash.ts
 * 在工作目录下执行，返回 stdout/stderr，超长按 2000 行/50KB 截断。
 */
public class BashTool implements ToolDefinition {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path workdir;
    private final long timeoutMs; // 默认超时

    public BashTool(Path workdir) { this(workdir, 30_000); }
    public BashTool(Path workdir, long timeoutMs) { this.workdir = workdir; this.timeoutMs = timeoutMs; }

    @Override public String name() { return "bash"; }
    @Override public String description() { return "在工作目录执行 bash 命令，返回 stdout 与 stderr，超长输出截断为最后 2000 行或 50KB。"; }
    @Override public com.fasterxml.jackson.databind.JsonNode parameters() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type","object");
        ObjectNode props = MAPPER.createObjectNode();
        ObjectNode cmd = MAPPER.createObjectNode(); cmd.put("type","string"); cmd.put("description","要执行的 shell 命令");
        props.set("command", cmd);
        ObjectNode to = MAPPER.createObjectNode(); to.put("type","number"); to.put("description","超时时间（毫秒）");
        props.set("timeout", to);
        schema.set("properties", props);
        var req = MAPPER.createArrayNode(); req.add("command"); schema.set("required", req);
        return schema;
    }

    @Override
    public ToolResult execute(String callId, Map<String, Object> args) throws Exception {
        String command = (String) args.get("command");
        if (command == null || command.isBlank()) return ToolResult.error("缺少 command");
        Number timeoutN = (Number) args.get("timeout");
        long timeout = timeoutN != null ? timeoutN.longValue() : timeoutMs;

        ProcessBuilder pb = new ProcessBuilder("bash", "-lc", command);
        pb.directory(workdir.toFile());
        pb.redirectErrorStream(false);
        Process proc = pb.start();

        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();

        Thread outT = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = r.readLine()) != null) out.append(line).append("\n");
            } catch (Exception ignored) {}
        });
        Thread errT = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = r.readLine()) != null) err.append(line).append("\n");
            } catch (Exception ignored) {}
        });
        outT.start(); errT.start();

        boolean finished = proc.waitFor(timeout, TimeUnit.MILLISECONDS);
        if (!finished) {
            proc.destroyForcibly();
            return ToolResult.error("命令超时（" + timeout + "ms）: " + command);
        }
        outT.join(1000); errT.join(1000);
        int exit = proc.exitValue();
        String combined = "";
        if (out.length() > 0) combined += out.toString();
        if (err.length() > 0) combined += (combined.isEmpty() ? "" : "\n[stderr]\n") + err.toString();
        if (combined.isBlank()) combined = "(无输出)";
        String truncated = truncate(combined);
        String header = "$ " + command + "\n(退出码 " + exit + ")\n";
        if (exit != 0) return ToolResult.error(header + truncated);
        return ToolResult.ok(header + truncated);
    }

    /** 截断超长输出，保留最后 2000 行或 50KB */
    private String truncate(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= ToolDefinition.DEFAULT_MAX_BYTES) {
            String[] lines = s.split("\n", -1);
            if (lines.length <= ToolDefinition.DEFAULT_MAX_LINES) return s;
            String[] tail = java.util.Arrays.copyOfRange(lines, lines.length - ToolDefinition.DEFAULT_MAX_LINES, lines.length);
            return "... [已截断，保留最后 2000 行]\n" + String.join("\n", tail);
        }
        String tail = new String(java.util.Arrays.copyOfRange(b, Math.max(0, b.length - ToolDefinition.DEFAULT_MAX_BYTES), b.length), StandardCharsets.UTF_8);
        return "... [已截断，保留最后 50KB]\n" + tail;
    }
}
