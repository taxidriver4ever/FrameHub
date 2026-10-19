package com.framehub.user.config;

import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app")
public record AppProperties(
    boolean trustGateway,
    @NotBlank String jwtSecret,
    @NotBlank String codeSecret,
    @NotBlank String issuer,
    @NotBlank String audience,
    @Min(1) long accessSeconds,
    @Min(1) long refreshSeconds,
    @NotNull @Min(0) @Max(1023) Integer workerId,
    @NotEmpty List<String> allowedOrigins,
    boolean cookieSecure,
    @NotBlank String mailFrom,
    @NotBlank String resetUrl,
    @NotBlank String minioEndpoint,
    @NotBlank String minioInternalEndpoint,
    @NotBlank String minioAccessKey,
    @NotBlank String minioSecretKey,
    @NotBlank String minioBucket) {}
