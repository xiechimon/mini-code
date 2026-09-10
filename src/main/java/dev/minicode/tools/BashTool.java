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
 * <p>
 * execute 无 signal 参数（有意偏离 pi，见 docs/adr/0005）：取消依赖线程中断——
 * {@code Future.cancel(true)} 中断 {@code proc.waitFor} 后 {@code destroyForcibly} 进程，
 * 不留下孤儿子进程。
 * </p>
 */
public class BashTool implements ToolDefinition {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Workspace workspace;
    private final long timeoutMs; // 默认超时

    public BashTool(Path workdir) {
        this(new Workspace(workdir), 30_000);
    }

    public BashTool(Path workdir, long timeoutMs) {
        this(new Workspace(workdir), timeoutMs);
    }

    // 供测试或深模块内部直接注入 Workspace
    BashTool(Workspace workspace, long timeoutMs) {
        this.workspace = workspace;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "在工作目录执行 bash 命令，返回 stdout 与 stderr，超长输出截断为最后 2000 行或 50KB。";
    }

    @Override
    public com.fasterxml.jackson.databind.JsonNode parameters() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = MAPPER.createObjectNode();
        ObjectNode cmd = MAPPER.createObjectNode();
        cmd.put("type", "string");
        cmd.put("description", "要执行的 shell 命令");
        props.set("command", cmd);
        ObjectNode to = MAPPER.createObjectNode();
        to.put("type", "number");
        to.put("description", "超时时间（毫秒）");
        props.set("timeout", to);
        schema.set("properties", props);
        var req = MAPPER.createArrayNode();
        req.add("command");
        schema.set("required", req);
        return schema;
    }

    @Override
    public ToolResult execute(String callId, Map<String, Object> args) throws Exception {
        String command = (String) args.get("command");
        if (command == null || command.isBlank()) return ToolResult.error("缺少 command");
        Number timeoutN = (Number) args.get("timeout");
        long timeout = timeoutN != null ? timeoutN.longValue() : timeoutMs;

        ProcessBuilder pb = new ProcessBuilder("bash", "-lc", command);
        pb.directory(workspace.root().toFile());
        pb.redirectErrorStream(false);
        Process proc = pb.start();

        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();

        Thread outT = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append("\n");
            } catch (Exception ignored) {
            }
        });
        Thread errT = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) err.append(line).append("\n");
            } catch (Exception ignored) {
            }
        });
        outT.start();
        errT.start();

        boolean finished;
        try {
            finished = proc.waitFor(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 并行组内被 Future.cancel(true) 中断：销毁进程 + 恢复中断位（见 docs/adr/0005）
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
            return ToolResult.error("命令被中断");
        }
        if (!finished) {
            proc.destroyForcibly();
            return ToolResult.error("命令超时（" + timeout + "ms）: " + command);
        }
        outT.join(1000);
        errT.join(1000);
        int exit = proc.exitValue();
        String combined = "";
        if (out.length() > 0) combined += out.toString();
        if (err.length() > 0) combined += (combined.isEmpty() ? "" : "\n[stderr]\n") + err;
        if (combined.isBlank()) combined = "(无输出)";
        String truncated = truncate(combined);
        String header = "$ " + command + "\n(退出码 " + exit + ")\n";
        if (exit != 0) return ToolResult.error(header + truncated);
        return ToolResult.ok(header + truncated);
    }

    /**
     * 截断超长输出，保留最后 2000 行或 50KB —— 委托深模块 Truncate
     */
    private String truncate(String s) {
        return Truncate.tail(s, ToolDefinition.DEFAULT_MAX_LINES, ToolDefinition.DEFAULT_MAX_BYTES);
    }
}
