package org.yan.lightofshadowbackend.shop.config;

import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    private final StorageProperties storageProperties;

    public StaticResourceConfig(StorageProperties storageProperties) {
        this.storageProperties = storageProperties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String baseDir = storageProperties.local() == null || storageProperties.local().baseDir() == null
                ? "uploads"
                : storageProperties.local().baseDir();
        String baseUrl = storageProperties.local() == null || storageProperties.local().baseUrl() == null
                ? "/media"
                : storageProperties.local().baseUrl();
        String location = "file:" + Paths.get(baseDir).toAbsolutePath() + "/";
        registry.addResourceHandler(baseUrl + "/**")
                .addResourceLocations(location)
                .setCacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic());
    }
}



