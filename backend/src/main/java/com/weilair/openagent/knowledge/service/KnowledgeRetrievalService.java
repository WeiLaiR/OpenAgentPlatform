package com.weilair.openagent.knowledge.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.weilair.openagent.knowledge.model.KnowledgeBaseDO;
import com.weilair.openagent.knowledge.model.KnowledgeSegmentDO;
import com.weilair.openagent.knowledge.persistence.mapper.KnowledgeSegmentMapper;
import com.weilair.openagent.web.dto.KnowledgeRetrieveRequest;
import com.weilair.openagent.web.vo.KnowledgeRetrieveVO;
import com.weilair.openagent.web.vo.RagSnippetVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class KnowledgeRetrievalService {
    /**
     * 当前阶段的检索链路保持最小闭环：
     * 1. 先把 query 做 embedding
     * 2. 再按 knowledgeBaseIds 映射到 partition 列表做 Milvus 向量召回
     * 3. 最后按 milvus_primary_key 回查 MySQL 原文片段
     *
     * 后续聊天主链路接 RAG 时，直接复用这里的检索结果即可。
     */

    private static final int DEFAULT_TOP_K = 4;
    private static final int MAX_TOP_K = 10;
    private static final double DEFAULT_MIN_SCORE = 0.55d;

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeEmbeddingService knowledgeEmbeddingService;
    private final MilvusKnowledgeVectorService milvusKnowledgeVectorService;
    private final KnowledgeSegmentMapper knowledgeSegmentMapper;

    public KnowledgeRetrievalService(
            KnowledgeBaseService knowledgeBaseService,
            KnowledgeEmbeddingService knowledgeEmbeddingService,
            MilvusKnowledgeVectorService milvusKnowledgeVectorService,
            KnowledgeSegmentMapper knowledgeSegmentMapper
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeEmbeddingService = knowledgeEmbeddingService;
        this.milvusKnowledgeVectorService = milvusKnowledgeVectorService;
        this.knowledgeSegmentMapper = knowledgeSegmentMapper;
    }

    public KnowledgeRetrieveVO retrieve(KnowledgeRetrieveRequest request) {
        List<RagSnippetVO> snippets = retrieveSnippets(
                request.query(),
                request.knowledgeBaseIds(),
                request.topK(),
                request.minScore()
        );
        return new KnowledgeRetrieveVO(request.query(), snippets, snippets.size());
    }

    public List<RagSnippetVO> retrieveSnippets(
            String query,
            List<Long> knowledgeBaseIds,
            Integer topK,
            Double minScore
    ) {
        List<Long> distinctKnowledgeBaseIds = knowledgeBaseIds.stream().distinct().toList();
        List<KnowledgeBaseDO> knowledgeBases = distinctKnowledgeBaseIds.stream()
                .map(knowledgeBaseService::requireKnowledgeBase)
                .toList();
        String collectionName = resolveCollectionName(knowledgeBases);
        List<String> partitionNames = knowledgeBases.stream()
                .map(KnowledgeBaseDO::getMilvusPartitionName)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();

        float[] queryEmbedding = knowledgeEmbeddingService.embedAll(List.of(query)).getFirst();
        List<MilvusSearchHit> searchHits = milvusKnowledgeVectorService.searchSegments(
                collectionName,
                partitionNames,
                queryEmbedding,
                resolveTopK(topK)
        );
        List<MilvusSearchHit> filteredHits = searchHits.stream()
                .filter(hit -> hit.score() >= resolveMinScore(minScore))
                .toList();
        if (filteredHits.isEmpty()) {
            return List.of();
        }

        List<String> segmentRefs = filteredHits.stream().map(MilvusSearchHit::segmentRef).toList();
        Map<String, KnowledgeSegmentDO> segmentsByPrimaryKey = knowledgeSegmentMapper.selectByMilvusPrimaryKeys(segmentRefs).stream()
                .collect(LinkedHashMap::new, (map, segment) -> map.put(segment.getMilvusPrimaryKey(), segment), Map::putAll);

        return filteredHits.stream()
                .map(hit -> toSnippet(hit, segmentsByPrimaryKey.get(hit.segmentRef())))
                .filter(snippet -> snippet != null)
                .toList();
    }

    private RagSnippetVO toSnippet(MilvusSearchHit searchHit, KnowledgeSegmentDO segment) {
        if (segment == null) {
            return null;
        }
        return new RagSnippetVO(
                segment.getKnowledgeBaseId(),
                segment.getFileId(),
                segment.getSegmentNo(),
                searchHit.score(),
                segment.getTextPreview(),
                segment.getFullText(),
                segment.getTokenCount(),
                segment.getPageNo(),
                segment.getSourceTitle(),
                segment.getSourcePath(),
                segment.getMilvusPrimaryKey()
        );
    }

    private String resolveCollectionName(List<KnowledgeBaseDO> knowledgeBases) {
        String collectionName = knowledgeBases.getFirst().getMilvusCollectionName();
        boolean multipleCollections = knowledgeBases.stream()
                .map(KnowledgeBaseDO::getMilvusCollectionName)
                .distinct()
                .count() > 1;
        if (multipleCollections) {
            throw new IllegalStateException("当前检索接口仅支持单 collection 检索。");
        }
        return collectionName;
    }

    private int resolveTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, MAX_TOP_K);
    }

    private double resolveMinScore(Double minScore) {
        return minScore == null ? DEFAULT_MIN_SCORE : minScore;
    }
}
