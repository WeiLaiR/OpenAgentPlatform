package com.weilair.openagent.web.vo;

public record McpToolSnapshotVO(
        Long id,
        Long mcpServerId,
        String serverName,
        String runtimeToolName,
        String originToolName,
        String toolTitle,
        String description,
        Object inputSchemaJson,
        Object outputSchemaJson,
        Boolean enabled,
        String riskLevel,
        String versionNo,
        String syncHash,
        Long syncedAt,
        Long updatedAt
) {
}
