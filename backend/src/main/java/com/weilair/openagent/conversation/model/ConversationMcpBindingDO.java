package com.weilair.openagent.conversation.model;

import java.time.LocalDateTime;

public class ConversationMcpBindingDO {

    private Long id;
    private Long conversationId;
    private Long mcpServerId;
    private Boolean selected;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public Long getMcpServerId() {
        return mcpServerId;
    }

    public void setMcpServerId(Long mcpServerId) {
        this.mcpServerId = mcpServerId;
    }

    public Boolean getSelected() {
        return selected;
    }

    public void setSelected(Boolean selected) {
        this.selected = selected;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
