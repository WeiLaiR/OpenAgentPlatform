package com.weilair.openagent.memory.service;

import dev.langchain4j.model.TokenCountEstimator;

/**
 * 这里只描述“当前会话 memory 应该采用哪种 LangChain4j 官方窗口策略”。
 * 它不是自定义 memory 抽象，只是把 TokenWindow / MessageWindow 所需的配置汇总起来，
 * 便于聊天链路和 ChatMemoryStore 持久化层共享同一份判定结果。
 */
public record ConversationMemorySpec(
        String memoryType,
        Integer maxMessages,
        Integer maxTokens,
        String tokenizerName,
        Boolean alwaysKeepSystemMessageFirst,
        TokenCountEstimator tokenCountEstimator
) {

    public static final String TOKEN_WINDOW = "TOKEN_WINDOW";
    public static final String MESSAGE_WINDOW = "MESSAGE_WINDOW";

    public boolean useTokenWindow() {
        return tokenCountEstimator != null;
    }
}
