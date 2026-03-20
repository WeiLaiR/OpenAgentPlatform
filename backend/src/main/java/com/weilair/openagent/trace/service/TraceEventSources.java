package com.weilair.openagent.trace.service;

/**
 * `trace_event.event_source` 当前固定维护两层来源：
 * 1. `APP_CUSTOM`：平台编排、RAG、Tool、会话控制等平台事件
 * 2. `LANGCHAIN4J_NATIVE`：直接映射 LangChain4j `ChatModelListener` 的原生模型观测事件
 *
 * 这层常量单独抽出来，目的是避免两个事件层继续各自写死字符串。
 */
public final class TraceEventSources {

    public static final String APP_CUSTOM = "APP_CUSTOM";
    public static final String LANGCHAIN4J_NATIVE = "LANGCHAIN4J_NATIVE";

    private TraceEventSources() {
    }
}
