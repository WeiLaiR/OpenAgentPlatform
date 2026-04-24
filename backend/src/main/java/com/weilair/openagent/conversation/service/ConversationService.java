package com.weilair.openagent.conversation.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.weilair.openagent.ai.config.OpenAgentMemoryProperties;
import com.weilair.openagent.chat.service.AgentToolConfirmationService;
import com.weilair.openagent.chat.persistence.mapper.AgentToolConfirmationMapper;
import com.weilair.openagent.chat.model.ChatMode;
import com.weilair.openagent.common.util.TimeUtils;
import com.weilair.openagent.conversation.exception.ConversationNotFoundException;
import com.weilair.openagent.conversation.model.ConversationDO;
import com.weilair.openagent.conversation.model.ConversationKbBindingDO;
import com.weilair.openagent.conversation.model.ConversationMcpBindingDO;
import com.weilair.openagent.conversation.model.ConversationMessageDO;
import com.weilair.openagent.conversation.persistence.mapper.ConversationKbBindingMapper;
import com.weilair.openagent.conversation.persistence.mapper.ConversationMapper;
import com.weilair.openagent.conversation.persistence.mapper.ConversationMcpBindingMapper;
import com.weilair.openagent.conversation.persistence.mapper.ConversationMessageMapper;
import com.weilair.openagent.knowledge.service.KnowledgeBaseService;
import com.weilair.openagent.mcp.service.McpServerService;
import com.weilair.openagent.memory.model.ChatMemorySessionDO;
import com.weilair.openagent.memory.persistence.mapper.ChatMemoryMessageMapper;
import com.weilair.openagent.memory.persistence.mapper.ChatMemorySessionMapper;
import com.weilair.openagent.memory.service.ConversationMemoryService;
import com.weilair.openagent.trace.persistence.mapper.TraceEventMapper;
import com.weilair.openagent.web.dto.ChatSendRequest;
import com.weilair.openagent.web.dto.ConversationCreateRequest;
import com.weilair.openagent.web.dto.ConversationSettingsUpdateRequest;
import com.weilair.openagent.web.vo.ConversationMemoryClearVO;
import com.weilair.openagent.web.vo.ConversationMessageVO;
import com.weilair.openagent.web.vo.ConversationVO;
import com.weilair.openagent.web.vo.ToolConfirmationPendingVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ConversationService {

    private static final Long DEFAULT_USER_ID = 1L;
    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int DEFAULT_MESSAGE_LIMIT = 200;
    private static final int DEFAULT_CONTEXT_TURN_LIMIT = 5;

    private final OpenAgentMemoryProperties memoryProperties;
    private final ConversationMapper conversationMapper;
    private final ConversationKbBindingMapper conversationKbBindingMapper;
    private final ConversationMcpBindingMapper conversationMcpBindingMapper;
    private final ConversationMessageMapper conversationMessageMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final McpServerService mcpServerService;
    private final ConversationMemoryService conversationMemoryService;
    private final AgentToolConfirmationService agentToolConfirmationService;
    private final ChatMemorySessionMapper chatMemorySessionMapper;
    private final ChatMemoryMessageMapper chatMemoryMessageMapper;
    private final TraceEventMapper traceEventMapper;
    private final AgentToolConfirmationMapper agentToolConfirmationMapper;

    public ConversationService(
            OpenAgentMemoryProperties memoryProperties,
            ConversationMapper conversationMapper,
            ConversationKbBindingMapper conversationKbBindingMapper,
            ConversationMcpBindingMapper conversationMcpBindingMapper,
            ConversationMessageMapper conversationMessageMapper,
            KnowledgeBaseService knowledgeBaseService,
            McpServerService mcpServerService,
            ConversationMemoryService conversationMemoryService,
            AgentToolConfirmationService agentToolConfirmationService,
            ChatMemorySessionMapper chatMemorySessionMapper,
            ChatMemoryMessageMapper chatMemoryMessageMapper,
            TraceEventMapper traceEventMapper,
            AgentToolConfirmationMapper agentToolConfirmationMapper
    ) {
        this.memoryProperties = memoryProperties;
        this.conversationMapper = conversationMapper;
        this.conversationKbBindingMapper = conversationKbBindingMapper;
        this.conversationMcpBindingMapper = conversationMcpBindingMapper;
        this.conversationMessageMapper = conversationMessageMapper;
        this.knowledgeBaseService = knowledgeBaseService;
        this.mcpServerService = mcpServerService;
        this.conversationMemoryService = conversationMemoryService;
        this.agentToolConfirmationService = agentToolConfirmationService;
        this.chatMemorySessionMapper = chatMemorySessionMapper;
        this.chatMemoryMessageMapper = chatMemoryMessageMapper;
        this.traceEventMapper = traceEventMapper;
        this.agentToolConfirmationMapper = agentToolConfirmationMapper;
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
        conversation.setMemoryEnabled(Boolean.TRUE.equals(memoryProperties.getDefaultEnabled()));
        conversation.setModeCode(resolveModeCode(conversation.getEnableRag(), conversation.getEnableAgent()));
        conversation.setStatus(1);
        conversationMapper.insert(conversation);
        return toConversationVO(resolveSettingsSnapshot(conversation.getId()));
    }

    /**
     * 聊天入口允许不带 conversationId，这样首轮发送时也能自动落成一个有状态会话。
     */
    @Transactional
    public ConversationDO resolveConversation(ChatSendRequest request) {
        if (request.conversationId() != null) {
            return requireConversation(request.conversationId());
        }

        List<Long> knowledgeBaseIds = normalizeKnowledgeBaseIds(request.knowledgeBaseIds());
        List<Long> mcpServerIds = normalizeMcpServerIds(request.mcpServerIds());
        ConversationDO conversation = new ConversationDO();
        conversation.setUserId(DEFAULT_USER_ID);
        conversation.setTitle(resolveTitle(null, request.message()));
        conversation.setEnableRag(Boolean.TRUE.equals(request.enableRag()));
        conversation.setEnableAgent(Boolean.TRUE.equals(request.enableAgent()));
        conversation.setMemoryEnabled(request.memoryEnabled() != null
                ? request.memoryEnabled()
                : Boolean.TRUE.equals(memoryProperties.getDefaultEnabled()));
        conversation.setModeCode(resolveModeCode(conversation.getEnableRag(), conversation.getEnableAgent()));
        conversation.setStatus(1);
        conversationMapper.insert(conversation);
        replaceKnowledgeBaseBindings(conversation.getId(), knowledgeBaseIds);
        replaceMcpServerBindings(conversation.getId(), mcpServerIds);
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
                .map(this::resolveSettingsSnapshot)
                .map(this::toConversationVO)
                .toList();
    }

    public ConversationVO getSettings(Long conversationId) {
        return toConversationVO(resolveSettingsSnapshot(conversationId));
    }

    public List<ConversationMessageVO> listMessages(Long conversationId) {
        requireConversation(conversationId);
        Map<String, ToolConfirmationPendingVO> confirmationsByRequestId = agentToolConfirmationService
                .listByConversationId(conversationId)
                .stream()
                .collect(Collectors.toMap(
                        ToolConfirmationPendingVO::requestId,
                        Function.identity(),
                        (left, right) -> right
                ));
        return conversationMessageMapper.selectByConversationId(conversationId, DEFAULT_MESSAGE_LIMIT).stream()
                .map(message -> toConversationMessageVO(
                        message,
                        "ASSISTANT".equalsIgnoreCase(message.getRoleCode())
                                ? confirmationsByRequestId.get(message.getRequestId())
                                : null
                ))
                .toList();
    }

    /**
     * 会话 settings 接口按“完整快照覆盖”工作：
     * 由 `conversation`、`conversation_kb_binding`、`conversation_mcp_binding`
     * 共同承接当前会话的默认模式、memory、知识库和 MCP 绑定。
     */
    @Transactional
    public ConversationVO updateSettings(Long conversationId, ConversationSettingsUpdateRequest request) {
        ConversationSettingsSnapshot currentSnapshot = resolveSettingsSnapshot(conversationId);
        Boolean enableRag = request.enableRag() != null ? request.enableRag() : currentSnapshot.enableRag();
        Boolean enableAgent = request.enableAgent() != null ? request.enableAgent() : currentSnapshot.enableAgent();
        Boolean memoryEnabled = request.memoryEnabled() != null ? request.memoryEnabled() : currentSnapshot.memoryEnabled();
        List<Long> knowledgeBaseIds = request.knowledgeBaseIds() != null
                ? normalizeKnowledgeBaseIds(request.knowledgeBaseIds())
                : currentSnapshot.knowledgeBaseIds();
        List<Long> mcpServerIds = request.mcpServerIds() != null
                ? normalizeMcpServerIds(request.mcpServerIds())
                : currentSnapshot.mcpServerIds();

        conversationMapper.updateSettings(
                conversationId,
                enableRag,
                enableAgent,
                memoryEnabled,
                resolveModeCode(enableRag, enableAgent)
        );
        if (request.knowledgeBaseIds() != null) {
            replaceKnowledgeBaseBindings(conversationId, knowledgeBaseIds);
        }
        if (request.mcpServerIds() != null) {
            replaceMcpServerBindings(conversationId, mcpServerIds);
        }
        return toConversationVO(resolveSettingsSnapshot(conversationId));
    }

    @Transactional
    public ConversationMemoryClearVO clearMemory(Long conversationId) {
        requireConversation(conversationId);
        conversationMemoryService.clearMemory(conversationId);
        return new ConversationMemoryClearVO(conversationId, Boolean.TRUE);
    }

    /**
     * 当前删除策略先采用物理删除，确保聊天页的历史列表不会残留空壳会话。
     * 由于当前表结构没有建立数据库级联外键，这里由 service 层统一清理会话消息、Trace、会话绑定和 Memory。
     */
    @Transactional
    public void deleteConversation(Long conversationId) {
        requireConversation(conversationId);

        ChatMemorySessionDO memorySession = chatMemorySessionMapper.selectByConversationId(conversationId);
        if (memorySession != null) {
            chatMemoryMessageMapper.deleteByMemorySessionId(memorySession.getId());
            chatMemorySessionMapper.deleteByConversationId(conversationId);
        }

        traceEventMapper.deleteByConversationId(conversationId);
        agentToolConfirmationMapper.deleteByConversationId(conversationId);
        conversationMessageMapper.deleteByConversationId(conversationId);
        conversationKbBindingMapper.deleteByConversationId(conversationId);
        conversationMcpBindingMapper.deleteByConversationId(conversationId);
        conversationMapper.deleteById(conversationId);
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

    public ConversationSettingsSnapshot resolveSettingsSnapshot(Long conversationId) {
        return resolveSettingsSnapshot(requireConversation(conversationId));
    }

    public ConversationSettingsSnapshot resolveSettingsSnapshot(ConversationDO conversation) {
        return new ConversationSettingsSnapshot(
                conversation,
                Boolean.TRUE.equals(conversation.getEnableRag()),
                Boolean.TRUE.equals(conversation.getEnableAgent()),
                Boolean.TRUE.equals(conversation.getMemoryEnabled()),
                normalizeIds(conversationKbBindingMapper.selectSelectedKnowledgeBaseIds(conversation.getId())),
                normalizeIds(conversationMcpBindingMapper.selectSelectedServerIds(conversation.getId()))
        );
    }

    private ConversationVO toConversationVO(ConversationSettingsSnapshot snapshot) {
        ConversationDO conversation = snapshot.conversation();
        return new ConversationVO(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getModeCode(),
                snapshot.enableRag(),
                snapshot.enableAgent(),
                snapshot.memoryEnabled(),
                snapshot.knowledgeBaseIds(),
                snapshot.mcpServerIds(),
                resolveStatusLabel(conversation.getStatus()),
                TimeUtils.toEpochMillis(conversation.getLastMessageAt()),
                TimeUtils.toEpochMillis(conversation.getCreatedAt()),
                TimeUtils.toEpochMillis(conversation.getUpdatedAt())
        );
    }

    private ConversationMessageVO toConversationMessageVO(
            ConversationMessageDO message,
            ToolConfirmationPendingVO pendingConfirmation
    ) {
        return new ConversationMessageVO(
                message.getId(),
                message.getConversationId(),
                message.getRoleCode(),
                message.getMessageType(),
                message.getContent(),
                message.getRequestId(),
                message.getFinishReason(),
                TimeUtils.toEpochMillis(message.getCreatedAt()),
                pendingConfirmation
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

    private List<Long> normalizeKnowledgeBaseIds(List<Long> knowledgeBaseIds) {
        List<Long> normalized = normalizeIds(knowledgeBaseIds);
        normalized.forEach(knowledgeBaseService::requireKnowledgeBase);
        return normalized;
    }

    private List<Long> normalizeMcpServerIds(List<Long> mcpServerIds) {
        List<Long> normalized = normalizeIds(mcpServerIds);
        normalized.forEach(mcpServerService::requireServer);
        return normalized;
    }

    private List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        List<Long> normalized = new ArrayList<>();
        for (Long id : ids) {
            if (id != null && normalized.stream().noneMatch(existing -> Objects.equals(existing, id))) {
                normalized.add(id);
            }
        }
        return List.copyOf(normalized);
    }

    private void replaceKnowledgeBaseBindings(Long conversationId, List<Long> knowledgeBaseIds) {
        conversationKbBindingMapper.deleteByConversationId(conversationId);
        if (knowledgeBaseIds.isEmpty()) {
            return;
        }

        List<ConversationKbBindingDO> bindings = knowledgeBaseIds.stream()
                .map(knowledgeBaseId -> {
                    ConversationKbBindingDO binding = new ConversationKbBindingDO();
                    binding.setConversationId(conversationId);
                    binding.setKnowledgeBaseId(knowledgeBaseId);
                    binding.setSelected(Boolean.TRUE);
                    return binding;
                })
                .toList();
        conversationKbBindingMapper.insertBatch(bindings);
    }

    private void replaceMcpServerBindings(Long conversationId, List<Long> mcpServerIds) {
        conversationMcpBindingMapper.deleteByConversationId(conversationId);
        if (mcpServerIds.isEmpty()) {
            return;
        }

        List<ConversationMcpBindingDO> bindings = mcpServerIds.stream()
                .map(serverId -> {
                    ConversationMcpBindingDO binding = new ConversationMcpBindingDO();
                    binding.setConversationId(conversationId);
                    binding.setMcpServerId(serverId);
                    binding.setSelected(Boolean.TRUE);
                    return binding;
                })
                .toList();
        conversationMcpBindingMapper.insertBatch(bindings);
    }

    private String resolveModeCode(Boolean enableRag, Boolean enableAgent) {
        return ChatMode.fromFlags(enableRag, enableAgent).code();
    }

    private String resolveStatusLabel(Integer status) {
        if (status != null && status == 1) {
            return "ACTIVE";
        }
        return "UNKNOWN";
    }
}
