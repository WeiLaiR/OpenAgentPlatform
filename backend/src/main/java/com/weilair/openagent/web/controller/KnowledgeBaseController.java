package com.weilair.openagent.web.controller;

import java.util.List;

import com.weilair.openagent.common.response.ApiResponse;
import com.weilair.openagent.knowledge.service.KnowledgeBaseService;
import com.weilair.openagent.knowledge.service.KnowledgeRetrievalService;
import com.weilair.openagent.web.dto.KnowledgeBaseCreateRequest;
import com.weilair.openagent.web.dto.KnowledgeRetrieveRequest;
import com.weilair.openagent.web.vo.KnowledgeBaseVO;
import com.weilair.openagent.web.vo.KnowledgeRetrieveVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;

    public KnowledgeBaseController(
            KnowledgeBaseService knowledgeBaseService,
            KnowledgeRetrievalService knowledgeRetrievalService
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeRetrievalService = knowledgeRetrievalService;
    }

    @PostMapping
    public ApiResponse<KnowledgeBaseVO> createKnowledgeBase(@Valid @RequestBody KnowledgeBaseCreateRequest request) {
        return ApiResponse.success(knowledgeBaseService.createKnowledgeBase(request));
    }

    @GetMapping
    public ApiResponse<List<KnowledgeBaseVO>> listKnowledgeBases(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer limit
    ) {
        return ApiResponse.success(knowledgeBaseService.listKnowledgeBases(keyword, status, limit));
    }

    @PostMapping("/retrieve")
    public ApiResponse<KnowledgeRetrieveVO> retrieve(@Valid @RequestBody KnowledgeRetrieveRequest request) {
        return ApiResponse.success(knowledgeRetrievalService.retrieve(request));
    }
}
