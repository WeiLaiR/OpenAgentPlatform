package com.weilair.openagent.web.vo;

public record SystemHealthVO(
        String appStatus,
        String mysqlStatus,
        String milvusStatus,
        String chatModelStatus,
        String embeddingModelStatus,
        Integer healthyMcpServers,
        Long timestamp
) {
}
