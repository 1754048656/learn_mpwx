package com.xkcoding.mpwechatdemo.autoconfiguration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 属性注入
 */
@Data
@ConfigurationProperties(prefix = "wechat")
public class MpWeChatProperties {
    private String appId;
    private String appSecret;
    private String token;
}
