package com.weilair.openagent.mcp.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openagent.mcp")
public class OpenAgentMcpProperties {
    /**
     * 这一组配置只描述“平台如何作为 MCP client 去连接外部 server”，
     * 不保存具体某个 server 的注册信息。
     * 具体 server 仍然落在数据库，配置文件只保留全局超时、日志和协议默认值。
     */

    private String clientName = "openagent";
    private String clientVersion = "0.0.1";
    private String protocolVersion = "2024-11-05";
    private Duration initializationTimeout = Duration.ofSeconds(30);
    private Duration toolExecutionTimeout = Duration.ofSeconds(60);
    private Duration resourcesTimeout = Duration.ofSeconds(60);
    private Duration promptsTimeout = Duration.ofSeconds(60);
    private Duration pingTimeout = Duration.ofSeconds(10);
    private Duration reconnectInterval = Duration.ofSeconds(5);
    private Duration httpTimeout = Duration.ofSeconds(20);
    private Boolean logEvents = false;
    private Boolean logRequests = false;
    private Boolean logResponses = false;
    private Boolean subsidiaryChannel = false;

    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public String getClientVersion() {
        return clientVersion;
    }

    public void setClientVersion(String clientVersion) {
        this.clientVersion = clientVersion;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public Duration getInitializationTimeout() {
        return initializationTimeout;
    }

    public void setInitializationTimeout(Duration initializationTimeout) {
        this.initializationTimeout = initializationTimeout;
    }

    public Duration getToolExecutionTimeout() {
        return toolExecutionTimeout;
    }

    public void setToolExecutionTimeout(Duration toolExecutionTimeout) {
        this.toolExecutionTimeout = toolExecutionTimeout;
    }

    public Duration getResourcesTimeout() {
        return resourcesTimeout;
    }

    public void setResourcesTimeout(Duration resourcesTimeout) {
        this.resourcesTimeout = resourcesTimeout;
    }

    public Duration getPromptsTimeout() {
        return promptsTimeout;
    }

    public void setPromptsTimeout(Duration promptsTimeout) {
        this.promptsTimeout = promptsTimeout;
    }

    public Duration getPingTimeout() {
        return pingTimeout;
    }

    public void setPingTimeout(Duration pingTimeout) {
        this.pingTimeout = pingTimeout;
    }

    public Duration getReconnectInterval() {
        return reconnectInterval;
    }

    public void setReconnectInterval(Duration reconnectInterval) {
        this.reconnectInterval = reconnectInterval;
    }

    public Duration getHttpTimeout() {
        return httpTimeout;
    }

    public void setHttpTimeout(Duration httpTimeout) {
        this.httpTimeout = httpTimeout;
    }

    public Boolean getLogEvents() {
        return logEvents;
    }

    public void setLogEvents(Boolean logEvents) {
        this.logEvents = logEvents;
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

    public Boolean getSubsidiaryChannel() {
        return subsidiaryChannel;
    }

    public void setSubsidiaryChannel(Boolean subsidiaryChannel) {
        this.subsidiaryChannel = subsidiaryChannel;
    }
}
