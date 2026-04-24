package com.weilair.openagent.chat.model;

/**
 * 四种聊天运行模式本质上是同一套编排骨架下的四种配置结果。
 * 这里单独抽成枚举，目的是把“模式判断”从具体聊天执行流程里拿出来，
 * 避免后续继续在各个 service 中散落 `enableRag` / `enableAgent` 的布尔组合判断。
 */
public enum ChatMode {

    CHAT("CHAT", false, false),
    RAG("RAG", true, false),
    AGENT("AGENT", false, true),
    RAG_AGENT("RAG_AGENT", true, true);

    private final String code;
    private final boolean ragEnabled;
    private final boolean agentEnabled;

    ChatMode(String code, boolean ragEnabled, boolean agentEnabled) {
        this.code = code;
        this.ragEnabled = ragEnabled;
        this.agentEnabled = agentEnabled;
    }

    public String code() {
        return code;
    }

    public boolean ragEnabled() {
        return ragEnabled;
    }

    public boolean agentEnabled() {
        return agentEnabled;
    }

    public static ChatMode fromFlags(Boolean enableRag, Boolean enableAgent) {
        boolean rag = Boolean.TRUE.equals(enableRag);
        boolean agent = Boolean.TRUE.equals(enableAgent);
        if (rag && agent) {
            return RAG_AGENT;
        }
        if (rag) {
            return RAG;
        }
        if (agent) {
            return AGENT;
        }
        return CHAT;
    }

    public static ChatMode fromCode(String code) {
        if (code == null || code.isBlank()) {
            return CHAT;
        }
        for (ChatMode value : values()) {
            if (value.code.equalsIgnoreCase(code.trim())) {
                return value;
            }
        }
        return CHAT;
    }
}
