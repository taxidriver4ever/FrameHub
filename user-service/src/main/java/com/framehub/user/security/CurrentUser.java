package com.framehub.user.security;

public record CurrentUser(long id, long version, String jti, String family, long expiresAt) {}
