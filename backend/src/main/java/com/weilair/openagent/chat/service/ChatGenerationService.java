package com.weilair.openagent.chat.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import com.weilair.openagent.chat.exception.ChatServiceUnavailableException;
import com.weilair.openagent.chat.model.ChatStreamSession;
import com.weilair.openagent.common.request.RequestIdContext;
import com.weilair.openagent.conversation.model.ConversationDO;
import com.weilair.openagent.conversation.service.ConversationService;
import com.weilair.openagent.knowledge.service.KnowledgeRetrievalService;
import com.weilair.openagent.mcp.service.McpToolRuntimeService;
import com.weilair.openagent.mcp.service.McpToolRuntimeService.McpToolRuntime;
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
    private static final int MAX_AGENT_TOOL_ROUNDS = 8;
    private static final int FALLBACK_STREAM_CHUNK_CODE_POINTS = 24;
    private static final long FALLBACK_STREAM_DELAY_MILLIS = 15L;

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectProvider<StreamingChatModel> streamingChatModelProvider;
    private final ChatStreamSessionStore sessionStore;
    private final ChatContextAssembler chatContextAssembler;
    private final ConversationService conversationService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final McpToolRuntimeService mcpToolRuntimeService;
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
            McpToolRuntimeService mcpToolRuntimeService,
            TraceService traceService,
            ConversationExecutionGuard executionGuard
    ) {
        this.chatModelProvider = chatModelProvider;
        this.streamingChatModelProvider = streamingChatModelProvider;
        this.sessionStore = sessionStore;
        this.chatContextAssembler = chatContextAssembler;
        this.conversationService = conversationService;
        this.knowledgeRetrievalService = knowledgeRetrievalService;
        this.mcpToolRuntimeService = mcpToolRuntimeService;
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
            ChatContextAssembler.ChatContextSnapshot chatContext =
                    chatContextAssembler.assemble(conversation, request.message(), ragSnippets);
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
            ChatRoundResult roundResult = generateSyncAnswer(
                    chatModel,
                    conversation,
                    request,
                    requestId,
                    userMessageId,
                    chatContext,
                    ragSnippets.size(),
                    null
            );
            Long assistantMessageId = conversationService.saveAssistantMessage(
                    conversation.getId(),
                    requestId,
                    userMessageId,
                    roundResult.answer(),
                    "stop",
                    null
            );
            applyChatMemoryUpdate(chatContext, roundResult);
            long elapsedMillis = System.currentTimeMillis() - startedAt;
            appendTrace(
                    conversation.getId(),
                    requestId,
                    assistantMessageId,
                    "FINAL_RESPONSE_COMPLETED",
                    "COMPLETED",
                    tracePayload("answerLength", roundResult.answer().length()),
                    true,
                    (int) elapsedMillis,
                    null
            );

            return new ChatAnswerVO(
                    requestId,
                    conversation.getId(),
                    roundResult.answer(),
                    "stop",
                    !ragSnippets.isEmpty(),
                    roundResult.usedTools(),
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
        ConversationDO conversation = conversationService.resolveConversation(request);
        ChatStreamSession session = sessionStore.create(conversation.getId());
        boolean agentEnabled = isAgentEnabled(conversation, request);
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
            ChatContextAssembler.ChatContextSnapshot chatContext =
                    chatContextAssembler.assemble(conversation, request.message(), ragSnippets);

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
            if (agentEnabled) {
                ChatModel chatModel = requireChatModel();
                executorService.submit(() -> runAgentRoundAndEmit(chatModel, session, conversation, request, chatContext, ragSnippets.size()));
            } else {
                StreamingChatModel streamingChatModel = requireStreamingChatModel();
                executorService.submit(() -> streamAnswer(streamingChatModel, session, chatContext, ragSnippets.size()));
            }

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
            ChatContextAssembler.ChatContextSnapshot chatContext,
            int ragSnippetCount
    ) {
        session.status("RUNNING");
        long startedAt = System.currentTimeMillis();
        appendTrace(
                session,
                "MODEL_REQUEST_STARTED",
                "MODEL",
                session.userMessageId(),
                modelStartPayload("STREAM", ragSnippetCount, false, 0, 1),
                true,
                null
        );

        try {
            streamingChatModel.chat(chatContext.requestMessages(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                session.appendAnswer(partialResponse);
                markResponseStreamingStarted(session);
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
                    emitAnswerTokens(session, answer);
                    Long assistantMessageId = conversationService.saveAssistantMessage(
                            session.conversationId(),
                            session.requestId(),
                            session.userMessageId(),
                            answer,
                            "stop",
                            null
                    );
                    applyChatMemoryUpdate(chatContext, new ChatRoundResult(answer, false, aiMessage, null));

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

    /**
     * 当前 Agent 版先优先保证 “MCP tool 能稳定调用” 这件事成立：
     * - 普通聊天继续走原来的真流式回调
     * - Agent 模式先在后台完成 tool loop，再通过 SSE 返回最终答案
     *
     * 这样做的原因是：tool calling 会引入多轮模型/工具往返，
     * 如果在这一阶段同时追求“真流式 token + 多轮 tool loop”，实现会明显更脆弱。
     * 等后续进入更完整的 Agent 编排阶段，再把这里升级为更细粒度的流式回调。
     */
    private void runAgentRoundAndEmit(
            ChatModel chatModel,
            ChatStreamSession session,
            ConversationDO conversation,
            ChatSendRequest request,
            ChatContextAssembler.ChatContextSnapshot chatContext,
            int ragSnippetCount
    ) {
        session.status("RUNNING");
        long startedAt = System.currentTimeMillis();

        try {
            ChatRoundResult roundResult = generateSyncAnswer(
                    chatModel,
                    conversation,
                    request,
                    session.requestId(),
                    session.userMessageId(),
                    chatContext,
                    ragSnippetCount,
                    session
            );
            session.appendAnswer(roundResult.answer());
            emitAnswerTokens(session, roundResult.answer());
            Long assistantMessageId = conversationService.saveAssistantMessage(
                    session.conversationId(),
                    session.requestId(),
                    session.userMessageId(),
                    roundResult.answer(),
                    "stop",
                    null
            );
            applyChatMemoryUpdate(chatContext, roundResult);

            sessionStore.appendEvent(
                    session,
                    "message_end",
                    new ChatStreamCompletedVO(session.requestId(), roundResult.answer(), "stop")
            );
            appendTrace(
                    session,
                    "FINAL_RESPONSE_COMPLETED",
                    "COMPLETED",
                    assistantMessageId,
                    tracePayload("answerLength", roundResult.answer().length()),
                    true,
                    (int) (System.currentTimeMillis() - startedAt)
            );
            sessionStore.complete(session);
        } catch (Exception exception) {
            try {
                Long assistantMessageId = conversationService.saveAssistantMessage(
                        session.conversationId(),
                        session.requestId(),
                        session.userMessageId(),
                        "",
                        "error",
                        safeMessage(exception)
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
            }
        } finally {
            executionGuard.release(session.conversationId(), session.requestId());
        }
    }

    private ChatRoundResult generateSyncAnswer(
            ChatModel chatModel,
            ConversationDO conversation,
            ChatSendRequest request,
            String requestId,
            Long userMessageId,
            ChatContextAssembler.ChatContextSnapshot chatContext,
            int ragSnippetCount,
            ChatStreamSession session
    ) {
        /**
         * 这里统一承担“同步聊天”和“Agent tool loop”两条路径：
         * 1. 没开 Agent，或没有可用工具时，直接走普通 ChatModel.chat(messages)
         * 2. 有可用工具时，改走 LangChain4j 官方 ChatRequest + toolSpecifications
         *
         * 这样做的目的，是把 Tool Calling 明确收敛到 LangChain4j 官方抽象上，
         * 避免项目自己再发明一套“模型请求 + 工具执行”的协议层。
         */
        boolean agentEnabled = isAgentEnabled(conversation, request);

        try (McpToolRuntime toolRuntime = openToolRuntime(conversation, request, requestId, userMessageId, session)) {
            if (!agentEnabled || !toolRuntime.hasTools()) {
                appendTrace(
                        conversation.getId(),
                        requestId,
                        userMessageId,
                        "MODEL_REQUEST_STARTED",
                        "MODEL",
                        modelStartPayload("SYNC", ragSnippetCount, agentEnabled, 0, 1),
                        true,
                        null,
                        session
                );

                ChatResponse response = chatModel.chat(chatContext.requestMessages());
                AiMessage aiMessage = response.aiMessage();
                return new ChatRoundResult(resolveAnswerText(aiMessage), false, aiMessage, null);
            }

            List<ChatMessage> messages = new ArrayList<>(chatContext.requestMessages());
            List<ChatMessage> turnMessages = new ArrayList<>();
            turnMessages.add(chatContext.userMessage());
            boolean usedTools = false;

            for (int round = 1; round <= MAX_AGENT_TOOL_ROUNDS; round++) {
                appendTrace(
                        conversation.getId(),
                        requestId,
                        userMessageId,
                        "MODEL_REQUEST_STARTED",
                        "MODEL",
                        modelStartPayload("SYNC", ragSnippetCount, true, toolRuntime.toolCount(), round),
                        true,
                        null,
                        session
                );

                ChatResponse response = chatModel.chat(buildToolChatRequest(messages, toolRuntime));
                AiMessage aiMessage = response.aiMessage();
                if (aiMessage != null && aiMessage.hasToolExecutionRequests()) {
                    usedTools = true;
                    messages.add(aiMessage);
                    turnMessages.add(aiMessage);
                    List<ToolExecutionResultMessage> toolResults = executeToolRequests(
                            toolRuntime,
                            aiMessage.toolExecutionRequests(),
                            conversation,
                            requestId,
                            userMessageId,
                            round,
                            session
                    );
                    messages.addAll(toolResults);
                    turnMessages.addAll(toolResults);
                    continue;
                }

                return new ChatRoundResult(
                        resolveAnswerText(aiMessage),
                        usedTools,
                        aiMessage,
                        usedTools ? buildFinalMemoryMessages(chatContext, turnMessages, aiMessage) : null
                );
            }
        }

        throw new IllegalStateException("Agent 工具调用轮次超过限制，请检查模型输出或工具配置。");
    }

    private McpToolRuntime openToolRuntime(
            ConversationDO conversation,
            ChatSendRequest request,
            String requestId,
            Long userMessageId,
            ChatStreamSession session
    ) {
        /**
         * Tool runtime 的职责是把“平台配置层的 MCP Server / Tool 快照”
         * 转成“当前这一轮聊天真正可挂到模型上的 Tool 集合”。
         *
         * 当前策略先按“所有已启用且健康的工具都可用”处理；
         * 会话级绑定 `conversation_mcp_binding` 留到下一阶段再接入。
         */
        if (!isAgentEnabled(conversation, request)) {
            return McpToolRuntime.empty();
        }

        try {
            McpToolRuntime runtime = mcpToolRuntimeService.openRuntime(conversation.getId(), request.message());
            if (runtime.hasTools()) {
                appendTrace(
                        conversation.getId(),
                        requestId,
                        userMessageId,
                        "AGENT_TOOLS_ATTACHED",
                        "TOOL",
                        agentToolsPayload(runtime.toolCount(), runtime.toolNames()),
                        true,
                        null,
                        session
                );
            } else {
                appendTrace(
                        conversation.getId(),
                        requestId,
                        userMessageId,
                        "AGENT_TOOLS_UNAVAILABLE",
                        "TOOL",
                        tracePayload("reason", "NO_ENABLED_TOOLS"),
                        true,
                        null,
                        session
                );
            }
            return runtime;
        } catch (Exception exception) {
            appendTrace(
                    conversation.getId(),
                    requestId,
                    userMessageId,
                    "AGENT_TOOLS_UNAVAILABLE",
                    "TOOL",
                    Map.of(
                            "reason", "LOAD_FAILED",
                            "message", safeMessage(exception)
                    ),
                    false,
                    null,
                    session
            );
            return McpToolRuntime.empty();
        }
    }

    private List<ToolExecutionResultMessage> executeToolRequests(
            McpToolRuntime toolRuntime,
            List<ToolExecutionRequest> toolExecutionRequests,
            ConversationDO conversation,
            String requestId,
            Long userMessageId,
            int modelRound,
            ChatStreamSession session
    ) {
        /**
         * LangChain4j 在这里把模型输出的 tool call 表达为 ToolExecutionRequest；
         * 我们负责逐个执行，并把结果重新包装成 ToolExecutionResultMessage，
         * 再交回模型进入下一轮推理。
         */
        List<ToolExecutionResultMessage> resultMessages = new ArrayList<>();

        for (ToolExecutionRequest toolExecutionRequest : toolExecutionRequests) {
            if (session != null) {
                sessionStore.sendTransientEvent(
                        session,
                        "progress",
                        new ChatStreamProgressVO(
                                requestId,
                                "CALLING_TOOL",
                                "正在调用工具 " + toolExecutionRequest.name() + " ...",
                                0L
                        )
                );
            }

            appendTrace(
                    conversation.getId(),
                    requestId,
                    userMessageId,
                    "TOOL_EXECUTION_REQUESTED",
                    "TOOL",
                    toolRequestPayload(toolExecutionRequest, modelRound),
                    true,
                    null,
                    session
            );

            long startedAt = System.currentTimeMillis();
            ToolExecutionResultMessage resultMessage = executeSingleToolRequest(
                    toolRuntime,
                    toolExecutionRequest,
                    conversation,
                    requestId
            );

            appendTrace(
                    conversation.getId(),
                    requestId,
                    userMessageId,
                    "TOOL_EXECUTION_COMPLETED",
                    "TOOL",
                    toolResultPayload(resultMessage, modelRound),
                    !Boolean.TRUE.equals(resultMessage.isError()),
                    (int) (System.currentTimeMillis() - startedAt),
                    session
            );

            resultMessages.add(resultMessage);
        }

        return resultMessages;
    }

    private ToolExecutionResultMessage executeSingleToolRequest(
            McpToolRuntime toolRuntime,
            ToolExecutionRequest toolExecutionRequest,
            ConversationDO conversation,
            String requestId
    ) {
        /**
         * 真正的工具执行完全委托给 LangChain4j 官方 ToolExecutor。
         * 这里自己保留的只是运行时上下文补充和错误兜底，
         * 目的是让失败结果也能回填给模型，而不是直接打断整轮 Agent 流程。
         */
        try {
            var toolExecutor = toolRuntime.toolExecutor(toolExecutionRequest.name());
            if (toolExecutor == null) {
                return ToolExecutionResultMessage.builder()
                        .id(toolExecutionRequest.id())
                        .toolName(toolExecutionRequest.name())
                        .text("Tool executor not found for " + toolExecutionRequest.name())
                        .isError(Boolean.TRUE)
                        .build();
            }

            var result = toolExecutor.executeWithContext(
                    toolExecutionRequest,
                    buildInvocationContext(conversation.getId(), requestId)
            );
            String resultText = resolveToolResultText(result);
            return ToolExecutionResultMessage.builder()
                    .id(toolExecutionRequest.id())
                    .toolName(toolExecutionRequest.name())
                    .text(resultText)
                    .isError(result.isError())
                    .build();
        } catch (Exception exception) {
            return ToolExecutionResultMessage.builder()
                    .id(toolExecutionRequest.id())
                    .toolName(toolExecutionRequest.name())
                    .text(safeMessage(exception))
                    .isError(Boolean.TRUE)
                    .build();
        }
    }

    private ChatRequest buildToolChatRequest(List<ChatMessage> messages, McpToolRuntime toolRuntime) {
        /**
         * 只有进入 Agent/tool loop 时，才需要把 `toolSpecifications`
         * 挂到 ChatRequest 上，让模型明确知道当前这轮可调用哪些工具。
         */
        return ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(toolRuntime.toolSpecifications())
                .build();
    }

    private InvocationContext buildInvocationContext(Long conversationId, String requestId) {
        return InvocationContext.builder()
                .invocationId(UUID.randomUUID())
                .interfaceName("ChatGenerationService")
                .methodName("agentToolRound")
                .chatMemoryId(conversationId)
                .methodArguments(List.of(requestId))
                .invocationParameters(InvocationParameters.from(Map.of(
                        "conversationId", conversationId,
                        "requestId", requestId
                )))
                .timestampNow()
                .build();
    }

    private String resolveAnswerText(AiMessage aiMessage) {
        if (aiMessage == null || aiMessage.text() == null || aiMessage.text().isBlank()) {
            return "";
        }
        return aiMessage.text();
    }

    private String resolveToolResultText(dev.langchain4j.service.tool.ToolExecutionResult result) {
        if (result == null) {
            return "";
        }
        if (result.resultText() != null && !result.resultText().isBlank()) {
            return result.resultText();
        }
        return result.result() == null ? "" : String.valueOf(result.result());
    }

    /**
     * 只要最终答案已经开始向前端输出，就不应再继续发 THINKING。
     * 这里统一负责把“首个正文片段已开始输出”这个状态打到 session 上，
     * 同时补一条 FINAL_RESPONSE_STREAMING trace。
     */
    private void markResponseStreamingStarted(ChatStreamSession session) {
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
    }

    /**
     * Agent 路径当前仍是同步 tool loop，部分模型/网关也可能只在 onCompleteResponse 才返回正文。
     * 为了避免 SSE 长时间只有 THINKING 没有正文，这里在“没有真实 partial token”时，
     * 用最终答案做一层后端补偿式 token 拆分，让前端仍然按 token/message_end 统一渲染。
     */
    private void emitAnswerTokens(ChatStreamSession session, String answer) {
        if (answer == null || answer.isBlank() || session.isResponseStreamingStarted()) {
            return;
        }

        markResponseStreamingStarted(session);
        for (String chunk : splitIntoStreamingChunks(answer)) {
            sessionStore.appendEvent(session, "token", new ChatStreamTokenVO(session.requestId(), chunk));
            try {
                Thread.sleep(FALLBACK_STREAM_DELAY_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private List<String> splitIntoStreamingChunks(String answer) {
        int codePointCount = answer.codePointCount(0, answer.length());
        List<String> chunks = new ArrayList<>();
        for (int index = 0; index < codePointCount; index += FALLBACK_STREAM_CHUNK_CODE_POINTS) {
            int beginIndex = answer.offsetByCodePoints(0, index);
            int endIndex = answer.offsetByCodePoints(
                    0,
                    Math.min(codePointCount, index + FALLBACK_STREAM_CHUNK_CODE_POINTS)
            );
            chunks.add(answer.substring(beginIndex, endIndex));
        }
        return chunks;
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

    private Map<String, Object> modelStartPayload(
            String mode,
            int ragSnippetCount,
            boolean agentEnabled,
            int toolCount,
            int modelRound
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", mode);
        payload.put("ragSnippetCount", ragSnippetCount);
        payload.put("agentEnabled", agentEnabled);
        payload.put("toolCount", toolCount);
        payload.put("modelRound", modelRound);
        return payload;
    }

    private Map<String, Object> agentToolsPayload(int toolCount, List<String> toolNames) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolCount", toolCount);
        payload.put("toolNames", toolNames);
        return payload;
    }

    private Map<String, Object> toolRequestPayload(ToolExecutionRequest toolExecutionRequest, int modelRound) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolCallId", toolExecutionRequest.id());
        payload.put("toolName", toolExecutionRequest.name());
        payload.put("modelRound", modelRound);
        payload.put("arguments", shorten(toolExecutionRequest.arguments(), 1000));
        return payload;
    }

    private Map<String, Object> toolResultPayload(ToolExecutionResultMessage resultMessage, int modelRound) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolCallId", resultMessage.id());
        payload.put("toolName", resultMessage.toolName());
        payload.put("modelRound", modelRound);
        payload.put("isError", Boolean.TRUE.equals(resultMessage.isError()));
        payload.put("resultPreview", shorten(resultMessage.text(), 1000));
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

    private String shorten(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return "未知错误";
        }
        return throwable.getMessage();
    }

    /**
     * memory 只在本轮成功完成后回写。
     * 这样可以保证失败请求不会把“只有 user、没有 assistant”的半轮对话污染到后续上下文里。
     */
    private void applyChatMemoryUpdate(
            ChatContextAssembler.ChatContextSnapshot chatContext,
            ChatRoundResult roundResult
    ) {
        if (chatContext == null || chatContext.chatMemory() == null || roundResult == null) {
            return;
        }

        if (roundResult.finalMemoryMessages() != null) {
            chatContext.chatMemory().set(roundResult.finalMemoryMessages());
            return;
        }

        chatContext.chatMemory().add(chatContext.userMessage());
        chatContext.chatMemory().add(resolveAssistantMessage(roundResult.aiMessage(), roundResult.answer()));
    }

    private List<ChatMessage> buildFinalMemoryMessages(
            ChatContextAssembler.ChatContextSnapshot chatContext,
            List<ChatMessage> turnMessages,
            AiMessage finalAiMessage
    ) {
        if (chatContext == null || chatContext.chatMemory() == null) {
            return null;
        }

        List<ChatMessage> finalMessages = new ArrayList<>(chatContext.baseMemoryMessages());
        finalMessages.addAll(turnMessages);
        finalMessages.add(resolveAssistantMessage(finalAiMessage, resolveAnswerText(finalAiMessage)));
        return finalMessages;
    }

    private AiMessage resolveAssistantMessage(AiMessage aiMessage, String fallbackAnswer) {
        if (aiMessage != null) {
            return aiMessage;
        }
        return AiMessage.from(fallbackAnswer == null ? "" : fallbackAnswer);
    }

    private record ChatRoundResult(
            String answer,
            boolean usedTools,
            AiMessage aiMessage,
            List<ChatMessage> finalMemoryMessages
    ) {
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
