package com.weilair.openagent.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "milvus")
public class OpenAgentMilvusProperties {
    /**
     * 这里固定的是“当前学习版 RAG”对 Milvus 的全局基线：
     * database / collection 由系统统一约束，
     * 每个知识库只动态生成自己的 partition，避免前后端和数据库层各写一套命名规则。
     */

    private String host;
    private Integer port = 19530;
    private String token;
    private String database = "openagent";
    private String collection = "knowledge_segment";
    private String partitionPrefix = "kb_";
    private Integer connectTimeoutMs = 10000;
    private String metricType = "COSINE";

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    public String getPartitionPrefix() {
        return partitionPrefix;
    }

    public void setPartitionPrefix(String partitionPrefix) {
        this.partitionPrefix = partitionPrefix;
    }

    public Integer getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(Integer connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public String getMetricType() {
        return metricType;
    }

    public void setMetricType(String metricType) {
        this.metricType = metricType;
    }
}
