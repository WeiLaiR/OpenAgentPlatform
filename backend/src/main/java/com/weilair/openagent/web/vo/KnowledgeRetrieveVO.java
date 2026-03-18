package com.weilair.openagent.web.vo;

import java.util.List;

public record KnowledgeRetrieveVO(
        String query,
        List<RagSnippetVO> snippets,
        Integer total
) {
}
