package com.weilair.openagent.chat.service;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import org.springframework.stereotype.Component;

@Component
public class AgentToolRiskGuard {
    /**
     * 这一层统一负责“工具是否允许真正执行”的第一版风险决策：
     * 1. 当前先把规则固定为 `LOW/MEDIUM -> ALLOW`，`HIGH -> REQUIRE_CONFIRMATION`
     * 2. 不把决策逻辑散落在各个 `ToolExecutor`
     * 3. 即使当前还没接入完整的“人工确认后继续本轮”，也先让高风险工具统一被平台拦截并可解释
     */

    private static final String REASON_HIGH_RISK_REQUIRES_CONFIRMATION = "HIGH_RISK_REQUIRES_CONFIRMATION";
    private static final String REASON_TOOL_METADATA_MISSING = "TOOL_METADATA_MISSING";
    private static final String REASON_TOOL_DISABLED = "TOOL_DISABLED";
    private static final String REASON_SERVER_UNHEALTHY = "SERVER_UNHEALTHY";

    public AgentToolRiskDecision evaluate(ToolRuntimeToolMetadata metadata) {
        if (metadata == null) {
            return AgentToolRiskDecision.deny(ToolRiskLevel.HIGH, REASON_TOOL_METADATA_MISSING);
        }
        if (!metadata.enabled()) {
            return AgentToolRiskDecision.deny(metadata.riskLevel(), REASON_TOOL_DISABLED);
        }
        if (metadata.healthStatus() != null && "UNHEALTHY".equalsIgnoreCase(metadata.healthStatus())) {
            return AgentToolRiskDecision.deny(metadata.riskLevel(), REASON_SERVER_UNHEALTHY);
        }
        if (metadata.riskLevel() == ToolRiskLevel.HIGH) {
            return AgentToolRiskDecision.requireConfirmation(metadata.riskLevel(), REASON_HIGH_RISK_REQUIRES_CONFIRMATION);
        }
        return AgentToolRiskDecision.allow(metadata.riskLevel(), "ALLOWED");
    }

    public ToolExecutor wrap(ToolExecutor delegate, ToolRuntimeToolMetadata metadata) {
        return new ToolExecutor() {
            @Override
            public String execute(ToolExecutionRequest toolExecutionRequest, Object memoryId) {
                return executeWithContext(toolExecutionRequest, null).resultText();
            }

            @Override
            public ToolExecutionResult executeWithContext(
                    ToolExecutionRequest toolExecutionRequest,
                    dev.langchain4j.invocation.InvocationContext invocationContext
            ) {
                AgentToolRiskDecision decision = evaluate(metadata);
                if (!decision.isAllowed()) {
                    return ToolExecutionResult.builder()
                            .isError(true)
                            .resultText(buildBlockedResultText(toolExecutionRequest, metadata, decision))
                            .attributes(blockedAttributes(toolExecutionRequest, metadata, decision))
                            .build();
                }
                return delegate.executeWithContext(toolExecutionRequest, invocationContext);
            }
        };
    }

    private String buildBlockedResultText(
            ToolExecutionRequest toolExecutionRequest,
            ToolRuntimeToolMetadata metadata,
            AgentToolRiskDecision decision
    ) {
        String toolName = toolExecutionRequest == null ? "unknown-tool" : toolExecutionRequest.name();
        if (decision.action() == AgentToolRiskDecision.Action.REQUIRE_CONFIRMATION) {
            return "工具 " + toolName + " 已被平台拦截：该工具当前标记为 HIGH 风险，"
                    + "第一版运行时要求先人工确认后才能执行，本轮未直接执行该工具。"
                    + "请基于这一限制继续回答，并明确说明工具曾被尝试调用但未执行。";
        }

        String reason = decision.reason() == null ? "UNKNOWN" : decision.reason();
        return "工具 " + toolName + " 已被平台阻断，原因: " + reason
                + "。本轮未执行该工具，请基于这一限制继续回答。";
    }

    private Map<String, Object> blockedAttributes(
            ToolExecutionRequest toolExecutionRequest,
            ToolRuntimeToolMetadata metadata,
            AgentToolRiskDecision decision
    ) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("toolCallId", toolExecutionRequest == null ? null : toolExecutionRequest.id());
        attributes.put("toolName", toolExecutionRequest == null ? null : toolExecutionRequest.name());
        attributes.put("riskLevel", decision.riskLevel().name());
        attributes.put("decision", decision.action().name());
        attributes.put("reason", decision.reason());
        if (metadata != null) {
            attributes.put("serverId", metadata.serverId());
            attributes.put("serverName", metadata.serverName());
            attributes.put("toolSnapshotId", metadata.toolSnapshotId());
        }
        return attributes;
    }
}
