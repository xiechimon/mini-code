package dev.minicode.cli;

import dev.minicode.ai.Dotenv;
import dev.minicode.ai.LlmConfig;

import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * 对齐 pi 源：`pi/pi-ai/env-api-keys.ts` 的 key 解析——本类是其**诊断视图**（mini-code 自加，
 * pi 无对应命令；形态参考 agent-reach doctor）：一条命令打印解析后的 provider/model/baseUrl、
 * key 的来源层与掩码值、.env 实际命中路径，并对网关做一次最小 ping。
 * <p>
 * 背景：key 错位排障（model 按 .env、key 捡了别处旧值）曾花掉一整轮会话逐层排查；
 * doctor 把「解析优先级 + 实际生效值 + 网关可达性」压进一次调用。
 * </p>
 * <p>
 * 测试缝：{@link #maskKey} / {@link #describeKeySource} / {@link #buildPingPayload} 为纯函数，
 * 单测直接断言；{@link #pingGateway} 走真实网络，不测（同 gateway tag 的取舍）。
 * </p>
 */
public final class Doctor {

    private Doctor() {
    }

    /** 入口：打印诊断报告。 */
    public static void run(PrintStream out) {
        LlmConfig cfg = LlmConfig.resolve();
        Map<String, String> sysEnv = System.getenv();
        Map<String, String> dotenv = Dotenv.load();
        Path envFile = Dotenv.findEnvFile(Path.of(System.getProperty("user.dir", ".")));

        out.println("mini-code doctor");
        out.println("  provider:  " + cfg.model().provider());
        out.println("  model:     " + cfg.model().id());
        out.println("  baseUrl:   " + cfg.model().baseUrl());
        out.println("  .env:      " + (envFile != null ? envFile : "未找到（向上查找无命中）"));
        out.println("  key:       " + describeKeySource(cfg.apiKey(), cfg.model().provider(), sysEnv, dotenv)
                + "  " + maskKey(cfg.apiKey()));
        out.println("  gateway:   " + pingGateway(cfg));
    }

    /** key 掩码：保留前 6 后 4，中间省略；短 key 全掩。 */
    static String maskKey(String key) {
        if (key == null || key.isBlank()) return "(空)";
        if (key.length() <= 10) return "***";
        return key.substring(0, 6) + "…" + key.substring(key.length() - 4);
    }

    /**
     * 报告 key 的来源层：按 LlmConfig.resolve 的优先级逐候选比对实际生效值。
     * 纯函数：环境表由调用方注入（sysEnv=System.getenv()，dotenv=Dotenv.load()）。
     */
    static String describeKeySource(String key, String provider, Map<String, String> sysEnv, Map<String, String> dotenv) {
        if (key == null || key.isBlank()) return "未找到（请求会带空 key，必 401）";
        // LLM_API_KEY 显式覆盖优先
        if (key.equals(sysEnv.get("LLM_API_KEY"))) return "LLM_API_KEY（系统环境）";
        if (key.equals(dotenv.get("LLM_API_KEY"))) return "LLM_API_KEY（.env）";
        // provider 专属变量（anthropic 有两个候选名，与 resolve 同序）
        String[] vars = "anthropic".equals(provider)
                ? new String[]{"ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY"}
                : new String[]{LlmConfig.envVarNameFor(provider)};
        for (String var : vars) {
            if (key.equals(sysEnv.get(var))) return var + "（系统环境）";
            if (key.equals(dotenv.get(var))) return var + "（.env）";
        }
        return "auth.json 兜底（或其他注入源）";
    }

    /** 最小 ping payload（max_tokens=1，非流式）。model id 不含引号字符，直接拼接。 */
    static String buildPingPayload(String modelId) {
        return "{\"model\":\"" + modelId + "\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],"
                + "\"max_tokens\":1,\"stream\":false}";
    }

    /**
     * 网关最小连通性检查：与 OpenAiCompatClient 同款路径（{baseUrl}/chat/completions）与鉴权头
     * （Authorization: Bearer）。非 200 时附响应体摘要——401/404 的 body 正是排障要看的。
     */
    static String pingGateway(LlmConfig cfg) {
        if (cfg.apiKey() == null || cfg.apiKey().isBlank()) return "跳过（无 key）";
        String base = cfg.model().baseUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildPingPayload(cfg.model().id())))
                    .build();
            long t0 = System.nanoTime();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (resp.statusCode() == 200) return "HTTP 200（" + ms + "ms）";
            String body = resp.body() == null ? "" : resp.body().replaceAll("\\s+", " ").trim();
            String snippet = body.length() > 160 ? body.substring(0, 160) + "…" : body;
            return "HTTP " + resp.statusCode() + "（" + ms + "ms）— " + snippet;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "中断";
        } catch (Exception e) {
            return "不可达：" + e.getClass().getSimpleName() + " " + e.getMessage();
        }
    }
}
