package org.yan.lightofshadowbackend.shop.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.yan.lightofshadowbackend.exception.ApiException;
import org.yan.lightofshadowbackend.common.ErrorCode;
import org.yan.lightofshadowbackend.shop.config.StorageProperties;
import org.yan.lightofshadowbackend.shop.service.StorageService;

@Service
public class LocalStorageService implements StorageService {

    private final StorageProperties storageProperties;

    public LocalStorageService(StorageProperties storageProperties) {
        this.storageProperties = storageProperties;
    }

    private static final List<String> ALLOWED = List.of("jpg", "jpeg", "png", "webp");

    @Override
    public String store(MultipartFile file, String purpose) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "文件为空");
        }
        long maxFileSize = storageProperties.local() == null || storageProperties.local().maxFileSizeBytes() <= 0
                ? 5 * 1024 * 1024L
                : storageProperties.local().maxFileSizeBytes();
        if (file.getSize() > maxFileSize) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "文件大小超过限制");
        }
        String original = file.getOriginalFilename();
        String ext = "";
        if (original != null) {
            int idx = original.lastIndexOf('.');
            if (idx >= 0 && idx < original.length() - 1) {
                ext = original.substring(idx + 1).toLowerCase();
            }
        }
        if (!ALLOWED.contains(ext)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "不支持的文件格式");
        }

        String uuid = UUID.randomUUID().toString();
        String subdir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
        String baseDir = storageProperties.local() == null || storageProperties.local().baseDir() == null
                ? "uploads"
                : storageProperties.local().baseDir();
        String baseUrl = storageProperties.local() == null || storageProperties.local().baseUrl() == null
                ? "/media"
                : storageProperties.local().baseUrl();

        Path dir = Paths.get(baseDir, subdir);
        Files.createDirectories(dir);
        String filename = uuid + "." + ext;
        Path target = dir.resolve(filename);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        String relative = subdir + "/" + filename;
        return baseUrl.endsWith("/") ? baseUrl + relative : baseUrl + "/" + relative;
    }
}



