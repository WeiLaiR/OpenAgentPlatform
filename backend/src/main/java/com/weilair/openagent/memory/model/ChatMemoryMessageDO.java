package com.weilair.openagent.memory.model;

import java.time.LocalDateTime;

public class ChatMemoryMessageDO {

    private Long id;
    private Long memorySessionId;
    private Integer messageOrder;
    private String roleCode;
    private String messageJson;
    private Integer tokenCount;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMemorySessionId() {
        return memorySessionId;
    }

    public void setMemorySessionId(Long memorySessionId) {
        this.memorySessionId = memorySessionId;
    }

    public Integer getMessageOrder() {
        return messageOrder;
    }

    public void setMessageOrder(Integer messageOrder) {
        this.messageOrder = messageOrder;
    }

    public String getRoleCode() {
        return roleCode;
    }

    public void setRoleCode(String roleCode) {
        this.roleCode = roleCode;
    }

    public String getMessageJson() {
        return messageJson;
    }

    public void setMessageJson(String messageJson) {
        this.messageJson = messageJson;
    }

    public Integer getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount) {
        this.tokenCount = tokenCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
