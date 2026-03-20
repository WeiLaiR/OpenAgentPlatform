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
     * 3. 当前轮 user message 与 RAG 片段先只进入 request messages，成功后再回写 memory
     *
     * 这样做的原因是：如果模型调用失败，不应把“未完成的一轮”提前污染 ChatMemory。
     */
    public ChatContextSnapshot assemble(
            ConversationDO conversation,
            String currentUserMessage,
            List<RagSnippetVO> ragSnippets
    ) {
        ChatMemory chatMemory = null;
        List<ChatMessage> baseMemoryMessages = List.of();

        if (Boolean.TRUE.equals(conversation.getMemoryEnabled())) {
            chatMemory = conversationMemoryService.getOrCreateMemory(conversation.getId());
            baseMemoryMessages = List.copyOf(chatMemory.messages());
        }

        UserMessage userMessage = UserMessage.from(currentUserMessage);
        List<ChatMessage> requestMessages = new ArrayList<>(baseMemoryMessages);

        SystemMessage transientRagMessage = null;
        if (ragSnippets != null && !ragSnippets.isEmpty()) {
            transientRagMessage = SystemMessage.from(buildRagContextMessage(ragSnippets));
            requestMessages.add(transientRagMessage);
        }

        requestMessages.add(userMessage);
        return new ChatContextSnapshot(
                chatMemory,
                baseMemoryMessages,
                userMessage,
                transientRagMessage,
                List.copyOf(requestMessages)
        );
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
            SystemMessage transientRagMessage,
            List<ChatMessage> requestMessages
    ) {
    }
}
