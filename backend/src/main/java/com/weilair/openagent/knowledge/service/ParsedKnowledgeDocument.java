package com.weilair.openagent.knowledge.service;

import java.util.Map;

record ParsedKnowledgeDocument(
        String parserName,
        String title,
        String content,
        Map<String, Object> metadata
) {
}
