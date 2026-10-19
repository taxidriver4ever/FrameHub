package com.framehub.user.config;

import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class InfrastructureConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  @Primary
  MinioClient minio(AppProperties p) {
    return MinioClient.builder()
        .endpoint(p.minioInternalEndpoint())
        .credentials(p.minioAccessKey(), p.minioSecretKey())
        .build();
  }

  @Bean("signingMinio")
  MinioClient signingMinio(AppProperties p) {
    return MinioClient.builder()
        .endpoint(p.minioEndpoint())
        .region("us-east-1")
        .credentials(p.minioAccessKey(), p.minioSecretKey())
        .build();
  }
}
