package com.weilair.openagent.knowledge.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.UnsupportedFeatureException;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import com.weilair.openagent.knowledge.model.KnowledgeBaseDO;
import com.weilair.openagent.knowledge.model.KnowledgeSegmentDO;
import com.weilair.openagent.knowledge.persistence.mapper.KnowledgeSegmentMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 这一层把项目当前“Milvus 做召回、MySQL 存原文”的知识库实现，
 * 适配成 LangChain4j 官方 `EmbeddingStore<TextSegment>` 形态。
 *
 * 为什么这里没有直接切到 LangChain4j 官方 `MilvusEmbeddingStore`：
 * 1. 当前工程基线使用的是 `langchain4j 1.12.1-beta21` 主线能力；
 * 2. 截至 2026-03-20，LangChain4j 官方公开的 `langchain4j-milvus` 工件版本仍停留在更早的 beta 线，
 *    与本项目当前核心依赖并未对齐；
 * 3. 项目已经沉淀了基于 Milvus Java Client v2 的 collection / partition / schema 管理与写入链路。
 *
 * 因此当前选择的回切策略是：
 * - 优先回到官方 `EmbeddingStoreContentRetriever`
 * - 仅在底层用一个最薄的 `EmbeddingStore<TextSegment>` 适配层桥接现有 Milvus/MySQL 能力
 *
 * 后续如果 LangChain4j 官方 Milvus 模块与当前主线版本完全对齐，
 * 这里应继续评估回切到官方 `MilvusEmbeddingStore`。
 */
@Component
public class KnowledgeTextSegmentEmbeddingStoreFactory {

    private final KnowledgeBaseService knowledgeBaseService;
    private final MilvusKnowledgeVectorService milvusKnowledgeVectorService;
    private final KnowledgeSegmentMapper knowledgeSegmentMapper;

    public KnowledgeTextSegmentEmbeddingStoreFactory(
            KnowledgeBaseService knowledgeBaseService,
            MilvusKnowledgeVectorService milvusKnowledgeVectorService,
            KnowledgeSegmentMapper knowledgeSegmentMapper
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.milvusKnowledgeVectorService = milvusKnowledgeVectorService;
        this.knowledgeSegmentMapper = knowledgeSegmentMapper;
    }

    public EmbeddingStore<TextSegment> create(List<Long> knowledgeBaseIds) {
        List<Long> distinctKnowledgeBaseIds = knowledgeBaseIds == null
                ? List.of()
                : knowledgeBaseIds.stream().distinct().toList();
        List<KnowledgeBaseDO> knowledgeBases = distinctKnowledgeBaseIds.stream()
                .map(knowledgeBaseService::requireKnowledgeBase)
                .toList();
        String collectionName = resolveCollectionName(knowledgeBases);
        List<String> partitionNames = knowledgeBases.stream()
                .map(KnowledgeBaseDO::getMilvusPartitionName)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        return new MilvusTextSegmentEmbeddingStore(collectionName, partitionNames);
    }

    private String resolveCollectionName(List<KnowledgeBaseDO> knowledgeBases) {
        if (knowledgeBases == null || knowledgeBases.isEmpty()) {
            throw new IllegalArgumentException("knowledgeBaseIds 不能为空。");
        }

        String collectionName = knowledgeBases.getFirst().getMilvusCollectionName();
        boolean multipleCollections = knowledgeBases.stream()
                .map(KnowledgeBaseDO::getMilvusCollectionName)
                .distinct()
                .count() > 1;
        if (multipleCollections) {
            throw new IllegalStateException("当前 EmbeddingStore 适配仅支持单 collection 检索。");
        }
        return collectionName;
    }

    private final class MilvusTextSegmentEmbeddingStore implements EmbeddingStore<TextSegment> {

        private final String collectionName;
        private final List<String> partitionNames;

        private MilvusTextSegmentEmbeddingStore(String collectionName, List<String> partitionNames) {
            this.collectionName = collectionName;
            this.partitionNames = partitionNames == null ? List.of() : List.copyOf(partitionNames);
        }

