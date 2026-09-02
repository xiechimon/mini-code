package dev.minicode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import dev.minicode.ai.Tool;

import java.util.Map;

/**
 * 可执行工具接口 — 将模型的工具定义与 Java 执行逻辑绑定。
 * 对应 pi-agent-core 中 harness/tools/* 的 createXxxTool。
 */
public interface ToolDefinition {

    // 通用截断阈值 — 对齐 pi 的 read 工具
    int DEFAULT_MAX_LINES = 2000;
    int DEFAULT_MAX_BYTES = 50 * 1024;

    /**
     * 工具名（发给模型的 function name）
     */
    String name();

    /**
     * 工具描述
     */
    String description();

    /**
     * 参数的 JSON Schema
     */
    JsonNode parameters();

    /**
     * 转为发给大模型的 Tool
     */
    default Tool toLlmTool() {
        return new Tool(name(), description(), parameters());
    }

    /**
     * 执行工具
     *
     * @param callId    OpenAI 的 tool_call_id
     * @param arguments 解析后的参数 Map
     * @return 执行结果（通过 ToolResult.isError 区分成功/失败）
     */
    ToolResult execute(String callId, Map<String, Object> arguments) throws Exception;
}
