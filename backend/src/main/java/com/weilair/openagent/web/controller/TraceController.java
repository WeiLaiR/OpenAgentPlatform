package com.weilair.openagent.web.controller;

import com.weilair.openagent.chat.service.ChatStreamSessionStore;
import com.weilair.openagent.common.response.ApiResponse;
import com.weilair.openagent.trace.service.TraceService;
import com.weilair.openagent.web.vo.TraceDetailVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/traces")
public class TraceController {

    private final TraceService traceService;
    private final ChatStreamSessionStore chatStreamSessionStore;

    public TraceController(TraceService traceService, ChatStreamSessionStore chatStreamSessionStore) {
        this.traceService = traceService;
        this.chatStreamSessionStore = chatStreamSessionStore;
    }

    @GetMapping("/{requestId}")
    public ApiResponse<TraceDetailVO> getTraceDetail(@PathVariable String requestId) {
        return ApiResponse.success(traceService.getTraceDetail(requestId));
    }

    @GetMapping("/stream/{requestId}")
    public SseEmitter streamTrace(@PathVariable String requestId) {
        return chatStreamSessionStore.connectTrace(requestId);
    }
}
