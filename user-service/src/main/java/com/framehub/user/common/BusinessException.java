package com.framehub.user.common;

public class BusinessException extends RuntimeException {
  private final int status;

  public BusinessException(int status, String message) {
    super(message);
    this.status = status;
  }

  public int status() {
    return status;
  }

  public static BusinessException unauthorized() {
    return new BusinessException(401, "凭证无效或已过期");
  }
}
