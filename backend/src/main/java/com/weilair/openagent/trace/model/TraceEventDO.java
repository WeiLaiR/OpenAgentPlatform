package com.weilair.openagent.trace.model;

import java.time.LocalDateTime;

public class TraceEventDO {

    private Long id;
    private Long conversationId;
    private String requestId;
    private Long messageId;
    private String eventType;
    private String eventStage;
    private String eventSource;
    private String eventPayloadJson;
    private Boolean successFlag;
    private Integer costMillis;
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

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getEventStage() {
        return eventStage;
    }

    public void setEventStage(String eventStage) {
        this.eventStage = eventStage;
    }

    public String getEventSource() {
        return eventSource;
    }

    public void setEventSource(String eventSource) {
        this.eventSource = eventSource;
    }

    public String getEventPayloadJson() {
        return eventPayloadJson;
    }

    public void setEventPayloadJson(String eventPayloadJson) {
        this.eventPayloadJson = eventPayloadJson;
    }

    public Boolean getSuccessFlag() {
        return successFlag;
    }

    public void setSuccessFlag(Boolean successFlag) {
        this.successFlag = successFlag;
    }

    public Integer getCostMillis() {
        return costMillis;
    }

    public void setCostMillis(Integer costMillis) {
        this.costMillis = costMillis;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
