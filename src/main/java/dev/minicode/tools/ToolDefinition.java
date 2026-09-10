package dev.minicode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import dev.minicode.ai.Tool;

import java.util.Map;

/**
 * 可执行工具接口 — 将模型的工具定义与 Java 执行逻辑绑定。
 * 对应 pi-agent-core 中 harness/tools/* 的 createXxxTool。
 * <p>
 * 并行执行时按 {@link ToolKind} 分组：同回合连续 READ_ONLY 工具并行、STATEFUL 串行。
 * 默认 {@link #kind()} 返回 {@link ToolKind#STATEFUL}（fail-safe），明确只读的工具
 * （如 ReadTool）应 override 返回 {@link ToolKind#READ_ONLY}。
 */
public interface ToolDefinition {

    /**
     * 工具类型：同一回合中多个工具执行时的并行策略。
     * <p>
     * fail-safe 理由：STATEFUL 默认让未知/未声明工具始终串行，避免文件系统或进程级别
     * 的写竞争。明确只读的工具显式声明 READ_ONLY 后同回合连续段内才并行。
     */
    enum ToolKind {
        /** 只读工具，同回合中连续 READ_ONLY 段内可并行执行。 */
        READ_ONLY,
        /** 有状态工具（写/编辑/bash），始终串行执行，保持文件系统副作用顺序。 */
        STATEFUL
    }

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
     * 工具类型，决定同回合多工具调用时的并行策略。
     * <p>
     * 默认 {@link ToolKind#STATEFUL}：fail-safe，未知或未显式声明只读的工具不并行，
     * 避免文件系统写竞争与副作用乱序（见 docs/adr/0005）。明确只读的工具（如
     * {@link ReadTool}）应 override 返回 {@link ToolKind#READ_ONLY}。
     *
     * @return 工具类型
     */
    default ToolKind kind() {
        return ToolKind.STATEFUL;
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