        @Override
        public String add(Embedding embedding) {
            throw new UnsupportedFeatureException("当前适配层只用于检索，不承接向量写入。");
        }

        @Override
        public void add(String id, Embedding embedding) {
            throw new UnsupportedFeatureException("当前适配层只用于检索，不承接向量写入。");
        }

        @Override
        public String add(Embedding embedding, TextSegment embedded) {
            throw new UnsupportedFeatureException("当前适配层只用于检索，不承接向量写入。");
        }

        @Override
        public List<String> addAll(List<Embedding> embeddings) {
            throw new UnsupportedFeatureException("当前适配层只用于检索，不承接向量写入。");
        }

        @Override
        public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
            if (request == null) {
                throw new IllegalArgumentException("EmbeddingSearchRequest 不能为空。");
            }
            if (request.filter() != null) {
                throw new UnsupportedFeatureException("当前适配层暂不支持 Metadata Filter 检索。");
            }
            if (partitionNames.isEmpty()) {
                return new EmbeddingSearchResult<>(List.of());
            }

            List<MilvusSearchHit> filteredHits = milvusKnowledgeVectorService.searchSegments(
                    collectionName,
                    partitionNames,
                    request.queryEmbedding().vector(),
                    request.maxResults()
            ).stream()
                    .filter(hit -> hit.score() >= request.minScore())
                    .toList();
            if (filteredHits.isEmpty()) {
                return new EmbeddingSearchResult<>(List.of());
            }

            List<String> segmentRefs = filteredHits.stream().map(MilvusSearchHit::segmentRef).toList();
            Map<String, KnowledgeSegmentDO> segmentsByPrimaryKey = knowledgeSegmentMapper.selectByMilvusPrimaryKeys(segmentRefs).stream()
                    .collect(LinkedHashMap::new, (map, segment) -> map.put(segment.getMilvusPrimaryKey(), segment), Map::putAll);

            List<EmbeddingMatch<TextSegment>> matches = filteredHits.stream()
                    .map(hit -> toEmbeddingMatch(hit, segmentsByPrimaryKey.get(hit.segmentRef())))
                    .filter(match -> match != null)
                    .toList();
            return new EmbeddingSearchResult<>(matches);
        }

        private EmbeddingMatch<TextSegment> toEmbeddingMatch(MilvusSearchHit searchHit, KnowledgeSegmentDO segment) {
            if (segment == null || !StringUtils.hasText(segment.getMilvusPrimaryKey())) {
                return null;
            }

            return new EmbeddingMatch<>(
                    (double) searchHit.score(),
                    segment.getMilvusPrimaryKey(),
                    null,
                    TextSegment.from(
                            StringUtils.hasText(segment.getFullText()) ? segment.getFullText() : segment.getTextPreview(),
                            buildSegmentMetadata(segment)
                    )
            );
        }

        private Metadata buildSegmentMetadata(KnowledgeSegmentDO segment) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("knowledgeBaseId", String.valueOf(segment.getKnowledgeBaseId()));
            metadata.put("fileId", String.valueOf(segment.getFileId()));
            metadata.put("segmentNo", String.valueOf(segment.getSegmentNo()));
            if (segment.getTokenCount() != null) {
                metadata.put("tokenCount", String.valueOf(segment.getTokenCount()));
            }
            if (segment.getPageNo() != null) {
                metadata.put("pageNo", String.valueOf(segment.getPageNo()));
            }
            if (StringUtils.hasText(segment.getTextPreview())) {
                metadata.put("textPreview", segment.getTextPreview());
            }
            if (StringUtils.hasText(segment.getFullText())) {
                metadata.put("fullText", segment.getFullText());
            }
            if (StringUtils.hasText(segment.getSourceTitle())) {
                metadata.put("sourceTitle", segment.getSourceTitle());
            }
            if (StringUtils.hasText(segment.getSourcePath())) {
                metadata.put("sourcePath", segment.getSourcePath());
            }
            metadata.put("milvusPrimaryKey", segment.getMilvusPrimaryKey());
            return Metadata.from(metadata);
        }
    }
}
