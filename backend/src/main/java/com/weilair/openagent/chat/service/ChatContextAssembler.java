package com.weilair.openagent.chat.service;

import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import com.weilair.openagent.conversation.model.ConversationDO;
import com.weilair.openagent.memory.service.ConversationMemoryService;
import com.weilair.openagent.web.vo.RagSnippetVO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ChatContextAssembler {

    private final ConversationMemoryService conversationMemoryService;

    public ChatContextAssembler(ConversationMemoryService conversationMemoryService) {
        this.conversationMemoryService = conversationMemoryService;
    }

    /**
     * 这里正式切到 LangChain4j ChatMemory 作为“模型上下文视图”：
     * 1. history 继续留在 conversation_message，供前端重放和审计使用
     * 2. memory 只负责本轮真正发给模型的上下文窗口
     * 3. `userMessage` 表示“原始用户输入”，`requestUserMessage` 表示“真正发给模型的当前轮输入”
     * 4. 当 RAG 切到 `RetrievalAugmentor` 后，增强后的内容只进入 `requestUserMessage`，不直接回写 memory
     *
     * 这样做的原因是：如果模型调用失败，不应把“未完成的一轮”提前污染 ChatMemory。
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
        UserMessage requestUserMessage = userMessage;
        List<ChatMessage> requestMessages = new ArrayList<>(baseMemoryMessages);

        SystemMessage transientRagMessage = null;
        if (ragSnippets != null && !ragSnippets.isEmpty()) {
            transientRagMessage = SystemMessage.from(buildRagContextMessage(ragSnippets));
            requestMessages.add(transientRagMessage);
        }

        requestMessages.add(requestUserMessage);
        return new ChatContextSnapshot(
                chatMemory,
                baseMemoryMessages,
                userMessage,
                requestUserMessage,
                transientRagMessage,
                List.copyOf(requestMessages)
        );
    }

    /**
     * 兼容当前仍以 conversation 持久配置为唯一 memory 开关来源的调用方式。
     * 等统一编排骨架全面切到 `ChatExecutionSpec` 后，这个重载可以再视情况收敛。
     */
    public ChatContextSnapshot assemble(
            ConversationDO conversation,
            String currentUserMessage,
            List<RagSnippetVO> ragSnippets
    ) {
        return assemble(conversation, null, currentUserMessage, ragSnippets);
    }

    private String buildRagContextMessage(List<RagSnippetVO> ragSnippets) {
        StringBuilder context = new StringBuilder();
        context.append("以下是从知识库中检索到的参考片段，请优先基于这些内容回答；如果片段不足以支持结论，要明确说明。\n\n");
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
            SystemMessage transientRagMessage,
            List<ChatMessage> requestMessages
    ) {

        public ChatContextSnapshot withRequestUserMessage(UserMessage requestUserMessage) {
            List<ChatMessage> rebuiltRequestMessages = new ArrayList<>(baseMemoryMessages);
            if (transientRagMessage != null) {
                rebuiltRequestMessages.add(transientRagMessage);
            }
            rebuiltRequestMessages.add(requestUserMessage);
            return new ChatContextSnapshot(
                    chatMemory,
                    baseMemoryMessages,
                    userMessage,
                    requestUserMessage,
                    transientRagMessage,
                    List.copyOf(rebuiltRequestMessages)
            );
        }
    }
}
