package com.weilair.openagent.chat.model;

import java.time.LocalDateTime;

public class AgentToolConfirmationDO {

    private Long id;
    private String requestId;
    private String continuationRequestId;
    private Long conversationId;
    private Long userMessageId;
    private String modeCode;
    private Boolean memoryEnabled;
    private String knowledgeBaseIdsJson;
    private String mcpServerIdsJson;
    private String userMessageText;
    private String toolCallId;
    private String toolName;
    private String toolArgumentsJson;
    private String toolTitle;
    private String serverName;
    private String riskLevel;
    private String statusCode;
    private String decisionReason;
    private LocalDateTime decisionAt;
    private LocalDateTime executedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getContinuationRequestId() {
        return continuationRequestId;
    }

    public void setContinuationRequestId(String continuationRequestId) {
        this.continuationRequestId = continuationRequestId;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public Long getUserMessageId() {
        return userMessageId;
    }

    public void setUserMessageId(Long userMessageId) {
        this.userMessageId = userMessageId;
    }

    public String getModeCode() {
        return modeCode;
    }

    public void setModeCode(String modeCode) {
        this.modeCode = modeCode;
    }

    public Boolean getMemoryEnabled() {
        return memoryEnabled;
    }

    public void setMemoryEnabled(Boolean memoryEnabled) {
        this.memoryEnabled = memoryEnabled;
    }

    public String getKnowledgeBaseIdsJson() {
        return knowledgeBaseIdsJson;
    }

    public void setKnowledgeBaseIdsJson(String knowledgeBaseIdsJson) {
        this.knowledgeBaseIdsJson = knowledgeBaseIdsJson;
    }

    public String getMcpServerIdsJson() {
        return mcpServerIdsJson;
    }

    public void setMcpServerIdsJson(String mcpServerIdsJson) {
        this.mcpServerIdsJson = mcpServerIdsJson;
    }

    public String getUserMessageText() {
        return userMessageText;
    }

    public void setUserMessageText(String userMessageText) {
        this.userMessageText = userMessageText;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getToolArgumentsJson() {
        return toolArgumentsJson;
    }

    public void setToolArgumentsJson(String toolArgumentsJson) {
        this.toolArgumentsJson = toolArgumentsJson;
    }

    public String getToolTitle() {
        return toolTitle;
    }

    public void setToolTitle(String toolTitle) {
        this.toolTitle = toolTitle;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public String getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(String statusCode) {
        this.statusCode = statusCode;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public void setDecisionReason(String decisionReason) {
        this.decisionReason = decisionReason;
    }

    public LocalDateTime getDecisionAt() {
        return decisionAt;
    }

    public void setDecisionAt(LocalDateTime decisionAt) {
        this.decisionAt = decisionAt;
    }

    public LocalDateTime getExecutedAt() {
        return executedAt;
    }

    public void setExecutedAt(LocalDateTime executedAt) {
        this.executedAt = executedAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
