package com.weilair.openagent.ai.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openagent.ai.chat")
public class OpenAgentChatProperties {
    /**
     * 这里先只绑定“聊天模型”配置。
     * 当前项目第一阶段先跑通 LangChain4j 的 Chat / Streaming 能力，
     * Embedding、RAG、MCP 等能力后续会拆到各自的配置对象中。
     */

    private String baseUrl;
    private String apiKey;
    private String modelName;
    private Double temperature = 0.2;
    private Duration timeout = Duration.ofMinutes(2);
    private Boolean logRequests = false;
    private Boolean logResponses = false;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Boolean getLogRequests() {
        return logRequests;
    }

    public void setLogRequests(Boolean logRequests) {
        this.logRequests = logRequests;
    }

    public Boolean getLogResponses() {
        return logResponses;
    }

    public void setLogResponses(Boolean logResponses) {
        this.logResponses = logResponses;
    }
}
