package com.weilair.openagent.knowledge.service;

import java.util.Map;

record KnowledgeChunk(
        int segmentNo,
        String text,
        Integer tokenCount,
        Integer pageNo,
        String sourceTitle,
        String sourcePath,
        Map<String, Object> metadata
) {
}
