package com.weilair.openagent.web.vo;

import java.util.List;

public record TraceDetailVO(
        String requestId,
        Long conversationId,
        List<TraceEventVO> events
) {
}
