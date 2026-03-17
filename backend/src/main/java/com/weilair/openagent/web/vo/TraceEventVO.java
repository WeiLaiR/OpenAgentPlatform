package com.weilair.openagent.web.vo;

public record TraceEventVO(
        Long id,
        String requestId,
        Long conversationId,
        Long messageId,
        String eventType,
        String eventStage,
        String eventSource,
        String eventPayloadJson,
        Boolean successFlag,
        Integer costMillis,
        Long createdAt
) {
}
