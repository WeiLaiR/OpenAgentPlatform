package com.weilair.openagent.chat.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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
import com.weilair.openagent.chat.prompt.PromptAssembly;
import com.weilair.openagent.chat.prompt.PromptTemplateCatalog;
import com.weilair.openagent.chat.prompt.PromptTemplateKey;
import com.weilair.openagent.chat.service.RagRuntimeResolver.RagRuntime;
import com.weilair.openagent.chat.service.ToolRuntimeResolver.ResolvedToolRuntime;
import com.weilair.openagent.memory.service.ConversationMemoryService;
import org.springframework.stereotype.Component;

@Component
public class AgentAiServiceFactory {
    /**
     * 这一层专门负责把 Agent 路径保持在 LangChain4j 官方 AI Services 主线内。
     *
     * 当前这一步新增的重点不是再发明一套 Agent Prompt 抽象，
     * 而是把 system prompt 也正式纳入官方 `AiServices` builder 生命周期：
     * 1. Prompt 模板继续来自统一 `PromptTemplateCatalog`
     * 2. 注入入口使用官方 `AiServices.systemMessage(String)`
     * 3. memory / RAG / tools 仍继续走官方 builder 配置
     */
    private static final int MAX_AGENT_TOOL_ROUNDS = 8;

    private final ConversationMemoryService conversationMemoryService;
    private final PromptTemplateCatalog promptTemplateCatalog;

    public AgentAiServiceFactory(
            ConversationMemoryService conversationMemoryService,
            PromptTemplateCatalog promptTemplateCatalog
    ) {
        this.conversationMemoryService = conversationMemoryService;
        this.promptTemplateCatalog = promptTemplateCatalog;
    }

    public SyncAgentAssistant createSyncAssistant(
            ChatModel chatModel,
            boolean memoryEnabled,
            RagRuntime ragRuntime,
            ResolvedToolRuntime toolRuntime,
            Consumer<BeforeToolExecution> beforeToolExecution,
            Consumer<ToolExecution> afterToolExecution
    ) {
        AgentPromptSpec promptSpec = buildAgentPromptSpec(toolRuntime);
        AiServices<SyncAgentAssistant> builder = AiServices.builder(SyncAgentAssistant.class)
                .chatModel(chatModel);
        return configureCommon(
                builder,
                promptSpec,
                memoryEnabled,
                ragRuntime,
                toolRuntime,
                beforeToolExecution,
                afterToolExecution
        ).build();
    }

    public StreamingAgentAssistant createStreamingAssistant(
            StreamingChatModel streamingChatModel,
            boolean memoryEnabled,
            RagRuntime ragRuntime,
            ResolvedToolRuntime toolRuntime,
            Consumer<BeforeToolExecution> beforeToolExecution,
            Consumer<ToolExecution> afterToolExecution
    ) {
        AgentPromptSpec promptSpec = buildAgentPromptSpec(toolRuntime);
        AiServices<StreamingAgentAssistant> builder = AiServices.builder(StreamingAgentAssistant.class)
                .streamingChatModel(streamingChatModel);
        return configureCommon(
                builder,
                promptSpec,
                memoryEnabled,
                ragRuntime,
                toolRuntime,
                beforeToolExecution,
                afterToolExecution
        ).build();
    }

    public PromptAssembly buildPromptAssembly(ResolvedToolRuntime toolRuntime) {
        return buildAgentPromptSpec(toolRuntime).promptAssembly();
    }

