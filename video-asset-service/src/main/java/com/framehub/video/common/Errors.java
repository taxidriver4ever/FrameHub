package com.framehub.video.common;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class Errors {
  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> api(ApiException e) {
    return error(e.status, e.getMessage());
  }

  @ExceptionHandler({
    org.springframework.web.bind.MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<?> bad(Exception e) {
    return error(400, "参数不合法");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> other(Exception e) {
    org.slf4j.LoggerFactory.getLogger(Errors.class)
        .error("Video request failed: {}", e.getClass().getSimpleName());
    return error(500, "视频服务暂时不可用");
  }

  private ResponseEntity<?> error(int status, String message) {
    return ResponseEntity.status(status).body(new ApiResponse<>(status, message, null));
  }
}
