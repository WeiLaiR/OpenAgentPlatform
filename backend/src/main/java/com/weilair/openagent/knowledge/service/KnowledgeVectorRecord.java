package com.weilair.openagent.knowledge.service;

record KnowledgeVectorRecord(
        String segmentRef,
        Long knowledgeBaseId,
        Long fileId,
        Integer segmentNo,
        Integer pageNo,
        float[] embedding
) {
}
