package org.yan.lightofshadowbackend.shop.proactive;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 主动提醒：开启定时扫描与异步事件监听。 */
@Configuration
@EnableScheduling
@EnableAsync
public class ProactiveConfig {
}
