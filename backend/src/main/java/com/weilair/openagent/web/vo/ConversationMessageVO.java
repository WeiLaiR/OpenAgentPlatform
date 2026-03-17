package com.weilair.openagent.web.vo;

public record ConversationMessageVO(
        Long id,
        Long conversationId,
        String roleCode,
        String messageType,
        String content,
        String requestId,
        String finishReason,
        Long createdAt
) {
}
