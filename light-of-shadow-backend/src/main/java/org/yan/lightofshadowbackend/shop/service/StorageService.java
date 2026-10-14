package org.yan.lightofshadowbackend.shop.service;

import java.io.IOException;

import org.springframework.web.multipart.MultipartFile;

public interface StorageService {

    /**
     * Store the given file and return a publicly accessible URL.
     */
    String store(MultipartFile file, String purpose) throws IOException;
}

