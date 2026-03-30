package com.weilair.openagent.chat.prompt;

/**
 * 第一版先把 Prompt 最小集合固定成代码内可枚举、可追踪的 5 块。
 * 后续如果接 Prompt 管理后台，也应该继续以这些稳定 key 作为桥接层，
 * 而不是让业务主链路重新回到“各处散落字符串”的状态。
 */
public enum PromptTemplateKey {
    SYSTEM_BASE("system-base"),
    RAG_CONTEXT("rag-context"),
    AGENT_TOOL_POLICY("agent-tool-policy"),
    MCP_TOOL_SELECTION("mcp-tool-selection"),
    ANSWER_FORMAT("answer-format");

    private final String code;

    PromptTemplateKey(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
