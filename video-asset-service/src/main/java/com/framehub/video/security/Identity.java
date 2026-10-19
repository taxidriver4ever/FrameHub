package com.framehub.video.security;

import java.util.List;

public record Identity(String userId, String creatorId, List<String> roles) {
  public long id() {
    return Long.parseLong(userId);
  }
}
