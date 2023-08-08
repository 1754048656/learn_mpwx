package com.xkcoding.mpwechatdemo.autoconfiguration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 自动装配类
 */
@Configuration
@EnableConfigurationProperties(MpWeChatProperties.class)
public class MpWeChatAutoConfiguration {
}
