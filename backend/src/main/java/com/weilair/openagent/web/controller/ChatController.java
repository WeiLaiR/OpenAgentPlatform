package com.weilair.openagent.web.controller;

import java.util.List;

import com.weilair.openagent.chat.service.ChatGenerationService;
import com.weilair.openagent.chat.service.ChatStreamSessionStore;
import com.weilair.openagent.common.response.ApiResponse;
import com.weilair.openagent.web.dto.ChatSendRequest;
import com.weilair.openagent.web.vo.ChatAnswerVO;
import com.weilair.openagent.web.vo.ChatRequestAcceptedVO;
import com.weilair.openagent.web.vo.ToolConfirmationPendingVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
    /**
     * 这里刻意把“提交生成请求”和“订阅流式结果”拆成两个接口：
     * 1. POST /send 负责创建任务并返回 requestId
     * 2. GET /stream/{requestId} 负责通过 SSE 接收增量结果
     *
     * 这样更接近平台化聊天系统的实际形态，后续接入 RAG、MCP、Trace 时也更容易扩展。
     */

    private final ChatGenerationService chatGenerationService;
    private final ChatStreamSessionStore chatStreamSessionStore;

    public ChatController(
            ChatGenerationService chatGenerationService,
            ChatStreamSessionStore chatStreamSessionStore
    ) {
        this.chatGenerationService = chatGenerationService;
        this.chatStreamSessionStore = chatStreamSessionStore;
    }

    @PostMapping("/send-sync")
    public ApiResponse<ChatAnswerVO> sendSync(@Valid @RequestBody ChatSendRequest request) {
        // 同步接口主要用于快速验证模型联通性，也便于和流式接口做效果对比。
        return ApiResponse.success(chatGenerationService.sendSync(request));
    }

    @PostMapping("/send")
    public ApiResponse<ChatRequestAcceptedVO> send(@Valid @RequestBody ChatSendRequest request) {
        return ApiResponse.success(chatGenerationService.submit(request));
    }

    @PostMapping("/tool-confirmations/{confirmationId}/approve")
    public ApiResponse<ChatRequestAcceptedVO> approveToolConfirmation(@PathVariable Long confirmationId) {
        return ApiResponse.success(chatGenerationService.approveToolConfirmation(confirmationId));
    }

    @PostMapping("/tool-confirmations/{confirmationId}/reject")
    public ApiResponse<ChatRequestAcceptedVO> rejectToolConfirmation(@PathVariable Long confirmationId) {
        return ApiResponse.success(chatGenerationService.rejectToolConfirmation(confirmationId));
    }

    @GetMapping("/tool-confirmations/pending")
    public ApiResponse<List<ToolConfirmationPendingVO>> listPendingToolConfirmations(
            @RequestParam Long conversationId
    ) {
        return ApiResponse.success(chatGenerationService.listPendingToolConfirmations(conversationId));
    }

    @GetMapping("/stream/{requestId}")
    public SseEmitter stream(@PathVariable String requestId) {
        return chatStreamSessionStore.connect(requestId);
    }
}
