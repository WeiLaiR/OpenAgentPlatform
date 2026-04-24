package com.weilair.openagent.chat.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weilair.openagent.chat.model.AgentToolConfirmationDO;
import com.weilair.openagent.chat.model.ChatMode;
import com.weilair.openagent.chat.persistence.mapper.AgentToolConfirmationMapper;
import com.weilair.openagent.web.vo.ToolConfirmationPendingVO;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentToolConfirmationService {

    private static final Duration DEFAULT_CONFIRMATION_TTL = Duration.ofMinutes(10);

    private final AgentToolConfirmationMapper agentToolConfirmationMapper;
    private final ObjectMapper objectMapper;

    public AgentToolConfirmationService(
            AgentToolConfirmationMapper agentToolConfirmationMapper,
            ObjectMapper objectMapper
    ) {
        this.agentToolConfirmationMapper = agentToolConfirmationMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ToolConfirmationPendingVO createPendingConfirmation(
            String requestId,
            Long conversationId,
            Long userMessageId,
            ChatExecutionSpec executionSpec,
            String userMessage,
            ToolRuntimeToolMetadata metadata,
            ToolExecutionRequest toolExecutionRequest
    ) {
        AgentToolConfirmationDO existing = agentToolConfirmationMapper.selectPendingByRequestAndToolCall(
                requestId,
                toolExecutionRequest.id()
        );
        if (existing != null) {
            return toPendingVO(existing);
        }

        AgentToolConfirmationDO confirmation = new AgentToolConfirmationDO();
        confirmation.setRequestId(requestId);
        confirmation.setConversationId(conversationId);
        confirmation.setUserMessageId(userMessageId);
        confirmation.setModeCode(executionSpec.modeCode());
        confirmation.setMemoryEnabled(executionSpec.memoryEnabled());
        confirmation.setKnowledgeBaseIdsJson(writeJson(executionSpec.knowledgeBaseIds()));
        confirmation.setMcpServerIdsJson(writeJson(executionSpec.mcpServerIds()));
        confirmation.setUserMessageText(userMessage);
        confirmation.setToolCallId(toolExecutionRequest.id());
        confirmation.setToolName(toolExecutionRequest.name());
        confirmation.setToolArgumentsJson(toolExecutionRequest.arguments());
        confirmation.setToolTitle(metadata == null ? null : metadata.toolTitle());
        confirmation.setServerName(metadata == null ? null : metadata.serverName());
        confirmation.setRiskLevel((metadata == null ? ToolRiskLevel.HIGH : metadata.riskLevel()).name());
        confirmation.setStatusCode("PENDING");
        confirmation.setExpiresAt(LocalDateTime.now().plus(DEFAULT_CONFIRMATION_TTL));
        agentToolConfirmationMapper.insert(confirmation);
        return toPendingVO(confirmation);
    }

    public AgentToolConfirmationDO requireConfirmation(Long confirmationId) {
        AgentToolConfirmationDO confirmation = agentToolConfirmationMapper.selectById(confirmationId);
        if (confirmation == null) {
            throw new IllegalArgumentException("待确认工具调用不存在: " + confirmationId);
        }
        return confirmation;
    }

    @Transactional
    public AgentToolConfirmationDO approve(Long confirmationId, String continuationRequestId) {
        AgentToolConfirmationDO confirmation = requirePending(confirmationId);
        if (agentToolConfirmationMapper.transitionStatus(
                confirmationId,
                "PENDING",
                "APPROVED",
                continuationRequestId,
                "USER_APPROVED"
        ) != 1) {
            throw new IllegalArgumentException("待确认工具调用状态已变化，无法确认执行: " + confirmationId);
        }
        return requireConfirmation(confirmationId);
    }

    @Transactional
    public AgentToolConfirmationDO reject(Long confirmationId, String continuationRequestId) {
        AgentToolConfirmationDO confirmation = requirePending(confirmationId);
        if (agentToolConfirmationMapper.transitionStatus(
                confirmationId,
                "PENDING",
                "REJECTED",
                continuationRequestId,
                "USER_REJECTED"
        ) != 1) {
            throw new IllegalArgumentException("待确认工具调用状态已变化，无法拒绝执行: " + confirmationId);
        }
        return requireConfirmation(confirmationId);
    }

    @Transactional
    public void markExecuted(Long confirmationId) {
        agentToolConfirmationMapper.markExecuted(confirmationId);
    }

    @Transactional
    public void markFailed(Long confirmationId, String reason) {
        agentToolConfirmationMapper.markFailed(confirmationId, reason);
    }

    public List<Long> readKnowledgeBaseIds(AgentToolConfirmationDO confirmation) {
        return readLongList(confirmation.getKnowledgeBaseIdsJson());
    }

    public List<Long> readMcpServerIds(AgentToolConfirmationDO confirmation) {
        return readLongList(confirmation.getMcpServerIdsJson());
    }

    public ChatExecutionSpec toExecutionSpec(AgentToolConfirmationDO confirmation, boolean streaming) {
        ChatMode chatMode = ChatMode.fromCode(confirmation.getModeCode());
        return new ChatExecutionSpec(
                chatMode,
                streaming,
                Boolean.TRUE.equals(confirmation.getMemoryEnabled()),
                readKnowledgeBaseIds(confirmation),
                readMcpServerIds(confirmation)
        );
    }

    public ToolExecutionRequest toToolExecutionRequest(AgentToolConfirmationDO confirmation) {
        return ToolExecutionRequest.builder()
                .id(confirmation.getToolCallId())
                .name(confirmation.getToolName())
                .arguments(confirmation.getToolArgumentsJson())
                .build();
    }

    public ToolConfirmationPendingVO toPendingVO(AgentToolConfirmationDO confirmation) {
        return new ToolConfirmationPendingVO(
                confirmation.getId(),
                confirmation.getRequestId(),
                confirmation.getConversationId(),
                confirmation.getToolCallId(),
                confirmation.getToolName(),
                confirmation.getToolTitle(),
                confirmation.getServerName(),
                confirmation.getRiskLevel(),
                confirmation.getStatusCode(),
                buildStatusMessage(confirmation)
        );
    }

    private AgentToolConfirmationDO requirePending(Long confirmationId) {
        AgentToolConfirmationDO confirmation = requireConfirmation(confirmationId);
        if ("PENDING".equalsIgnoreCase(confirmation.getStatusCode()) && isExpired(confirmation)) {
            agentToolConfirmationMapper.markExpired(confirmationId);
            throw new IllegalArgumentException("待确认工具调用已过期: " + confirmationId);
        }
        if (!"PENDING".equalsIgnoreCase(confirmation.getStatusCode())) {
            throw new IllegalArgumentException("待确认工具调用当前不可操作，状态为: " + confirmation.getStatusCode());
        }
        return confirmation;
    }

    private boolean isExpired(AgentToolConfirmationDO confirmation) {
        return confirmation.getExpiresAt() != null && confirmation.getExpiresAt().isBefore(LocalDateTime.now());
    }

    private String buildStatusMessage(AgentToolConfirmationDO confirmation) {
        return "工具 " + confirmation.getToolName()
                + " 被判定为 " + confirmation.getRiskLevel()
                + " 风险，需用户确认后才能继续执行。";
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("工具确认上下文序列化失败", exception);
        }
    }

    private List<Long> readLongList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("工具确认上下文反序列化失败", exception);
        }
    }
}
