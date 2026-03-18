package com.weilair.openagent.knowledge.service;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import com.weilair.openagent.knowledge.model.KnowledgeBaseDO;
import com.weilair.openagent.knowledge.model.KnowledgeFileDO;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class TikaKnowledgeDocumentParser implements KnowledgeDocumentParser {
    /**
     * 第一版统一用 Tika 拉平成纯文本：
     * - 保证 txt / md / docx 的解析入口一致
     * - 先把标题、content-type 等信息收进 metadata，给后续高级解析预留升级位
     */

    private static final String PARSER_NAME = "TIKA";
    private static final int MAX_PARSED_CHARACTERS = 2_000_000;

    private final Tika tika = new Tika();

    @Override
    public ParsedKnowledgeDocument parse(KnowledgeBaseDO knowledgeBase, KnowledgeFileDO knowledgeFile, InputStream inputStream) {
        validateParserStrategy(knowledgeBase);

        try {
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, knowledgeFile.getFileName());

            String content = normalizeContent(tika.parseToString(inputStream, metadata, MAX_PARSED_CHARACTERS));
            if (!StringUtils.hasText(content)) {
                throw new IllegalStateException("文档解析结果为空，暂时无法建立索引。");
            }

            Map<String, Object> parserMetadata = new LinkedHashMap<>();
            putIfText(parserMetadata, "resourceName", knowledgeFile.getFileName());
            putIfText(parserMetadata, "contentType", metadata.get(Metadata.CONTENT_TYPE));
            putIfText(parserMetadata, "title", metadata.get(TikaCoreProperties.TITLE));
            parserMetadata.put("parsedCharacterCount", content.length());

            String sourceTitle = StringUtils.hasText(metadata.get(TikaCoreProperties.TITLE))
                    ? metadata.get(TikaCoreProperties.TITLE)
                    : knowledgeFile.getFileName();
            return new ParsedKnowledgeDocument(PARSER_NAME, sourceTitle, content, parserMetadata);
        } catch (Exception exception) {
            throw new IllegalStateException("Tika 解析文件失败: " + exception.getMessage(), exception);
        }
    }

    private void validateParserStrategy(KnowledgeBaseDO knowledgeBase) {
        String parserStrategy = knowledgeBase.getParserStrategy();
        if (StringUtils.hasText(parserStrategy) && !"TIKA".equalsIgnoreCase(parserStrategy.trim())) {
            throw new IllegalArgumentException("当前仅支持 TIKA 解析策略，实际配置为: " + parserStrategy);
        }
    }

    private String normalizeContent(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n').trim();
        return StringUtils.hasText(normalized) ? normalized : null;
    }

    private void putIfText(Map<String, Object> metadata, String key, String value) {
        if (StringUtils.hasText(value)) {
            metadata.put(key, value.trim());
        }
    }
}
