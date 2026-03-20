package com.weilair.openagent.trace.service;

import com.weilair.openagent.chat.service.ChatOutputPort;
import org.springframework.stereotype.Component;

/**
 * `ChatModelListener` 只能拿到 LangChain4j 自己的 request/response/error 上下文，
 * 并不知道平台侧的 `conversationId/requestId/messageId`。
 *
 * 因此这里补一层“模型调用作用域”：
 * - 编排层在真正调用 `ChatModel/StreamingChatModel/AI Services` 前先打开作用域
 * - `ChatModelListener.onRequest()` 在同线程里读取当前作用域并写入 listener attributes
 * - `onResponse()/onError()` 再从 attributes 中取回，继续完成 native trace 落库
 *
 * 这层只承接“把平台请求上下文桥接进 LangChain4j listener 生命周期”这一件事，
 * 不参与平台事件本身的写入。
 */
@Component
public class ChatModelObservationContextHolder {

    private static final ThreadLocal<ChatModelObservationContext> CONTEXT_HOLDER = new ThreadLocal<>();

    public Scope open(ChatOutputPort outputPort, String executionMode) {
        ChatModelObservationContext previous = CONTEXT_HOLDER.get();
        CONTEXT_HOLDER.set(new ChatModelObservationContext(
                outputPort.conversationId(),
                outputPort.requestId(),
                outputPort.userMessageId(),
                executionMode,
                outputPort
        ));
        return new Scope(previous);
    }

    public ChatModelObservationContext current() {
        return CONTEXT_HOLDER.get();
    }

    public record ChatModelObservationContext(
            Long conversationId,
            String requestId,
            Long messageId,
            String executionMode,
            ChatOutputPort outputPort
    ) {
    }

    public final class Scope implements AutoCloseable {

        private final ChatModelObservationContext previous;
        private boolean closed;

        private Scope(ChatModelObservationContext previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CONTEXT_HOLDER.remove();
                return;
            }
            CONTEXT_HOLDER.set(previous);
        }
    }
}
