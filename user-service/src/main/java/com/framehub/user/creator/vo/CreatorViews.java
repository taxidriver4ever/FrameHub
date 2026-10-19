package com.framehub.user.creator.vo;

import java.time.Instant;

public final class CreatorViews {
  private CreatorViews() {}

  public record Own(
      String creatorId,
      String userId,
      String displayName,
      String bio,
      String avatarUrl,
      String status,
      Instant createdAt) {}

  public record Public(String creatorId, String displayName, String bio, String avatarUrl) {}
}
