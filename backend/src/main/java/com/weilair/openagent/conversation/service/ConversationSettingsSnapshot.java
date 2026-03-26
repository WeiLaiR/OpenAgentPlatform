package com.weilair.openagent.conversation.service;

import java.util.List;

import com.weilair.openagent.conversation.model.ConversationDO;

/**
 * 会话级配置快照把 conversation 主表和绑定表收口成一份只读结果，
 * 供 settings 接口、聊天配置解析和后续前端配置中心统一复用。
 */
public record ConversationSettingsSnapshot(
        ConversationDO conversation,
        boolean enableRag,
        boolean enableAgent,
        boolean memoryEnabled,
        List<Long> knowledgeBaseIds,
        List<Long> mcpServerIds
) {

    public ConversationSettingsSnapshot {
        knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        mcpServerIds = mcpServerIds == null ? List.of() : List.copyOf(mcpServerIds);
    }
}
