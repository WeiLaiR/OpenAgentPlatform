package com.weilair.openagent.web.vo;

import java.util.List;

public record McpToolSyncVO(
        Long serverId,
        String serverName,
        Integer syncedCount,
        List<String> runtimeToolNames,
        Long syncedAt
) {
}
