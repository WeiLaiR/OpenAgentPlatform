package com.weilair.openagent.web.vo;

public record KnowledgeSegmentVO(
        Long id,
        Long knowledgeBaseId,
        Long fileId,
        Integer segmentNo,
        String textPreview,
        String fullText,
        Integer tokenCount,
        Integer pageNo,
        String sourceTitle,
        String sourcePath,
        Object metadataJson,
        String milvusPrimaryKey,
        Long createdAt
) {
}
