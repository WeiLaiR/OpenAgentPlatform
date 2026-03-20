package com.weilair.openagent.trace.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import com.weilair.openagent.trace.service.ChatModelObservationContextHolder.ChatModelObservationContext;
import com.weilair.openagent.web.vo.TraceEventVO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 这一层负责做“LangChain4j 原生模型事件 -> 平台 trace_event”归一化。
 *
 * 设计边界固定如下：
 * - 只处理 `ChatModelListener` 的 request/response/error 三类事件
 * - 统一写入 `event_source=LANGCHAIN4J_NATIVE`
 * - payload 以“模型请求/响应审计”所需的最小结构化信息为主
 * - 不承接会话锁、RAG、Tool 运行时等平台编排事件
 */
@Component
public class LangChain4jNativeTracePublisher {

    private static final int MAX_MESSAGE_PREVIEW_LENGTH = 400;
    private static final int MAX_TEXT_PREVIEW_LENGTH = 1000;

    private final TraceService traceService;

    public LangChain4jNativeTracePublisher(TraceService traceService) {
        this.traceService = traceService;
    }

    public void publishRequest(
            ChatModelObservationContext observationContext,
            ChatModelRequestContext requestContext
    ) {
        append(
                observationContext,
                "CHAT_MODEL_REQUEST",
                "REQUESTED",
                buildRequestPayload(observationContext, requestContext),
                true,
                null
        );
    }

    public void publishResponse(
            ChatModelObservationContext observationContext,
            ChatModelResponseContext responseContext,
            Integer costMillis
    ) {
        append(
                observationContext,
                "CHAT_MODEL_RESPONSE",
                "RESPONDED",
                buildResponsePayload(observationContext, responseContext),
                true,
                costMillis
        );
    }

    public void publishError(
            ChatModelObservationContext observationContext,
            ChatModelErrorContext errorContext,
            Integer costMillis
    ) {
        append(
                observationContext,
                "CHAT_MODEL_ERROR",
                "FAILED",
                buildErrorPayload(observationContext, errorContext),
                false,
                costMillis
        );
    }

    private void append(
            ChatModelObservationContext observationContext,
            String eventType,
            String eventStage,
            Object payload,
            boolean successFlag,
            Integer costMillis
    ) {
        if (observationContext == null || !StringUtils.hasText(observationContext.requestId())) {
            return;
        }

        TraceEventVO traceEvent = traceService.appendTrace(
                observationContext.conversationId(),
                observationContext.requestId(),
                observationContext.messageId(),
                eventType,
                eventStage,
                TraceEventSources.LANGCHAIN4J_NATIVE,
                payload,
                successFlag,
                costMillis
        );
        observationContext.outputPort().appendTraceEvent(traceEvent);
    }

    private Map<String, Object> buildRequestPayload(
            ChatModelObservationContext observationContext,
            ChatModelRequestContext requestContext
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("executionMode", observationContext.executionMode());
        payload.put("modelProvider", resolveModelProvider(requestContext));
        payload.put("modelName", requestContext.chatRequest().modelName());
        payload.put("messageCount", requestContext.chatRequest().messages().size());
        payload.put("messages", summarizeMessages(requestContext.chatRequest().messages()));
        payload.put("temperature", requestContext.chatRequest().temperature());
        payload.put("topP", requestContext.chatRequest().topP());
        payload.put("maxOutputTokens", requestContext.chatRequest().maxOutputTokens());
        payload.put("toolSpecifications", summarizeToolSpecifications(requestContext.chatRequest().toolSpecifications()));
        return payload;
    }

    private Map<String, Object> buildResponsePayload(
            ChatModelObservationContext observationContext,
            ChatModelResponseContext responseContext
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("executionMode", observationContext.executionMode());
        payload.put("modelProvider", resolveModelProvider(responseContext));
        payload.put("modelName", firstNonBlank(
                responseContext.chatResponse().modelName(),
                responseContext.chatRequest().modelName()
        ));
        payload.put("responseId", responseContext.chatResponse().id());
        payload.put("finishReason", responseContext.chatResponse().finishReason() == null
                ? null
                : responseContext.chatResponse().finishReason().name());
        payload.put("tokenUsage", summarizeTokenUsage(responseContext.chatResponse().tokenUsage()));
        payload.put("aiMessage", summarizeAiMessage(responseContext.chatResponse().aiMessage()));
        return payload;
    }

    private Map<String, Object> buildErrorPayload(
            ChatModelObservationContext observationContext,
            ChatModelErrorContext errorContext
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("executionMode", observationContext.executionMode());
        payload.put("modelProvider", resolveModelProvider(errorContext));
        payload.put("modelName", errorContext.chatRequest().modelName());
        payload.put("errorType", errorContext.error().getClass().getName());
        payload.put("errorMessage", safeMessage(errorContext.error()));
        payload.put("messageCount", errorContext.chatRequest().messages().size());
        return payload;
    }

