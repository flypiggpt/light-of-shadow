package org.yan.lightofshadowbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.mybatis.spring.annotation.MapperScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan({"org.yan.lightofshadowbackend.auth.mapper", "org.yan.lightofshadowbackend.shop.mapper"})
public class LightOfShadowBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(LightOfShadowBackendApplication.class, args);
    }

}
