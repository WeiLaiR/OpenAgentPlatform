package com.weilair.openagent.web.vo;

public record KnowledgeFileDeleteVO(
        Long fileId,
        Long knowledgeBaseId,
        Integer deletedSegmentCount,
        Integer deletedVectorCount,
        Boolean storageDeleted
) {
}
