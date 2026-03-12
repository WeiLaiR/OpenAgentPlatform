package com.weilair.openagent.web.vo;

public record ChatStreamCompletedVO(
        String requestId,
        String answer,
        String finishReason
) {
}
