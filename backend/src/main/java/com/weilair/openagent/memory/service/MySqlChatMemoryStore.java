package com.weilair.openagent.memory.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import com.weilair.openagent.memory.model.ChatMemoryMessageDO;
import com.weilair.openagent.memory.model.ChatMemorySessionDO;
import com.weilair.openagent.memory.persistence.mapper.ChatMemoryMessageMapper;
import com.weilair.openagent.memory.persistence.mapper.ChatMemorySessionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MySqlChatMemoryStore implements ChatMemoryStore {

    private static final int ACTIVE_STATUS = 1;

    private final ChatMemorySessionMapper chatMemorySessionMapper;
    private final ChatMemoryMessageMapper chatMemoryMessageMapper;
    private final ConversationMemorySpecResolver memorySpecResolver;

    public MySqlChatMemoryStore(
            ChatMemorySessionMapper chatMemorySessionMapper,
            ChatMemoryMessageMapper chatMemoryMessageMapper,
            ConversationMemorySpecResolver memorySpecResolver
    ) {
        this.chatMemorySessionMapper = chatMemorySessionMapper;
        this.chatMemoryMessageMapper = chatMemoryMessageMapper;
        this.memorySpecResolver = memorySpecResolver;
    }

    /**
     * ChatMemoryStore 面向的是“当前 memory 视图”，不是完整 history。
     * 因此这里读取的是 chat_memory_message，而不是 conversation_message。
     */
    @Override
    @Transactional
    public List<ChatMessage> getMessages(Object memoryId) {
        ChatMemorySessionDO session = ensureSession(memoryId);
        return chatMemoryMessageMapper.selectByMemorySessionId(session.getId()).stream()
                .map(ChatMemoryMessageDO::getMessageJson)
                .map(ChatMessageDeserializer::messageFromJson)
                .toList();
    }

    /**
     * LangChain4j 的 updateMessages 语义是“整窗替换当前 memory 状态”。
     * 所以这里严格按文档约定：先删旧窗口，再批量写入新窗口。
     */
    @Override
    @Transactional
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        ChatMemorySessionDO session = ensureSession(memoryId);
        chatMemoryMessageMapper.deleteByMemorySessionId(session.getId());

        if (messages == null || messages.isEmpty()) {
            return;
        }

        List<ChatMemoryMessageDO> records = new ArrayList<>(messages.size());
        for (int index = 0; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            ChatMemoryMessageDO record = new ChatMemoryMessageDO();
            record.setMemorySessionId(session.getId());
            record.setMessageOrder(index + 1);
            record.setRoleCode(resolveRoleCode(message));
            record.setMessageJson(ChatMessageSerializer.messageToJson(message));
            records.add(record);
        }
        chatMemoryMessageMapper.insertBatch(records);
    }

    @Override
    @Transactional
    public void deleteMessages(Object memoryId) {
        ChatMemorySessionDO session = chatMemorySessionMapper.selectByMemoryId(normalizeMemoryId(memoryId));
        if (session == null) {
            return;
        }
        chatMemoryMessageMapper.deleteByMemorySessionId(session.getId());
    }

    private ChatMemorySessionDO ensureSession(Object memoryId) {
        String normalizedMemoryId = normalizeMemoryId(memoryId);
        ConversationMemorySpec memorySpec = memorySpecResolver.getMemorySpec();
        ChatMemorySessionDO existing = chatMemorySessionMapper.selectByMemoryId(normalizedMemoryId);
        if (existing != null) {
            syncSessionMetadata(existing, memorySpec);
            return existing;
        }

        ChatMemorySessionDO session = new ChatMemorySessionDO();
        session.setConversationId(resolveConversationId(memoryId));
        session.setMemoryId(normalizedMemoryId);
        applySessionMetadata(session, memorySpec);
        session.setStatus(ACTIVE_STATUS);
        chatMemorySessionMapper.insertIgnore(session);

        ChatMemorySessionDO created = chatMemorySessionMapper.selectByMemoryId(normalizedMemoryId);
        if (created == null) {
            throw new IllegalStateException("未能创建或加载 ChatMemory 会话: memoryId=" + normalizedMemoryId);
        }
        return created;
    }

    private void syncSessionMetadata(ChatMemorySessionDO session, ConversationMemorySpec memorySpec) {
        ChatMemorySessionDO latest = new ChatMemorySessionDO();
        latest.setId(session.getId());
        applySessionMetadata(latest, memorySpec);
        latest.setStatus(ACTIVE_STATUS);

        boolean changed = !Objects.equals(session.getMemoryType(), latest.getMemoryType())
                || !Objects.equals(session.getMaxMessages(), latest.getMaxMessages())
                || !Objects.equals(session.getMaxTokens(), latest.getMaxTokens())
                || !Objects.equals(session.getTokenizerName(), latest.getTokenizerName())
                || !Objects.equals(session.getStatus(), latest.getStatus());
        if (!changed) {
            return;
        }

        chatMemorySessionMapper.updateMetadata(latest);
        session.setMemoryType(latest.getMemoryType());
        session.setMaxMessages(latest.getMaxMessages());
        session.setMaxTokens(latest.getMaxTokens());
        session.setTokenizerName(latest.getTokenizerName());
        session.setStatus(latest.getStatus());
    }

    private void applySessionMetadata(ChatMemorySessionDO session, ConversationMemorySpec memorySpec) {
        session.setMemoryType(memorySpec.memoryType());
        session.setMaxMessages(memorySpec.maxMessages());
        session.setMaxTokens(memorySpec.maxTokens());
        session.setTokenizerName(memorySpec.tokenizerName());
    }

    private String normalizeMemoryId(Object memoryId) {
        if (memoryId == null) {
            throw new IllegalArgumentException("ChatMemory memoryId 不能为空");
        }
        return String.valueOf(memoryId);
    }

    private Long resolveConversationId(Object memoryId) {
        if (memoryId instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(memoryId));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("当前实现要求 memoryId 可映射为 conversationId: " + memoryId, exception);
        }
    }

    private String resolveRoleCode(ChatMessage message) {
        if (message instanceof SystemMessage) {
            return "SYSTEM";
        }
        if (message instanceof UserMessage) {
            return "USER";
        }
        if (message instanceof AiMessage) {
            return "ASSISTANT";
        }
        if (message instanceof ToolExecutionResultMessage) {
            return "TOOL";
        }
        return message.type().name();
    }
}
