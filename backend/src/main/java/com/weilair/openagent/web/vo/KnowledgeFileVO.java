package com.weilair.openagent.web.vo;

public record KnowledgeFileVO(
        Long id,
        Long knowledgeBaseId,
        String fileName,
        String fileExt,
        Long fileSize,
        String parseStatus,
        String indexStatus,
        String storageUri,
        String errorMessage,
        Long createdAt,
        Long updatedAt
) {
}
