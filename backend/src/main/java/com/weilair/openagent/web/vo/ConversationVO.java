package com.weilair.openagent.web.vo;

import java.util.List;

public record ConversationVO(
        Long id,
        String title,
        String modeCode,
        Boolean enableRag,
        Boolean enableAgent,
        Boolean memoryEnabled,
        List<Long> knowledgeBaseIds,
        List<Long> mcpServerIds,
        String status,
        Long lastMessageAt,
        Long createdAt,
        Long updatedAt
) {
}
