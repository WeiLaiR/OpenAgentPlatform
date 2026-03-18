package com.weilair.openagent.knowledge.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.weilair.openagent.ai.config.OpenAgentMilvusProperties;
import com.weilair.openagent.knowledge.model.KnowledgeBaseDO;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.partition.request.LoadPartitionsReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import io.milvus.v2.service.vector.request.UpsertReq;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class MilvusKnowledgeVectorService {
    /**
     * Milvus 只保存向量检索所需的最小字段。
     * 原文、preview 和更完整的 metadata 仍由 MySQL 的 knowledge_segment 负责。
     */

    private static final String FIELD_SEGMENT_REF = "segment_ref";
    private static final String FIELD_KNOWLEDGE_BASE_ID = "knowledge_base_id";
    private static final String FIELD_FILE_ID = "file_id";
    private static final String FIELD_SEGMENT_NO = "segment_no";
    private static final String FIELD_PAGE_NO = "page_no";
    private static final String FIELD_EMBEDDING = "embedding";
    private static final String FIELD_VECTOR = "embedding";
    private static final Pattern EXPECTED_METRIC_PATTERN = Pattern.compile("expected=([A-Za-z0-9_]+)");
    static final int UNKNOWN_PAGE_NO = -1;

    private final ObjectProvider<MilvusClientV2> milvusClientProvider;
    private final MilvusKnowledgeSchemaService milvusKnowledgeSchemaService;
    private final OpenAgentMilvusProperties milvusProperties;

    public MilvusKnowledgeVectorService(
            ObjectProvider<MilvusClientV2> milvusClientProvider,
            MilvusKnowledgeSchemaService milvusKnowledgeSchemaService,
            OpenAgentMilvusProperties milvusProperties
    ) {
        this.milvusClientProvider = milvusClientProvider;
        this.milvusKnowledgeSchemaService = milvusKnowledgeSchemaService;
        this.milvusProperties = milvusProperties;
    }

    public void replaceFileVectors(
            KnowledgeBaseDO knowledgeBase,
            List<String> existingVectorIds,
            List<KnowledgeVectorRecord> vectorRecords
    ) {
        if (vectorRecords == null || vectorRecords.isEmpty()) {
            throw new IllegalArgumentException("向量写入列表不能为空。");
        }

        MilvusClientV2 milvusClient = requireMilvusClient();
        milvusKnowledgeSchemaService.ensureKnowledgePartition(
                knowledgeBase.getMilvusCollectionName(),
                knowledgeBase.getMilvusPartitionName()
        );

        if (existingVectorIds != null && !existingVectorIds.isEmpty()) {
            milvusClient.delete(
                    DeleteReq.builder()
                            .collectionName(knowledgeBase.getMilvusCollectionName())
                            .partitionName(knowledgeBase.getMilvusPartitionName())
                            .ids(new ArrayList<>(existingVectorIds))
                            .build()
            );
        }

        milvusClient.upsert(
                UpsertReq.builder()
                        .collectionName(knowledgeBase.getMilvusCollectionName())
                        .partitionName(knowledgeBase.getMilvusPartitionName())
                        .data(toJsonRows(vectorRecords))
                        .build()
        );
    }

    public void deleteVectors(KnowledgeBaseDO knowledgeBase, List<String> vectorIds) {
        if (vectorIds == null || vectorIds.isEmpty()) {
            return;
        }

        requireMilvusClient().delete(
                DeleteReq.builder()
                        .collectionName(knowledgeBase.getMilvusCollectionName())
                        .partitionName(knowledgeBase.getMilvusPartitionName())
                        .ids(new ArrayList<>(vectorIds))
                        .build()
        );
    }

    /**
     * 检索阶段只返回 segment 主键和分数。
     * 原文片段仍然回到 MySQL 查，保持“向量库负责召回，关系库负责展示”的职责分离。
     */
    public List<MilvusSearchHit> searchSegments(
            String collectionName,
            List<String> partitionNames,
            float[] queryEmbedding,
            int topK
    ) {
        if (partitionNames == null || partitionNames.isEmpty()) {
            return List.of();
        }

        MilvusClientV2 milvusClient = requireMilvusClient();
        milvusClient.loadPartitions(
                LoadPartitionsReq.builder()
                        .collectionName(collectionName)
                        .partitionNames(partitionNames)
                        .build()
        );

        IndexParam.MetricType preferredMetricType = resolveMetricType(milvusProperties.getMetricType());
        SearchResp searchResp = searchWithMetricFallback(
                milvusClient,
                collectionName,
                partitionNames,
                queryEmbedding,
                topK,
                preferredMetricType
        );

        if (searchResp.getSearchResults() == null || searchResp.getSearchResults().isEmpty()) {
            return List.of();
        }

        List<SearchResp.SearchResult> firstQueryResults = searchResp.getSearchResults().getFirst();
        if (firstQueryResults == null || firstQueryResults.isEmpty()) {
            return List.of();
        }

        return firstQueryResults.stream()
                .filter(result -> result.getId() != null && result.getScore() != null)
                .map(result -> new MilvusSearchHit(result.getId().toString(), result.getScore()))
                .toList();
    }

    private SearchResp searchWithMetricFallback(
            MilvusClientV2 milvusClient,
            String collectionName,
            List<String> partitionNames,
            float[] queryEmbedding,
            int topK,
            IndexParam.MetricType preferredMetricType
    ) {
        try {
            return doSearch(milvusClient, collectionName, partitionNames, queryEmbedding, topK, preferredMetricType);
        } catch (RuntimeException exception) {
            Optional<IndexParam.MetricType> expectedMetricType = extractExpectedMetricType(exception);
            if (expectedMetricType.isPresent() && expectedMetricType.get() != preferredMetricType) {
                return doSearch(milvusClient, collectionName, partitionNames, queryEmbedding, topK, expectedMetricType.get());
            }
            throw exception;
        }
    }

    private SearchResp doSearch(
            MilvusClientV2 milvusClient,
            String collectionName,
            List<String> partitionNames,
            float[] queryEmbedding,
            int topK,
            IndexParam.MetricType metricType
    ) {
        return milvusClient.search(
                SearchReq.builder()
                        .collectionName(collectionName)
                        .partitionNames(partitionNames)
                        .annsField(FIELD_VECTOR)
                        .metricType(metricType)
                        .topK(topK)
                        .limit(topK)
                        .data(List.of(new FloatVec(queryEmbedding)))
                        .build()
        );
    }

    List<JsonObject> toJsonRows(List<KnowledgeVectorRecord> vectorRecords) {
        List<JsonObject> rows = new ArrayList<>(vectorRecords.size());
        for (KnowledgeVectorRecord vectorRecord : vectorRecords) {
            JsonObject row = new JsonObject();
            row.addProperty(FIELD_SEGMENT_REF, vectorRecord.segmentRef());
            row.addProperty(FIELD_KNOWLEDGE_BASE_ID, vectorRecord.knowledgeBaseId());
            row.addProperty(FIELD_FILE_ID, vectorRecord.fileId());
            row.addProperty(FIELD_SEGMENT_NO, vectorRecord.segmentNo());
            // 当前 txt / md 这类文档没有天然页码，Milvus collection 里约定用 -1 作为“未知页码”哨兵值。
            row.addProperty(FIELD_PAGE_NO, resolvePageNoForMilvus(vectorRecord.pageNo()));

            JsonArray embeddingArray = new JsonArray();
            for (float value : vectorRecord.embedding()) {
                embeddingArray.add(value);
            }
            row.add(FIELD_EMBEDDING, embeddingArray);
            rows.add(row);
        }
        return rows;
    }

    static int resolvePageNoForMilvus(Integer pageNo) {
        return pageNo == null ? UNKNOWN_PAGE_NO : pageNo;
    }

    static IndexParam.MetricType resolveMetricType(String metricType) {
        if (metricType == null || metricType.isBlank()) {
            return IndexParam.MetricType.COSINE;
        }

        try {
            return IndexParam.MetricType.valueOf(metricType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("不支持的 Milvus metricType: " + metricType, exception);
        }
    }

    static Optional<IndexParam.MetricType> extractExpectedMetricType(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                Matcher matcher = EXPECTED_METRIC_PATTERN.matcher(message);
                if (matcher.find()) {
                    return Optional.of(resolveMetricType(matcher.group(1)));
                }
            }
            current = current.getCause();
        }
        return Optional.empty();
    }

    private MilvusClientV2 requireMilvusClient() {
        MilvusClientV2 milvusClient = milvusClientProvider.getIfAvailable();
        if (milvusClient == null) {
            throw new IllegalStateException("MilvusClient 未配置，请先设置 milvus.host / milvus.port。");
        }
        return milvusClient;
    }
}
