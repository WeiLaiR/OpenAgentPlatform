package com.weilair.openagent.conversation.service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import com.weilair.openagent.conversation.persistence.mapper.ConversationMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ConversationTitleService {

    /**
     * 这里刻意继续复用 LangChain4j 官方 `ChatModel` 能力来做“一次性标题生成”，
     * 而不是自己重新拼 HTTP 请求。
     *
     * 这个任务本质上就是一个极短文本生成场景，
     * 直接走官方 `chat(messages)` 已经足够，同时也更符合本仓库“优先沿主线能力学习”的约束。
     */
    private static final Logger log = LoggerFactory.getLogger(ConversationTitleService.class);
    private static final int MAX_PROMPT_TEXT_LENGTH = 600;
    private static final int MAX_TITLE_LENGTH = 24;

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ConversationMapper conversationMapper;
    private final ExecutorService executorService;

    @Autowired
    public ConversationTitleService(
            ObjectProvider<ChatModel> chatModelProvider,
            ConversationMapper conversationMapper
    ) {
        this(chatModelProvider, conversationMapper, Executors.newVirtualThreadPerTaskExecutor());
    }

    ConversationTitleService(
            ObjectProvider<ChatModel> chatModelProvider,
            ConversationMapper conversationMapper,
            ExecutorService executorService
    ) {
        this.chatModelProvider = chatModelProvider;
        this.conversationMapper = conversationMapper;
        this.executorService = executorService;
    }

    /**
     * 标题生成属于“首轮完成后的附加体验增强”，不应该阻塞主回答返回，
     * 因此这里采用异步补写。
     *
     * 如果模型不可用、任务被拒绝或生成失败，就保留当前已有的回退标题，
     * 不让主链路因为“标题优化失败”而失败。
     */
    public void generateFirstRoundTitleAsync(Long conversationId, String userMessage, String assistantMessage) {
        if (conversationId == null || !StringUtils.hasText(userMessage) || !StringUtils.hasText(assistantMessage)) {
            return;
        }

        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            return;
        }

        try {
            executorService.submit(() -> generateAndPersistFirstRoundTitle(conversationId, chatModel, userMessage, assistantMessage));
        } catch (RejectedExecutionException exception) {
            log.warn("会话标题生成任务提交失败，conversationId={}", conversationId);
            if (log.isDebugEnabled()) {
                log.debug("会话标题生成任务提交异常详情", exception);
            }
        }
    }

    private void generateAndPersistFirstRoundTitle(
            Long conversationId,
            ChatModel chatModel,
            String userMessage,
            String assistantMessage
    ) {
        try {
            ChatResponse response = chatModel.chat(List.of(
                    SystemMessage.from("""
                            你是一个聊天会话标题生成器。
                            请根据首轮问答内容生成一个简洁标题，用于左侧会话列表。
                            要求：
                            1. 只输出标题本身，不要解释，不要加引号，不要加前缀。
                            2. 标题要概括主题，不要复述整句原话。
                            3. 默认使用中文，长度尽量控制在 8 到 18 个字内。
                            4. 不要输出“新会话”“未命名会话”“聊天记录”等空泛标题。
                            """),
                    UserMessage.from("""
                            用户首轮消息：
                            %s

                            助手首轮回复：
                            %s
                            """.formatted(
                            truncateForPrompt(userMessage),
                            truncateForPrompt(assistantMessage)
                    ))
            ));

            String generatedTitle = response.aiMessage() == null ? null : response.aiMessage().text();
            String normalizedTitle = normalizeTitle(generatedTitle);
            if (!StringUtils.hasText(normalizedTitle)) {
                return;
            }

            conversationMapper.updateTitle(conversationId, normalizedTitle);
        } catch (Exception exception) {
            log.warn("会话标题生成失败，conversationId={}", conversationId);
            if (log.isDebugEnabled()) {
                log.debug("会话标题生成异常详情", exception);
            }
        }
    }

    private String truncateForPrompt(String text) {
        String normalized = normalizeWhitespace(text);
        if (normalized.length() <= MAX_PROMPT_TEXT_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_PROMPT_TEXT_LENGTH);
    }

    private String normalizeTitle(String title) {
        String normalized = normalizeWhitespace(title);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }

        normalized = normalized
                .replaceFirst("^(标题|会话标题)\\s*[:：]\\s*", "")
                .replaceAll("^[\"“”'‘’]+|[\"“”'‘’]+$", "")
                .trim();

        if (!StringUtils.hasText(normalized)) {
            return null;
        }

        if (normalized.length() > MAX_TITLE_LENGTH) {
            normalized = normalized.substring(0, MAX_TITLE_LENGTH);
        }

        if ("新会话".equals(normalized) || "未命名会话".equals(normalized) || "聊天记录".equals(normalized)) {
            return null;
        }
        return normalized;
    }

    private String normalizeWhitespace(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    @PreDestroy
    void destroy() {
        executorService.shutdown();
    }
}
