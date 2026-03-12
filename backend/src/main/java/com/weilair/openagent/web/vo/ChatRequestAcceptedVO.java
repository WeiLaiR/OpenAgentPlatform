package com.weilair.openagent.web.vo;

public record ChatRequestAcceptedVO(
        String requestId,
        Long conversationId,
        String status,
        Long acceptedAt
) {
}
