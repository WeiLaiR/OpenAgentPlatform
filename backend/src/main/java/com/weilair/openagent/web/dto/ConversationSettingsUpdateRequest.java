package com.weilair.openagent.web.dto;

import java.util.List;

public record ConversationSettingsUpdateRequest(
        Boolean enableRag,
        Boolean enableAgent,
        Boolean memoryEnabled,
        List<Long> knowledgeBaseIds,
        List<Long> mcpServerIds
) {
}
