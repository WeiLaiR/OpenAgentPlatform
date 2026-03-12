package com.weilair.openagent.web.vo;

public record ChatAnswerVO(
        String requestId,
        Long conversationId,
        String answer,
        String finishReason,
        Boolean usedRag,
        Boolean usedTools,
        Long elapsedMillis
) {
}
