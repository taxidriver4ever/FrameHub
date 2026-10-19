package com.framehub.user.profile.vo;

import java.time.Instant;
import java.util.List;

public record ProfileView(
    String userId,
    String email,
    String nickname,
    String bio,
    String avatarUrl,
    List<String> roles,
    String status,
    Instant createdAt) {}
