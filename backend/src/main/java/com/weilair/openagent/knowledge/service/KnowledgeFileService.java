package com.weilair.openagent.knowledge.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.weilair.openagent.common.util.TimeUtils;
import com.weilair.openagent.knowledge.model.KnowledgeFileDO;
import com.weilair.openagent.knowledge.persistence.mapper.KnowledgeFileMapper;
import com.weilair.openagent.web.vo.KnowledgeFileVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class KnowledgeFileService {
    /**
     * 阶段四当前先只完成“上传文件 + 元数据入库”：
     * - 文件先稳定落到存储层
     * - knowledge_file 保存状态、hash 和存储位置
     * - 解析、分段、向量化留给下一步的索引链路
     */

    private static final Long DEFAULT_USER_ID = 1L;
    private static final int DEFAULT_LIST_LIMIT = 100;
    private static final String STORAGE_TYPE_LOCAL = "LOCAL";
    private static final Set<String> SUPPORTED_FILE_EXTENSIONS = Set.of("txt", "md", "docx");
    private static final String PARSE_STATUS_UPLOADED = "UPLOADED";
    private static final String INDEX_STATUS_PENDING = "PENDING";

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeFileMapper knowledgeFileMapper;
    private final KnowledgeFileStorageService knowledgeFileStorageService;

    public KnowledgeFileService(
            KnowledgeBaseService knowledgeBaseService,
            KnowledgeFileMapper knowledgeFileMapper,
            KnowledgeFileStorageService knowledgeFileStorageService
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeFileMapper = knowledgeFileMapper;
        this.knowledgeFileStorageService = knowledgeFileStorageService;
    }

    /**
     * 上传阶段要先把“输入边界”收紧：
     * - 只接收当前承诺支持的文档格式
     * - 只负责上传和落库，不在这里偷偷触发索引
     * 这样前后端和状态机都不会进入“看起来上传成功了，但索引行为其实不确定”的模糊状态。
     */
    @Transactional
    public KnowledgeFileVO uploadFile(Long knowledgeBaseId, MultipartFile file, Boolean autoIndex) {
        knowledgeBaseService.requireKnowledgeBase(knowledgeBaseId);
        validateUploadRequest(file, autoIndex);

        KnowledgeFileStorageService.StoredFile storedFile = knowledgeFileStorageService.storeKnowledgeFile(knowledgeBaseId, file);
        try {
            KnowledgeFileDO knowledgeFile = new KnowledgeFileDO();
            knowledgeFile.setKnowledgeBaseId(knowledgeBaseId);
            knowledgeFile.setFileName(resolveOriginalFileName(file));
            knowledgeFile.setFileExt(resolveFileExtension(knowledgeFile.getFileName()));
            knowledgeFile.setFileSize(storedFile.fileSize());
            knowledgeFile.setStorageType(resolveStorageType());
            knowledgeFile.setStorageUri(storedFile.storageUri());
            knowledgeFile.setFileHash(storedFile.fileHash());
            knowledgeFile.setParseStatus(PARSE_STATUS_UPLOADED);
            knowledgeFile.setIndexStatus(INDEX_STATUS_PENDING);
            knowledgeFile.setUploadedBy(DEFAULT_USER_ID);
            knowledgeFileMapper.insert(knowledgeFile);
            return toKnowledgeFileVO(requireKnowledgeFile(knowledgeFile.getId()));
        } catch (RuntimeException exception) {
            cleanupStoredFile(storedFile.storageUri(), exception);
            throw exception;
        }
    }

    /**
     * 文件列表是知识库管理页的基础数据源。
     * 先把上传时间、解析状态、索引状态和存储位置回出来，后续片段页和重建索引入口都基于这层展开。
     */
    public List<KnowledgeFileVO> listKnowledgeFiles(Long knowledgeBaseId, Integer limit) {
        knowledgeBaseService.requireKnowledgeBase(knowledgeBaseId);
        int effectiveLimit = limit == null || limit <= 0 ? DEFAULT_LIST_LIMIT : Math.min(limit, DEFAULT_LIST_LIMIT);
        return knowledgeFileMapper.selectByKnowledgeBaseId(knowledgeBaseId, effectiveLimit).stream()
                .map(this::toKnowledgeFileVO)
                .toList();
    }

    public KnowledgeFileDO requireKnowledgeFile(Long fileId) {
        KnowledgeFileDO knowledgeFile = knowledgeFileMapper.selectById(fileId);
        if (knowledgeFile == null) {
            throw new IllegalArgumentException("知识库文件不存在: " + fileId);
        }
        return knowledgeFile;
    }

    private void validateUploadRequest(MultipartFile file, Boolean autoIndex) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空。");
        }
        if (Boolean.TRUE.equals(autoIndex)) {
            throw new IllegalArgumentException("autoIndex=true 暂未接入，当前阶段请先上传文件，再走独立索引接口。");
        }

        String fileName = resolveOriginalFileName(file);
        String fileExtension = resolveFileExtension(fileName);
        if (!SUPPORTED_FILE_EXTENSIONS.contains(fileExtension)) {
            throw new IllegalArgumentException("当前仅支持上传 txt、md、docx 文件，实际文件类型为: " + fileExtension);
        }
    }

    private KnowledgeFileVO toKnowledgeFileVO(KnowledgeFileDO knowledgeFile) {
        return new KnowledgeFileVO(
                knowledgeFile.getId(),
                knowledgeFile.getKnowledgeBaseId(),
                knowledgeFile.getFileName(),
                knowledgeFile.getFileExt(),
                knowledgeFile.getFileSize(),
                knowledgeFile.getParseStatus(),
                knowledgeFile.getIndexStatus(),
                knowledgeFile.getStorageUri(),
                knowledgeFile.getErrorMessage(),
                TimeUtils.toEpochMillis(knowledgeFile.getCreatedAt()),
                TimeUtils.toEpochMillis(knowledgeFile.getUpdatedAt())
        );
    }

    private String resolveOriginalFileName(MultipartFile file) {
        String originalFilename = StringUtils.cleanPath(file.getOriginalFilename());
        if (!StringUtils.hasText(originalFilename)) {
            throw new IllegalArgumentException("上传文件名不能为空。");
        }

        int lastSlashIndex = Math.max(originalFilename.lastIndexOf('/'), originalFilename.lastIndexOf('\\'));
        String fileName = lastSlashIndex >= 0 ? originalFilename.substring(lastSlashIndex + 1) : originalFilename;
        if (!StringUtils.hasText(fileName) || fileName.contains("..")) {
            throw new IllegalArgumentException("上传文件名不合法。");
        }
        return fileName.trim();
    }

    private String resolveFileExtension(String fileName) {
        String extension = StringUtils.getFilenameExtension(fileName);
        if (!StringUtils.hasText(extension)) {
            throw new IllegalArgumentException("上传文件必须带有扩展名。");
        }
        return extension.trim().toLowerCase(Locale.ROOT);
    }

    private String resolveStorageType() {
        String storageType = knowledgeFileStorageService.getStorageType();
        return StringUtils.hasText(storageType) ? storageType.trim().toUpperCase(Locale.ROOT) : STORAGE_TYPE_LOCAL;
    }

    private void cleanupStoredFile(String storageUri, RuntimeException originalException) {
        try {
            knowledgeFileStorageService.deleteStoredFile(storageUri);
        } catch (RuntimeException cleanupException) {
            originalException.addSuppressed(cleanupException);
        }
    }
}
