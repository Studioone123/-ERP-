package com.erp.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 经营分析的配置项，对应 application.yml 里的 {@code erp.ai.*}。
 *
 * <p>设计原则：<b>不配 key 也能用</b>。只填 base-url/model 不填 api-key 时，
 * 系统自动切换到内置规则引擎做分析，页面会明确标注「本地规则模式」，
 * 不会因为缺配置而报 500 —— 演示环境双击 start.bat 就能跑。
 *
 * <p>密钥只从环境变量 {@code ERP_AI_API_KEY} 注入，不写入任何配置文件，
 * 避免提交 Git 时泄露（简历/面试时可以主动讲这一点）。
 */
@ConfigurationProperties(prefix = "erp.ai")
public class AiProperties {

    /** 总开关：false 时强制走本地规则引擎，不发起任何外部调用 */
    private boolean enabled = true;

    /** OpenAI 兼容协议的 API Key；留空表示未配置，走规则引擎 */
    private String apiKey = "";

    /** OpenAI 兼容协议的 Base URL（DeepSeek / 通义 / Kimi / OpenAI 均可） */
    private String baseUrl = "https://api.deepseek.com";

    /** 模型名 */
    private String model = "deepseek-chat";

    /** 单次调用超时（秒）。LLM 响应慢，给足时间但要兜底，不能拖死请求线程 */
    private int timeoutSeconds = 45;

    /** 生成上限：分析结论是给人看的，不需要长文 */
    private int maxTokens = 1500;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    /** 是否具备真实调用 LLM 的条件 */
    public boolean llmReady() {
        return enabled && !apiKey.isBlank();
    }
}
