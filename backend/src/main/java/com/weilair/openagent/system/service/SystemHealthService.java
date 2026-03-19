package com.weilair.openagent.system.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import com.weilair.openagent.ai.config.OpenAgentMilvusProperties;
import com.weilair.openagent.knowledge.service.MilvusKnowledgeSchemaService;
import com.weilair.openagent.mcp.service.McpServerService;
import com.weilair.openagent.web.vo.SystemHealthVO;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class SystemHealthService {
    /**
     * 健康检查当前只做“基础可用性探测”，还不是完整的 AI 链路自检。
     * 对 LangChain4j 相关部分，这里先判断 Spring 容器里是否成功装配了 ChatModel / StreamingChatModel，
     * 以便在不真正发起一次模型调用的前提下，让前端先看到当前配置状态。
     */

    private final ObjectProvider<DataSource> dataSourceProvider;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectProvider<StreamingChatModel> streamingChatModelProvider;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final MilvusKnowledgeSchemaService milvusKnowledgeSchemaService;
    private final OpenAgentMilvusProperties milvusProperties;
    private final ObjectProvider<McpServerService> mcpServerServiceProvider;

    public SystemHealthService(
            ObjectProvider<DataSource> dataSourceProvider,
            ObjectProvider<ChatModel> chatModelProvider,
            ObjectProvider<StreamingChatModel> streamingChatModelProvider,
            ObjectProvider<EmbeddingModel> embeddingModelProvider,
            MilvusKnowledgeSchemaService milvusKnowledgeSchemaService,
            OpenAgentMilvusProperties milvusProperties,
            ObjectProvider<McpServerService> mcpServerServiceProvider
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.chatModelProvider = chatModelProvider;
        this.streamingChatModelProvider = streamingChatModelProvider;
        this.embeddingModelProvider = embeddingModelProvider;
        this.milvusKnowledgeSchemaService = milvusKnowledgeSchemaService;
        this.milvusProperties = milvusProperties;
        this.mcpServerServiceProvider = mcpServerServiceProvider;
    }

    public SystemHealthVO getHealth() {
        return new SystemHealthVO(
                "UP",
                checkMysqlStatus(),
                checkMilvusStatus(),
                checkChatModelStatus(),
                checkEmbeddingModelStatus(),
                countHealthyMcpServers(),
                System.currentTimeMillis()
        );
    }

    private String checkMysqlStatus() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            return "NOT_CONFIGURED";
        }

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            return "UP";
        } catch (SQLException exception) {
            return "DOWN";
        }
    }

    private String checkChatModelStatus() {
        // 这里只代表“Bean 已经可用”，不代表远端模型服务一定健康。
        return chatModelProvider.getIfAvailable() != null || streamingChatModelProvider.getIfAvailable() != null
                ? "UP"
                : "NOT_CONFIGURED";
    }

    private String checkEmbeddingModelStatus() {
        // 当前同样先看 Bean 是否已经装配成功，真正 embedding 调用健康留到索引 / 检索联调时再做深测。
        return embeddingModelProvider.getIfAvailable() != null ? "UP" : "NOT_CONFIGURED";
    }

    private String checkMilvusStatus() {
        return milvusKnowledgeSchemaService.checkStatus(milvusProperties.getCollection());
    }

    private Integer countHealthyMcpServers() {
        McpServerService mcpServerService = mcpServerServiceProvider.getIfAvailable();
        return mcpServerService != null ? mcpServerService.countHealthyServers() : 0;
    }
}