    private <T> AiServices<T> configureCommon(
            AiServices<T> builder,
            AgentPromptSpec promptSpec,
            boolean memoryEnabled,
            RagRuntime ragRuntime,
            ResolvedToolRuntime toolRuntime,
            Consumer<BeforeToolExecution> beforeToolExecution,
            Consumer<ToolExecution> afterToolExecution
    ) {
        /**
         * Agent 路径当前按“每次请求构建一个轻量 AI Service 代理”的方式工作，
         * 因此这里直接在 builder 上挂当前轮 system prompt，
         * 就能把 Prompt 注入边界稳定收口到官方 AI Services builder。
         */
        builder.systemMessage(promptSpec.systemPrompt());

        /**
         * memory / RAG / tools 三块继续优先走 LangChain4j 官方 builder 配置，
         * 避免项目自己重新包装一层“半官方”的 Agent 生命周期。
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

    private AgentPromptSpec buildAgentPromptSpec(ResolvedToolRuntime toolRuntime) {
        PromptAssembly promptAssembly = buildAgentPromptAssembly(toolRuntime);
        String systemPrompt = promptAssembly.blocks().stream()
                .map(PromptAssembly.PromptBlock::content)
                .collect(Collectors.joining("\n\n"));
        return new AgentPromptSpec(promptAssembly, systemPrompt);
    }

    private PromptAssembly buildAgentPromptAssembly(ResolvedToolRuntime toolRuntime) {
        List<PromptAssembly.PromptBlock> blocks = new ArrayList<>();

        blocks.add(new PromptAssembly.PromptBlock(
                PromptTemplateKey.SYSTEM_BASE,
                promptTemplateCatalog.render(PromptTemplateKey.SYSTEM_BASE),
                Map.of()
        ));
        blocks.add(new PromptAssembly.PromptBlock(
                PromptTemplateKey.AGENT_TOOL_POLICY,
                promptTemplateCatalog.render(PromptTemplateKey.AGENT_TOOL_POLICY),
                Map.of()
        ));
        blocks.add(new PromptAssembly.PromptBlock(
                PromptTemplateKey.MCP_TOOL_SELECTION,
                buildMcpToolSelectionPrompt(toolRuntime),
                Map.of(
                        "toolCount", toolRuntime == null ? 0 : toolRuntime.toolCount(),
                        "toolNames", toolRuntime == null ? List.of() : toolRuntime.toolNames(),
                        "toolRuntimeStatus", toolRuntime == null ? "UNKNOWN" : toolRuntime.status().name()
                )
        ));
        blocks.add(new PromptAssembly.PromptBlock(
                PromptTemplateKey.ANSWER_FORMAT,
                promptTemplateCatalog.render(PromptTemplateKey.ANSWER_FORMAT),
                Map.of()
        ));

        return new PromptAssembly(blocks);
    }

    private String buildMcpToolSelectionPrompt(ResolvedToolRuntime toolRuntime) {
        StringBuilder prompt = new StringBuilder(promptTemplateCatalog.render(PromptTemplateKey.MCP_TOOL_SELECTION));
        prompt.append("\n\n");

        if (toolRuntime == null) {
            prompt.append("当前会话未提供工具运行时信息。");
            return prompt.toString();
        }

        prompt.append("当前工具运行时状态: ").append(toolRuntime.status().name()).append("\n");
        if (toolRuntime.reason() != null && !toolRuntime.reason().isBlank()) {
            prompt.append("当前不可用原因: ").append(toolRuntime.reason()).append("\n");
        }
        if (toolRuntime.failureMessage() != null && !toolRuntime.failureMessage().isBlank()) {
            prompt.append("工具加载失败信息: ").append(toolRuntime.failureMessage()).append("\n");
        }

        if (!toolRuntime.hasTools()) {
            prompt.append("当前会话没有可用 MCP 工具，不要编造工具调用。");
            return prompt.toString();
        }

        prompt.append("当前会话可用 MCP 工具列表:\n");
        for (String toolName : toolRuntime.toolNames()) {
            prompt.append("- ").append(toolName).append("\n");
        }
        prompt.append("只能在这些工具里做选择；如果它们都不适合，就直接基于已有上下文回答。");
        return prompt.toString().trim();
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

    private record AgentPromptSpec(
            PromptAssembly promptAssembly,
            String systemPrompt
    ) {
    }
}
