package com.weilair.openagent.knowledge.service;

import java.util.List;

import com.weilair.openagent.ai.config.OpenAgentEmbeddingProperties;
import com.weilair.openagent.ai.config.OpenAgentMilvusProperties;
import com.weilair.openagent.common.util.TimeUtils;
import com.weilair.openagent.knowledge.model.KnowledgeBaseDO;
import com.weilair.openagent.knowledge.persistence.mapper.KnowledgeBaseMapper;
import com.weilair.openagent.web.dto.KnowledgeBaseCreateRequest;
import com.weilair.openagent.web.vo.KnowledgeBaseVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class KnowledgeBaseService {
    /**
     * 阶段四先把“知识库容器”这层稳定下来：
     * - MySQL 保存知识库自己的配置、命名和分块参数
     * - Milvus 只负责创建 / 校验对应 partition
     *
     * 这样文件上传、索引、检索后续都可以直接复用这里固化下来的 collection / partition 信息。
     */

    private static final Long DEFAULT_USER_ID = 1L;
    private static final int DEFAULT_LIST_LIMIT = 100;
    private static final int STATUS_ACTIVE = 1;
    private static final String DEFAULT_PARSER_STRATEGY = "TIKA";
    private static final String DEFAULT_CHUNK_STRATEGY = "DEFAULT";
    private static final int DEFAULT_CHUNK_SIZE = 1000;
    private static final int DEFAULT_CHUNK_OVERLAP = 150;
    private static final String PENDING_PARTITION_NAME = "__PENDING__";

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final MilvusKnowledgeSchemaService milvusKnowledgeSchemaService;
    private final OpenAgentEmbeddingProperties embeddingProperties;
    private final OpenAgentMilvusProperties milvusProperties;

    public KnowledgeBaseService(
            KnowledgeBaseMapper knowledgeBaseMapper,
            MilvusKnowledgeSchemaService milvusKnowledgeSchemaService,
            OpenAgentEmbeddingProperties embeddingProperties,
            OpenAgentMilvusProperties milvusProperties
    ) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.milvusKnowledgeSchemaService = milvusKnowledgeSchemaService;
        this.embeddingProperties = embeddingProperties;
        this.milvusProperties = milvusProperties;
    }

    /**
     * 创建知识库时同时完成 Milvus partition 准备，
     * 目的是把“配置存在但向量库分区还没准备好”的半成品状态直接挡在入口外。
     */
    @Transactional
    public KnowledgeBaseVO createKnowledgeBase(KnowledgeBaseCreateRequest request) {
        KnowledgeBaseDO knowledgeBase = new KnowledgeBaseDO();
        knowledgeBase.setOwnerUserId(DEFAULT_USER_ID);
        knowledgeBase.setName(request.name().trim());
        knowledgeBase.setDescription(trimToNull(request.description()));
        knowledgeBase.setStatus(STATUS_ACTIVE);
        knowledgeBase.setEmbeddingModelName(resolveEmbeddingModelName(request.embeddingModelName()));
        knowledgeBase.setEmbeddingDimension(resolveEmbeddingDimension(request.embeddingDimension()));
        knowledgeBase.setMilvusDatabaseName(milvusProperties.getDatabase());
        knowledgeBase.setMilvusCollectionName(milvusProperties.getCollection());
        knowledgeBase.setMilvusPartitionName(PENDING_PARTITION_NAME);
        knowledgeBase.setParserStrategy(resolveTextOrDefault(request.parserStrategy(), DEFAULT_PARSER_STRATEGY));
        knowledgeBase.setChunkStrategy(resolveTextOrDefault(request.chunkStrategy(), DEFAULT_CHUNK_STRATEGY));
        knowledgeBase.setChunkSize(resolvePositiveOrDefault(request.chunkSize(), DEFAULT_CHUNK_SIZE));
        knowledgeBase.setChunkOverlap(resolveNonNegativeOrDefault(request.chunkOverlap(), DEFAULT_CHUNK_OVERLAP));

        try {
            knowledgeBaseMapper.insert(knowledgeBase);
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("知识库名称已存在，请更换名称后重试。");
        }

        // partition 需要依赖数据库生成的知识库 ID，因此先插入 MySQL，再按统一命名规则补建 Milvus 分区。
        knowledgeBase.setMilvusPartitionName(resolvePartitionName(knowledgeBase.getId()));
        // 由于 partition 名依赖 ID，这里补做一次更新前的内存回填，再由下方的 mapper 结果读取最终状态。
        updatePartitionName(knowledgeBase);
        milvusKnowledgeSchemaService.ensureKnowledgePartition(
                knowledgeBase.getMilvusCollectionName(),
                knowledgeBase.getMilvusPartitionName()
        );

        return toKnowledgeBaseVO(requireKnowledgeBase(knowledgeBase.getId()));
    }

    public List<KnowledgeBaseVO> listKnowledgeBases(String keyword, Integer status, Integer limit) {
        int effectiveLimit = limit == null || limit <= 0 ? DEFAULT_LIST_LIMIT : Math.min(limit, DEFAULT_LIST_LIMIT);
        Integer effectiveStatus = status == null ? STATUS_ACTIVE : status;
        return knowledgeBaseMapper.selectList(trimToNull(keyword), effectiveStatus, effectiveLimit).stream()
                .map(this::toKnowledgeBaseVO)
                .toList();
    }

    public KnowledgeBaseDO requireKnowledgeBase(Long knowledgeBaseId) {
        KnowledgeBaseDO knowledgeBase = knowledgeBaseMapper.selectById(knowledgeBaseId);
        if (knowledgeBase == null) {
            throw new IllegalArgumentException("知识库不存在: " + knowledgeBaseId);
        }
        return knowledgeBase;
    }

    private KnowledgeBaseVO toKnowledgeBaseVO(KnowledgeBaseDO knowledgeBase) {
        return new KnowledgeBaseVO(
                knowledgeBase.getId(),
                knowledgeBase.getName(),
                knowledgeBase.getDescription(),
                resolveStatusLabel(knowledgeBase.getStatus()),
                knowledgeBase.getEmbeddingModelName(),
                knowledgeBase.getEmbeddingDimension(),
                knowledgeBase.getMilvusDatabaseName(),
                knowledgeBase.getMilvusCollectionName(),
                knowledgeBase.getMilvusPartitionName(),
                knowledgeBase.getParserStrategy(),
                knowledgeBase.getChunkStrategy(),
                knowledgeBase.getChunkSize(),
                knowledgeBase.getChunkOverlap(),
                knowledgeBase.getFileCount(),
                knowledgeBase.getSegmentCount(),
                TimeUtils.toEpochMillis(knowledgeBase.getCreatedAt()),
                TimeUtils.toEpochMillis(knowledgeBase.getUpdatedAt())
        );
    }

    private void updatePartitionName(KnowledgeBaseDO knowledgeBase) {
        // 当前表结构把 partition_name 放在 knowledge_base 里，
        // 所以 ID 生成后需要立即把固定命名规则回写，后续索引和检索才能直接读取。
        knowledgeBaseMapper.updatePartitionName(knowledgeBase.getId(), knowledgeBase.getMilvusPartitionName());
    }

    private String resolvePartitionName(Long knowledgeBaseId) {
        return milvusProperties.getPartitionPrefix() + knowledgeBaseId;
    }

    private String resolveEmbeddingModelName(String embeddingModelName) {
        String configuredModelName = resolveTextOrDefault(embeddingModelName, embeddingProperties.getModelName());
        if (!StringUtils.hasText(configuredModelName)) {
            throw new IllegalArgumentException("未提供 embeddingModelName，且系统默认 openagent.ai.embedding.model-name 也未配置。");
        }
        return configuredModelName;
    }

    private Integer resolveEmbeddingDimension(Integer embeddingDimension) {
        int resolvedDimension = embeddingDimension != null ? embeddingDimension : resolvePositiveOrDefault(
                embeddingProperties.getDimension(),
                1024
        );
        if (resolvedDimension <= 0) {
            throw new IllegalArgumentException("embeddingDimension 必须大于 0。");
        }
        return resolvedDimension;
    }

    private int resolvePositiveOrDefault(Integer value, int defaultValue) {
        return value == null || value <= 0 ? defaultValue : value;
    }

    private int resolveNonNegativeOrDefault(Integer value, int defaultValue) {
        return value == null || value < 0 ? defaultValue : value;
    }

    private String resolveTextOrDefault(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String resolveStatusLabel(Integer status) {
        return status != null && status == STATUS_ACTIVE ? "ACTIVE" : "DISABLED";
    }
}
