package com.weilair.openagent.memory.service;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.stereotype.Service;

@Service
public class ConversationMemoryService {

    private final ChatMemoryStore chatMemoryStore;
    private final ConversationMemorySpecResolver memorySpecResolver;

    public ConversationMemoryService(
            ChatMemoryStore chatMemoryStore,
            ConversationMemorySpecResolver memorySpecResolver
    ) {
        this.chatMemoryStore = chatMemoryStore;
        this.memorySpecResolver = memorySpecResolver;
    }

    /**
     * 这里不额外发明 memory 抽象，直接返回 LangChain4j 官方 ChatMemory。
     * conversationId 直接作为稳定 memoryId，后续切到 AI Services + @MemoryId 时也能复用。
     */
    public ChatMemory getOrCreateMemory(Long conversationId) {
        ConversationMemorySpec memorySpec = memorySpecResolver.getMemorySpec();
        if (memorySpec.useTokenWindow()) {
            // 优先走官方 TokenWindowChatMemory；只有官方 tokenizer 无法解析时才会降级。
            return TokenWindowChatMemory.builder()
                    .id(conversationId)
                    .maxTokens(memorySpec.maxTokens(), memorySpec.tokenCountEstimator())
                    .chatMemoryStore(chatMemoryStore)
                    .alwaysKeepSystemMessageFirst(Boolean.TRUE.equals(memorySpec.alwaysKeepSystemMessageFirst()))
                    .build();
        }

        // 非 OpenAI tokenizer 场景下，仍然坚持使用 LangChain4j 官方 MessageWindowChatMemory。
        return MessageWindowChatMemory.builder()
                .id(conversationId)
                .maxMessages(memorySpec.maxMessages())
                .chatMemoryStore(chatMemoryStore)
                .alwaysKeepSystemMessageFirst(Boolean.TRUE.equals(memorySpec.alwaysKeepSystemMessageFirst()))
                .build();
    }
}
