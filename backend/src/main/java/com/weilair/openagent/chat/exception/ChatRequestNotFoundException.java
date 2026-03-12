package com.weilair.openagent.chat.exception;

public class ChatRequestNotFoundException extends RuntimeException {

    public ChatRequestNotFoundException(String requestId) {
        super("聊天请求不存在或已过期: " + requestId);
    }
}
