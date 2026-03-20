package com.weilair.openagent.chat.service;

/**
 * 同步模式下的输出口只需要做一件事：在一次请求生命周期内稳定缓冲最终答案。
 * 它不负责实时事件发送，但会保留和流式输出口一致的状态接口，
 * 这样 `ChatOrchestrator` 可以沿用同一套生命周期写法。
 */
public class BufferedChatOutputPort implements ChatOutputPort {

    private final String requestId;
    private final Long conversationId;
    private final StringBuilder answerBuilder;

    private Long userMessageId;
    private String status;
    private String finishReason;
    private boolean responseStreamingStarted;

    public BufferedChatOutputPort(String requestId, Long conversationId) {
        this.requestId = requestId;
        this.conversationId = conversationId;
        this.answerBuilder = new StringBuilder();
        this.status = "ACCEPTED";
        this.finishReason = "stop";
    }

    @Override
    public String requestId() {
        return requestId;
    }

    @Override
    public Long conversationId() {
        return conversationId;
    }

    @Override
    public Long userMessageId() {
        return userMessageId;
    }

    @Override
    public void userMessageId(Long userMessageId) {
        this.userMessageId = userMessageId;
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public void markRunning() {
        this.status = "RUNNING";
    }

    @Override
    public boolean isFinished() {
        return "COMPLETED".equals(status) || "FAILED".equals(status);
    }

    @Override
    public boolean markResponseStreamingStarted() {
        if (responseStreamingStarted) {
            return false;
        }
        responseStreamingStarted = true;
        return true;
    }

    @Override
    public boolean isResponseStreamingStarted() {
        return responseStreamingStarted;
    }

    @Override
    public String answer() {
        return answerBuilder.toString();
    }

    @Override
    public void replaceAnswer(String answer) {
        answerBuilder.setLength(0);
        if (answer != null) {
            answerBuilder.append(answer);
        }
    }

    @Override
    public void appendAnswer(String answerChunk) {
        if (answerChunk != null) {
            answerBuilder.append(answerChunk);
        }
    }

    @Override
    public String finishReason() {
        return finishReason;
    }

    @Override
    public void emitStarted() {
        // 同步模式没有 message_start 事件。
    }

    @Override
    public void emitProgress(String stage, String message, long elapsedMillis) {
        // 同步模式没有 progress/thinking 事件。
    }

    @Override
    public void emitToken(String token) {
        // 同步模式不会逐 token 对外发送事件。
    }

    @Override
    public void emitMessageEnd(String answer, String finishReason) {
        this.finishReason = finishReason == null || finishReason.isBlank() ? "stop" : finishReason;
    }

    @Override
    public void complete() {
        this.status = "COMPLETED";
    }

    @Override
    public void fail(String message) {
        this.status = "FAILED";
        this.finishReason = "error";
    }
}
