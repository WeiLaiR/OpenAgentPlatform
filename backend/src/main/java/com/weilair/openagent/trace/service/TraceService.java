package com.weilair.openagent.trace.service;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weilair.openagent.common.util.TimeUtils;
import com.weilair.openagent.trace.model.TraceEventDO;
import com.weilair.openagent.trace.persistence.mapper.TraceEventMapper;
import com.weilair.openagent.web.vo.TraceDetailVO;
import com.weilair.openagent.web.vo.TraceEventVO;
import org.springframework.stereotype.Service;

@Service
public class TraceService {

    private final TraceEventMapper traceEventMapper;
    private final ObjectMapper objectMapper;

    public TraceService(TraceEventMapper traceEventMapper, ObjectMapper objectMapper) {
        this.traceEventMapper = traceEventMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * trace_event 既是调试时间线，也是后续接 LangChain4j 原生 observability 的落点。
     */
    public TraceEventVO appendTrace(
            Long conversationId,
            String requestId,
            Long messageId,
            String eventType,
            String eventStage,
            String eventSource,
            Object payload,
            boolean successFlag,
            Integer costMillis
    ) {
        TraceEventDO traceEvent = new TraceEventDO();
        traceEvent.setConversationId(conversationId);
        traceEvent.setRequestId(requestId);
        traceEvent.setMessageId(messageId);
        traceEvent.setEventType(eventType);
        traceEvent.setEventStage(eventStage);
        traceEvent.setEventSource(eventSource);
        traceEvent.setEventPayloadJson(writeJson(payload));
        traceEvent.setSuccessFlag(successFlag);
        traceEvent.setCostMillis(costMillis);
        traceEventMapper.insert(traceEvent);
        return toTraceEventVO(traceEvent);
    }

    public TraceDetailVO getTraceDetail(String requestId) {
        List<TraceEventVO> events = traceEventMapper.selectByRequestId(requestId).stream()
                .map(this::toTraceEventVO)
                .toList();
        Long conversationId = events.isEmpty() ? null : events.get(0).conversationId();
        return new TraceDetailVO(requestId, conversationId, events);
    }

    private TraceEventVO toTraceEventVO(TraceEventDO traceEvent) {
        return new TraceEventVO(
                traceEvent.getId(),
                traceEvent.getRequestId(),
                traceEvent.getConversationId(),
                traceEvent.getMessageId(),
                traceEvent.getEventType(),
                traceEvent.getEventStage(),
                traceEvent.getEventSource(),
                traceEvent.getEventPayloadJson(),
                traceEvent.getSuccessFlag(),
                traceEvent.getCostMillis(),
                TimeUtils.toEpochMillis(traceEvent.getCreatedAt())
        );
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Trace 事件序列化失败", exception);
        }
    }
}
