package com.weilair.openagent.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openagent.ai")
public class OpenAgentLangChain4jProperties {
    /**
     * 引入 `langchain4j-mcp` 后，类路径里会同时存在多个 LangChain4j HTTP client factory。
     * 这里显式固定默认实现，避免 LangChain4j 在运行时因为“发现多个候选实现”直接拒绝启动。
     *
     * 当前支持：
     * - `JDK`
     * - `SPRING_REST_CLIENT`
     * - 直接填写 LangChain4j `HttpClientBuilderFactory` 的完整类名
     */
    private String httpClientFactory = "JDK";

    public String getHttpClientFactory() {
        return httpClientFactory;
    }

    public void setHttpClientFactory(String httpClientFactory) {
        this.httpClientFactory = httpClientFactory;
    }
}
