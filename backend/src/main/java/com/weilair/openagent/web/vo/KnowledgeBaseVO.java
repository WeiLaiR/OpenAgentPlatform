package com.weilair.openagent.web.vo;

public record KnowledgeBaseVO(
        Long id,
        String name,
        String description,
        String status,
        String embeddingModelName,
        Integer embeddingDimension,
        String milvusDatabaseName,
        String milvusCollectionName,
        String milvusPartitionName,
        String parserStrategy,
        String chunkStrategy,
        Integer chunkSize,
        Integer chunkOverlap,
        Long fileCount,
        Long segmentCount,
        Long createdAt,
        Long updatedAt
) {
}
