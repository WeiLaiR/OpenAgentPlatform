package com.weilair.openagent.knowledge.service;

import java.io.InputStream;

import org.springframework.web.multipart.MultipartFile;

public interface KnowledgeFileStorageService {

    /**
     * 当前阶段先把“文件能稳定落盘”单独抽出来：
     * - 这样上传接口只关心元数据入库
     * - 后续切换到 MinIO / OSS / S3 时，知识库主链路不需要整体重写
     */
    StoredFile storeKnowledgeFile(Long knowledgeBaseId, MultipartFile file);

    /**
     * 当数据库写入失败时，需要把已经写入存储层的脏文件清掉，
     * 否则上传重试会不断留下孤儿文件。
     */
    void deleteStoredFile(String storageUri);

    /**
     * 索引链路需要把已经上传的文件重新读出来交给解析器。
     */
    InputStream openInputStream(String storageUri);

    String getStorageType();

    record StoredFile(
            String storageUri,
            String fileHash,
            long fileSize
    ) {
    }
}
