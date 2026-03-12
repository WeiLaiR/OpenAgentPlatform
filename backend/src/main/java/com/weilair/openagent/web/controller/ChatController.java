package com.weilair.openagent.web.controller;

import com.weilair.openagent.chat.service.ChatGenerationService;
import com.weilair.openagent.chat.service.ChatStreamSessionStore;
import com.weilair.openagent.common.response.ApiResponse;
import com.weilair.openagent.web.dto.ChatSendRequest;
import com.weilair.openagent.web.vo.ChatAnswerVO;
import com.weilair.openagent.web.vo.ChatRequestAcceptedVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

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
        return ApiResponse.success(chatGenerationService.sendSync(request));
    }

    @PostMapping("/send")
    public ApiResponse<ChatRequestAcceptedVO> send(@Valid @RequestBody ChatSendRequest request) {
        return ApiResponse.success(chatGenerationService.submit(request));
    }

    @GetMapping("/stream/{requestId}")
    public SseEmitter stream(@PathVariable String requestId) {
        return chatStreamSessionStore.connect(requestId);
    }
}
