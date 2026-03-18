package com.weilair.openagent.chat.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import com.weilair.openagent.chat.exception.ChatServiceUnavailableException;
import com.weilair.openagent.chat.model.ChatStreamSession;
import com.weilair.openagent.common.request.RequestIdContext;
import com.weilair.openagent.conversation.model.ConversationDO;
import com.weilair.openagent.conversation.service.ConversationService;
import com.weilair.openagent.knowledge.service.KnowledgeRetrievalService;
import com.weilair.openagent.trace.service.TraceService;
import com.weilair.openagent.web.dto.ChatSendRequest;
import com.weilair.openagent.web.vo.ChatAnswerVO;
import com.weilair.openagent.web.vo.ChatRequestAcceptedVO;
import com.weilair.openagent.web.vo.ChatStreamCompletedVO;
import com.weilair.openagent.web.vo.ChatStreamProgressVO;
import com.weilair.openagent.web.vo.ChatStreamStartedVO;
import com.weilair.openagent.web.vo.ChatStreamTokenVO;
import com.weilair.openagent.web.vo.RagSnippetVO;
import com.weilair.openagent.web.vo.TraceEventVO;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class ChatGenerationService {
    /**
     * 当前聊天主链路已经从“纯内存联调版”升级为“有状态会话版”：
     * 1. 聊天请求先解析到 conversation
     * 2. user / assistant 消息分别持久化
     * 3. request 级 trace 既推给前端也落到 trace_event
     *
     * 这里仍然没有接入 ChatMemory、RAG、Tools、MCP，
     * 但已经把后续这些能力所依赖的 conversationId / requestId / trace 骨架先搭好了。
     */

    private static final int RAG_TOP_K = 4;
    private static final double RAG_MIN_SCORE = 0.55d;

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectProvider<StreamingChatModel> streamingChatModelProvider;
    private final ChatStreamSessionStore sessionStore;
    private final ChatContextAssembler chatContextAssembler;
    private final ConversationService conversationService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final TraceService traceService;
    private final ConversationExecutionGuard executionGuard;
    private final ExecutorService executorService;

    public ChatGenerationService(
            ObjectProvider<ChatModel> chatModelProvider,
            ObjectProvider<StreamingChatModel> streamingChatModelProvider,
            ChatStreamSessionStore sessionStore,
            ChatContextAssembler chatContextAssembler,
            ConversationService conversationService,
            KnowledgeRetrievalService knowledgeRetrievalService,
            TraceService traceService,
            ConversationExecutionGuard executionGuard
    ) {
        this.chatModelProvider = chatModelProvider;
        this.streamingChatModelProvider = streamingChatModelProvider;
        this.sessionStore = sessionStore;
        this.chatContextAssembler = chatContextAssembler;
        this.conversationService = conversationService;
        this.knowledgeRetrievalService = knowledgeRetrievalService;
        this.traceService = traceService;
        this.executionGuard = executionGuard;
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 同步接口主要用于快速验证状态化聊天是否已经成立：
     * 会话存在、消息能入库、trace 能落表，然后再返回完整答案。
     */
    public ChatAnswerVO sendSync(ChatSendRequest request) {
        ChatModel chatModel = requireChatModel();
        ConversationDO conversation = conversationService.resolveConversation(request);
        String requestId = RequestIdContext.getRequestId();
        executionGuard.acquire(conversation.getId(), requestId);

        try {
            long startedAt = System.currentTimeMillis();
            Long userMessageId = conversationService.saveUserMessage(conversation.getId(), requestId, request.message());
            List<RagSnippetVO> ragSnippets = retrieveRagSnippets(conversation, request, requestId, userMessageId, null);
            List<ChatMessage> contextMessages = chatContextAssembler.assemble(conversation, request.message(), ragSnippets);
            appendTrace(
                    conversation.getId(),
                    requestId,
                    userMessageId,
                    "USER_MESSAGE_RECEIVED",
                    "RECEIVED",
                    tracePayload("message", request.message()),
                    true,
                    null,
                    null
            );
            appendTrace(
                    conversation.getId(),
                    requestId,
                    userMessageId,
                    "MODEL_REQUEST_STARTED",
                    "MODEL",
                    modelStartPayload("SYNC", ragSnippets.size()),
                    true,
                    null,
                    null
            );

            String answer = chatModel.chat(contextMessages).aiMessage().text();
            Long assistantMessageId = conversationService.saveAssistantMessage(
                    conversation.getId(),
                    requestId,
                    userMessageId,
                    answer,
                    "stop",
                    null
            );
            long elapsedMillis = System.currentTimeMillis() - startedAt;
            appendTrace(
                    conversation.getId(),
                    requestId,
                    assistantMessageId,
                    "FINAL_RESPONSE_COMPLETED",
                    "COMPLETED",
                    tracePayload("answerLength", answer.length()),
                    true,
                    (int) elapsedMillis,
                    null
            );

            return new ChatAnswerVO(
                    requestId,
                    conversation.getId(),
                    answer,
                    "stop",
                    !ragSnippets.isEmpty(),
                    isAgentEnabled(conversation, request),
                    elapsedMillis
            );
        } finally {
            executionGuard.release(conversation.getId(), requestId);
        }
    }

    /**
     * 流式接口仍然保持“两步式”，但 requestId 现在不再只是内存任务号，
     * 它同时也是 trace_event 和 conversation_message 关联的业务主键。
     */
    public ChatRequestAcceptedVO submit(ChatSendRequest request) {
        StreamingChatModel streamingChatModel = requireStreamingChatModel();
        ConversationDO conversation = conversationService.resolveConversation(request);
        ChatStreamSession session = sessionStore.create(conversation.getId());
        executionGuard.acquire(conversation.getId(), session.requestId());

        try {
            Long userMessageId = conversationService.saveUserMessage(conversation.getId(), session.requestId(), request.message());
            session.userMessageId(userMessageId);
            List<RagSnippetVO> ragSnippets = retrieveRagSnippets(
                    conversation,
                    request,
                    session.requestId(),
                    userMessageId,
                    session
            );
            List<ChatMessage> contextMessages = chatContextAssembler.assemble(conversation, request.message(), ragSnippets);

            sessionStore.appendEvent(
                    session,
                    "message_start",
                    new ChatStreamStartedVO(session.requestId(), session.conversationId())
            );
            appendTrace(
                    session,
                    "USER_MESSAGE_RECEIVED",
                    "RECEIVED",
                    userMessageId,
                    tracePayload("message", request.message()),
                    true,
                    null
            );

            executorService.submit(() -> emitThinkingProgress(session));
            executorService.submit(() -> streamAnswer(streamingChatModel, session, contextMessages, ragSnippets.size()));

            return new ChatRequestAcceptedVO(
                    session.requestId(),
                    conversation.getId(),
                    session.status(),
                    session.createdAt()
            );
        } catch (Exception exception) {
            executionGuard.release(conversation.getId(), session.requestId());
            throw exception;
        }
    }

    @PreDestroy
    void destroy() {
        executorService.shutdown();
    }

    /**
     * 这里把流式 token、assistant 消息落库和 trace 事件写入放到同一条执行线上，
     * 这样前端时间线和数据库里的请求级轨迹不会漂移。
     */
    private void streamAnswer(
            StreamingChatModel streamingChatModel,
            ChatStreamSession session,
            List<ChatMessage> messages,
            int ragSnippetCount
    ) {
        session.status("RUNNING");
        long startedAt = System.currentTimeMillis();
        appendTrace(
                session,
                "MODEL_REQUEST_STARTED",
                "MODEL",
                session.userMessageId(),
                modelStartPayload("STREAM", ragSnippetCount),
                true,
                null
        );

        try {
            streamingChatModel.chat(messages, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                session.appendAnswer(partialResponse);
                if (session.markTraceStreamingStarted()) {
                    appendTrace(
                            session,
                            "FINAL_RESPONSE_STREAMING",
                            "STREAM",
                            session.userMessageId(),
                            tracePayload("status", "FIRST_TOKEN"),
                            true,
                            null
                    );
                }
                sessionStore.appendEvent(
                        session,
                        "token",
                        new ChatStreamTokenVO(session.requestId(), partialResponse)
                );
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                try {
                    AiMessage aiMessage = completeResponse.aiMessage();
                    String answer = aiMessage != null && aiMessage.text() != null ? aiMessage.text() : session.answer();
                    Long assistantMessageId = conversationService.saveAssistantMessage(
                            session.conversationId(),
                            session.requestId(),
                            session.userMessageId(),
                            answer,
                            "stop",
                            null
                    );

                    sessionStore.appendEvent(
                            session,
                            "message_end",
                            new ChatStreamCompletedVO(session.requestId(), answer, "stop")
                    );
                    appendTrace(
                            session,
                            "FINAL_RESPONSE_COMPLETED",
                            "COMPLETED",
                            assistantMessageId,
                            tracePayload("answerLength", answer.length()),
                            true,
                            (int) (System.currentTimeMillis() - startedAt)
                    );
                    sessionStore.complete(session);
                } catch (Exception exception) {
                    sessionStore.fail(session, exception.getMessage());
                } finally {
                    executionGuard.release(session.conversationId(), session.requestId());
                }
            }

            @Override
            public void onError(Throwable error) {
                try {
                    String answer = session.answer();
                    Long assistantMessageId = conversationService.saveAssistantMessage(
                            session.conversationId(),
                            session.requestId(),
                            session.userMessageId(),
                            answer,
                            "error",
                            error.getMessage()
                    );
                    appendTrace(
                            session,
                            "ERROR_OCCURRED",
                            "FAILED",
                            assistantMessageId,
                            tracePayload("message", error.getMessage()),
                            false,
                            (int) (System.currentTimeMillis() - startedAt)
                    );
                    sessionStore.fail(session, safeMessage(error));
                } catch (Exception exception) {
                    sessionStore.fail(session, safeMessage(exception));
                } finally {
                    executionGuard.release(session.conversationId(), session.requestId());
                }
            }
        });
        } catch (Exception exception) {
            try {
                Long assistantMessageId = conversationService.saveAssistantMessage(
                        session.conversationId(),
                        session.requestId(),
                        session.userMessageId(),
                        session.answer(),
                        "error",
                        exception.getMessage()
                );
                appendTrace(
                        session,
                        "ERROR_OCCURRED",
                        "FAILED",
                        assistantMessageId,
                        tracePayload("message", safeMessage(exception)),
                        false,
                        (int) (System.currentTimeMillis() - startedAt)
                );
            } finally {
                sessionStore.fail(session, safeMessage(exception));
                executionGuard.release(session.conversationId(), session.requestId());
            }
        }
    }

    /**
     * thinking/progress 只用于缓解推理模型首 token 前的静默期。
     * 这些提示不写 conversation_message，不写 trace_event，也不会参与后续模型上下文。
     */
    private void emitThinkingProgress(ChatStreamSession session) {
        long startedAt = System.currentTimeMillis();
        while (!session.isFinished() && !session.isResponseStreamingStarted()) {
            sessionStore.sendTransientEvent(
                    session,
                    "progress",
                    new ChatStreamProgressVO(
                            session.requestId(),
                            "THINKING",
                            "模型正在思考，请稍候…",
                            System.currentTimeMillis() - startedAt
                    )
            );

            try {
                Thread.sleep(1500L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private TraceEventVO appendTrace(
            ChatStreamSession session,
            String eventType,
            String eventStage,
            Long messageId,
            Object payload,
            boolean successFlag,
            Integer costMillis
    ) {
        return appendTrace(
                session.conversationId(),
                session.requestId(),
                messageId,
                eventType,
                eventStage,
                payload,
                successFlag,
                costMillis,
                session
        );
    }

    private TraceEventVO appendTrace(
            Long conversationId,
            String requestId,
            Long messageId,
            String eventType,
            String eventStage,
            Object payload,
            boolean successFlag,
            Integer costMillis,
            ChatStreamSession session
    ) {
        TraceEventVO traceEvent = traceService.appendTrace(
                conversationId,
                requestId,
                messageId,
                eventType,
                eventStage,
                "APP_CUSTOM",
                payload,
                successFlag,
                costMillis
        );
        if (session != null) {
            sessionStore.appendTraceEvent(session, traceEvent);
        }
        return traceEvent;
    }

    private Map<String, Object> tracePayload(String key, Object value) {
        return Map.of(key, value == null ? "" : value);
    }

    private Map<String, Object> modelStartPayload(String mode, int ragSnippetCount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", mode);
        payload.put("ragSnippetCount", ragSnippetCount);
        return payload;
    }

    private List<RagSnippetVO> retrieveRagSnippets(
            ConversationDO conversation,
            ChatSendRequest request,
            String requestId,
            Long userMessageId,
            ChatStreamSession session
    ) {
        if (!isRagEnabled(conversation, request) || request.knowledgeBaseIds() == null || request.knowledgeBaseIds().isEmpty()) {
            return List.of();
        }

        long startedAt = System.currentTimeMillis();
        Map<String, Object> startPayload = new LinkedHashMap<>();
        startPayload.put("knowledgeBaseIds", request.knowledgeBaseIds());
        startPayload.put("topK", RAG_TOP_K);
        startPayload.put("minScore", RAG_MIN_SCORE);
        appendTrace(
                conversation.getId(),
                requestId,
                userMessageId,
                "RAG_RETRIEVAL_STARTED",
                "RAG",
                startPayload,
                true,
                null,
                session
        );

        List<RagSnippetVO> ragSnippets = knowledgeRetrievalService.retrieveSnippets(
                request.message(),
                request.knowledgeBaseIds(),
                RAG_TOP_K,
                RAG_MIN_SCORE
        );

        Map<String, Object> selectedPayload = new LinkedHashMap<>();
        selectedPayload.put("count", ragSnippets.size());
        selectedPayload.put("segmentRefs", ragSnippets.stream().map(RagSnippetVO::milvusPrimaryKey).toList());
        appendTrace(
                conversation.getId(),
                requestId,
                userMessageId,
                "RAG_SEGMENTS_SELECTED",
                "RAG",
                selectedPayload,
                true,
                null,
                session
        );

        Map<String, Object> finishedPayload = new LinkedHashMap<>();
        finishedPayload.put("count", ragSnippets.size());
        finishedPayload.put("knowledgeBaseIds", request.knowledgeBaseIds());
        appendTrace(
                conversation.getId(),
                requestId,
                userMessageId,
                "RAG_RETRIEVAL_FINISHED",
                "RAG",
                finishedPayload,
                true,
                (int) (System.currentTimeMillis() - startedAt),
                session
        );
        return ragSnippets;
    }

    private boolean isRagEnabled(ConversationDO conversation, ChatSendRequest request) {
        return request.enableRag() != null ? Boolean.TRUE.equals(request.enableRag()) : Boolean.TRUE.equals(conversation.getEnableRag());
    }

    private boolean isAgentEnabled(ConversationDO conversation, ChatSendRequest request) {
        return request.enableAgent() != null ? Boolean.TRUE.equals(request.enableAgent()) : Boolean.TRUE.equals(conversation.getEnableAgent());
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return "未知错误";
        }
        return throwable.getMessage();
    }

    private ChatModel requireChatModel() {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new ChatServiceUnavailableException("ChatModel 未配置，请先设置 OPENAGENT_CHAT_BASE_URL、OPENAGENT_CHAT_API_KEY 和 OPENAGENT_CHAT_MODEL_NAME。");
        }
        return chatModel;
    }

    private StreamingChatModel requireStreamingChatModel() {
        StreamingChatModel streamingChatModel = streamingChatModelProvider.getIfAvailable();
        if (streamingChatModel == null) {
            throw new ChatServiceUnavailableException("StreamingChatModel 未配置，请先设置 OPENAGENT_CHAT_BASE_URL、OPENAGENT_CHAT_API_KEY 和 OPENAGENT_CHAT_MODEL_NAME。");
        }
        return streamingChatModel;
    }
}
