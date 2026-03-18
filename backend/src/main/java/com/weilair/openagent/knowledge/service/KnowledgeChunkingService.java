package com.weilair.openagent.knowledge.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.weilair.openagent.knowledge.model.KnowledgeBaseDO;
import com.weilair.openagent.knowledge.model.KnowledgeFileDO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class KnowledgeChunkingService {
    /**
     * 当前阶段的分片策略保持简单、稳定、可解释：
     * 1. 先按空行切段落
     * 2. 超长段落再按字符窗口切分
     * 3. 再按 chunkSize / chunkOverlap 合并成最终 segment
     *
     * 后续如果要引入标题感知和目录感知，只需要替换这里，不必重写索引主流程。
     */

    private static final int DEFAULT_CHUNK_SIZE = 1000;
    private static final int DEFAULT_CHUNK_OVERLAP = 150;
    private static final int TEXT_PREVIEW_LIMIT = 240;

    public List<KnowledgeChunk> split(KnowledgeBaseDO knowledgeBase, KnowledgeFileDO knowledgeFile, ParsedKnowledgeDocument document) {
        validateChunkStrategy(knowledgeBase);

        int chunkSize = resolveChunkSize(knowledgeBase);
        int chunkOverlap = resolveChunkOverlap(knowledgeBase, chunkSize);
        List<ParagraphUnit> paragraphUnits = toParagraphUnits(document.content(), chunkSize, chunkOverlap);
        if (paragraphUnits.isEmpty()) {
            return List.of();
        }

        List<KnowledgeChunk> chunks = new ArrayList<>();
        StringBuilder currentText = new StringBuilder();
        int currentParagraphStart = -1;
        int currentParagraphEnd = -1;
        int nextSegmentNo = 1;

        for (ParagraphUnit paragraphUnit : paragraphUnits) {
            if (currentText.isEmpty()) {
                currentText.append(paragraphUnit.text());
                currentParagraphStart = paragraphUnit.paragraphIndex();
                currentParagraphEnd = paragraphUnit.paragraphIndex();
                continue;
            }

            int candidateLength = currentText.length() + 2 + paragraphUnit.text().length();
            if (candidateLength <= chunkSize) {
                currentText.append("\n\n").append(paragraphUnit.text());
                currentParagraphEnd = paragraphUnit.paragraphIndex();
                continue;
            }

            chunks.add(buildChunk(
                    nextSegmentNo++,
                    currentText.toString(),
                    currentParagraphStart,
                    currentParagraphEnd,
                    knowledgeBase,
                    knowledgeFile,
                    document
            ));

            String overlapText = resolveOverlapText(currentText.toString(), chunkOverlap);
            currentText = new StringBuilder();
            if (StringUtils.hasText(overlapText) && overlapText.length() + 2 + paragraphUnit.text().length() <= chunkSize) {
                currentText.append(overlapText).append("\n\n").append(paragraphUnit.text());
            } else {
                currentText.append(paragraphUnit.text());
            }
            currentParagraphStart = paragraphUnit.paragraphIndex();
            currentParagraphEnd = paragraphUnit.paragraphIndex();
        }

        if (!currentText.isEmpty()) {
            chunks.add(buildChunk(
                    nextSegmentNo,
                    currentText.toString(),
                    currentParagraphStart,
                    currentParagraphEnd,
                    knowledgeBase,
                    knowledgeFile,
                    document
            ));
        }
        return chunks;
    }

    static String toTextPreview(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String preview = text.replaceAll("\\s+", " ").trim();
        if (preview.length() <= TEXT_PREVIEW_LIMIT) {
            return preview;
        }
        return preview.substring(0, TEXT_PREVIEW_LIMIT);
    }

    private List<ParagraphUnit> toParagraphUnits(String content, int chunkSize, int chunkOverlap) {
        String[] rawParagraphs = content.split("\\n\\s*\\n+");
        List<ParagraphUnit> paragraphUnits = new ArrayList<>();
        int paragraphIndex = 1;
        for (String rawParagraph : rawParagraphs) {
            String paragraph = rawParagraph == null ? null : rawParagraph.trim();
            if (!StringUtils.hasText(paragraph)) {
                continue;
            }

            if (paragraph.length() <= chunkSize) {
                paragraphUnits.add(new ParagraphUnit(paragraph, paragraphIndex++));
                continue;
            }

            paragraphUnits.addAll(splitLongParagraph(paragraph, paragraphIndex++, chunkSize, chunkOverlap));
        }
        return paragraphUnits;
    }

    private List<ParagraphUnit> splitLongParagraph(String paragraph, int paragraphIndex, int chunkSize, int chunkOverlap) {
        List<ParagraphUnit> units = new ArrayList<>();
        int start = 0;
        int overlap = Math.min(chunkOverlap, Math.max(0, chunkSize / 2));
        while (start < paragraph.length()) {
            int end = Math.min(start + chunkSize, paragraph.length());
            units.add(new ParagraphUnit(paragraph.substring(start, end).trim(), paragraphIndex));
            if (end >= paragraph.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return units;
    }

    private KnowledgeChunk buildChunk(
            int segmentNo,
            String text,
            int paragraphStart,
            int paragraphEnd,
            KnowledgeBaseDO knowledgeBase,
            KnowledgeFileDO knowledgeFile,
            ParsedKnowledgeDocument document
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("parserName", document.parserName());
        metadata.put("chunkStrategy", knowledgeBase.getChunkStrategy());
        metadata.put("chunkSize", resolveChunkSize(knowledgeBase));
        metadata.put("chunkOverlap", resolveChunkOverlap(knowledgeBase, resolveChunkSize(knowledgeBase)));
        metadata.put("paragraphStart", paragraphStart);
        metadata.put("paragraphEnd", paragraphEnd);
        metadata.put("sourceFileExt", knowledgeFile.getFileExt());

        return new KnowledgeChunk(
                segmentNo,
                text,
                estimateTokenCount(text),
                null,
                document.title(),
                knowledgeFile.getStorageUri(),
                metadata
        );
    }

    private int estimateTokenCount(String text) {
        // 第一版先用经验值回填 token_count，后续再替换成真实 tokenizer 统计。
        return Math.max(1, (int) Math.ceil(text.length() / 4.0d));
    }

    private String resolveOverlapText(String text, int chunkOverlap) {
        if (!StringUtils.hasText(text) || chunkOverlap <= 0) {
            return null;
        }
        int startIndex = Math.max(0, text.length() - chunkOverlap);
        String overlap = text.substring(startIndex).trim();
        return StringUtils.hasText(overlap) ? overlap : null;
    }

    private int resolveChunkSize(KnowledgeBaseDO knowledgeBase) {
        Integer configuredChunkSize = knowledgeBase.getChunkSize();
        return configuredChunkSize == null || configuredChunkSize <= 0 ? DEFAULT_CHUNK_SIZE : configuredChunkSize;
    }

    private int resolveChunkOverlap(KnowledgeBaseDO knowledgeBase, int chunkSize) {
        Integer configuredChunkOverlap = knowledgeBase.getChunkOverlap();
        int resolved = configuredChunkOverlap == null || configuredChunkOverlap < 0 ? DEFAULT_CHUNK_OVERLAP : configuredChunkOverlap;
        return Math.min(resolved, Math.max(0, chunkSize - 1));
    }

    private void validateChunkStrategy(KnowledgeBaseDO knowledgeBase) {
        String chunkStrategy = knowledgeBase.getChunkStrategy();
        if (StringUtils.hasText(chunkStrategy) && !"DEFAULT".equalsIgnoreCase(chunkStrategy.trim())) {
            throw new IllegalArgumentException("当前仅支持 DEFAULT 分片策略，实际配置为: " + chunkStrategy);
        }
    }

    private record ParagraphUnit(String text, int paragraphIndex) {
    }
}
