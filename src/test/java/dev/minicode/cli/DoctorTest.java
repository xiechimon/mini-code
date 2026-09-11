package dev.minicode.cli;

import dev.minicode.ai.LlmConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Doctor 纯函数单测：maskKey / describeKeySource / buildPingPayload。
 * pingGateway 走真实网络不在此测（取舍同 gateway tag，见 .scratch 规格与 AGENTS.md 测试要求）。
 */
class DoctorTest {

    @Test
    void maskKeepsHeadAndTail() {
        assertEquals("sk-abc…wxyz", Doctor.maskKey("sk-abcdefghijklmnop-wxyz"));
        assertEquals("***", Doctor.maskKey("short"));
        assertEquals("(空)", Doctor.maskKey(null));
        assertEquals("(空)", Doctor.maskKey("  "));
    }

    @Test
    void keySourceFollowsResolvePriority() {
        Map<String, String> sysEnv = Map.of("LLM_API_KEY", "k-sys");
        Map<String, String> dotenv = Map.of("ANTHROPIC_AUTH_TOKEN", "k-dot", "DEEPSEEK_API_KEY", "k-ds");
        // LLM_API_KEY 系统环境最优先
        assertEquals("LLM_API_KEY（系统环境）", Doctor.describeKeySource("k-sys", "anthropic", sysEnv, dotenv));
        // provider 专属变量命中 .env
        assertEquals("ANTHROPIC_AUTH_TOKEN（.env）", Doctor.describeKeySource("k-dot", "anthropic", sysEnv, dotenv));
        assertEquals("DEEPSEEK_API_KEY（.env）", Doctor.describeKeySource("k-ds", "deepseek", sysEnv, dotenv));
        // anthropic 第二候选名
        Map<String, String> sys2 = Map.of("ANTHROPIC_API_KEY", "k-alt");
        assertEquals("ANTHROPIC_API_KEY（系统环境）", Doctor.describeKeySource("k-alt", "anthropic", sys2, Map.of()));
    }

    @Test
    void keySourceUnknownFallsBackToAuthJsonHint() {
        assertEquals("auth.json 兜底（或其他注入源）",
                Doctor.describeKeySource("k-mystery", "openai", Map.of(), Map.of()));
        assertTrue(Doctor.describeKeySource(null, "openai", Map.of(), Map.of()).contains("未找到"));
    }

    @Test
    void pingPayloadIsMinimalNonStreaming() {
        String payload = Doctor.buildPingPayload("m1");
        assertTrue(payload.contains("\"model\":\"m1\""), payload);
        assertTrue(payload.contains("\"max_tokens\":1"), payload);
        assertTrue(payload.contains("\"stream\":false"), payload);
    }

    @Test
    void envVarNameForCoversKnownProviders() {
        assertEquals("OPENCODE_API_KEY", LlmConfig.envVarNameFor("opencode-go"));
        assertEquals("ANTHROPIC_AUTH_TOKEN", LlmConfig.envVarNameFor("anthropic"));
        assertEquals("MINIMAX_CN_API_KEY", LlmConfig.envVarNameFor("minimax-cn"));
        assertEquals("LLM_API_KEY", LlmConfig.envVarNameFor("whatever-unknown"));
    }
}
