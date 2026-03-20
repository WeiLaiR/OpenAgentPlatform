package com.weilair.openagent.chat.model;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class ChatStreamSession {
    /**
     * 一次流式聊天请求在后端内存中的运行态对象。
     * 它和数据库里的 conversation 不是一回事：
     * conversation 是业务会话，
     * 这里是某一次“流式生成任务”的临时执行上下文。
     */

    private final String requestId;
    private final Long conversationId;
    private final long createdAt;
    private final List<ChatStreamEvent> events;
    private final List<ChatStreamEvent> traceEvents;
    private final StringBuilder answerBuilder;

    private volatile SseEmitter emitter;
    private volatile SseEmitter traceEmitter;
    private volatile String status;
    private volatile long finishedAt;
    private volatile Long userMessageId;
    private volatile boolean traceStreamingStarted;

    public ChatStreamSession(String requestId, Long conversationId) {
        this.requestId = requestId;
        this.conversationId = conversationId;
        this.createdAt = Instant.now().toEpochMilli();
        this.events = new CopyOnWriteArrayList<>();
        this.traceEvents = new CopyOnWriteArrayList<>();
        this.answerBuilder = new StringBuilder();
        // ACCEPTED 表示请求已被接收，但模型生成线程还未正式开始执行。
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

    public List<ChatStreamEvent> traceEvents() {
        return traceEvents;
    }

    public SseEmitter emitter() {
        return emitter;
    }

    public void emitter(SseEmitter emitter) {
        this.emitter = emitter;
    }

    public SseEmitter traceEmitter() {
        return traceEmitter;
    }

    public void traceEmitter(SseEmitter traceEmitter) {
        this.traceEmitter = traceEmitter;
    }

    public String status() {
        return status;
    }

    public void status(String status) {
        this.status = status;
    }

    public void appendAnswer(String content) {
        if (content != null) {
            this.answerBuilder.append(content);
        }
    }

    public void replaceAnswer(String content) {
        this.answerBuilder.setLength(0);
        this.answerBuilder.append(content == null ? "" : content);
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

    public Long userMessageId() {
        return userMessageId;
    }

    public void userMessageId(Long userMessageId) {
        this.userMessageId = userMessageId;
    }

    public boolean markTraceStreamingStarted() {
        if (traceStreamingStarted) {
            return false;
        }
        traceStreamingStarted = true;
        return true;
    }

    public boolean isResponseStreamingStarted() {
        return traceStreamingStarted;
    }

    public boolean isFinished() {
        return "COMPLETED".equals(status) || "FAILED".equals(status);
    }
}
