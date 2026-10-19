package com.framehub.video.commerce.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("commerce.wechat")
public record WechatProperties(
    boolean enabled,
    String merchantId,
    String appId,
    String merchantSerial,
    Path privateKeyPath,
    String platformKeyId,
    Path platformPublicKeyPath,
    String apiV3Key,
    String notifyUrl) {}
