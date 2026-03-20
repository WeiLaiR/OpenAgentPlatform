package com.weilair.openagent.web.dto;

public record ConversationSettingsUpdateRequest(
        Boolean enableRag,
        Boolean enableAgent
) {
}
