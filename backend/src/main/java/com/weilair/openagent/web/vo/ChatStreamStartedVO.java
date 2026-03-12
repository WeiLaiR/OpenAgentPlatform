package com.weilair.openagent.web.vo;

public record ChatStreamStartedVO(
        String requestId,
        Long conversationId
) {
}
