package com.weilair.openagent.web.vo;

import java.util.List;
import java.util.Map;

public record McpServerVO(
        Long id,
        String name,
        String description,
        String protocolType,
        String transportType,
        String endpoint,
        String command,
        List<String> args,
        Map<String, String> env,
        Map<String, String> headers,
        String authType,
        Object authConfig,
        Boolean enabled,
        String healthStatus,
        String riskLevel,
        Long toolCount,
        Long lastConnectedAt,
        Long lastSyncAt,
        Long createdAt,
        Long updatedAt
) {
}
