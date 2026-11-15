package com.erp.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LLM 调用客户端 —— 只依赖「OpenAI 兼容协议」（{@code POST /v1/chat/completions}），
 * 不绑定任何一家厂商 SDK。
 *
 * <p>为什么不用各家官方 SDK？DeepSeek、通义千问、Kimi、智谱、OpenAI 全都提供
 * OpenAI 兼容端点，用纯 HTTP 调用意味着：<b>换厂商只改两个配置项</b>
 * （base-url 和 model），代码零改动，也不引入额外依赖。
 *
 * <p>工程要点：
 * <ul>
 *   <li>显式设置连接/读取超时 —— LLM 可能很慢，但绝不能无限等待拖垮 Tomcat 线程池</li>
 *   <li>异常不上抛给 Controller，返回 {@link Optional#empty()} 由 Service 层降级</li>
 *   <li>密钥只出现在 Authorization 头里，日志与异常消息都不打印 key</li>
 * </ul>
 */
@Component
public class LlmClient {

    private final AiProperties props;
    private final ObjectMapper objectMapper;

    public LlmClient(AiProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * 发起一次对话补全。失败（网络/超时/限流/格式异常）一律返回 empty，
     * 由调用方决定是否降级 —— 对外的 LLM 调用永远是「尽力而为」，
     * 不能成为核心业务流程的单点。
     */
    public Optional<String> chat(String systemPrompt, String userPrompt) {
        if (!props.llmReady()) {
            return Optional.empty();
        }
        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(10));
            factory.setReadTimeout(Duration.ofSeconds(props.getTimeoutSeconds()));

            RestClient client = RestClient.builder()
                    .baseUrl(props.getBaseUrl())
                    .requestFactory(factory)
                    .defaultHeader("Authorization", "Bearer " + props.getApiKey())
                    .build();

            Map<String, Object> body = Map.of(
                    "model", props.getModel(),
                    "temperature", 0.3,
                    "max_tokens", props.getMaxTokens(),
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)));

            JsonNode resp = client.post()
                    .uri("/v1/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            if (resp == null) {
                return Optional.empty();
            }
            JsonNode content = resp.path("choices").path(0).path("message").path("content");
            String text = content.asText("").trim();
            return text.isEmpty() ? Optional.empty() : Optional.of(text);
        } catch (Exception e) {
            // 只记类型与一句话原因，避免把可能含敏感信息的响应体写进日志
            System.err.println("[AI] LLM 调用失败，将使用本地规则引擎降级: "
                    + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).lines().findFirst().orElse(""));
            return Optional.empty();
        }
    }
}
