package com.framehub.video.controller;

import com.framehub.video.common.ApiResponse;
import com.framehub.video.dto.UploadRequests.*;
import com.framehub.video.security.Identity;
import com.framehub.video.service.*;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/v1/videos", "/assets"})
public class VideoController {
  private final UploadService uploads;
  private final VideoService videos;

  public VideoController(UploadService uploads, VideoService videos) {
    this.uploads = uploads;
    this.videos = videos;
  }

  @PostMapping("/uploads/init")
  public ApiResponse<?> init(@AuthenticationPrincipal Identity user, @Valid @RequestBody Init req) {
    return ApiResponse.ok(uploads.init(user, req));
  }

  @PostMapping("/uploads/{id}/parts/presign")
  public ApiResponse<?> presign(
      @AuthenticationPrincipal Identity user,
      @PathVariable String id,
      @Valid @RequestBody Presign req) {
    return ApiResponse.ok(uploads.presign(user.id(), id, req));
  }

  @GetMapping("/uploads/{id}")
  public ApiResponse<?> progress(@AuthenticationPrincipal Identity user, @PathVariable String id) {
    return ApiResponse.ok(uploads.progress(user.id(), id));
  }

  @PostMapping("/uploads/{id}/complete")
  public ApiResponse<?> complete(@AuthenticationPrincipal Identity user, @PathVariable String id)
      throws Exception {
    return ApiResponse.ok(uploads.complete(user.id(), id));
  }

  @PostMapping("/uploads/{id}/abort")
  public ApiResponse<?> abort(@AuthenticationPrincipal Identity user, @PathVariable String id) {
    uploads.abort(user.id(), id);
    return ApiResponse.ok(null);
  }

  @DeleteMapping("/uploads/{id}")
  public ApiResponse<?> deleteUpload(
      @AuthenticationPrincipal Identity user, @PathVariable String id) {
    uploads.abort(user.id(), id);
    return ApiResponse.ok(null);
  }

  @GetMapping("/me")
  public ApiResponse<?> list(
      @AuthenticationPrincipal Identity user,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String status) {
    return ApiResponse.ok(videos.list(user.id(), page, size, status));
  }

  @GetMapping("/{id}")
  public ApiResponse<?> get(@AuthenticationPrincipal Identity user, @PathVariable String id) {
    return ApiResponse.ok(videos.get(user.id(), id));
  }

  @GetMapping("/{id}/playback")
  public ApiResponse<?> playback(@AuthenticationPrincipal Identity user, @PathVariable String id) {
    return ApiResponse.ok(videos.playback(user.id(), id));
  }
}
