package com.weilair.openagent.chat.service;

import com.weilair.openagent.web.vo.TraceEventVO;

/**
 * `ChatOutputPort` 是统一编排骨架第二轮收口里的“输出边界”。
 * 它先不直接引入 LangChain4j `TokenStream`，而是把当前实现里的两类输出形态先收口到同一个接口：
 * 1. 同步模式：只在内存里缓冲最终答案
 * 2. 流式模式：把 token / message_end / error 写入 SSE 会话
 *
 * 后续如果切到 `AI Services + TokenStream`，编排主链路仍然可以继续依赖这层，
 * 只需要把底层输出适配从“当前自定义会话”替换成“官方流式回调”即可。
 */
public interface ChatOutputPort {

    String requestId();

    Long conversationId();

    Long userMessageId();

    void userMessageId(Long userMessageId);

    String status();

    void markRunning();

    boolean isFinished();

    boolean markResponseStreamingStarted();

    boolean isResponseStreamingStarted();

    String answer();

    void replaceAnswer(String answer);

    void appendAnswer(String answerChunk);

    String finishReason();

    void emitStarted();

    void emitProgress(String stage, String message, long elapsedMillis);

    void emitToken(String token);

    void emitMessageEnd(String answer, String finishReason);

    void complete();

    void fail(String message);

    default void appendTraceEvent(TraceEventVO traceEvent) {
        // 同步模式没有 SSE trace 通道，因此默认不做额外处理。
    }
}
