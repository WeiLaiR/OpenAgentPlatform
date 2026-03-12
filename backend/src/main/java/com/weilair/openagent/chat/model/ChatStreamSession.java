package com.weilair.openagent.chat.model;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class ChatStreamSession {

    private final String requestId;
    private final Long conversationId;
    private final long createdAt;
    private final List<ChatStreamEvent> events;
    private final StringBuilder answerBuilder;

    private volatile SseEmitter emitter;
    private volatile String status;
    private volatile long finishedAt;

    public ChatStreamSession(String requestId, Long conversationId) {
        this.requestId = requestId;
        this.conversationId = conversationId;
        this.createdAt = Instant.now().toEpochMilli();
        this.events = new CopyOnWriteArrayList<>();
        this.answerBuilder = new StringBuilder();
        this.status = "ACCEPTED";
    }

    public String requestId() {
        return requestId;
    }

    public Long conversationId() {
        return conversationId;
    }

    public long createdAt() {
        return createdAt;
    }

    public List<ChatStreamEvent> events() {
        return events;
    }

    public SseEmitter emitter() {
        return emitter;
    }

    public void emitter(SseEmitter emitter) {
        this.emitter = emitter;
    }

    public String status() {
        return status;
    }

    public void status(String status) {
        this.status = status;
    }

    public void appendAnswer(String content) {
        this.answerBuilder.append(content);
    }

    public String answer() {
        return answerBuilder.toString();
    }

    public long finishedAt() {
        return finishedAt;
    }

    public void finishedAt(long finishedAt) {
        this.finishedAt = finishedAt;
    }

    public boolean isFinished() {
        return "COMPLETED".equals(status) || "FAILED".equals(status);
    }
}
