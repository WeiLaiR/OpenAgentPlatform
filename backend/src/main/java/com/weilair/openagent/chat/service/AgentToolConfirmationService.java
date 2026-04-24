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
import com.weilair.openagent.common.util.TimeUtils;
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
    public List<ToolConfirmationPendingVO> listByConversationId(Long conversationId) {
        return loadConversationConfirmations(conversationId).stream()
                .map(this::toPendingVO)
                .toList();
    }

    /**
     * 页面刷新后不再依赖前端内存恢复高风险工具确认状态。
     * 这里以会话为粒度读取仍处于 PENDING 的记录，同时把已经超过 TTL 的记录推进到 EXPIRED，
     * 让“可继续操作的待确认项”和“历史治理状态”保持一致。
     */
    @Transactional
    public List<ToolConfirmationPendingVO> listPendingByConversationId(Long conversationId) {
        return loadConversationConfirmations(conversationId).stream()
                .filter(confirmation -> "PENDING".equalsIgnoreCase(confirmation.getStatusCode()))
                .map(this::toPendingVO)
                .toList();
    }

    @Transactional
    public ToolConfirmationDecision approve(Long confirmationId, String continuationRequestId) {
        return continueDecision(
                confirmationId,
                "APPROVED",
                "USER_APPROVED",
                continuationRequestId,
                "确认执行"
        );
    }

    @Transactional
    public ToolConfirmationDecision reject(Long confirmationId, String continuationRequestId) {
        return continueDecision(
                confirmationId,
                "REJECTED",
                "USER_REJECTED",
                continuationRequestId,
                "拒绝执行"
        );
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
                confirmation.getUserMessageId(),
                confirmation.getToolCallId(),
                confirmation.getToolName(),
                confirmation.getToolTitle(),
                confirmation.getServerName(),
                confirmation.getRiskLevel(),
                confirmation.getStatusCode(),
                buildStatusMessage(confirmation),
                TimeUtils.toEpochMillis(confirmation.getExpiresAt()),
                TimeUtils.toEpochMillis(confirmation.getCreatedAt())
        );
    }

    private boolean isExpired(AgentToolConfirmationDO confirmation) {
        return confirmation.getExpiresAt() != null && confirmation.getExpiresAt().isBefore(LocalDateTime.now());
    }

    private String buildStatusMessage(AgentToolConfirmationDO confirmation) {
        String displayName = confirmation.getToolTitle() != null && !confirmation.getToolTitle().isBlank()
                ? confirmation.getToolTitle()
                : confirmation.getToolName();
        String statusCode = confirmation.getStatusCode() == null ? "PENDING" : confirmation.getStatusCode().toUpperCase();
        return switch (statusCode) {
            case "APPROVED" -> "工具 " + displayName + " 已确认，系统正在继续执行这一轮回答。";
            case "REJECTED" -> "工具 " + displayName + " 已被拒绝执行，系统会改用拒绝结果继续完成回答。";
            case "EXECUTED" -> "工具 " + displayName + " 已确认并执行完成。";
            case "FAILED" -> "工具 " + displayName + " 的确认续跑已失败"
                    + buildReasonSuffix(confirmation.getDecisionReason())
                    + "。";
            case "EXPIRED" -> "工具 " + displayName + " 的确认已过期，当前不能再继续执行。";
            default -> "工具 " + displayName
                    + " 被判定为 " + confirmation.getRiskLevel()
                    + " 风险，需用户确认后才能继续执行。";
        };
    }

    private List<AgentToolConfirmationDO> loadConversationConfirmations(Long conversationId) {
        return agentToolConfirmationMapper.selectByConversationId(conversationId).stream()
                .map(this::expireIfNecessary)
                .toList();
    }

    private AgentToolConfirmationDO expireIfNecessary(AgentToolConfirmationDO confirmation) {
        if (!"PENDING".equalsIgnoreCase(confirmation.getStatusCode()) || !isExpired(confirmation)) {
            return confirmation;
        }
        agentToolConfirmationMapper.markExpired(confirmation.getId());
        return requireConfirmation(confirmation.getId());
    }

    private String buildReasonSuffix(String decisionReason) {
        if (decisionReason == null || decisionReason.isBlank()) {
            return "";
        }
        return "，原因：" + decisionReason;
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

    /**
     * 工具确认接口要同时处理两类情况：
     * 1. 正常从 PENDING 进入 APPROVED / REJECTED，并启动新的 continuation
     * 2. 用户刷新页面、双击按钮或多个页面并发操作时，请求落在“状态已经切走”的窗口期
     *
     * 第二类情况如果已经进入同一决策，就直接复用已有 continuationRequestId，
     * 避免重复创建续跑链路；如果状态已经走到其他分支，再明确返回当前不可继续操作的原因。
     */
    private ToolConfirmationDecision continueDecision(
            Long confirmationId,
            String targetStatus,
            String decisionReason,
            String continuationRequestId,
            String actionLabel
    ) {
        AgentToolConfirmationDO confirmation = normalizeOperableConfirmation(confirmationId);
        if (agentToolConfirmationMapper.transitionStatus(
                confirmationId,
                "PENDING",
                targetStatus,
                continuationRequestId,
                decisionReason
        ) == 1) {
            return new ToolConfirmationDecision(requireConfirmation(confirmationId), false);
        }

        AgentToolConfirmationDO latest = requireConfirmation(confirmationId);
        if (targetStatus.equalsIgnoreCase(latest.getStatusCode()) && hasText(latest.getContinuationRequestId())) {
            return new ToolConfirmationDecision(latest, true);
        }
        throw new IllegalArgumentException(buildInoperableMessage(latest, actionLabel));
    }

    private AgentToolConfirmationDO normalizeOperableConfirmation(Long confirmationId) {
        AgentToolConfirmationDO confirmation = requireConfirmation(confirmationId);
        if (!"PENDING".equalsIgnoreCase(confirmation.getStatusCode())) {
            return confirmation;
        }
        if (!isExpired(confirmation)) {
            return confirmation;
        }
        agentToolConfirmationMapper.markExpired(confirmationId);
        return requireConfirmation(confirmationId);
    }

    private String buildInoperableMessage(AgentToolConfirmationDO confirmation, String actionLabel) {
        String statusCode = confirmation.getStatusCode() == null ? "PENDING" : confirmation.getStatusCode().toUpperCase();
        return switch (statusCode) {
            case "APPROVED" -> hasText(confirmation.getContinuationRequestId())
                    ? "待确认工具调用已确认执行，正在使用已有续跑请求继续生成: " + confirmation.getContinuationRequestId()
                    : "待确认工具调用已确认执行，当前不能重复" + actionLabel + "。";
            case "REJECTED" -> hasText(confirmation.getContinuationRequestId())
                    ? "待确认工具调用已拒绝执行，正在使用已有续跑请求继续生成: " + confirmation.getContinuationRequestId()
                    : "待确认工具调用已拒绝执行，当前不能重复" + actionLabel + "。";
            case "EXECUTED" -> "待确认工具调用已执行完成，请刷新会话查看最终结果。";
            case "FAILED" -> "待确认工具调用的确认续跑已经失败，请刷新会话查看失败状态。";
            case "EXPIRED" -> "待确认工具调用已过期，当前不能再" + actionLabel + "。";
            default -> "待确认工具调用当前不可操作，状态为: " + statusCode;
        };
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record ToolConfirmationDecision(
            AgentToolConfirmationDO confirmation,
            boolean reusedExistingContinuation
    ) {
    }
}
