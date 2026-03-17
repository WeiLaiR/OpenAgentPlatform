package com.weilair.openagent.web.controller;

import java.util.List;

import com.weilair.openagent.common.response.ApiResponse;
import com.weilair.openagent.conversation.service.ConversationService;
import com.weilair.openagent.web.dto.ConversationCreateRequest;
import com.weilair.openagent.web.vo.ConversationMessageVO;
import com.weilair.openagent.web.vo.ConversationVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    public ApiResponse<ConversationVO> createConversation(@Valid @RequestBody ConversationCreateRequest request) {
        return ApiResponse.success(conversationService.createConversation(request));
    }

    @GetMapping
    public ApiResponse<List<ConversationVO>> listConversations() {
        return ApiResponse.success(conversationService.listConversations());
    }

    @GetMapping("/{conversationId}/messages")
    public ApiResponse<List<ConversationMessageVO>> listMessages(@PathVariable Long conversationId) {
        return ApiResponse.success(conversationService.listMessages(conversationId));
    }
}
