package com.weilair.openagent.chat.service;

import java.util.List;

import com.weilair.openagent.chat.model.ChatMode;

/**
 * 这是一轮聊天请求在真正进入编排骨架前的“已解析运行规格”。
 * 它表达的是本轮最终生效的配置结果，而不是请求原始入参：
 * 1. 模式已经被解析为四种之一
 * 2. streaming / memory / knowledge base 这些附属能力也已经收口
 *
 * 后续如果补上会话级 MCP 绑定、memory 策略或 prompt 变体，
 * 也应继续往这层收口，而不是再让下游直接读取原始 DTO。
 */
public record ChatExecutionSpec(
        ChatMode mode,
        boolean streaming,
        boolean memoryEnabled,
        List<Long> knowledgeBaseIds,
        List<Long> mcpServerIds
) {

    public ChatExecutionSpec {
        knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        mcpServerIds = mcpServerIds == null ? List.of() : List.copyOf(mcpServerIds);
    }

    public boolean ragEnabled() {
        return mode != null && mode.ragEnabled();
    }

    public boolean agentEnabled() {
        return mode != null && mode.agentEnabled();
    }

    public String modeCode() {
        return mode == null ? ChatMode.CHAT.code() : mode.code();
    }
}
