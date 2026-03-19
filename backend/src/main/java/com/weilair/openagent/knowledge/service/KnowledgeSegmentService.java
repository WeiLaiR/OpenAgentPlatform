package com.weilair.openagent.knowledge.service;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weilair.openagent.common.util.TimeUtils;
import com.weilair.openagent.knowledge.model.KnowledgeSegmentDO;
import com.weilair.openagent.knowledge.persistence.mapper.KnowledgeSegmentMapper;
import com.weilair.openagent.web.vo.KnowledgeSegmentVO;
import org.springframework.stereotype.Service;
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

    private final KnowledgeFileService knowledgeFileService;
    private final KnowledgeSegmentMapper knowledgeSegmentMapper;
    private final ObjectMapper objectMapper;

    public KnowledgeSegmentService(
            KnowledgeFileService knowledgeFileService,
            KnowledgeSegmentMapper knowledgeSegmentMapper,
            ObjectMapper objectMapper
    ) {
        this.knowledgeFileService = knowledgeFileService;
        this.knowledgeSegmentMapper = knowledgeSegmentMapper;
        this.objectMapper = objectMapper;
    }

    public List<KnowledgeSegmentVO> listKnowledgeSegments(Long fileId, String keyword, Integer limit) {
        knowledgeFileService.requireKnowledgeFile(fileId);
        int effectiveLimit = limit == null || limit <= 0 ? DEFAULT_LIST_LIMIT : Math.min(limit, MAX_LIST_LIMIT);
        String effectiveKeyword = trimToNull(keyword);
        return knowledgeSegmentMapper.selectByFileId(fileId, effectiveKeyword, effectiveLimit).stream()
                .map(this::toKnowledgeSegmentVO)
                .toList();
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
}
