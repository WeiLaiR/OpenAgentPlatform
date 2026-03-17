package com.weilair.openagent.conversation.exception;

public class ConversationNotFoundException extends RuntimeException {

    public ConversationNotFoundException(Long conversationId) {
        super("会话不存在或已归档，conversationId=" + conversationId);
    }
}
