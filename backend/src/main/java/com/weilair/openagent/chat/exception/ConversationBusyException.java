package com.weilair.openagent.chat.exception;

public class ConversationBusyException extends RuntimeException {

    public ConversationBusyException(Long conversationId, String activeRequestId) {
        super("会话 " + conversationId + " 当前已有进行中的请求，请等待请求 " + activeRequestId + " 完成后重试。");
    }
}
