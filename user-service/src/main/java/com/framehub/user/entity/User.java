package com.framehub.user.entity;

import java.time.Instant;

public class User {
  public long userId;
  public String email, passwordHash, nickname, bio, avatarKey, status;
  public long authVersion;
  public Instant createdAt, updatedAt;
}
