package com.weilair.openagent.knowledge.service;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.partition.request.CreatePartitionReq;
import io.milvus.v2.service.partition.request.HasPartitionReq;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MilvusKnowledgeSchemaService {
    /**
     * 这层只负责“知识库与 Milvus schema 的边界动作”：
     * 1. 确认当前 collection 已存在
     * 2. 如果某个知识库对应的 partition 还不存在，则立即补建
     *
     * 这样后续文件上传 / 向量写入时，就不需要在索引链路里再次关心 partition 生命周期。
     */

    private final ObjectProvider<MilvusClientV2> milvusClientProvider;

    public MilvusKnowledgeSchemaService(ObjectProvider<MilvusClientV2> milvusClientProvider) {
        this.milvusClientProvider = milvusClientProvider;
    }

    public void ensureKnowledgePartition(String collectionName, String partitionName) {
        if (!StringUtils.hasText(collectionName) || !StringUtils.hasText(partitionName)) {
            throw new IllegalArgumentException("Milvus collection 和 partition 不能为空。");
        }

        MilvusClientV2 milvusClient = requireMilvusClient();
        boolean collectionExists = milvusClient.hasCollection(
                HasCollectionReq.builder()
                        .collectionName(collectionName)
                        .build()
        );
        if (!collectionExists) {
            throw new IllegalStateException("Milvus collection 不存在: " + collectionName);
        }

        boolean partitionExists = milvusClient.hasPartition(
                HasPartitionReq.builder()
                        .collectionName(collectionName)
                        .partitionName(partitionName)
                        .build()
        );
        if (partitionExists) {
            return;
        }

        milvusClient.createPartition(
                CreatePartitionReq.builder()
                        .collectionName(collectionName)
                        .partitionName(partitionName)
                        .build()
        );
    }

    public String checkStatus(String collectionName) {
        MilvusClientV2 milvusClient = milvusClientProvider.getIfAvailable();
        if (milvusClient == null) {
            return "NOT_CONFIGURED";
        }
        if (!StringUtils.hasText(collectionName)) {
            return "DOWN";
        }

        try {
            boolean collectionExists = milvusClient.hasCollection(
                    HasCollectionReq.builder()
                            .collectionName(collectionName)
                            .build()
            );
            return collectionExists ? "UP" : "DOWN";
        } catch (Exception exception) {
            return "DOWN";
        }
    }

    private MilvusClientV2 requireMilvusClient() {
        MilvusClientV2 milvusClient = milvusClientProvider.getIfAvailable();
        if (milvusClient == null) {
            throw new IllegalStateException("MilvusClient 未配置，请先设置 milvus.host / milvus.port。");
        }
        return milvusClient;
    }
}
