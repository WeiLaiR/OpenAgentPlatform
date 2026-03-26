package com.weilair.openagent.web.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatSendRequest(
        Long conversationId,
        @NotBlank(message = "消息不能为空")
        @Size(max = 20000, message = "消息长度不能超过 20000 个字符")
        String message,
        Boolean enableRag,
        Boolean enableAgent,
        Boolean memoryEnabled,
        List<Long> knowledgeBaseIds,
        List<Long> mcpServerIds
) {
}
