package com.weilair.openagent.knowledge.service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weilair.openagent.common.util.TimeUtils;
import com.weilair.openagent.knowledge.model.KnowledgeBaseDO;
import com.weilair.openagent.knowledge.model.KnowledgeFileDO;
import com.weilair.openagent.knowledge.model.KnowledgeSegmentDO;
import com.weilair.openagent.knowledge.persistence.mapper.KnowledgeFileMapper;
import com.weilair.openagent.knowledge.persistence.mapper.KnowledgeSegmentMapper;
import com.weilair.openagent.web.vo.KnowledgeFileIndexVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class KnowledgeFileIndexService {
    /**
     * 这里负责编排“上传后的独立索引动作”：
     * 1. 先把 knowledge_file 推进到 PARSING / INDEXING
     * 2. 读取存储层文件并做 Tika 解析
     * 3. 做段落优先分片
     * 4. 通过 LangChain4j EmbeddingModel 生成向量
     * 5. 写 Milvus，再把原文 segment 落回 MySQL
     */

    private static final String PARSE_STATUS_PARSING = "PARSING";
    private static final String PARSE_STATUS_PARSED = "PARSED";
    private static final String PARSE_STATUS_FAILED = "FAILED";
    private static final String INDEX_STATUS_INDEXING = "INDEXING";
    private static final String INDEX_STATUS_INDEXED = "INDEXED";
    private static final String INDEX_STATUS_FAILED = "FAILED";
    private static final int ERROR_MESSAGE_MAX_LENGTH = 500;

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeFileService knowledgeFileService;
    private final KnowledgeFileStorageService knowledgeFileStorageService;
    private final KnowledgeFileMapper knowledgeFileMapper;
    private final KnowledgeSegmentMapper knowledgeSegmentMapper;
    private final KnowledgeDocumentParser knowledgeDocumentParser;
    private final KnowledgeChunkingService knowledgeChunkingService;
    private final KnowledgeEmbeddingService knowledgeEmbeddingService;
    private final MilvusKnowledgeVectorService milvusKnowledgeVectorService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public KnowledgeFileIndexService(
            KnowledgeBaseService knowledgeBaseService,
            KnowledgeFileService knowledgeFileService,
            KnowledgeFileStorageService knowledgeFileStorageService,
            KnowledgeFileMapper knowledgeFileMapper,
            KnowledgeSegmentMapper knowledgeSegmentMapper,
            KnowledgeDocumentParser knowledgeDocumentParser,
            KnowledgeChunkingService knowledgeChunkingService,
            KnowledgeEmbeddingService knowledgeEmbeddingService,
            MilvusKnowledgeVectorService milvusKnowledgeVectorService,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeFileService = knowledgeFileService;
        this.knowledgeFileStorageService = knowledgeFileStorageService;
        this.knowledgeFileMapper = knowledgeFileMapper;
        this.knowledgeSegmentMapper = knowledgeSegmentMapper;
        this.knowledgeDocumentParser = knowledgeDocumentParser;
        this.knowledgeChunkingService = knowledgeChunkingService;
        this.knowledgeEmbeddingService = knowledgeEmbeddingService;
        this.milvusKnowledgeVectorService = milvusKnowledgeVectorService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    public KnowledgeFileIndexVO indexKnowledgeFile(Long fileId) {
        KnowledgeFileDO knowledgeFile = knowledgeFileService.requireKnowledgeFile(fileId);
        KnowledgeBaseDO knowledgeBase = knowledgeBaseService.requireKnowledgeBase(knowledgeFile.getKnowledgeBaseId());
        List<String> existingVectorIds = knowledgeSegmentMapper.selectMilvusPrimaryKeysByFileId(fileId);

        // 先把文件推进到运行中状态，前端后续才能明确看到“已开始解析 / 索引”。
        updateProcessingState(fileId, PARSE_STATUS_PARSING, INDEX_STATUS_INDEXING, null, null, null);

        ParsedKnowledgeDocument parsedDocument = null;
        String parserResultJson = null;
        List<String> newVectorIds = List.of();
        try (InputStream inputStream = knowledgeFileStorageService.openInputStream(knowledgeFile.getStorageUri())) {
            parsedDocument = knowledgeDocumentParser.parse(knowledgeBase, knowledgeFile, inputStream);
            List<KnowledgeChunk> chunks = knowledgeChunkingService.split(knowledgeBase, knowledgeFile, parsedDocument);
            if (chunks.isEmpty()) {
                throw new IllegalStateException("文档分片结果为空，无法建立索引。");
            }

            // 解析完成后立即把 parser 结果写回 knowledge_file，便于前端展示解析摘要和失败定位。
            parserResultJson = writeJson(buildParserResult(knowledgeFile, knowledgeBase, parsedDocument, chunks.size()));
            updateProcessingState(
                    fileId,
                    PARSE_STATUS_PARSED,
                    INDEX_STATUS_INDEXING,
                    parsedDocument.parserName(),
                    parserResultJson,
                    null
            );

            List<float[]> embeddings = knowledgeEmbeddingService.embedAll(chunks.stream().map(KnowledgeChunk::text).toList());
            List<KnowledgeSegmentDO> segmentRows = buildSegments(knowledgeBase, knowledgeFile, chunks);
            List<KnowledgeVectorRecord> vectorRecords = buildVectorRecords(segmentRows, embeddings);
            newVectorIds = segmentRows.stream().map(KnowledgeSegmentDO::getMilvusPrimaryKey).toList();

            milvusKnowledgeVectorService.replaceFileVectors(knowledgeBase, existingVectorIds, vectorRecords);
            finalizeIndexing(fileId, segmentRows, parsedDocument.parserName(), parserResultJson);
            return toIndexVO(knowledgeFileService.requireKnowledgeFile(fileId), segmentRows.size());
        } catch (Exception exception) {
            handleIndexFailure(knowledgeBase, fileId, parsedDocument, parserResultJson, newVectorIds, exception);
            throw exception instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new IllegalStateException("知识库文件索引失败: " + exception.getMessage(), exception);
        }
    }

    private void finalizeIndexing(Long fileId, List<KnowledgeSegmentDO> segmentRows, String parserName, String parserResultJson) {
        transactionTemplate.executeWithoutResult(status -> {
            knowledgeSegmentMapper.deleteByFileId(fileId);
            knowledgeSegmentMapper.batchInsert(segmentRows);
            updateProcessingState(fileId, PARSE_STATUS_PARSED, INDEX_STATUS_INDEXED, parserName, parserResultJson, null);
        });
    }

    private void handleIndexFailure(
            KnowledgeBaseDO knowledgeBase,
            Long fileId,
            ParsedKnowledgeDocument parsedDocument,
            String parserResultJson,
            List<String> newVectorIds,
            Exception exception
    ) {
        if (newVectorIds != null && !newVectorIds.isEmpty()) {
            try {
                milvusKnowledgeVectorService.deleteVectors(knowledgeBase, newVectorIds);
            } catch (RuntimeException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
        }

        String parserName = parsedDocument != null ? parsedDocument.parserName() : null;
        String parseStatus = parsedDocument == null ? PARSE_STATUS_FAILED : PARSE_STATUS_PARSED;
        updateProcessingState(
                fileId,
                parseStatus,
                INDEX_STATUS_FAILED,
                parserName,
                parserResultJson,
                truncateErrorMessage(exception.getMessage())
        );
    }

    private List<KnowledgeSegmentDO> buildSegments(
            KnowledgeBaseDO knowledgeBase,
            KnowledgeFileDO knowledgeFile,
            List<KnowledgeChunk> chunks
    ) {
        List<KnowledgeSegmentDO> segments = new ArrayList<>(chunks.size());
        for (KnowledgeChunk chunk : chunks) {
            KnowledgeSegmentDO segment = new KnowledgeSegmentDO();
            segment.setKnowledgeBaseId(knowledgeBase.getId());
            segment.setFileId(knowledgeFile.getId());
            segment.setSegmentNo(chunk.segmentNo());
            segment.setTextPreview(KnowledgeChunkingService.toTextPreview(chunk.text()));
            segment.setFullText(chunk.text());
            segment.setTokenCount(chunk.tokenCount());
            segment.setPageNo(chunk.pageNo());
            segment.setSourceTitle(chunk.sourceTitle());
            segment.setSourcePath(chunk.sourcePath());
            segment.setMetadataJson(writeJson(chunk.metadata()));
            segment.setMilvusPrimaryKey(resolveMilvusPrimaryKey(knowledgeBase.getId(), knowledgeFile.getId(), chunk.segmentNo()));
            segments.add(segment);
        }
        return segments;
    }

    private List<KnowledgeVectorRecord> buildVectorRecords(List<KnowledgeSegmentDO> segments, List<float[]> embeddings) {
        if (segments.size() != embeddings.size()) {
            throw new IllegalStateException("segment 数量与 embedding 数量不一致，无法写入 Milvus。");
        }

        List<KnowledgeVectorRecord> vectorRecords = new ArrayList<>(segments.size());
        for (int index = 0; index < segments.size(); index++) {
            KnowledgeSegmentDO segment = segments.get(index);
            vectorRecords.add(new KnowledgeVectorRecord(
                    segment.getMilvusPrimaryKey(),
                    segment.getKnowledgeBaseId(),
                    segment.getFileId(),
                    segment.getSegmentNo(),
                    segment.getPageNo(),
                    embeddings.get(index)
            ));
        }
        return vectorRecords;
    }

    private Map<String, Object> buildParserResult(
            KnowledgeFileDO knowledgeFile,
            KnowledgeBaseDO knowledgeBase,
            ParsedKnowledgeDocument parsedDocument,
            int segmentCount
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fileName", knowledgeFile.getFileName());
        result.put("fileExt", knowledgeFile.getFileExt());
        result.put("parserName", parsedDocument.parserName());
        result.put("sourceTitle", parsedDocument.title());
        result.put("segmentCount", segmentCount);
        result.put("chunkSize", knowledgeBase.getChunkSize());
        result.put("chunkOverlap", knowledgeBase.getChunkOverlap());
        result.put("metadata", parsedDocument.metadata());
        return result;
    }

    private KnowledgeFileIndexVO toIndexVO(KnowledgeFileDO knowledgeFile, int segmentCount) {
        return new KnowledgeFileIndexVO(
                knowledgeFile.getId(),
                knowledgeFile.getKnowledgeBaseId(),
                knowledgeFile.getParseStatus(),
                knowledgeFile.getIndexStatus(),
                knowledgeFile.getParserName(),
                segmentCount,
                knowledgeFile.getErrorMessage(),
                TimeUtils.toEpochMillis(knowledgeFile.getUpdatedAt())
        );
    }

    private void updateProcessingState(
            Long fileId,
            String parseStatus,
            String indexStatus,
            String parserName,
            String parserResultJson,
            String errorMessage
    ) {
        knowledgeFileMapper.updateProcessingState(
                fileId,
                parseStatus,
                indexStatus,
                parserName,
                parserResultJson,
                errorMessage
        );
    }

    private String resolveMilvusPrimaryKey(Long knowledgeBaseId, Long fileId, Integer segmentNo) {
        return "ks_%d_%d_%d".formatted(knowledgeBaseId, fileId, segmentNo);
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("知识库索引结果序列化失败", exception);
        }
    }

    private String truncateErrorMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.length() <= ERROR_MESSAGE_MAX_LENGTH) {
            return errorMessage;
        }
        return errorMessage.substring(0, ERROR_MESSAGE_MAX_LENGTH);
    }
}
