package com.weilair.openagent.ai.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(OpenAgentChatProperties.class)
public class OpenAgentAiConfig {

    @Bean
    @ConditionalOnExpression(
            "'${openagent.ai.chat.base-url:}' != '' and '${openagent.ai.chat.model-name:}' != ''"
    )
    public ChatModel chatModel(OpenAgentChatProperties properties) {
        return OpenAiChatModel.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(resolveApiKey(properties.getApiKey()))
                .modelName(properties.getModelName())
                .temperature(properties.getTemperature())
                .timeout(properties.getTimeout())
                .logRequests(Boolean.TRUE.equals(properties.getLogRequests()))
                .logResponses(Boolean.TRUE.equals(properties.getLogResponses()))
                .build();
    }

    @Bean
    @ConditionalOnExpression(
            "'${openagent.ai.chat.base-url:}' != '' and '${openagent.ai.chat.model-name:}' != ''"
    )
    public StreamingChatModel streamingChatModel(OpenAgentChatProperties properties) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(resolveApiKey(properties.getApiKey()))
                .modelName(properties.getModelName())
                .temperature(properties.getTemperature())
                .timeout(properties.getTimeout())
                .logRequests(Boolean.TRUE.equals(properties.getLogRequests()))
                .logResponses(Boolean.TRUE.equals(properties.getLogResponses()))
                .build();
    }

    private String resolveApiKey(String apiKey) {
        return StringUtils.hasText(apiKey) ? apiKey : "demo";
    }
}
