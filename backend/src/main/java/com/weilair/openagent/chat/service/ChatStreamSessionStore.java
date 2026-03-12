package com.weilair.openagent.chat.service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.weilair.openagent.chat.exception.ChatRequestNotFoundException;
import com.weilair.openagent.chat.model.ChatStreamEvent;
import com.weilair.openagent.chat.model.ChatStreamSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class ChatStreamSessionStore {

    private static final long SSE_TIMEOUT_MILLIS = 0L;

    private final Map<String, ChatStreamSession> sessions = new ConcurrentHashMap<>();
    private final Duration retention;

    public ChatStreamSessionStore(@Value("${openagent.chat.session-retention:PT10M}") Duration retention) {
        this.retention = retention;
    }

    public ChatStreamSession create(Long conversationId) {
        cleanupExpiredSessions();
        String requestId = "chat_" + Instant.now().toEpochMilli() + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        ChatStreamSession session = new ChatStreamSession(requestId, conversationId);
        sessions.put(requestId, session);
        return session;
    }

    public ChatStreamSession getRequired(String requestId) {
        cleanupExpiredSessions();
        ChatStreamSession session = sessions.get(requestId);
        if (session == null) {
            throw new ChatRequestNotFoundException(requestId);
        }
        return session;
    }

    public SseEmitter connect(String requestId) {
        ChatStreamSession session = getRequired(requestId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        session.emitter(emitter);
        emitter.onCompletion(() -> session.emitter(null));
        emitter.onTimeout(() -> session.emitter(null));
        emitter.onError(error -> session.emitter(null));

        replayBufferedEvents(session, emitter);

        if (session.isFinished()) {
            emitter.complete();
        }
        return emitter;
    }

    public void appendEvent(ChatStreamSession session, String eventName, Object payload) {
        ChatStreamEvent event = new ChatStreamEvent(eventName, payload);
        session.events().add(event);
        SseEmitter emitter = session.emitter();
        if (emitter != null) {
            send(emitter, event);
        }
    }

    public void complete(ChatStreamSession session) {
        session.status("COMPLETED");
        session.finishedAt(System.currentTimeMillis());
        SseEmitter emitter = session.emitter();
        if (emitter != null) {
            emitter.complete();
        }
    }

    public void fail(ChatStreamSession session, String message) {
        session.status("FAILED");
        session.finishedAt(System.currentTimeMillis());
        appendEvent(session, "error", new StreamErrorPayload(session.requestId(), message));
        SseEmitter emitter = session.emitter();
        if (emitter != null) {
            emitter.complete();
        }
    }

    private void replayBufferedEvents(ChatStreamSession session, SseEmitter emitter) {
        for (ChatStreamEvent event : session.events()) {
            send(emitter, event);
        }
    }

    private void send(SseEmitter emitter, ChatStreamEvent event) {
        try {
            emitter.send(
                    SseEmitter.event()
                            .name(event.eventName())
                            .data(event.payload(), MediaType.APPLICATION_JSON)
            );
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        }
    }

    private void cleanupExpiredSessions() {
        long expirationThreshold = Instant.now().minus(retention).toEpochMilli();
        sessions.entrySet().removeIf(entry -> {
            ChatStreamSession session = entry.getValue();
            long referenceTime = session.finishedAt() > 0 ? session.finishedAt() : session.createdAt();
            return referenceTime < expirationThreshold;
        });
    }

    public record StreamErrorPayload(
            String requestId,
            String message
    ) {
    }
}
