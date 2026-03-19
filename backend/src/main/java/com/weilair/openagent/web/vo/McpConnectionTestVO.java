package com.weilair.openagent.web.vo;

import java.util.List;

public record McpConnectionTestVO(
        Long serverId,
        String serverName,
        String healthStatus,
        Integer toolCount,
        List<String> toolNames,
        Long checkedAt
) {
}
