package com.weilair.openagent.web.vo;

public record ConversationMemoryClearVO(
        Long conversationId,
        Boolean cleared
) {
}
