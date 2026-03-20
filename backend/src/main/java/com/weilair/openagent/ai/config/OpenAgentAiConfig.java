package com.weilair.openagent.ai.config;

import java.net.URI;
import java.util.List;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties({
        OpenAgentLangChain4jProperties.class,
        OpenAgentChatProperties.class,
        OpenAgentEmbeddingProperties.class,
        OpenAgentMemoryProperties.class
})
public class OpenAgentAiConfig {
    static final String LANGCHAIN4J_HTTP_CLIENT_FACTORY_PROPERTY = "langchain4j.http.clientBuilderFactory";
    static final String JDK_HTTP_CLIENT_FACTORY_CLASS =
            "dev.langchain4j.http.client.jdk.JdkHttpClientBuilderFactory";
    static final String SPRING_REST_CLIENT_FACTORY_CLASS =
            "dev.langchain4j.http.client.spring.restclient.SpringRestClientBuilderFactory";
    public OpenAgentAiConfig(OpenAgentLangChain4jProperties langChain4jProperties) {
        configureLangChain4jHttpClientFactory(langChain4jProperties.getHttpClientFactory());
    }

    /**
     * 这一层只负责把 Spring 配置装配成 LangChain4j Bean。
     * 当前没有使用 LangChain4j 官方 starter 自动生成 ChatModel，
     * 而是手动创建 OpenAI 协议兼容模型，目的是让自定义配置项、后续扩展和学习路径都更清晰。
     */

    @Bean
    @ConditionalOnExpression(
            "'${openagent.ai.chat.base-url:}' != '' and '${openagent.ai.chat.model-name:}' != ''"
    )
    public ChatModel chatModel(
            OpenAgentChatProperties properties,
            ObjectProvider<ChatModelListener> chatModelListenersProvider
    ) {
        // ChatModel 对应“同步一次性返回完整答案”的调用方式。
        return OpenAiChatModel.builder()
                .baseUrl(normalizeOpenAiCompatibleBaseUrl(properties.getBaseUrl()))
                .apiKey(resolveApiKey(properties.getApiKey()))
                .modelName(properties.getModelName())
                .temperature(properties.getTemperature())
                .timeout(properties.getTimeout())
                .logRequests(Boolean.TRUE.equals(properties.getLogRequests()))
                .logResponses(Boolean.TRUE.equals(properties.getLogResponses()))
                .listeners(resolveChatModelListeners(chatModelListenersProvider))
                .build();
    }

    @Bean
    @ConditionalOnExpression(
            "'${openagent.ai.chat.base-url:}' != '' and '${openagent.ai.chat.model-name:}' != ''"
    )
    public StreamingChatModel streamingChatModel(
            OpenAgentChatProperties properties,
            ObjectProvider<ChatModelListener> chatModelListenersProvider
    ) {
        // StreamingChatModel 对应“边生成边回调 token”的调用方式，后端会把它再映射成 SSE。
        return OpenAiStreamingChatModel.builder()
                .baseUrl(normalizeOpenAiCompatibleBaseUrl(properties.getBaseUrl()))
                .apiKey(resolveApiKey(properties.getApiKey()))
                .modelName(properties.getModelName())
                .temperature(properties.getTemperature())
                .timeout(properties.getTimeout())
                .logRequests(Boolean.TRUE.equals(properties.getLogRequests()))
                .logResponses(Boolean.TRUE.equals(properties.getLogResponses()))
                .listeners(resolveChatModelListeners(chatModelListenersProvider))
                .build();
    }

    @Bean
    @ConditionalOnExpression(
            "'${openagent.ai.embedding.base-url:}' != '' and '${openagent.ai.embedding.model-name:}' != ''"
    )
    public EmbeddingModel embeddingModel(OpenAgentEmbeddingProperties properties) {
        // EmbeddingModel 会被知识库索引和检索共用，因此这里优先做成全局默认 Bean。
        return OpenAiEmbeddingModel.builder()
                .baseUrl(normalizeOpenAiCompatibleBaseUrl(properties.getBaseUrl()))
                .apiKey(resolveApiKey(properties.getApiKey()))
                .modelName(properties.getModelName())
                .timeout(properties.getTimeout())
                .logRequests(Boolean.TRUE.equals(properties.getLogRequests()))
                .logResponses(Boolean.TRUE.equals(properties.getLogResponses()))
                .build();
    }

    private String resolveApiKey(String apiKey) {
        // 有些内网 OpenAI 兼容网关并不校验 api key，但 LangChain4j builder 仍要求提供非空值。
        return StringUtils.hasText(apiKey) ? apiKey : "demo";
    }

    static void configureLangChain4jHttpClientFactory(String configuredFactory) {
        // 允许外部通过 JVM system property 覆盖；项目配置只作为默认兜底。
        if (StringUtils.hasText(System.getProperty(LANGCHAIN4J_HTTP_CLIENT_FACTORY_PROPERTY))) {
            return;
        }

        System.setProperty(
                LANGCHAIN4J_HTTP_CLIENT_FACTORY_PROPERTY,
                resolveLangChain4jHttpClientFactory(configuredFactory)
        );
    }

    static String resolveLangChain4jHttpClientFactory(String configuredFactory) {
        if (!StringUtils.hasText(configuredFactory)) {
            return JDK_HTTP_CLIENT_FACTORY_CLASS;
        }

        return switch (configuredFactory.trim().toUpperCase()) {
            case "JDK" -> JDK_HTTP_CLIENT_FACTORY_CLASS;
            case "SPRING_REST_CLIENT", "SPRING_REST" -> SPRING_REST_CLIENT_FACTORY_CLASS;
            default -> configuredFactory.trim();
        };
    }

    public static String normalizeOpenAiCompatibleBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return baseUrl;
        }

        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        try {
            URI uri = URI.create(normalized);
            String path = uri.getPath();
            if (!StringUtils.hasText(path) || "/".equals(path)) {
                return normalized + "/v1";
            }
        } catch (IllegalArgumentException ignored) {
            return normalized;
        }

        return normalized;
    }

    private List<ChatModelListener> resolveChatModelListeners(
            ObjectProvider<ChatModelListener> chatModelListenersProvider
    ) {
        /**
         * 这里显式把 Spring Bean 形式存在的 `ChatModelListener` 挂回模型 builder。
         *
         * 这样后续继续补更多 LangChain4j 原生 observability listener 时，
         * 不需要再改 ChatModel/StreamingChatModel 的装配代码。
         */
        return chatModelListenersProvider.orderedStream().toList();
    }
}
