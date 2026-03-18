package com.weilair.openagent.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgeBaseCreateRequest(
        @NotBlank(message = "知识库名称不能为空")
        @Size(max = 128, message = "知识库名称长度不能超过 128 个字符")
        String name,
        @Size(max = 512, message = "知识库描述长度不能超过 512 个字符")
        String description,
        @Size(max = 128, message = "embeddingModelName 长度不能超过 128 个字符")
        String embeddingModelName,
        Integer embeddingDimension,
        @Size(max = 64, message = "parserStrategy 长度不能超过 64 个字符")
        String parserStrategy,
        @Size(max = 64, message = "chunkStrategy 长度不能超过 64 个字符")
        String chunkStrategy,
        Integer chunkSize,
        Integer chunkOverlap
) {
}
