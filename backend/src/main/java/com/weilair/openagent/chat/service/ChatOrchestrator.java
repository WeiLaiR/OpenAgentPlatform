package com.weilair.openagent.chat.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import dev.langchain4j.data.message.UserMessage;
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
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;
import com.weilair.openagent.chat.prompt.PromptAssembly;
import com.weilair.openagent.chat.exception.ChatServiceUnavailableException;
import com.weilair.openagent.chat.model.ChatStreamSession;
import com.weilair.openagent.chat.service.AgentAiServiceFactory.StreamingAgentAssistant;
import com.weilair.openagent.chat.service.AgentAiServiceFactory.SyncAgentAssistant;
import com.weilair.openagent.chat.service.RagRuntimeResolver.RagAugmentationResult;
import com.weilair.openagent.chat.service.RagRuntimeResolver.RagRuntime;
import com.weilair.openagent.chat.service.ToolRuntimeResolver.ResolvedToolRuntime;
import com.weilair.openagent.common.request.RequestIdContext;
import com.weilair.openagent.conversation.model.ConversationDO;
import com.weilair.openagent.conversation.service.ConversationService;
import com.weilair.openagent.conversation.service.ConversationTitleService;
import com.weilair.openagent.trace.service.ChatModelObservationContextHolder;
import com.weilair.openagent.web.dto.ChatSendRequest;
import com.weilair.openagent.web.vo.ChatAnswerVO;
import com.weilair.openagent.web.vo.ChatRequestAcceptedVO;
import com.weilair.openagent.web.vo.RagSnippetVO;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ChatOrchestrator {
    /**
     * 这是统一聊天编排骨架的第二轮落点：
     * 1. 统一承接 sync / stream 两种入口
     * 2. 在真正执行前先通过 `ModeResolver` 收口运行规格
     * 3. 再按固定顺序推进 memory / RAG / tools / model
     * 4. 把输出、trace、RAG runtime、Tool runtime 从主类里继续往外抽
     *
     * 当前仍然保留了部分低层执行细节在这一类中，
     * 例如流式 token 拆分和手工 tool loop，
     * 但主类现在更明确地只负责“生命周期编排”，而不再直接承担各类运行时装配细节。
     */

    private static final int MAX_AGENT_TOOL_ROUNDS = 8;
    private static final int FALLBACK_STREAM_CHUNK_CODE_POINTS = 24;
    private static final long FALLBACK_STREAM_DELAY_MILLIS = 15L;

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectProvider<StreamingChatModel> streamingChatModelProvider;
    private final ChatStreamSessionStore sessionStore;
    private final ChatContextAssembler chatContextAssembler;
    private final ConversationService conversationService;
    private final ModeResolver modeResolver;
    private final AgentAiServiceFactory agentAiServiceFactory;
    private final RagRuntimeResolver ragRuntimeResolver;
    private final ToolRuntimeResolver toolRuntimeResolver;
    private final TracePublisher tracePublisher;
    private final ConversationTitleService conversationTitleService;
    private final ChatModelObservationContextHolder chatModelObservationContextHolder;
    private final ConversationExecutionGuard executionGuard;
    private final ExecutorService executorService;

    public ChatOrchestrator(
            ObjectProvider<ChatModel> chatModelProvider,
            ObjectProvider<StreamingChatModel> streamingChatModelProvider,
            ChatStreamSessionStore sessionStore,
            ChatContextAssembler chatContextAssembler,
            ConversationService conversationService,
            ModeResolver modeResolver,
            AgentAiServiceFactory agentAiServiceFactory,
            RagRuntimeResolver ragRuntimeResolver,
            ToolRuntimeResolver toolRuntimeResolver,
            TracePublisher tracePublisher,
            ConversationTitleService conversationTitleService,
            ChatModelObservationContextHolder chatModelObservationContextHolder,
            ConversationExecutionGuard executionGuard
    ) {
        this.chatModelProvider = chatModelProvider;
        this.streamingChatModelProvider = streamingChatModelProvider;
        this.sessionStore = sessionStore;
        this.chatContextAssembler = chatContextAssembler;
        this.conversationService = conversationService;
        this.modeResolver = modeResolver;
        this.agentAiServiceFactory = agentAiServiceFactory;
        this.ragRuntimeResolver = ragRuntimeResolver;
        this.toolRuntimeResolver = toolRuntimeResolver;
        this.tracePublisher = tracePublisher;
        this.conversationTitleService = conversationTitleService;
        this.chatModelObservationContextHolder = chatModelObservationContextHolder;
        this.executionGuard = executionGuard;
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 同步接口主要用于快速验证状态化聊天是否已经成立：
     * 会话存在、消息能入库、trace 能落表，然后再返回完整答案。
     */
    public ChatAnswerVO sendSync(ChatSendRequest request) {
        ConversationDO conversation = conversationService.resolveConversation(request);
        var settingsSnapshot = conversationService.resolveSettingsSnapshot(conversation);
        ChatExecutionSpec executionSpec = modeResolver.resolve(settingsSnapshot, request, false);
        String requestId = RequestIdContext.getRequestId();
        ChatOutputPort outputPort = new BufferedChatOutputPort(requestId, conversation.getId());
        executionGuard.acquire(conversation.getId(), requestId);

        try {
            long startedAt = System.currentTimeMillis();
            Long userMessageId = conversationService.saveUserMessage(conversation.getId(), outputPort.requestId(), request.message());
            outputPort.userMessageId(userMessageId);
            tracePublisher.append(
                    outputPort,
                    userMessageId,
                    "USER_MESSAGE_RECEIVED",
                    "RECEIVED",
                    tracePayload("message", request.message()),
                    true,
                    null
            );
            traceResolvedExecutionSpec(outputPort, request, executionSpec);
            ChatAnswerResult answerResult;
            if (executionSpec.agentEnabled()) {
                ChatModel chatModel = requireChatModel();
                answerResult = generateAgentAnswer(chatModel, conversation, executionSpec, request, outputPort);
            } else {
                ChatModel chatModel = requireChatModel();
                ChatContextAssembler.ChatContextSnapshot baseChatContext =
                        chatContextAssembler.assemble(conversation, executionSpec, request.message(), List.of());
                ResolvedRagContext resolvedRagContext = resolveRagContext(baseChatContext, executionSpec, outputPort);
                ChatContextAssembler.ChatContextSnapshot chatContext = resolvedRagContext.chatContext();
                tracePromptAssemblyResolved(outputPort, executionSpec, chatContext);
                ChatRoundResult roundResult = generateSyncAnswer(
                        chatModel,
                        conversation,
                        executionSpec,
                        request,
                        chatContext,
                        resolvedRagContext.ragSnippets().size(),
                        outputPort
                );
                outputPort.replaceAnswer(roundResult.answer());
                outputPort.emitMessageEnd(outputPort.answer(), "stop");
                Long assistantMessageId = conversationService.saveAssistantMessage(
                        outputPort.conversationId(),
                        outputPort.requestId(),
                        outputPort.userMessageId(),
                        outputPort.answer(),
                        outputPort.finishReason(),
                        null
                );
                applyChatMemoryUpdate(chatContext, roundResult);
                answerResult = new ChatAnswerResult(
                        assistantMessageId,
                        resolvedRagContext.ragSnippets(),
                        roundResult.usedTools()
                );
            }

            long elapsedMillis = System.currentTimeMillis() - startedAt;
            scheduleFirstRoundConversationTitle(request, conversation.getId(), outputPort.answer());
            tracePublisher.append(
                    outputPort,
                    answerResult.assistantMessageId(),
                    "FINAL_RESPONSE_COMPLETED",
                    "COMPLETED",
                    tracePayload("answerLength", outputPort.answer().length()),
                    true,
                    (int) elapsedMillis
            );
            outputPort.complete();

            return new ChatAnswerVO(
                    outputPort.requestId(),
                    conversation.getId(),
                    outputPort.answer(),
                    outputPort.finishReason(),
                    !answerResult.ragSnippets().isEmpty(),
                    answerResult.usedTools(),
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
        var settingsSnapshot = conversationService.resolveSettingsSnapshot(conversation);
        ChatExecutionSpec executionSpec = modeResolver.resolve(settingsSnapshot, request, true);
        ChatStreamSession session = sessionStore.create(conversation.getId());
        ChatOutputPort outputPort = new StreamingChatOutputPort(sessionStore, session);
        executionGuard.acquire(conversation.getId(), outputPort.requestId());

        try {
            Long userMessageId = conversationService.saveUserMessage(conversation.getId(), outputPort.requestId(), request.message());
            outputPort.userMessageId(userMessageId);
            outputPort.emitStarted();
            tracePublisher.append(
                    outputPort,
                    userMessageId,
                    "USER_MESSAGE_RECEIVED",
                    "RECEIVED",
                    tracePayload("message", request.message()),
                    true,
                    null
            );
            traceResolvedExecutionSpec(outputPort, request, executionSpec);
            executorService.submit(() -> emitThinkingProgress(outputPort));
            if (executionSpec.agentEnabled()) {
                StreamingChatModel streamingChatModel = requireStreamingChatModel();
                executorService.submit(() -> streamAgentAnswer(
                        streamingChatModel,
                        outputPort,
                        conversation,
                        executionSpec,
                        request
                ));
            } else {
                ChatContextAssembler.ChatContextSnapshot baseChatContext =
                        chatContextAssembler.assemble(conversation, executionSpec, request.message(), List.of());
                ResolvedRagContext resolvedRagContext = resolveRagContext(baseChatContext, executionSpec, outputPort);
                ChatContextAssembler.ChatContextSnapshot chatContext = resolvedRagContext.chatContext();
                tracePromptAssemblyResolved(outputPort, executionSpec, chatContext);
                StreamingChatModel streamingChatModel = requireStreamingChatModel();
                executorService.submit(() -> streamAnswer(
                        streamingChatModel,
                        outputPort,
                        chatContext,
                        resolvedRagContext.ragSnippets().size(),
                        request
                ));
            }

            return new ChatRequestAcceptedVO(
                    outputPort.requestId(),
                    conversation.getId(),
                    outputPort.status(),
                    session.createdAt()
            );
        } catch (Exception exception) {
            executionGuard.release(conversation.getId(), outputPort.requestId());
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
            ChatOutputPort outputPort,
            ChatContextAssembler.ChatContextSnapshot chatContext,
            int ragSnippetCount,
            ChatSendRequest request
    ) {
        outputPort.markRunning();
        long startedAt = System.currentTimeMillis();
        tracePublisher.append(
                outputPort,
                outputPort.userMessageId(),
                "MODEL_REQUEST_STARTED",
                "MODEL",
                modelStartPayload("STREAM", ragSnippetCount, false, 0, 1),
                true,
                null
        );

        try {
            withChatModelObservation(outputPort, "STREAM", () -> streamingChatModel.chat(
                    chatContext.requestMessages(),
                    new StreamingChatResponseHandler() {
                        @Override
                        public void onPartialResponse(String partialResponse) {
                            outputPort.appendAnswer(partialResponse);
                            markResponseStreamingStarted(outputPort);
                            outputPort.emitToken(partialResponse);
                        }

                        @Override
                        public void onCompleteResponse(ChatResponse completeResponse) {
                            try {
                                AiMessage aiMessage = completeResponse.aiMessage();
                                String answer = aiMessage != null && aiMessage.text() != null
                                        ? aiMessage.text()
                                        : outputPort.answer();
                                emitAnswerTokens(outputPort, answer);
                                Long assistantMessageId = conversationService.saveAssistantMessage(
                                        outputPort.conversationId(),
                                        outputPort.requestId(),
                                        outputPort.userMessageId(),
                                        answer,
                                        "stop",
                                        null
                                );
                                applyChatMemoryUpdate(chatContext, new ChatRoundResult(answer, false, aiMessage, null));
                                scheduleFirstRoundConversationTitle(request, outputPort.conversationId(), answer);
                                outputPort.emitMessageEnd(answer, "stop");
                                tracePublisher.append(
                                        outputPort,
                                        assistantMessageId,
                                        "FINAL_RESPONSE_COMPLETED",
                                        "COMPLETED",
                                        tracePayload("answerLength", answer.length()),
                                        true,
                                        (int) (System.currentTimeMillis() - startedAt)
                                );
                                outputPort.complete();
                            } catch (Exception exception) {
                                outputPort.fail(safeMessage(exception));
                            } finally {
                                executionGuard.release(outputPort.conversationId(), outputPort.requestId());
                            }
                        }

                        @Override
                        public void onError(Throwable error) {
                            try {
                                String answer = outputPort.answer();
                                Long assistantMessageId = conversationService.saveAssistantMessage(
                                        outputPort.conversationId(),
                                        outputPort.requestId(),
                                        outputPort.userMessageId(),
                                        answer,
                                        "error",
                                        error.getMessage()
                                );
                                tracePublisher.append(
                                        outputPort,
                                        assistantMessageId,
                                        "ERROR_OCCURRED",
                                        "FAILED",
                                        tracePayload("message", error.getMessage()),
                                        false,
                                        (int) (System.currentTimeMillis() - startedAt)
                                );
                                outputPort.fail(safeMessage(error));
                            } catch (Exception exception) {
                                outputPort.fail(safeMessage(exception));
                            } finally {
                                executionGuard.release(outputPort.conversationId(), outputPort.requestId());
                            }
                        }
                    }
            ));
        } catch (Exception exception) {
            try {
                Long assistantMessageId = conversationService.saveAssistantMessage(
                        outputPort.conversationId(),
                        outputPort.requestId(),
                        outputPort.userMessageId(),
                        outputPort.answer(),
                        "error",
                        exception.getMessage()
                );
                tracePublisher.append(
                        outputPort,
                        assistantMessageId,
                        "ERROR_OCCURRED",
                        "FAILED",
                        tracePayload("message", safeMessage(exception)),
                        false,
                        (int) (System.currentTimeMillis() - startedAt)
                );
            } finally {
                outputPort.fail(safeMessage(exception));
                executionGuard.release(outputPort.conversationId(), outputPort.requestId());
            }
        }
    }

    /**
     * thinking/progress 只用于缓解推理模型首 token 前的静默期。
     * 这些提示不写 conversation_message，不写 trace_event，也不会参与后续模型上下文。
     */
    private void emitThinkingProgress(ChatOutputPort outputPort) {
        long startedAt = System.currentTimeMillis();
        while (!outputPort.isFinished() && !outputPort.isResponseStreamingStarted()) {
            outputPort.emitProgress(
                    "THINKING",
                    "模型正在思考，请稍候…",
                    System.currentTimeMillis() - startedAt
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
    private ChatAnswerResult generateAgentAnswer(
            ChatModel chatModel,
            ConversationDO conversation,
            ChatExecutionSpec executionSpec,
            ChatSendRequest request,
            ChatOutputPort outputPort
    ) {
        RagRuntime ragRuntime = ragRuntimeResolver.resolve(executionSpec);
        try (ResolvedToolRuntime toolRuntime = openToolRuntime(conversation, executionSpec, request, outputPort)) {
            traceRagStarted(outputPort, ragRuntime);
            PromptAssembly promptAssembly = agentAiServiceFactory.buildPromptAssembly(toolRuntime);
            tracePromptAssemblyResolved(
                    outputPort,
                    promptAssembly,
                    promptAssemblyPayload(
                            promptAssembly,
                            "AGENT_AI_SERVICE",
                            executionSpec.modeCode(),
                            Map.of(
                                    "systemMessageCount", promptAssembly.blocks().size(),
                                    "toolCount", toolRuntime.toolCount(),
                                    "toolRuntimeStatus", toolRuntime.status().name()
                            )
                    )
            );
            traceModelRequestStarted(outputPort, "SYNC", ragRuntime.enabled(), toolRuntime);

            SyncAgentAssistant assistant = agentAiServiceFactory.createSyncAssistant(
                    chatModel,
                    executionSpec.memoryEnabled(),
                    ragRuntime,
                    toolRuntime,
                    beforeToolExecution -> traceBeforeToolExecution(outputPort, beforeToolExecution),
                    toolExecution -> traceToolExecution(outputPort, toolExecution)
            );
            Result<String> result = withChatModelObservation(
                    outputPort,
                    "SYNC",
                    () -> assistant.answer(conversation.getId(), request.message())
            );
            List<RagSnippetVO> ragSnippets = traceRagCompleted(outputPort, ragRuntime, ragRuntimeResolver.toRagSnippets(result.sources()));

            String answer = result.content() == null ? "" : result.content();
            String finishReason = resolveFinishReason(result.finishReason());
            outputPort.replaceAnswer(answer);
            outputPort.emitMessageEnd(answer, finishReason);

            Long assistantMessageId = conversationService.saveAssistantMessage(
                    outputPort.conversationId(),
                    outputPort.requestId(),
                    outputPort.userMessageId(),
                    answer,
                    finishReason,
                    null
            );

            return new ChatAnswerResult(
                    assistantMessageId,
                    ragSnippets,
                    result.toolExecutions() != null && !result.toolExecutions().isEmpty()
            );
        }
    }

    private void streamAgentAnswer(
            StreamingChatModel streamingChatModel,
            ChatOutputPort outputPort,
            ConversationDO conversation,
            ChatExecutionSpec executionSpec,
            ChatSendRequest request
    ) {
        RagRuntime ragRuntime = ragRuntimeResolver.resolve(executionSpec);
        ResolvedToolRuntime toolRuntime = openToolRuntime(conversation, executionSpec, request, outputPort);
        outputPort.markRunning();
        traceRagStarted(outputPort, ragRuntime);
        PromptAssembly promptAssembly = agentAiServiceFactory.buildPromptAssembly(toolRuntime);
        tracePromptAssemblyResolved(
                outputPort,
                promptAssembly,
                promptAssemblyPayload(
                        promptAssembly,
                        "AGENT_AI_SERVICE",
                        executionSpec.modeCode(),
                        Map.of(
                                "systemMessageCount", promptAssembly.blocks().size(),
                                "toolCount", toolRuntime.toolCount(),
                                "toolRuntimeStatus", toolRuntime.status().name()
                        )
                )
        );
        traceModelRequestStarted(outputPort, "STREAM", ragRuntime.enabled(), toolRuntime);

        AtomicReference<List<RagSnippetVO>> ragSnippetsRef = new AtomicReference<>(List.of());

        try {
            StreamingAgentAssistant assistant = agentAiServiceFactory.createStreamingAssistant(
                    streamingChatModel,
                    executionSpec.memoryEnabled(),
                    ragRuntime,
                    toolRuntime,
                    beforeToolExecution -> traceBeforeToolExecution(outputPort, beforeToolExecution),
                    toolExecution -> traceToolExecution(outputPort, toolExecution)
            );

            withChatModelObservation(outputPort, "STREAM", () -> assistant.answer(conversation.getId(), request.message())
                    .onPartialResponse(partialResponse -> {
                        outputPort.appendAnswer(partialResponse);
                        markResponseStreamingStarted(outputPort);
                        outputPort.emitToken(partialResponse);
                    })
                    .onRetrieved(contents -> ragSnippetsRef.set(
                            traceRagCompleted(outputPort, ragRuntime, ragRuntimeResolver.toRagSnippets(contents))
                    ))
                    .onCompleteResponse(completeResponse -> {
                        try {
                            if (ragRuntime.enabled() && ragSnippetsRef.get().isEmpty()) {
                                ragSnippetsRef.set(traceRagCompleted(outputPort, ragRuntime, List.of()));
                            }

                            AiMessage aiMessage = completeResponse.aiMessage();
                            String answer = resolveAnswerText(aiMessage);
                            if (answer.isBlank()) {
                                answer = outputPort.answer();
                            }
                            outputPort.replaceAnswer(answer);
                            emitAnswerTokens(outputPort, answer);
                            String finishReason = resolveFinishReason(completeResponse.finishReason());
                            Long assistantMessageId = conversationService.saveAssistantMessage(
                                    outputPort.conversationId(),
                                    outputPort.requestId(),
                                    outputPort.userMessageId(),
                                    answer,
                                    finishReason,
                                    null
                            );
                            scheduleFirstRoundConversationTitle(request, outputPort.conversationId(), answer);
                            outputPort.emitMessageEnd(answer, finishReason);
                            tracePublisher.append(
                                    outputPort,
                                    assistantMessageId,
                                    "FINAL_RESPONSE_COMPLETED",
                                    "COMPLETED",
                                    tracePayload("answerLength", answer.length()),
                                    true,
                                    null
                            );
                            outputPort.complete();
                        } catch (Exception exception) {
                            outputPort.fail(safeMessage(exception));
                        } finally {
                            toolRuntime.close();
                            executionGuard.release(outputPort.conversationId(), outputPort.requestId());
                        }
                    })
                    .onError(error -> {
                        try {
                            Long assistantMessageId = conversationService.saveAssistantMessage(
                                    outputPort.conversationId(),
                                    outputPort.requestId(),
                                    outputPort.userMessageId(),
                                    outputPort.answer(),
                                    "error",
                                    safeMessage(error)
                            );
                            tracePublisher.append(
                                    outputPort,
                                    assistantMessageId,
                                    "ERROR_OCCURRED",
                                    "FAILED",
                                    tracePayload("message", safeMessage(error)),
                                    false,
                                    null
                            );
                            outputPort.fail(safeMessage(error));
                        } finally {
                            toolRuntime.close();
                            executionGuard.release(outputPort.conversationId(), outputPort.requestId());
                        }
                    })
                    .start());
        } catch (Exception exception) {
            try {
                Long assistantMessageId = conversationService.saveAssistantMessage(
                        outputPort.conversationId(),
                        outputPort.requestId(),
                        outputPort.userMessageId(),
                        outputPort.answer(),
                        "error",
                        safeMessage(exception)
                );
                tracePublisher.append(
                        outputPort,
                        assistantMessageId,
                        "ERROR_OCCURRED",
                        "FAILED",
                        tracePayload("message", safeMessage(exception)),
                        false,
                        null
                );
            } finally {
                toolRuntime.close();
                outputPort.fail(safeMessage(exception));
                executionGuard.release(outputPort.conversationId(), outputPort.requestId());
            }
        }
    }

    private ChatRoundResult generateSyncAnswer(
            ChatModel chatModel,
            ConversationDO conversation,
            ChatExecutionSpec executionSpec,
            ChatSendRequest request,
            ChatContextAssembler.ChatContextSnapshot chatContext,
            int ragSnippetCount,
            ChatOutputPort outputPort
    ) {
        /**
         * 这里统一承担“同步聊天”和“Agent tool loop”两条路径：
         * 1. 没开 Agent，或没有可用工具时，直接走普通 ChatModel.chat(messages)
         * 2. 有可用工具时，改走 LangChain4j 官方 ChatRequest + toolSpecifications
         *
         * 这样做的目的，是把 Tool Calling 明确收敛到 LangChain4j 官方抽象上，
         * 避免项目自己再发明一套“模型请求 + 工具执行”的协议层。
         */
        try (ResolvedToolRuntime toolRuntime = openToolRuntime(conversation, executionSpec, request, outputPort)) {
            if (!executionSpec.agentEnabled() || !toolRuntime.hasTools()) {
                tracePublisher.append(
                        outputPort,
                        outputPort.userMessageId(),
                        "MODEL_REQUEST_STARTED",
                        "MODEL",
                        modelStartPayload("SYNC", ragSnippetCount, executionSpec.agentEnabled(), 0, 1),
                        true,
                        null
                );

                ChatResponse response = withChatModelObservation(
                        outputPort,
                        "SYNC",
                        () -> chatModel.chat(chatContext.requestMessages())
                );
                AiMessage aiMessage = response.aiMessage();
                return new ChatRoundResult(resolveAnswerText(aiMessage), false, aiMessage, null);
            }

            List<ChatMessage> messages = new ArrayList<>(chatContext.requestMessages());
            List<ChatMessage> turnMessages = new ArrayList<>();
            turnMessages.add(chatContext.userMessage());
            boolean usedTools = false;

            for (int round = 1; round <= MAX_AGENT_TOOL_ROUNDS; round++) {
                tracePublisher.append(
                        outputPort,
                        outputPort.userMessageId(),
                        "MODEL_REQUEST_STARTED",
                        "MODEL",
                        modelStartPayload("SYNC", ragSnippetCount, true, toolRuntime.toolCount(), round),
                        true,
                        null
                );

                ChatResponse response = withChatModelObservation(
                        outputPort,
                        "SYNC",
                        () -> chatModel.chat(buildToolChatRequest(messages, toolRuntime))
                );
                AiMessage aiMessage = response.aiMessage();
                if (aiMessage != null && aiMessage.hasToolExecutionRequests()) {
                    usedTools = true;
                    messages.add(aiMessage);
                    turnMessages.add(aiMessage);
                    List<ToolExecutionResultMessage> toolResults = executeToolRequests(
                            toolRuntime,
                            aiMessage.toolExecutionRequests(),
                            conversation,
                            outputPort,
                            round
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

    private ResolvedToolRuntime openToolRuntime(
            ConversationDO conversation,
            ChatExecutionSpec executionSpec,
            ChatSendRequest request,
            ChatOutputPort outputPort
    ) {
        /**
         * Tool runtime 的职责是把“平台配置层的 MCP Server / Tool 快照”
         * 转成“当前这一轮聊天真正可挂到模型上的 Tool 集合”。
         *
         * 当前已经统一按 `ChatExecutionSpec` 中的最终生效 MCP Server 集合装配，
         * 这里不再自行解释会话级绑定或请求覆盖优先级。
         */
        ResolvedToolRuntime runtime = toolRuntimeResolver.openRuntime(conversation.getId(), executionSpec, request.message());
        if (!executionSpec.agentEnabled()) {
            return runtime;
        }

        if (runtime.hasTools()) {
            tracePublisher.append(
                    outputPort,
                    outputPort.userMessageId(),
                    "AGENT_TOOLS_ATTACHED",
                    "TOOL",
                    agentToolsPayload(runtime.toolCount(), runtime.toolNames()),
                    true,
                    null
            );
            return runtime;
        }

        if (runtime.status() == ToolRuntimeResolver.ToolRuntimeStatus.LOAD_FAILED) {
            tracePublisher.append(
                    outputPort,
                    outputPort.userMessageId(),
                    "AGENT_TOOLS_UNAVAILABLE",
                    "TOOL",
                    Map.of(
                            "reason", runtime.reason(),
                            "message", runtime.failureMessage()
                    ),
                    false,
                    null
            );
            return runtime;
        }

        tracePublisher.append(
                outputPort,
                outputPort.userMessageId(),
                "AGENT_TOOLS_UNAVAILABLE",
                "TOOL",
                tracePayload("reason", runtime.reason()),
                true,
                null
        );
        return runtime;
    }

    private List<ToolExecutionResultMessage> executeToolRequests(
            ResolvedToolRuntime toolRuntime,
            List<ToolExecutionRequest> toolExecutionRequests,
            ConversationDO conversation,
            ChatOutputPort outputPort,
            int modelRound
    ) {
        /**
         * LangChain4j 在这里把模型输出的 tool call 表达为 ToolExecutionRequest；
         * 我们负责逐个执行，并把结果重新包装成 ToolExecutionResultMessage，
         * 再交回模型进入下一轮推理。
         */
        List<ToolExecutionResultMessage> resultMessages = new ArrayList<>();

        for (ToolExecutionRequest toolExecutionRequest : toolExecutionRequests) {
            outputPort.emitProgress("CALLING_TOOL", "正在调用工具 " + toolExecutionRequest.name() + " ...", 0L);

            tracePublisher.append(
                    outputPort,
                    outputPort.userMessageId(),
                    "TOOL_EXECUTION_REQUESTED",
                    "TOOL",
                    toolRequestPayload(toolExecutionRequest, modelRound),
                    true,
                    null
            );

            long startedAt = System.currentTimeMillis();
            ToolExecutionResultMessage resultMessage = executeSingleToolRequest(
                    toolRuntime,
                    toolExecutionRequest,
                    conversation,
                    outputPort.requestId()
            );

            tracePublisher.append(
                    outputPort,
                    outputPort.userMessageId(),
                    "TOOL_EXECUTION_COMPLETED",
                    "TOOL",
                    toolResultPayload(resultMessage, modelRound),
                    !Boolean.TRUE.equals(resultMessage.isError()),
                    (int) (System.currentTimeMillis() - startedAt)
            );

            resultMessages.add(resultMessage);
        }

        return resultMessages;
    }

    private ToolExecutionResultMessage executeSingleToolRequest(
            ResolvedToolRuntime toolRuntime,
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

    private ChatRequest buildToolChatRequest(List<ChatMessage> messages, ResolvedToolRuntime toolRuntime) {
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
                .interfaceName("ChatOrchestrator")
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
     * 这里统一负责把“首个正文片段已开始输出”这个状态打到 output port 上，
     * 同时补一条 FINAL_RESPONSE_STREAMING trace。
     */
    private void markResponseStreamingStarted(ChatOutputPort outputPort) {
        if (outputPort.markResponseStreamingStarted()) {
            tracePublisher.append(
                    outputPort,
                    outputPort.userMessageId(),
                    "FINAL_RESPONSE_STREAMING",
                    "STREAM",
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
    private void emitAnswerTokens(ChatOutputPort outputPort, String answer) {
        if (answer == null || answer.isBlank() || outputPort.isResponseStreamingStarted()) {
            return;
        }

        outputPort.replaceAnswer(answer);
        markResponseStreamingStarted(outputPort);
        for (String chunk : splitIntoStreamingChunks(answer)) {
            outputPort.emitToken(chunk);
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

    private void tracePromptAssemblyResolved(
            ChatOutputPort outputPort,
            ChatExecutionSpec executionSpec,
            ChatContextAssembler.ChatContextSnapshot chatContext
    ) {
        tracePromptAssemblyResolved(
                outputPort,
                chatContext.promptAssembly(),
                promptAssemblyPayload(
                        chatContext.promptAssembly(),
                        "CHAT_CONTEXT_ASSEMBLER",
                        executionSpec.modeCode(),
                        Map.of(
                                "systemMessageCount", chatContext.systemMessages().size(),
                                "historyMessageCount", chatContext.baseMemoryMessages().size(),
                                "requestMessageCount", chatContext.requestMessages().size(),
                                "requestStructure", "system -> history -> user"
                        )
                )
        );
    }

    private void tracePromptAssemblyResolved(
            ChatOutputPort outputPort,
            PromptAssembly promptAssembly,
            Map<String, Object> payload
    ) {
        if (promptAssembly == null || promptAssembly.blocks().isEmpty()) {
            return;
        }
        tracePublisher.append(
                outputPort,
                outputPort.userMessageId(),
                "PROMPT_ASSEMBLY_RESOLVED",
                "PROMPT",
                payload,
                true,
                null
        );
    }

    private Map<String, Object> promptAssemblyPayload(
            PromptAssembly promptAssembly,
            String promptSource,
            String modeCode,
            Map<String, Object> additionalFields
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("promptSource", promptSource);
        payload.put("modeCode", modeCode);
        PromptAssembly.PromptAssemblySummary summary = promptAssembly.summary();
        payload.put("promptKeys", summary.promptKeys());
        payload.put("blockCount", summary.blockCount());
        payload.put("variableSummary", summary.variableSummary());
        if (additionalFields != null && !additionalFields.isEmpty()) {
            payload.putAll(additionalFields);
        }
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

    private void traceModelRequestStarted(
            ChatOutputPort outputPort,
            String mode,
            boolean ragEnabled,
            ResolvedToolRuntime toolRuntime
    ) {
        tracePublisher.append(
                outputPort,
                outputPort.userMessageId(),
                "MODEL_REQUEST_STARTED",
                "MODEL",
                modelStartPayload(mode, ragEnabled ? -1 : 0, true, toolRuntime.toolCount(), 1),
                true,
                null
        );
    }

    private void traceResolvedExecutionSpec(
            ChatOutputPort outputPort,
            ChatSendRequest request,
            ChatExecutionSpec executionSpec
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("modeCode", executionSpec.modeCode());
        payload.put("streaming", executionSpec.streaming());
        payload.put("ragEnabled", executionSpec.ragEnabled());
        payload.put("agentEnabled", executionSpec.agentEnabled());
        payload.put("memoryEnabled", executionSpec.memoryEnabled());
        payload.put("knowledgeBaseIds", executionSpec.knowledgeBaseIds());
        payload.put("mcpServerIds", executionSpec.mcpServerIds());
        payload.put("requestOverrides", Map.of(
                "enableRag", request.enableRag() != null,
                "enableAgent", request.enableAgent() != null,
                "memoryEnabled", request.memoryEnabled() != null,
                "knowledgeBaseIds", request.knowledgeBaseIds() != null,
                "mcpServerIds", request.mcpServerIds() != null
        ));
        tracePublisher.append(
                outputPort,
                outputPort.userMessageId(),
                "CHAT_EXECUTION_SPEC_RESOLVED",
                "CONFIG",
                payload,
                true,
                null
        );
    }

    private void traceRagStarted(ChatOutputPort outputPort, RagRuntime ragRuntime) {
        if (ragRuntime == null || !ragRuntime.enabled()) {
            return;
        }

        Map<String, Object> startPayload = new LinkedHashMap<>();
        startPayload.put("knowledgeBaseIds", ragRuntime.knowledgeBaseIds());
        startPayload.put("topK", ragRuntime.topK());
        startPayload.put("minScore", ragRuntime.minScore());
        tracePublisher.append(
                outputPort,
                outputPort.userMessageId(),
                "RAG_RETRIEVAL_STARTED",
                "RAG",
                startPayload,
                true,
                null
        );
    }

    private List<RagSnippetVO> traceRagCompleted(
            ChatOutputPort outputPort,
            RagRuntime ragRuntime,
            List<RagSnippetVO> ragSnippets
    ) {
        if (ragRuntime == null || !ragRuntime.enabled()) {
            return List.of();
        }

        List<RagSnippetVO> safeSnippets = ragSnippets == null ? List.of() : List.copyOf(ragSnippets);
        Map<String, Object> selectedPayload = new LinkedHashMap<>();
        selectedPayload.put("count", safeSnippets.size());
        selectedPayload.put("segmentRefs", safeSnippets.stream().map(RagSnippetVO::milvusPrimaryKey).toList());
        tracePublisher.append(
                outputPort,
                outputPort.userMessageId(),
                "RAG_SEGMENTS_SELECTED",
                "RAG",
                selectedPayload,
                true,
                null
        );

        Map<String, Object> finishedPayload = new LinkedHashMap<>();
        finishedPayload.put("count", safeSnippets.size());
        finishedPayload.put("knowledgeBaseIds", ragRuntime.knowledgeBaseIds());
        tracePublisher.append(
                outputPort,
                outputPort.userMessageId(),
                "RAG_RETRIEVAL_FINISHED",
                "RAG",
                finishedPayload,
                true,
                null
        );
        return safeSnippets;
    }

    private void traceBeforeToolExecution(ChatOutputPort outputPort, BeforeToolExecution beforeToolExecution) {
        if (beforeToolExecution == null || beforeToolExecution.request() == null) {
            return;
        }

        ToolExecutionRequest toolExecutionRequest = beforeToolExecution.request();
        outputPort.emitProgress("CALLING_TOOL", "正在调用工具 " + toolExecutionRequest.name() + " ...", 0L);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolCallId", toolExecutionRequest.id());
        payload.put("toolName", toolExecutionRequest.name());
        payload.put("arguments", shorten(toolExecutionRequest.arguments(), 1000));
        tracePublisher.append(
                outputPort,
                outputPort.userMessageId(),
                "TOOL_EXECUTION_REQUESTED",
                "TOOL",
                payload,
                true,
                null
        );
    }

    private void traceToolExecution(ChatOutputPort outputPort, ToolExecution toolExecution) {
        if (toolExecution == null || toolExecution.request() == null) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolCallId", toolExecution.request().id());
        payload.put("toolName", toolExecution.request().name());
        payload.put("isError", toolExecution.hasFailed());
        payload.put("resultPreview", shorten(toolExecution.result(), 1000));
        tracePublisher.append(
                outputPort,
                outputPort.userMessageId(),
                "TOOL_EXECUTION_COMPLETED",
                "TOOL",
                payload,
                !toolExecution.hasFailed(),
                null
        );
    }

    private String resolveFinishReason(FinishReason finishReason) {
        if (finishReason == null) {
            return "stop";
        }
        return finishReason.name().toLowerCase();
    }

    /**
     * 首轮标题生成只对“草稿态 -> 正式会话”这一跳生效。
     *
     * 因此这里直接使用“请求入参里没有 conversationId”作为判定条件，
     * 避免在后续轮次反复覆盖标题，也避免把失败轮次写进标题生成链路。
     */
    private void scheduleFirstRoundConversationTitle(
            ChatSendRequest request,
            Long conversationId,
            String assistantAnswer
    ) {
        if (request.conversationId() != null || !StringUtils.hasText(assistantAnswer)) {
            return;
        }
        conversationTitleService.generateFirstRoundTitleAsync(conversationId, request.message(), assistantAnswer);
    }

    private ResolvedRagContext resolveRagContext(
            ChatContextAssembler.ChatContextSnapshot baseChatContext,
            ChatExecutionSpec executionSpec,
            ChatOutputPort outputPort
    ) {
        RagRuntime ragRuntime = ragRuntimeResolver.resolve(executionSpec);
        if (!ragRuntime.enabled()) {
            return new ResolvedRagContext(baseChatContext, List.of());
        }

        long startedAt = System.currentTimeMillis();
        Map<String, Object> startPayload = new LinkedHashMap<>();
        startPayload.put("knowledgeBaseIds", ragRuntime.knowledgeBaseIds());
        startPayload.put("topK", ragRuntime.topK());
        startPayload.put("minScore", ragRuntime.minScore());
        tracePublisher.append(
                outputPort,
                outputPort.userMessageId(),
                "RAG_RETRIEVAL_STARTED",
                "RAG",
                startPayload,
                true,
                null
        );

        RagAugmentationResult ragAugmentationResult = ragRuntimeResolver.augment(
                ragRuntime,
                outputPort.conversationId(),
                outputPort.requestId(),
                baseChatContext.userMessage(),
                baseChatContext.baseMemoryMessages()
        );
        List<RagSnippetVO> ragSnippets = ragAugmentationResult.ragSnippets();

        Map<String, Object> selectedPayload = new LinkedHashMap<>();
        selectedPayload.put("count", ragSnippets.size());
        selectedPayload.put("segmentRefs", ragSnippets.stream().map(RagSnippetVO::milvusPrimaryKey).toList());
        tracePublisher.append(
                outputPort,
                outputPort.userMessageId(),
                "RAG_SEGMENTS_SELECTED",
                "RAG",
                selectedPayload,
                true,
                null
        );

        Map<String, Object> finishedPayload = new LinkedHashMap<>();
        finishedPayload.put("count", ragSnippets.size());
        finishedPayload.put("knowledgeBaseIds", ragRuntime.knowledgeBaseIds());
        tracePublisher.append(
                outputPort,
                outputPort.userMessageId(),
                "RAG_RETRIEVAL_FINISHED",
                "RAG",
                finishedPayload,
                true,
                (int) (System.currentTimeMillis() - startedAt)
        );
        return new ResolvedRagContext(
                chatContextAssembler.rebuildWithResolvedRequest(
                        baseChatContext,
                        ragAugmentationResult.requestUserMessage(),
                        ragSnippets
                ),
                ragSnippets
        );
    }

    private String shorten(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private <T> T withChatModelObservation(
            ChatOutputPort outputPort,
            String executionMode,
            Supplier<T> action
    ) {
        try (ChatModelObservationContextHolder.Scope ignored =
                     chatModelObservationContextHolder.open(outputPort, executionMode)) {
            return action.get();
        }
    }

    private void withChatModelObservation(
            ChatOutputPort outputPort,
            String executionMode,
            Runnable action
    ) {
        try (ChatModelObservationContextHolder.Scope ignored =
                     chatModelObservationContextHolder.open(outputPort, executionMode)) {
            action.run();
        }
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

    private record ChatAnswerResult(
            Long assistantMessageId,
            List<RagSnippetVO> ragSnippets,
            boolean usedTools
    ) {
    }

    private record ResolvedRagContext(
            ChatContextAssembler.ChatContextSnapshot chatContext,
            List<RagSnippetVO> ragSnippets
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
