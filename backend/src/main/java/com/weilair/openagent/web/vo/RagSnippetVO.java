package com.weilair.openagent.web.vo;

public record RagSnippetVO(
        Long knowledgeBaseId,
        Long fileId,
        Integer segmentNo,
        Float score,
        String textPreview,
        String fullText,
        Integer tokenCount,
        Integer pageNo,
        String sourceTitle,
        String sourcePath,
        String milvusPrimaryKey
) {
}
