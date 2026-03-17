package com.weilair.openagent.chat.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.weilair.openagent.chat.exception.ConversationBusyException;
import org.springframework.stereotype.Component;

@Component
public class ConversationExecutionGuard {

    private final Map<Long, String> activeRequests = new ConcurrentHashMap<>();

    /**
     * 当前先做进程内串行保护，避免同一会话在流式输出过程中被重复写入。
     */
    public void acquire(Long conversationId, String requestId) {
        String activeRequestId = activeRequests.putIfAbsent(conversationId, requestId);
        if (activeRequestId != null && !activeRequestId.equals(requestId)) {
            throw new ConversationBusyException(conversationId, activeRequestId);
        }
    }

    public void release(Long conversationId, String requestId) {
        activeRequests.remove(conversationId, requestId);
    }
}
