package com.weilair.openagent.trace.service;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import com.weilair.openagent.trace.service.ChatModelObservationContextHolder.ChatModelObservationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 这是当前平台挂到 LangChain4j `ChatModel` / `StreamingChatModel` 上的原生 listener。
 *
 * 它只做三件事：
 * 1. 在 `onRequest()` 阶段把平台请求上下文拷进 LangChain4j listener attributes
 * 2. 记录模型调用开始时间，供 response/error 计算耗时
 * 3. 把 request/response/error 委托给 `LangChain4jNativeTracePublisher` 做统一归一化落库
 *
 * 这样平台事件层和 LangChain4j 原生事件层就能真正并列，而不是继续混在 orchestrator 里。
 */
@Component
public class LangChain4jChatModelListener implements ChatModelListener {

    private static final String OBSERVATION_CONTEXT_ATTRIBUTE = "openagent.nativeTrace.observationContext";
    private static final String STARTED_AT_ATTRIBUTE = "openagent.nativeTrace.startedAt";

    private final ChatModelObservationContextHolder observationContextHolder;
    private final LangChain4jNativeTracePublisher nativeTracePublisher;

    public LangChain4jChatModelListener(
            ChatModelObservationContextHolder observationContextHolder,
            LangChain4jNativeTracePublisher nativeTracePublisher
    ) {
        this.observationContextHolder = observationContextHolder;
        this.nativeTracePublisher = nativeTracePublisher;
    }

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        ChatModelObservationContext observationContext = observationContextHolder.current();
        if (!isUsable(observationContext)) {
            return;
        }

        requestContext.attributes().put(OBSERVATION_CONTEXT_ATTRIBUTE, observationContext);
        requestContext.attributes().put(STARTED_AT_ATTRIBUTE, System.currentTimeMillis());
        nativeTracePublisher.publishRequest(observationContext, requestContext);
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        ChatModelObservationContext observationContext = resolveObservationContext(responseContext.attributes());
        if (!isUsable(observationContext)) {
            return;
        }

        nativeTracePublisher.publishResponse(
                observationContext,
                responseContext,
                resolveCostMillis(responseContext.attributes())
        );
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        ChatModelObservationContext observationContext = resolveObservationContext(errorContext.attributes());
        if (!isUsable(observationContext)) {
            return;
        }

        nativeTracePublisher.publishError(
                observationContext,
                errorContext,
                resolveCostMillis(errorContext.attributes())
        );
    }

    @SuppressWarnings("unchecked")
    private ChatModelObservationContext resolveObservationContext(java.util.Map<Object, Object> attributes) {
        Object observationContext = attributes.get(OBSERVATION_CONTEXT_ATTRIBUTE);
        return observationContext instanceof ChatModelObservationContext context ? context : null;
    }

    private Integer resolveCostMillis(java.util.Map<Object, Object> attributes) {
        Object startedAt = attributes.get(STARTED_AT_ATTRIBUTE);
        if (!(startedAt instanceof Long startTime)) {
            return null;
        }
        return (int) (System.currentTimeMillis() - startTime);
    }

    private boolean isUsable(ChatModelObservationContext observationContext) {
        return observationContext != null
                && observationContext.conversationId() != null
                && observationContext.messageId() != null
                && StringUtils.hasText(observationContext.requestId());
    }
}
