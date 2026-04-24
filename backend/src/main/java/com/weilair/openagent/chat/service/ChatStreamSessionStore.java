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
    /**
     * 这是一个进程内的流式会话存储。
     * 当前阶段先用内存保存 requestId -> SSE 会话状态，目的是把 LangChain4j 流式输出跑通。
     *
     * 后续如果要支持多实例部署、断线恢复、历史回放，就要把这层下沉到数据库或消息队列。
     */

    /**
     * 传给 SseEmitter 的超时时间。
     * 这里设置为 0，表示不由 Spring 主动超时，而是把连接生命周期交给业务侧控制。
     */
    private static final long SSE_TIMEOUT_MILLIS = 0L;

    /**
     * requestId -> ChatStreamSession。
     * 之所以用 ConcurrentHashMap，是因为：
     * 1. HTTP 请求线程会创建和查询会话
     * 2. LangChain4j 的流式回调线程会持续往会话里写事件
     * 3. SSE 连接线程也会读取同一个会话并发送历史事件
     */
    private final Map<String, ChatStreamSession> sessions = new ConcurrentHashMap<>();

    /**
     * 流式会话在内存中的保留时间。
     * 即使一次生成已经完成，也不会立刻删除，这样前端稍晚连上 SSE 时仍能拿到结果。
     */
    private final Duration retention;

    public ChatStreamSessionStore(@Value("${openagent.chat.session-retention:PT10M}") Duration retention) {
        // 保留时间通过配置注入，便于后续按环境调节。
        this.retention = retention;
    }

    public ChatStreamSession create(Long conversationId) {
        // 每次创建新会话前，顺手做一次轻量清理，避免长期累积过期对象。
        cleanupExpiredSessions();

        // requestId 采用“时间戳 + 短随机串”，方便排查日志，也避免简单递增 id 带来的冲突和暴露问题。
        String requestId = "chat_" + Instant.now().toEpochMilli() + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        // 创建一条新的流式任务运行态记录。
        ChatStreamSession session = new ChatStreamSession(requestId, conversationId);

        // 立即放入内存索引，后续 /send 返回 requestId 后，/stream 就能凭 requestId 找到它。
        sessions.put(requestId, session);

        // 把新会话返回给上层服务继续写入 message_start 事件、启动模型生成等操作。
        return session;
    }

    public ChatStreamSession getRequired(String requestId) {
        // 查询前也先清一次过期数据，减少取到陈旧会话的概率。
        cleanupExpiredSessions();

        // 直接按 requestId 定位流式任务。
        ChatStreamSession session = sessions.get(requestId);
        if (session == null) {
            // 没查到就明确抛业务异常，而不是返回 null 让上层继续判空。
            throw new ChatRequestNotFoundException(requestId);
        }
        return session;
    }

    /**
     * 当上层因为重复确认改为复用已有 continuation 时，
     * 需要把这次未真正投入执行的空会话移除，避免残留在内存索引里。
     */
    public void remove(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        sessions.remove(requestId);
    }

    public SseEmitter connect(String requestId) {
        // 先确认 requestId 对应的流式任务确实存在。
        ChatStreamSession session = getRequired(requestId);

        // 为这次 HTTP SSE 连接创建一个新的发射器对象。
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);

        // 把当前发射器挂到 session 上，后续 token 产生时就能实时推送到这个连接。
        session.emitter(emitter);

        // 连接关闭、超时、出错时，都把 session 里的 emitter 清空，避免继续向失效连接发送数据。
        emitter.onCompletion(() -> session.emitter(null));
        emitter.onTimeout(() -> session.emitter(null));
        emitter.onError(error -> session.emitter(null));

        // 前端可能在后端已经产生部分 token 之后才真正连上 SSE，所以这里要先重放缓冲事件。
        replayBufferedEvents(session, emitter);

        if (session.isFinished()) {
            // 如果这次连接建立时任务已经结束，就补发完历史事件后立即关闭 SSE。
            emitter.complete();
        }
        return emitter;
    }

    public SseEmitter connectTrace(String requestId) {
        ChatStreamSession session = getRequired(requestId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        session.traceEmitter(emitter);
        emitter.onCompletion(() -> session.traceEmitter(null));
        emitter.onTimeout(() -> session.traceEmitter(null));
        emitter.onError(error -> session.traceEmitter(null));
        replayBufferedTraceEvents(session, emitter);
        if (session.isFinished()) {
            emitter.complete();
        }
        return emitter;
    }

    public void appendEvent(ChatStreamSession session, String eventName, Object payload) {
        // 先把事件名和负载封装成统一对象，便于缓冲、重放和发送。
        ChatStreamEvent event = new ChatStreamEvent(eventName, payload);

        // 先入缓冲，再尝试实时发送，这样断线重连或稍晚订阅时仍能拿到之前的事件。
        session.events().add(event);

        // 读取当前会话上是否已经有活动中的 SSE 连接。
        SseEmitter emitter = session.emitter();
        if (emitter != null) {
            // 如果前端已连上，就立即把这条事件推过去，形成“边生成边消费”的效果。
            send(emitter, event);
        }
    }

    public void appendTraceEvent(ChatStreamSession session, Object payload) {
        ChatStreamEvent event = new ChatStreamEvent("trace", payload);
        session.traceEvents().add(event);

        SseEmitter emitter = session.traceEmitter();
        if (emitter != null) {
            send(emitter, event);
        }
    }

    /**
     * progress/thinking 这类提示只服务当前在线用户体验，不做事件缓冲，也不参与历史重放。
     */
    public void sendTransientEvent(ChatStreamSession session, String eventName, Object payload) {
        SseEmitter emitter = session.emitter();
        if (emitter != null) {
            send(emitter, new ChatStreamEvent(eventName, payload));
        }
    }

    public void complete(ChatStreamSession session) {
        // 先把任务状态改成 COMPLETED，表示这次流式生成正常结束。
        session.status("COMPLETED");

        // 记录结束时间，后续清理过期会话时会用到。
        session.finishedAt(System.currentTimeMillis());

        // 正常结束时，如果前端仍在线，就主动关闭 SSE。
        SseEmitter emitter = session.emitter();
        if (emitter != null) {
            emitter.complete();
        }

        SseEmitter traceEmitter = session.traceEmitter();
        if (traceEmitter != null) {
            traceEmitter.complete();
        }
    }

    public void fail(ChatStreamSession session, String message) {
        // 异常结束时先把状态改成 FAILED，和正常完成区分开。
        session.status("FAILED");

        // 同样记录结束时间，保持清理策略一致。
        session.finishedAt(System.currentTimeMillis());

        // 失败场景下补发一个 error 事件，让前端能感知这次流式任务为何终止。
        appendEvent(session, "error", new StreamErrorPayload(session.requestId(), message));

        // 错误事件发完后，关闭当前 SSE 连接。
        SseEmitter emitter = session.emitter();
        if (emitter != null) {
            emitter.complete();
        }

        SseEmitter traceEmitter = session.traceEmitter();
        if (traceEmitter != null) {
            traceEmitter.complete();
        }
    }

    private void replayBufferedEvents(ChatStreamSession session, SseEmitter emitter) {
        // 顺序重放历史事件，保证前端后连上时看到的事件顺序和真实产生顺序一致。
        for (ChatStreamEvent event : session.events()) {
            send(emitter, event);
        }
    }

    private void replayBufferedTraceEvents(ChatStreamSession session, SseEmitter emitter) {
        for (ChatStreamEvent event : session.traceEvents()) {
            send(emitter, event);
        }
    }

    private void send(SseEmitter emitter, ChatStreamEvent event) {
        try {
            // 事件名采用独立字段，前端可以按 message_start / token / message_end / error 分开处理。
            emitter.send(
                    SseEmitter.event()
                            .name(event.eventName())
                            .data(event.payload(), MediaType.APPLICATION_JSON)
            );
        } catch (IOException exception) {
            // 一旦底层连接写失败，就把 emitter 标记为错误完成，让连接生命周期尽快收敛。
            emitter.completeWithError(exception);
        }
    }

    private void cleanupExpiredSessions() {
        // 未完成会话按创建时间清理，已完成会话按结束时间清理，避免内存长期累积。
        long expirationThreshold = Instant.now().minus(retention).toEpochMilli();
        sessions.entrySet().removeIf(entry -> {
            ChatStreamSession session = entry.getValue();

            // 已完成任务优先用 finishedAt 作为参考时间；还没结束的任务则退回 createdAt。
            long referenceTime = session.finishedAt() > 0 ? session.finishedAt() : session.createdAt();

            // 早于过期阈值的会话从内存索引中移除。
            return referenceTime < expirationThreshold;
        });
    }

    /**
     * 统一 SSE error 事件的负载结构。
     * 单独建 record 是为了让前端始终拿到稳定 JSON，而不是零散字符串。
     */
    public record StreamErrorPayload(
            String requestId,
            String message
    ) {
    }
}
