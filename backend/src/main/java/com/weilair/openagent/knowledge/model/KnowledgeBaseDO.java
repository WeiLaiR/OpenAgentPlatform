package com.weilair.openagent.knowledge.model;

import java.time.LocalDateTime;

public class KnowledgeBaseDO {

    private Long id;
    private Long ownerUserId;
    private String name;
    private String description;
    private Integer status;
    private String embeddingModelName;
    private Integer embeddingDimension;
    private String milvusDatabaseName;
    private String milvusCollectionName;
    private String milvusPartitionName;
    private String parserStrategy;
    private String chunkStrategy;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private Long fileCount;
    private Long segmentCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getEmbeddingModelName() {
        return embeddingModelName;
    }

    public void setEmbeddingModelName(String embeddingModelName) {
        this.embeddingModelName = embeddingModelName;
    }

    public Integer getEmbeddingDimension() {
        return embeddingDimension;
    }

    public void setEmbeddingDimension(Integer embeddingDimension) {
        this.embeddingDimension = embeddingDimension;
    }

    public String getMilvusDatabaseName() {
        return milvusDatabaseName;
    }

    public void setMilvusDatabaseName(String milvusDatabaseName) {
        this.milvusDatabaseName = milvusDatabaseName;
    }

    public String getMilvusCollectionName() {
        return milvusCollectionName;
    }

    public void setMilvusCollectionName(String milvusCollectionName) {
        this.milvusCollectionName = milvusCollectionName;
    }

    public String getMilvusPartitionName() {
        return milvusPartitionName;
    }

    public void setMilvusPartitionName(String milvusPartitionName) {
        this.milvusPartitionName = milvusPartitionName;
    }

    public String getParserStrategy() {
        return parserStrategy;
    }

    public void setParserStrategy(String parserStrategy) {
        this.parserStrategy = parserStrategy;
    }

    public String getChunkStrategy() {
        return chunkStrategy;
    }

    public void setChunkStrategy(String chunkStrategy) {
        this.chunkStrategy = chunkStrategy;
    }

    public Integer getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(Integer chunkSize) {
        this.chunkSize = chunkSize;
    }

    public Integer getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(Integer chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }

    public Long getFileCount() {
        return fileCount;
    }

    public void setFileCount(Long fileCount) {
        this.fileCount = fileCount;
    }

    public Long getSegmentCount() {
        return segmentCount;
    }

    public void setSegmentCount(Long segmentCount) {
        this.segmentCount = segmentCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
