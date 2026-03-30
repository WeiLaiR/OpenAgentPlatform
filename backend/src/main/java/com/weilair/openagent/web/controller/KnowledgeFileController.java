package com.weilair.openagent.web.controller;

import java.util.List;

import com.weilair.openagent.common.response.ApiResponse;
import com.weilair.openagent.knowledge.service.KnowledgeFileIndexService;
import com.weilair.openagent.knowledge.service.KnowledgeFileService;
import com.weilair.openagent.knowledge.service.KnowledgeSegmentService;
import com.weilair.openagent.web.dto.KnowledgeSegmentUpdateRequest;
import com.weilair.openagent.web.vo.KnowledgeFileDeleteVO;
import com.weilair.openagent.web.vo.KnowledgeFileIndexVO;
import com.weilair.openagent.web.vo.KnowledgeFileVO;
import com.weilair.openagent.web.vo.KnowledgeSegmentVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class KnowledgeFileController {

    private final KnowledgeFileService knowledgeFileService;
    private final KnowledgeFileIndexService knowledgeFileIndexService;
    private final KnowledgeSegmentService knowledgeSegmentService;

    public KnowledgeFileController(
            KnowledgeFileService knowledgeFileService,
            KnowledgeFileIndexService knowledgeFileIndexService,
            KnowledgeSegmentService knowledgeSegmentService
    ) {
        this.knowledgeFileService = knowledgeFileService;
        this.knowledgeFileIndexService = knowledgeFileIndexService;
        this.knowledgeSegmentService = knowledgeSegmentService;
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/files/upload")
    public ApiResponse<KnowledgeFileVO> uploadKnowledgeFile(
            @PathVariable Long knowledgeBaseId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) Boolean autoIndex
    ) {
        return ApiResponse.success(knowledgeFileService.uploadFile(knowledgeBaseId, file, autoIndex));
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/files")
    public ApiResponse<List<KnowledgeFileVO>> listKnowledgeFiles(
            @PathVariable Long knowledgeBaseId,
            @RequestParam(required = false) Integer limit
    ) {
        return ApiResponse.success(knowledgeFileService.listKnowledgeFiles(knowledgeBaseId, limit));
    }

    @GetMapping("/knowledge-files/{fileId}/segments")
    public ApiResponse<List<KnowledgeSegmentVO>> listKnowledgeSegments(
            @PathVariable Long fileId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer limit
    ) {
        return ApiResponse.success(knowledgeSegmentService.listKnowledgeSegments(fileId, keyword, limit));
    }

    @PostMapping("/knowledge-files/{fileId}/index")
    public ApiResponse<KnowledgeFileIndexVO> indexKnowledgeFile(@PathVariable Long fileId) {
        return ApiResponse.success(knowledgeFileIndexService.indexKnowledgeFile(fileId));
    }

    @DeleteMapping("/knowledge-files/{fileId}")
    public ApiResponse<KnowledgeFileDeleteVO> deleteKnowledgeFile(@PathVariable Long fileId) {
        return ApiResponse.success(knowledgeFileService.deleteKnowledgeFile(fileId));
    }

    @PutMapping("/knowledge-segments/{segmentId}")
    public ApiResponse<KnowledgeSegmentVO> updateKnowledgeSegment(
            @PathVariable Long segmentId,
            @Valid @RequestBody KnowledgeSegmentUpdateRequest request
    ) {
        return ApiResponse.success(knowledgeSegmentService.updateKnowledgeSegment(
                segmentId,
                request.fullText(),
                request.metadataJson()
        ));
    }

    @PostMapping("/knowledge-segments/{segmentId}/re-embed")
    public ApiResponse<KnowledgeSegmentVO> reembedKnowledgeSegment(@PathVariable Long segmentId) {
        return ApiResponse.success(knowledgeSegmentService.reembedKnowledgeSegment(segmentId));
    }
}
