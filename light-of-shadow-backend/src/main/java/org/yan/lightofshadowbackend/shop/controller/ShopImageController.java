package org.yan.lightofshadowbackend.shop.controller;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.yan.lightofshadowbackend.common.ApiResponse;
import org.yan.lightofshadowbackend.shop.service.ShopAuthSupport;
import org.yan.lightofshadowbackend.shop.service.StorageService;

@RestController
@RequestMapping("/api/shop/images")
public class ShopImageController {

    private final StorageService storageService;
    private final ShopAuthSupport authSupport;

    public ShopImageController(StorageService storageService, ShopAuthSupport authSupport) {
        this.storageService = storageService;
        this.authSupport = authSupport;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, String>> upload(@RequestParam("file") MultipartFile file,
            @RequestParam(value = "purpose", required = false) String purpose,
            @RequestHeader(value = "Authorization", required = false) String auth) throws IOException {
        authSupport.resolveMerchantId(auth);
        String url = storageService.store(file, purpose);
        return ApiResponse.success(Map.of("url", url, "path", url));
    }
}


