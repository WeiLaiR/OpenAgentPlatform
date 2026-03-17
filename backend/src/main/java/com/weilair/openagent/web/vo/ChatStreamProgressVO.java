package com.weilair.openagent.web.vo;

public record ChatStreamProgressVO(
        String requestId,
        String status,
        String message,
        Long elapsedMillis
) {
}
