package com.weilair.openagent.web.vo;

public record ToolConfirmationPendingVO(
        Long id,
        String requestId,
        Long conversationId,
        String toolCallId,
        String toolName,
        String toolTitle,
        String serverName,
        String riskLevel,
        String status,
        String statusMessage
) {
}
