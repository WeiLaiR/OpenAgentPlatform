package com.weilair.openagent.knowledge.service;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.weilair.openagent.common.util.TimeUtils;
import com.weilair.openagent.knowledge.model.KnowledgeBaseDO;
import com.weilair.openagent.knowledge.model.KnowledgeSegmentDO;
import com.weilair.openagent.knowledge.persistence.mapper.KnowledgeSegmentMapper;
import com.weilair.openagent.web.vo.KnowledgeSegmentVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class KnowledgeSegmentService {
    /**
     * 片段列表是“文件级管理”向“片段级管理”过渡的第一步：
     * - 先支持按文件查看切分结果，帮助排查“为什么命中了这些片段”
     * - 先把原文、预览、token 和 metadata 回出来，编辑与单片重算留到下一阶段
     */
    private static final int DEFAULT_LIST_LIMIT = 200;
    private static final int MAX_LIST_LIMIT = 500;

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeFileService knowledgeFileService;
    private final KnowledgeSegmentMapper knowledgeSegmentMapper;
    private final KnowledgeEmbeddingService knowledgeEmbeddingService;
    private final MilvusKnowledgeVectorService milvusKnowledgeVectorService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public KnowledgeSegmentService(
            KnowledgeBaseService knowledgeBaseService,
            KnowledgeFileService knowledgeFileService,
            KnowledgeSegmentMapper knowledgeSegmentMapper,
            KnowledgeEmbeddingService knowledgeEmbeddingService,
            MilvusKnowledgeVectorService milvusKnowledgeVectorService,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeFileService = knowledgeFileService;
        this.knowledgeSegmentMapper = knowledgeSegmentMapper;
        this.knowledgeEmbeddingService = knowledgeEmbeddingService;
        this.milvusKnowledgeVectorService = milvusKnowledgeVectorService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    public List<KnowledgeSegmentVO> listKnowledgeSegments(Long fileId, String keyword, Integer limit) {
        knowledgeFileService.requireKnowledgeFile(fileId);
        int effectiveLimit = limit == null || limit <= 0 ? DEFAULT_LIST_LIMIT : Math.min(limit, MAX_LIST_LIMIT);
        String effectiveKeyword = trimToNull(keyword);
        return knowledgeSegmentMapper.selectByFileId(fileId, effectiveKeyword, effectiveLimit).stream()
                .map(this::toKnowledgeSegmentVO)
                .toList();
    }

    /**
     * 片段级文本修订只允许覆盖当前 segment 的内容，不改变分片边界：
     * - fullText 变了，就同步回写 preview / tokenCount / metadata
     * - milvus_primary_key 继续沿用，按“同一片段覆盖更新”处理
     * - 如果 Milvus 更新失败，数据库修改要一起回滚，避免关系库和向量库脱节
     */
    public KnowledgeSegmentVO updateKnowledgeSegment(Long segmentId, String fullText, Object metadata) {
        KnowledgeSegmentDO existingSegment = requireKnowledgeSegment(segmentId);
        KnowledgeBaseDO knowledgeBase = knowledgeBaseService.requireKnowledgeBase(existingSegment.getKnowledgeBaseId());

        String normalizedText = normalizeSegmentText(fullText);
        existingSegment.setFullText(normalizedText);
        existingSegment.setTextPreview(KnowledgeChunkingService.toTextPreview(normalizedText));
        existingSegment.setTokenCount(KnowledgeChunkingService.estimateTokenCount(normalizedText));
        existingSegment.setMetadataJson(resolveUpdatedMetadataJson(existingSegment.getMetadataJson(), metadata));

        List<float[]> embeddings = knowledgeEmbeddingService.embedAll(List.of(normalizedText));
        KnowledgeVectorRecord vectorRecord = buildVectorRecord(existingSegment, embeddings.getFirst());

        transactionTemplate.executeWithoutResult(status -> {
            int updatedRows = knowledgeSegmentMapper.updateEditableFields(existingSegment);
            if (updatedRows <= 0) {
                throw new IllegalStateException("知识库片段更新失败，未找到可更新记录: " + segmentId);
            }
            milvusKnowledgeVectorService.replaceVectors(
                    knowledgeBase,
                    List.of(existingSegment.getMilvusPrimaryKey()),
                    List.of(vectorRecord)
            );
        });
        return toKnowledgeSegmentVO(existingSegment);
    }

    /**
     * 单片向量重算用于“原文没变，但怀疑当前向量需要修复”的场景：
     * - 不改 segment 文本和 metadata
     * - 只基于现有 fullText 重新生成 embedding，并覆盖当前 Milvus 记录
     */
    public KnowledgeSegmentVO reembedKnowledgeSegment(Long segmentId) {
        KnowledgeSegmentDO existingSegment = requireKnowledgeSegment(segmentId);
        KnowledgeBaseDO knowledgeBase = knowledgeBaseService.requireKnowledgeBase(existingSegment.getKnowledgeBaseId());
        if (!StringUtils.hasText(existingSegment.getFullText())) {
            throw new IllegalStateException("知识库片段原文为空，无法重算向量: " + segmentId);
        }

        List<float[]> embeddings = knowledgeEmbeddingService.embedAll(List.of(existingSegment.getFullText()));
        milvusKnowledgeVectorService.replaceVectors(
                knowledgeBase,
                List.of(existingSegment.getMilvusPrimaryKey()),
                List.of(buildVectorRecord(existingSegment, embeddings.getFirst()))
        );
        return toKnowledgeSegmentVO(existingSegment);
    }

    public KnowledgeSegmentDO requireKnowledgeSegment(Long segmentId) {
        KnowledgeSegmentDO segment = knowledgeSegmentMapper.selectById(segmentId);
        if (segment == null) {
            throw new IllegalArgumentException("知识库片段不存在: " + segmentId);
        }
        return segment;
    }

    private KnowledgeSegmentVO toKnowledgeSegmentVO(KnowledgeSegmentDO segment) {
        return new KnowledgeSegmentVO(
                segment.getId(),
                segment.getKnowledgeBaseId(),
                segment.getFileId(),
                segment.getSegmentNo(),
                segment.getTextPreview(),
                segment.getFullText(),
                segment.getTokenCount(),
                segment.getPageNo(),
                segment.getSourceTitle(),
                segment.getSourcePath(),
                parseMetadata(segment.getMetadataJson()),
                segment.getMilvusPrimaryKey(),
                TimeUtils.toEpochMillis(segment.getCreatedAt())
        );
    }

    private Object parseMetadata(String metadataJson) {
        if (!StringUtils.hasText(metadataJson)) {
            return null;
        }

        try {
            return objectMapper.readValue(metadataJson, Object.class);
        } catch (Exception exception) {
            return metadataJson;
        }
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String normalizeSegmentText(String fullText) {
        if (!StringUtils.hasText(fullText)) {
            throw new IllegalArgumentException("知识库片段文本不能为空。");
        }
        return fullText.trim();
    }

    private String resolveUpdatedMetadataJson(String existingMetadataJson, Object metadata) {
        if (metadata == null) {
            return existingMetadataJson;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("知识库片段 metadata 序列化失败。", exception);
        }
    }

    private KnowledgeVectorRecord buildVectorRecord(KnowledgeSegmentDO segment, float[] embedding) {
        return new KnowledgeVectorRecord(
                segment.getMilvusPrimaryKey(),
                segment.getKnowledgeBaseId(),
                segment.getFileId(),
                segment.getSegmentNo(),
                segment.getPageNo(),
                embedding
        );
    }
}
