package dev.minicode.ai;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 供大模型调用的工具定义（会发送给模型）。
 * 对应 pi-ai 中的 Tool。
 */
public record Tool(
        String name,        // 工具名
        String description, // 工具描述
        JsonNode parameters // JSON Schema 参数定义
) {}
