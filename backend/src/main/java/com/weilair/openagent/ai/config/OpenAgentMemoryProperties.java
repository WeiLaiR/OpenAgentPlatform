package com.weilair.openagent.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openagent.ai.memory")
public class OpenAgentMemoryProperties {

    /**
     * 新建会话时是否默认开启 memory。
     * 会话建成后，具体是否启用仍然以 conversation.memory_enabled 为准。
     */
    private Boolean defaultEnabled = true;

    /**
     * 当官方 TokenCountEstimator 不可用时，回退到 MessageWindowChatMemory。
     * 这里保留 message 数量上限，避免非 OpenAI tokenizer 场景下 memory 完全失控。
     */
    private Integer maxMessages = 20;

    /**
     * 第一优先级仍然是官方 TokenWindowChatMemory。
     * 只有在 tokenizer 无法解析时，才降级到 MessageWindowChatMemory。
     */
    private Integer maxTokens = 4000;

    /**
     * LangChain4j 的窗口裁剪默认会尽量保留首条 system message。
     * 这里把行为配置化，便于后续接系统 prompt 与 RAG 时做边界验证。
     */
    private Boolean alwaysKeepSystemMessageFirst = true;

    public Boolean getDefaultEnabled() {
        return defaultEnabled;
    }

    public void setDefaultEnabled(Boolean defaultEnabled) {
        this.defaultEnabled = defaultEnabled;
    }

    public Integer getMaxMessages() {
        return maxMessages;
    }

    public void setMaxMessages(Integer maxMessages) {
        this.maxMessages = maxMessages;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Boolean getAlwaysKeepSystemMessageFirst() {
        return alwaysKeepSystemMessageFirst;
    }

    public void setAlwaysKeepSystemMessageFirst(Boolean alwaysKeepSystemMessageFirst) {
        this.alwaysKeepSystemMessageFirst = alwaysKeepSystemMessageFirst;
    }
}
