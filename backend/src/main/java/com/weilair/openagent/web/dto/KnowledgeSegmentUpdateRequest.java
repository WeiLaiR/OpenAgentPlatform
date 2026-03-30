package com.weilair.openagent.web.dto;

import jakarta.validation.constraints.NotBlank;

public record KnowledgeSegmentUpdateRequest(
        @NotBlank(message = "知识库片段文本不能为空")
        String fullText,
        Object metadataJson
) {
}
