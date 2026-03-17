package com.weilair.openagent.web.dto;

import jakarta.validation.constraints.Size;

public record ConversationCreateRequest(
        @Size(max = 255, message = "会话标题长度不能超过 255 个字符")
        String title,
        Boolean enableRag,
        Boolean enableAgent
) {
}
