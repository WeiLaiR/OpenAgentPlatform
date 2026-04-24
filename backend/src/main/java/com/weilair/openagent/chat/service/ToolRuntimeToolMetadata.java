package com.weilair.openagent.chat.service;

public record ToolRuntimeToolMetadata(
        Long toolSnapshotId,
        Long serverId,
        String serverName,
        String runtimeToolName,
        String originToolName,
        String toolTitle,
        ToolRiskLevel riskLevel,
        boolean enabled,
        String healthStatus
) {
}
