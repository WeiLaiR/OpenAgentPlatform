package com.weilair.openagent.knowledge.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

import com.weilair.openagent.common.config.OpenAgentStorageProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class LocalKnowledgeFileStorageService implements KnowledgeFileStorageService {

    private static final String STORAGE_TYPE_LOCAL = "LOCAL";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final OpenAgentStorageProperties storageProperties;

    public LocalKnowledgeFileStorageService(OpenAgentStorageProperties storageProperties) {
        this.storageProperties = storageProperties;
    }

    /**
     * 第一版先只支持 LOCAL 存储，把上传文件稳定写入本地目录。
     * 后续如果切 MinIO / OSS，接口层和知识库元数据层都可以继续复用这套返回结构。
     */
    @Override
    public StoredFile storeKnowledgeFile(Long knowledgeBaseId, MultipartFile file) {
        Path baseDir = requireBaseDir();
        String extension = resolveExtension(file.getOriginalFilename());
        String relativeStorageUri = buildRelativeStorageUri(knowledgeBaseId, extension);
        Path targetFile = resolveStoragePath(baseDir, relativeStorageUri);

        try {
            Files.createDirectories(targetFile.getParent());

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // 这里边写文件边计算 hash，避免为了 file_hash 再额外读第二遍上传内容。
            try (InputStream inputStream = file.getInputStream();
                 DigestInputStream digestInputStream = new DigestInputStream(inputStream, digest)) {
                long copiedBytes = Files.copy(digestInputStream, targetFile, StandardCopyOption.REPLACE_EXISTING);
                return new StoredFile(relativeStorageUri, HexFormat.of().formatHex(digest.digest()), copiedBytes);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("保存上传文件失败: " + exception.getMessage(), exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("系统不支持 SHA-256 文件摘要计算。", exception);
        }
    }

    @Override
    public void deleteStoredFile(String storageUri) {
        if (!StringUtils.hasText(storageUri)) {
            return;
        }

        Path baseDir = requireBaseDir();
        Path targetFile = resolveStoragePath(baseDir, storageUri);
        try {
            Files.deleteIfExists(targetFile);
        } catch (IOException exception) {
            throw new IllegalStateException("清理已上传文件失败: " + exception.getMessage(), exception);
        }
    }

    @Override
    public InputStream openInputStream(String storageUri) {
        if (!StringUtils.hasText(storageUri)) {
            throw new IllegalArgumentException("storageUri 不能为空。");
        }

        Path baseDir = requireBaseDir();
        Path targetFile = resolveStoragePath(baseDir, storageUri);
        try {
            return Files.newInputStream(targetFile);
        } catch (IOException exception) {
            throw new IllegalStateException("读取已上传文件失败: " + exception.getMessage(), exception);
        }
    }

    @Override
    public String getStorageType() {
        return STORAGE_TYPE_LOCAL;
    }

    private Path requireBaseDir() {
        String configuredType = normalizeStorageType(storageProperties.getType());
        if (!STORAGE_TYPE_LOCAL.equals(configuredType)) {
            throw new IllegalArgumentException("当前仅支持 LOCAL 存储，实际配置为: " + configuredType);
        }
        if (!StringUtils.hasText(storageProperties.getLocal().getBaseDir())) {
            throw new IllegalArgumentException("未配置 openagent.storage.local.base-dir，无法保存上传文件。");
        }
        return Path.of(storageProperties.getLocal().getBaseDir()).toAbsolutePath().normalize();
    }

    private Path resolveStoragePath(Path baseDir, String storageUri) {
        Path targetFile = baseDir.resolve(storageUri.replace("/", java.io.File.separator)).normalize();
        if (!targetFile.startsWith(baseDir)) {
            throw new IllegalArgumentException("非法 storageUri，超出了本地存储根目录范围。");
        }
        return targetFile;
    }

    private String buildRelativeStorageUri(Long knowledgeBaseId, String extension) {
        // storageUri 只保存相对路径，避免把绝对盘符耦合进数据库，后续迁移机器或切换存储实现时更容易处理。
        return "knowledge-bases/%d/%s/%s.%s".formatted(
                knowledgeBaseId,
                LocalDate.now().format(DATE_FORMATTER),
                UUID.randomUUID(),
                extension
        );
    }

    private String resolveExtension(String originalFilename) {
        String extension = StringUtils.getFilenameExtension(originalFilename);
        return StringUtils.hasText(extension) ? extension.trim().toLowerCase(Locale.ROOT) : "bin";
    }

    private String normalizeStorageType(String storageType) {
        return StringUtils.hasText(storageType) ? storageType.trim().toUpperCase(Locale.ROOT) : STORAGE_TYPE_LOCAL;
    }
}
