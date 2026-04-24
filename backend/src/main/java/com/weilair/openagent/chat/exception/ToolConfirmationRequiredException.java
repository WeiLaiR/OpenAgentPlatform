package com.weilair.openagent.chat.exception;

import com.weilair.openagent.web.vo.ToolConfirmationPendingVO;

public class ToolConfirmationRequiredException extends RuntimeException {

    private final ToolConfirmationPendingVO pendingConfirmation;

    public ToolConfirmationRequiredException(ToolConfirmationPendingVO pendingConfirmation) {
        super(pendingConfirmation == null ? "工具确认已触发" : pendingConfirmation.statusMessage());
        this.pendingConfirmation = pendingConfirmation;
    }

    public ToolConfirmationPendingVO pendingConfirmation() {
        return pendingConfirmation;
    }
}
