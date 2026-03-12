package com.weilair.openagent.system.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import com.weilair.openagent.web.vo.SystemHealthVO;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class SystemHealthService {

    private final ObjectProvider<DataSource> dataSourceProvider;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectProvider<StreamingChatModel> streamingChatModelProvider;

    public SystemHealthService(
            ObjectProvider<DataSource> dataSourceProvider,
            ObjectProvider<ChatModel> chatModelProvider,
            ObjectProvider<StreamingChatModel> streamingChatModelProvider
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.chatModelProvider = chatModelProvider;
        this.streamingChatModelProvider = streamingChatModelProvider;
    }

    public SystemHealthVO getHealth() {
        return new SystemHealthVO(
                "UP",
                checkMysqlStatus(),
                "NOT_CONFIGURED",
                checkChatModelStatus(),
                "NOT_CONFIGURED",
                0,
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
        return chatModelProvider.getIfAvailable() != null || streamingChatModelProvider.getIfAvailable() != null
                ? "UP"
                : "NOT_CONFIGURED";
    }
}
