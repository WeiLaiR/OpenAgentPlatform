package com.weilair.openagent.web.vo;

public record ConversationVO(
        Long id,
        String title,
        String modeCode,
        Boolean enableRag,
        Boolean enableAgent,
        String status,
        Long lastMessageAt,
        Long createdAt,
        Long updatedAt
) {
}
