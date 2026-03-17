package com.weilair.openagent.conversation.service;

import java.time.LocalDateTime;
import java.util.List;

import com.weilair.openagent.common.util.TimeUtils;
import com.weilair.openagent.conversation.exception.ConversationNotFoundException;
import com.weilair.openagent.conversation.model.ConversationDO;
import com.weilair.openagent.conversation.model.ConversationMessageDO;
import com.weilair.openagent.conversation.persistence.mapper.ConversationMapper;
import com.weilair.openagent.conversation.persistence.mapper.ConversationMessageMapper;
import com.weilair.openagent.web.dto.ChatSendRequest;
import com.weilair.openagent.web.dto.ConversationCreateRequest;
import com.weilair.openagent.web.vo.ConversationMessageVO;
import com.weilair.openagent.web.vo.ConversationVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ConversationService {

    private static final Long DEFAULT_USER_ID = 1L;
    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int DEFAULT_MESSAGE_LIMIT = 200;
    private static final int DEFAULT_CONTEXT_TURN_LIMIT = 5;

    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper conversationMessageMapper;

    public ConversationService(
            ConversationMapper conversationMapper,
            ConversationMessageMapper conversationMessageMapper
    ) {
        this.conversationMapper = conversationMapper;
        this.conversationMessageMapper = conversationMessageMapper;
    }

    /**
     * 创建会话时先保存持久配置，后续聊天请求中的覆盖项只影响单次执行。
     */
    @Transactional
    public ConversationVO createConversation(ConversationCreateRequest request) {
        ConversationDO conversation = new ConversationDO();
        conversation.setUserId(DEFAULT_USER_ID);
        conversation.setTitle(resolveTitle(request.title(), "新会话"));
        conversation.setEnableRag(Boolean.TRUE.equals(request.enableRag()));
        conversation.setEnableAgent(Boolean.TRUE.equals(request.enableAgent()));
        conversation.setMemoryEnabled(Boolean.TRUE);
        conversation.setModeCode(resolveModeCode(conversation.getEnableRag(), conversation.getEnableAgent()));
        conversation.setStatus(1);
        conversationMapper.insert(conversation);
        return toConversationVO(requireConversation(conversation.getId()));
    }

    /**
     * 聊天入口允许不带 conversationId，这样首轮发送时也能自动落成一个有状态会话。
     */
    @Transactional
    public ConversationDO resolveConversation(ChatSendRequest request) {
        if (request.conversationId() != null) {
            return requireConversation(request.conversationId());
        }

        ConversationDO conversation = new ConversationDO();
        conversation.setUserId(DEFAULT_USER_ID);
        conversation.setTitle(resolveTitle(null, request.message()));
        conversation.setEnableRag(Boolean.TRUE.equals(request.enableRag()));
        conversation.setEnableAgent(Boolean.TRUE.equals(request.enableAgent()));
        conversation.setMemoryEnabled(Boolean.TRUE);
        conversation.setModeCode(resolveModeCode(conversation.getEnableRag(), conversation.getEnableAgent()));
        conversation.setStatus(1);
        conversationMapper.insert(conversation);
        return requireConversation(conversation.getId());
    }

    public ConversationDO requireConversation(Long conversationId) {
        ConversationDO conversation = conversationMapper.selectActiveById(conversationId);
        if (conversation == null) {
            throw new ConversationNotFoundException(conversationId);
        }
        return conversation;
    }

    public List<ConversationVO> listConversations() {
        return conversationMapper.selectRecent(DEFAULT_LIST_LIMIT).stream()
                .map(this::toConversationVO)
                .toList();
    }

    public List<ConversationMessageVO> listMessages(Long conversationId) {
        requireConversation(conversationId);
        return conversationMessageMapper.selectByConversationId(conversationId, DEFAULT_MESSAGE_LIMIT).stream()
                .map(this::toConversationMessageVO)
                .toList();
    }

    /**
     * 当前初版上下文管理只回灌最近若干轮“已完成的正式消息”。
     * 这里刻意不直接把所有 history 都送进模型，也不把失败消息、thinking/progress 混进去，
     * 目的是先获得可控的连续对话能力，并给后续 ChatMemory 正式接入保留清晰边界。
     */
    public List<ConversationMessageDO> listRecentContextMessages(Long conversationId, Integer turnLimit) {
        requireConversation(conversationId);
        int effectiveTurnLimit = turnLimit == null || turnLimit <= 0 ? DEFAULT_CONTEXT_TURN_LIMIT : turnLimit;
        return conversationMessageMapper.selectRecentContextMessages(conversationId, effectiveTurnLimit);
    }

    /**
     * user / assistant 消息都统一写入 conversation_message，刷新页面后可以直接回放。
     */
    @Transactional
    public Long saveUserMessage(Long conversationId, String requestId, String content) {
        ConversationMessageDO message = new ConversationMessageDO();
        message.setConversationId(conversationId);
        message.setUserId(DEFAULT_USER_ID);
        message.setRoleCode("USER");
        message.setMessageType("TEXT");
        message.setContent(content);
        message.setRequestId(requestId);
        conversationMessageMapper.insert(message);
        touchConversation(conversationId);
        return message.getId();
    }

    @Transactional
    public Long saveAssistantMessage(
            Long conversationId,
            String requestId,
            Long parentMessageId,
            String content,
            String finishReason,
            String errorMessage
    ) {
        ConversationMessageDO message = new ConversationMessageDO();
        message.setConversationId(conversationId);
        message.setUserId(DEFAULT_USER_ID);
        message.setRoleCode("ASSISTANT");
        message.setMessageType("TEXT");
        message.setContent(content);
        message.setRequestId(requestId);
        message.setParentMessageId(parentMessageId);
        message.setFinishReason(finishReason);
        message.setErrorMessage(errorMessage);
        conversationMessageMapper.insert(message);
        touchConversation(conversationId);
        return message.getId();
    }

    @Transactional
    public void touchConversation(Long conversationId) {
        conversationMapper.updateLastMessageAt(conversationId, LocalDateTime.now());
    }

    private ConversationVO toConversationVO(ConversationDO conversation) {
        return new ConversationVO(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getModeCode(),
                conversation.getEnableRag(),
                conversation.getEnableAgent(),
                resolveStatusLabel(conversation.getStatus()),
                TimeUtils.toEpochMillis(conversation.getLastMessageAt()),
                TimeUtils.toEpochMillis(conversation.getCreatedAt()),
                TimeUtils.toEpochMillis(conversation.getUpdatedAt())
        );
    }

    private ConversationMessageVO toConversationMessageVO(ConversationMessageDO message) {
        return new ConversationMessageVO(
                message.getId(),
                message.getConversationId(),
                message.getRoleCode(),
                message.getMessageType(),
                message.getContent(),
                message.getRequestId(),
                message.getFinishReason(),
                TimeUtils.toEpochMillis(message.getCreatedAt())
        );
    }

    private String resolveTitle(String explicitTitle, String fallbackSource) {
        if (StringUtils.hasText(explicitTitle)) {
            return explicitTitle.trim();
        }
        String fallback = fallbackSource == null ? "新会话" : fallbackSource.trim();
        if (fallback.isEmpty()) {
            return "新会话";
        }
        return fallback.length() > 40 ? fallback.substring(0, 40) : fallback;
    }

    private String resolveModeCode(Boolean enableRag, Boolean enableAgent) {
        boolean ragEnabled = Boolean.TRUE.equals(enableRag);
        boolean agentEnabled = Boolean.TRUE.equals(enableAgent);
        if (ragEnabled && agentEnabled) {
            return "RAG_AGENT";
        }
        if (ragEnabled) {
            return "RAG";
        }
        if (agentEnabled) {
            return "AGENT";
        }
        return "CHAT";
    }

    private String resolveStatusLabel(Integer status) {
        if (status != null && status == 1) {
            return "ACTIVE";
        }
        return "UNKNOWN";
    }
}
