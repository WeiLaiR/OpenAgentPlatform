package com.weilair.openagent.knowledge.service;

import java.io.InputStream;

import com.weilair.openagent.knowledge.model.KnowledgeBaseDO;
import com.weilair.openagent.knowledge.model.KnowledgeFileDO;

public interface KnowledgeDocumentParser {

    /**
     * 当前阶段先只接一套基础解析器，实现 txt / md / docx 稳定入库。
     * 后续如果要升级成标题感知或目录感知解析，只需要替换这一层。
     */
    ParsedKnowledgeDocument parse(KnowledgeBaseDO knowledgeBase, KnowledgeFileDO knowledgeFile, InputStream inputStream);
}
