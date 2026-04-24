package com.weilair.openagent.chat.service;

import com.weilair.openagent.chat.model.ChatStreamSession;
import com.weilair.openagent.web.vo.ChatStreamCompletedVO;
import com.weilair.openagent.web.vo.ChatStreamProgressVO;
import com.weilair.openagent.web.vo.ChatStreamStartedVO;
import com.weilair.openagent.web.vo.ChatStreamTokenVO;
import com.weilair.openagent.web.vo.TraceEventVO;
import com.weilair.openagent.web.vo.ToolConfirmationPendingVO;

/**
 * 流式模式当前仍然落在自定义 `ChatStreamSessionStore` 上。
 * 这一层的意义不是再发明一套流式协议，而是先把“编排链路如何写事件”从主类中抽出来，
 * 这样后续回切 `TokenStream` 时，只需要替换输出适配层，而不是重写整个聊天生命周期。
 */
public class StreamingChatOutputPort implements ChatOutputPort {

    private final ChatStreamSessionStore sessionStore;
    private final ChatStreamSession session;

    private String finishReason;

    public StreamingChatOutputPort(ChatStreamSessionStore sessionStore, ChatStreamSession session) {
        this.sessionStore = sessionStore;
        this.session = session;
        this.finishReason = "stop";
    }

    @Override
    public String requestId() {
        return session.requestId();
    }

    @Override
    public Long conversationId() {
        return session.conversationId();
    }

    @Override
    public Long userMessageId() {
        return session.userMessageId();
    }

    @Override
    public void userMessageId(Long userMessageId) {
        session.userMessageId(userMessageId);
    }

    @Override
    public String status() {
        return session.status();
    }

    @Override
    public void markRunning() {
        session.status("RUNNING");
    }

    @Override
    public boolean isFinished() {
        return session.isFinished();
    }

    @Override
    public boolean markResponseStreamingStarted() {
        return session.markTraceStreamingStarted();
    }

    @Override
    public boolean isResponseStreamingStarted() {
        return session.isResponseStreamingStarted();
    }

    @Override
    public String answer() {
        return session.answer();
    }

    @Override
    public void replaceAnswer(String answer) {
        session.replaceAnswer(answer == null ? "" : answer);
    }

    @Override
    public void appendAnswer(String answerChunk) {
        if (answerChunk != null) {
            session.appendAnswer(answerChunk);
        }
    }

    @Override
    public String finishReason() {
        return finishReason;
    }

    @Override
    public void emitStarted() {
        sessionStore.appendEvent(
                session,
                "message_start",
                new ChatStreamStartedVO(session.requestId(), session.conversationId())
        );
    }

    @Override
    public void emitProgress(String stage, String message, long elapsedMillis) {
        sessionStore.sendTransientEvent(
                session,
                "progress",
                new ChatStreamProgressVO(session.requestId(), stage, message, elapsedMillis)
        );
    }

    @Override
    public void emitToken(String token) {
        sessionStore.appendEvent(session, "token", new ChatStreamTokenVO(session.requestId(), token));
    }

    @Override
    public void emitMessageEnd(String answer, String finishReason, ToolConfirmationPendingVO pendingConfirmation) {
        this.finishReason = finishReason == null || finishReason.isBlank() ? "stop" : finishReason;
        sessionStore.appendEvent(
                session,
                "message_end",
                new ChatStreamCompletedVO(session.requestId(), answer, this.finishReason, pendingConfirmation)
        );
    }

    @Override
    public void complete() {
        sessionStore.complete(session);
    }

    @Override
    public void fail(String message) {
        this.finishReason = "error";
        sessionStore.fail(session, message);
    }

    @Override
    public void appendTraceEvent(TraceEventVO traceEvent) {
        sessionStore.appendTraceEvent(session, traceEvent);
    }
}
