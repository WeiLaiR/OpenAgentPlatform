package com.weilair.openagent.web.vo;

public record KnowledgeFileIndexVO(
        Long fileId,
        Long knowledgeBaseId,
        String parseStatus,
        String indexStatus,
        String parserName,
        Integer segmentCount,
        String errorMessage,
        Long updatedAt
) {
}
