package com.weilair.openagent.chat.service;

import com.weilair.openagent.web.dto.ChatSendRequest;
import com.weilair.openagent.web.vo.ChatAnswerVO;
import com.weilair.openagent.web.vo.ChatRequestAcceptedVO;
import org.springframework.stereotype.Service;

@Service
public class ChatGenerationService {

    /**
     * 对外仍然保留 `ChatGenerationService` 这个应用服务入口，
     * 这样控制层和现有调用点不需要在这一轮重构里一起改名。
     *
     * 真正的模式解析、上下文装配和执行编排已经下沉到 `ChatOrchestrator`，
     * 这里后续只保留“兼容旧入口”的薄适配职责。
     */
    private final ChatOrchestrator chatOrchestrator;

    public ChatGenerationService(ChatOrchestrator chatOrchestrator) {
        this.chatOrchestrator = chatOrchestrator;
    }

    public ChatAnswerVO sendSync(ChatSendRequest request) {
        return chatOrchestrator.sendSync(request);
    }

    public ChatRequestAcceptedVO submit(ChatSendRequest request) {
        return chatOrchestrator.submit(request);
    }

    public ChatRequestAcceptedVO approveToolConfirmation(Long confirmationId) {
        return chatOrchestrator.approveToolConfirmation(confirmationId);
    }

    public ChatRequestAcceptedVO rejectToolConfirmation(Long confirmationId) {
        return chatOrchestrator.rejectToolConfirmation(confirmationId);
    }
}
