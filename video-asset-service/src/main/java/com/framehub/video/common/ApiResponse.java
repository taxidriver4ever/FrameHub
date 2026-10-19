package com.framehub.video.common;

public record ApiResponse<T>(int code, String message, T data) {
  public static <T> ApiResponse<T> ok(T value) {
    return new ApiResponse<>(0, "success", value);
  }
}
