package dev.minicode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import dev.minicode.ai.Tool;

import java.util.Map;

/**
 * Executable tool — pairs LLM-facing Tool descriptor with Java execution.
 * Mirrors pi-agent-core harness/tools/* createXxxTool<TContext>
 */
public interface ToolDefinition {

    String name();
    String description();
    JsonNode parameters(); // JSON Schema

    /** Convert to LLM Tool */
    default Tool toLlmTool() {
        return new Tool(name(), description(), parameters());
    }

    /**
     * @param callId    OpenAI tool_call id
     * @param arguments parsed arguments (Map)
     * @return tool output (isError via ToolResult)
     */
    ToolResult execute(String callId, Map<String, Object> arguments) throws Exception;

    // common truncate limits — mirrors pi read tool
    int DEFAULT_MAX_LINES = 2000;
    int DEFAULT_MAX_BYTES = 50 * 1024;
}
