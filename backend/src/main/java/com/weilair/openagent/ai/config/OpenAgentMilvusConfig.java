package com.weilair.openagent.ai.config;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(OpenAgentMilvusProperties.class)
public class OpenAgentMilvusConfig {
    /**
     * 当前阶段只接 Milvus 的“连接 + schema 管理”能力，
     * 也就是让知识库创建时能够自动校验 collection / partition 是否可用。
     * 真正的向量写入和检索会在后续文件索引、RAG 检索链路中继续接上。
     */

    @Bean
    @ConditionalOnExpression("'${milvus.host:}' != ''")
    public MilvusClientV2 milvusClient(OpenAgentMilvusProperties properties) {
        ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder()
                .uri("http://" + properties.getHost() + ":" + properties.getPort())
                .dbName(properties.getDatabase());

        if (properties.getConnectTimeoutMs() != null) {
            builder.connectTimeoutMs(properties.getConnectTimeoutMs());
        }
        if (StringUtils.hasText(properties.getToken())) {
            builder.token(properties.getToken());
        }

        return new MilvusClientV2(builder.build());
    }
}
