package com.weilair.openagent.chat.service;

import com.weilair.openagent.trace.service.TraceService;
import com.weilair.openagent.web.vo.TraceEventVO;
import org.springframework.stereotype.Component;

@Component
public class TracePublisher {
    /**
     * 当前平台 trace 已固定为双层结构：
     * 1. 平台编排 / RAG / Tool / 会话生命周期等事件继续由这里写入 `APP_CUSTOM`
     * 2. LangChain4j 原生模型 request/response/error 事件由 `ChatModelListener` 并列写入 `LANGCHAIN4J_NATIVE`
     *
     * 因此这里的职责已经固定为“平台事件出口”，不再承担原生模型观测事件。
     */
    private final TraceService traceService;

    public TracePublisher(TraceService traceService) {
        this.traceService = traceService;
    }

    public TraceEventVO append(
            ChatOutputPort outputPort,
            Long messageId,
            String eventType,
            String eventStage,
            Object payload,
            boolean successFlag,
            Integer costMillis
    ) {
        TraceEventVO traceEvent = traceService.appendTrace(
                outputPort.conversationId(),
                outputPort.requestId(),
                messageId,
                eventType,
                eventStage,
                com.weilair.openagent.trace.service.TraceEventSources.APP_CUSTOM,
                payload,
                successFlag,
                costMillis
        );
        outputPort.appendTraceEvent(traceEvent);
        return traceEvent;
    }
}
