package dev.minicode.ai;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * LLM-facing tool definition (sent to provider).
 * Mirrors pi-ai Tool.
 */
public record Tool(
        String name,
        String description,
        JsonNode parameters // JSON Schema
) {}
