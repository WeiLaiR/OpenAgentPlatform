package com.weilair.openagent.chat.service;

import java.util.function.Consumer;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import dev.langchain4j.service.tool.ToolExecution;
import com.weilair.openagent.chat.service.RagRuntimeResolver.RagRuntime;
import com.weilair.openagent.chat.service.ToolRuntimeResolver.ResolvedToolRuntime;
import com.weilair.openagent.memory.service.ConversationMemoryService;
import org.springframework.stereotype.Component;

@Component
public class AgentAiServiceFactory {
    /**
     * 这一层专门负责把 Agent 路径回切到 LangChain4j 官方 AI Services。
     *
     * 当前选择“按请求构建轻量 AI Service 代理”，而不是把所有模式都一次性重写成统一接口，原因有两点：
     * 1. 这一轮目标只聚焦 Agent 官方化，非 Agent 链路先保持稳定；
     * 2. ToolProvider、RetrievalAugmentor、ChatMemory 开关都带有明显的请求级动态性，逐次构建更清晰。
     *
     * 这里已经接上：
     * - `ToolProvider`
     * - `RetrievalAugmentor`
     * - `ChatMemoryProvider + @MemoryId`
     * - `TokenStream`
     *
     * 当前模型级 observability 已通过 `ChatModelListener` 并列接入；
     * 编排层这里保留的仍然只是平台侧 before/after tool 事件桥接。
     */
    private static final int MAX_AGENT_TOOL_ROUNDS = 8;

    private final ConversationMemoryService conversationMemoryService;

    public AgentAiServiceFactory(ConversationMemoryService conversationMemoryService) {
        this.conversationMemoryService = conversationMemoryService;
    }

    public SyncAgentAssistant createSyncAssistant(
            ChatModel chatModel,
            boolean memoryEnabled,
            RagRuntime ragRuntime,
            ResolvedToolRuntime toolRuntime,
            Consumer<BeforeToolExecution> beforeToolExecution,
            Consumer<ToolExecution> afterToolExecution
    ) {
        AiServices<SyncAgentAssistant> builder = AiServices.builder(SyncAgentAssistant.class)
                .chatModel(chatModel);
        return configureCommon(builder, memoryEnabled, ragRuntime, toolRuntime, beforeToolExecution, afterToolExecution).build();
    }

    public StreamingAgentAssistant createStreamingAssistant(
            StreamingChatModel streamingChatModel,
            boolean memoryEnabled,
            RagRuntime ragRuntime,
            ResolvedToolRuntime toolRuntime,
            Consumer<BeforeToolExecution> beforeToolExecution,
            Consumer<ToolExecution> afterToolExecution
    ) {
        AiServices<StreamingAgentAssistant> builder = AiServices.builder(StreamingAgentAssistant.class)
                .streamingChatModel(streamingChatModel);
        return configureCommon(builder, memoryEnabled, ragRuntime, toolRuntime, beforeToolExecution, afterToolExecution).build();
    }

    private <T> AiServices<T> configureCommon(
            AiServices<T> builder,
            boolean memoryEnabled,
            RagRuntime ragRuntime,
            ResolvedToolRuntime toolRuntime,
            Consumer<BeforeToolExecution> beforeToolExecution,
            Consumer<ToolExecution> afterToolExecution
    ) {
        /**
         * memory、RAG、tools 三块都优先走 LangChain4j 官方 builder 配置，
         * 这样 Agent 请求的“上下文增强 + 工具调用 + 记忆回写”都会回到同一条官方生命周期里。
         */
        builder.chatMemoryProvider(memoryId -> memoryEnabled
                ? conversationMemoryService.getOrCreateMemory(resolveConversationId(memoryId))
                : MessageWindowChatMemory.builder()
                        .id("stateless-" + resolveConversationId(memoryId))
                        .maxMessages(32)
                        .build()
        );

        if (ragRuntime != null && ragRuntime.enabled()) {
            builder.retrievalAugmentor(ragRuntime.retrievalAugmentor());
            builder.storeRetrievedContentInChatMemory(false);
        }

        if (toolRuntime != null && toolRuntime.hasTools()) {
            builder.toolProvider(toolRuntime.toolProvider());
        }

        builder.maxSequentialToolsInvocations(MAX_AGENT_TOOL_ROUNDS);
        builder.toolArgumentsErrorHandler((error, context) -> ToolErrorHandlerResult.text(safeMessage(error)));
        builder.toolExecutionErrorHandler((error, context) -> ToolErrorHandlerResult.text(safeMessage(error)));

        if (beforeToolExecution != null) {
            builder.beforeToolExecution(beforeToolExecution);
        }
        if (afterToolExecution != null) {
            builder.afterToolExecution(afterToolExecution);
        }

        return builder;
    }

    private Long resolveConversationId(Object memoryId) {
        if (memoryId instanceof Number number) {
            return number.longValue();
        }
        if (memoryId == null) {
            throw new IllegalArgumentException("Agent AI Service 需要非空 conversationId 作为 memoryId");
        }
        return Long.valueOf(String.valueOf(memoryId));
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return "未知工具错误";
        }
        return throwable.getMessage();
    }

    public interface SyncAgentAssistant {
        @UserMessage("{{message}}")
        Result<String> answer(@MemoryId Long conversationId, @V("message") String message);
    }

    public interface StreamingAgentAssistant {
        @UserMessage("{{message}}")
        TokenStream answer(@MemoryId Long conversationId, @V("message") String message);
    }
}