    private List<Map<String, Object>> summarizeMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        return messages.stream()
                .map(this::summarizeMessage)
                .toList();
    }

    private Map<String, Object> summarizeMessage(ChatMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", message.type().name());

        if (message instanceof SystemMessage systemMessage) {
            payload.put("textPreview", shorten(systemMessage.text(), MAX_MESSAGE_PREVIEW_LENGTH));
            return payload;
        }

        if (message instanceof UserMessage userMessage) {
            payload.put("textPreview", shorten(userMessage.singleText(), MAX_MESSAGE_PREVIEW_LENGTH));
            return payload;
        }

        if (message instanceof AiMessage aiMessage) {
            payload.put("textPreview", shorten(aiMessage.text(), MAX_MESSAGE_PREVIEW_LENGTH));
            payload.put("toolRequests", summarizeToolRequests(aiMessage.toolExecutionRequests()));
            return payload;
        }

        if (message instanceof ToolExecutionResultMessage toolResultMessage) {
            payload.put("toolName", toolResultMessage.toolName());
            payload.put("isError", Boolean.TRUE.equals(toolResultMessage.isError()));
            payload.put("textPreview", shorten(toolResultMessage.text(), MAX_MESSAGE_PREVIEW_LENGTH));
            return payload;
        }

        payload.put("textPreview", shorten(String.valueOf(message), MAX_MESSAGE_PREVIEW_LENGTH));
        return payload;
    }

    private Map<String, Object> summarizeAiMessage(AiMessage aiMessage) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (aiMessage == null) {
            payload.put("textPreview", "");
            payload.put("toolRequests", List.of());
            return payload;
        }

        payload.put("textPreview", shorten(aiMessage.text(), MAX_TEXT_PREVIEW_LENGTH));
        payload.put("toolRequests", summarizeToolRequests(aiMessage.toolExecutionRequests()));
        return payload;
    }

    private List<Map<String, Object>> summarizeToolRequests(List<ToolExecutionRequest> toolExecutionRequests) {
        if (toolExecutionRequests == null || toolExecutionRequests.isEmpty()) {
            return List.of();
        }

        return toolExecutionRequests.stream()
                .map(toolExecutionRequest -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("toolCallId", toolExecutionRequest.id());
                    payload.put("toolName", toolExecutionRequest.name());
                    payload.put("argumentsPreview", shorten(toolExecutionRequest.arguments(), MAX_MESSAGE_PREVIEW_LENGTH));
                    return payload;
                })
                .toList();
    }

    private List<Map<String, Object>> summarizeToolSpecifications(List<ToolSpecification> toolSpecifications) {
        if (toolSpecifications == null || toolSpecifications.isEmpty()) {
            return List.of();
        }

        return toolSpecifications.stream()
                .map(toolSpecification -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("name", toolSpecification.name());
                    payload.put("description", shorten(toolSpecification.description(), MAX_MESSAGE_PREVIEW_LENGTH));
                    return payload;
                })
                .toList();
    }

    private Map<String, Object> summarizeTokenUsage(TokenUsage tokenUsage) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (tokenUsage == null) {
            payload.put("inputTokenCount", null);
            payload.put("outputTokenCount", null);
            payload.put("totalTokenCount", null);
            return payload;
        }

        payload.put("inputTokenCount", tokenUsage.inputTokenCount());
        payload.put("outputTokenCount", tokenUsage.outputTokenCount());
        payload.put("totalTokenCount", tokenUsage.totalTokenCount());
        return payload;
    }

    private String resolveModelProvider(ChatModelRequestContext requestContext) {
        return requestContext.modelProvider() == null ? "UNKNOWN" : requestContext.modelProvider().name();
    }

    private String resolveModelProvider(ChatModelResponseContext responseContext) {
        return responseContext.modelProvider() == null ? "UNKNOWN" : responseContext.modelProvider().name();
    }

    private String resolveModelProvider(ChatModelErrorContext errorContext) {
        return errorContext.modelProvider() == null ? "UNKNOWN" : errorContext.modelProvider().name();
    }

    private String firstNonBlank(String primary, String fallback) {
        if (StringUtils.hasText(primary)) {
            return primary;
        }
        return fallback;
    }

    private String shorten(String value, int maxLength) {
        if (!StringUtils.hasText(value) || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null || !StringUtils.hasText(throwable.getMessage())) {
            return "未知模型错误";
        }
        return throwable.getMessage();
    }
}
