package com.weilair.openagent.chat.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import com.weilair.openagent.chat.prompt.PromptAssembly;
import com.weilair.openagent.chat.prompt.PromptTemplateCatalog;
import com.weilair.openagent.chat.prompt.PromptTemplateKey;
import com.weilair.openagent.conversation.model.ConversationDO;
import com.weilair.openagent.memory.service.ConversationMemoryService;
import com.weilair.openagent.web.vo.RagSnippetVO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ChatContextAssembler {

    private final ConversationMemoryService conversationMemoryService;
    private final PromptTemplateCatalog promptTemplateCatalog;

    public ChatContextAssembler(
            ConversationMemoryService conversationMemoryService,
            PromptTemplateCatalog promptTemplateCatalog
    ) {
        this.conversationMemoryService = conversationMemoryService;
        this.promptTemplateCatalog = promptTemplateCatalog;
    }

    /**
     * 这里正式把非 Agent 主链路里的 Prompt 装配收口到同一个入口：
     * 1. history 继续留在 ChatMemory / conversation_message，负责“已发生过什么”；
     * 2. PromptAssembly 负责“这一轮额外给模型的稳定指令块”；
     * 3. requestUserMessage 负责“这一轮真正发给模型的用户消息”，允许在 RAG 增强后替换；
     * 4. system prompt 仍然只做瞬时注入，不直接写回 memory。
     */
    public ChatContextSnapshot assemble(
            ConversationDO conversation,
            ChatExecutionSpec executionSpec,
            String currentUserMessage,
            List<RagSnippetVO> ragSnippets
    ) {
        ChatMemory chatMemory = null;
        List<ChatMessage> baseMemoryMessages = List.of();

        boolean memoryEnabled = executionSpec != null
                ? executionSpec.memoryEnabled()
                : Boolean.TRUE.equals(conversation.getMemoryEnabled());

        if (memoryEnabled) {
            chatMemory = conversationMemoryService.getOrCreateMemory(conversation.getId());
            baseMemoryMessages = List.copyOf(chatMemory.messages());
        }

        UserMessage userMessage = UserMessage.from(currentUserMessage);
        return rebuildSnapshot(
                chatMemory,
                baseMemoryMessages,
                userMessage,
                userMessage,
                ragSnippets
        );
    }

    /**
     * 兼容当前仍有部分调用点只按 conversation 默认配置判断 memory 开关。
     */
    public ChatContextSnapshot assemble(
            ConversationDO conversation,
            String currentUserMessage,
            List<RagSnippetVO> ragSnippets
    ) {
        return assemble(conversation, null, currentUserMessage, ragSnippets);
    }

    /**
     * RAG 检索发生在首轮 assemble 之后，因此这里单独提供一次“基于增强后用户消息重建最终请求”的入口。
     * 这样 orchestrator 不需要知道 Prompt 模板细节，只负责把 RAG 运行结果回填给 assembler。
     */
    public ChatContextSnapshot rebuildWithResolvedRequest(
            ChatContextSnapshot baseSnapshot,
            UserMessage requestUserMessage,
            List<RagSnippetVO> ragSnippets
    ) {
        if (baseSnapshot == null) {
            throw new IllegalArgumentException("baseSnapshot 不能为空");
        }
        return rebuildSnapshot(
                baseSnapshot.chatMemory(),
                baseSnapshot.baseMemoryMessages(),
                baseSnapshot.userMessage(),
                requestUserMessage,
                ragSnippets
        );
    }

    private ChatContextSnapshot rebuildSnapshot(
            ChatMemory chatMemory,
            List<ChatMessage> baseMemoryMessages,
            UserMessage userMessage,
            UserMessage requestUserMessage,
            List<RagSnippetVO> ragSnippets
    ) {
        PromptAssembly promptAssembly = buildPromptAssembly(ragSnippets);
        List<SystemMessage> systemMessages = promptAssembly.toSystemMessages();

        /**
         * 这里显式采用 system -> history -> user 的请求结构：
         * 1. 让 system prompt 在当前轮保持最高优先级；
         * 2. 避免把临时 Prompt 块写回 memory，污染后续轮次；
         * 3. 继续兼容当前“memory 只存 user / assistant”的实现边界。
         */
        List<ChatMessage> requestMessages = new ArrayList<>();
        requestMessages.addAll(systemMessages);
        requestMessages.addAll(baseMemoryMessages);
        requestMessages.add(requestUserMessage);

        return new ChatContextSnapshot(
                chatMemory,
                baseMemoryMessages,
                userMessage,
                requestUserMessage,
                systemMessages,
                promptAssembly,
                List.copyOf(requestMessages)
        );
    }

    private PromptAssembly buildPromptAssembly(List<RagSnippetVO> ragSnippets) {
        List<PromptAssembly.PromptBlock> blocks = new ArrayList<>();

        blocks.add(new PromptAssembly.PromptBlock(
                PromptTemplateKey.SYSTEM_BASE,
                promptTemplateCatalog.render(PromptTemplateKey.SYSTEM_BASE),
                Map.of()
        ));
        blocks.add(new PromptAssembly.PromptBlock(
                PromptTemplateKey.ANSWER_FORMAT,
                promptTemplateCatalog.render(PromptTemplateKey.ANSWER_FORMAT),
                Map.of()
        ));

        if (ragSnippets != null && !ragSnippets.isEmpty()) {
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("userMessage", "");
            variables.put("contents", buildRagContextSections(ragSnippets));
            variables.put("snippetCount", ragSnippets.size());
            blocks.add(new PromptAssembly.PromptBlock(
                    PromptTemplateKey.RAG_CONTEXT,
                    promptTemplateCatalog.render(PromptTemplateKey.RAG_CONTEXT, variables),
                    variables
            ));
        }

        return new PromptAssembly(blocks);
    }

    private String buildRagContextSections(List<RagSnippetVO> ragSnippets) {
        StringBuilder context = new StringBuilder();
        for (int index = 0; index < ragSnippets.size(); index++) {
            RagSnippetVO snippet = ragSnippets.get(index);
            context.append("[片段 ").append(index + 1).append("]\n");
            context.append("knowledgeBaseId=").append(snippet.knowledgeBaseId())
                    .append(", fileId=").append(snippet.fileId())
                    .append(", segmentNo=").append(snippet.segmentNo())
                    .append(", score=").append(snippet.score())
                    .append("\n");
            if (StringUtils.hasText(snippet.sourceTitle())) {
                context.append("标题: ").append(snippet.sourceTitle()).append("\n");
            }
            if (StringUtils.hasText(snippet.fullText())) {
                context.append(snippet.fullText()).append("\n");
            }
            context.append("\n");
        }
        return context.toString().trim();
    }

    public record ChatContextSnapshot(
            ChatMemory chatMemory,
            List<ChatMessage> baseMemoryMessages,
            UserMessage userMessage,
            UserMessage requestUserMessage,
            List<SystemMessage> systemMessages,
            PromptAssembly promptAssembly,
            List<ChatMessage> requestMessages
    ) {
        public ChatContextSnapshot {
            baseMemoryMessages = baseMemoryMessages == null ? List.of() : List.copyOf(baseMemoryMessages);
            systemMessages = systemMessages == null ? List.of() : List.copyOf(systemMessages);
            requestMessages = requestMessages == null ? List.of() : List.copyOf(requestMessages);
        }
    }
}
