package com.weilair.openagent.chat.model;

public record ChatStreamEvent(
        String eventName,
        Object payload
) {
}
