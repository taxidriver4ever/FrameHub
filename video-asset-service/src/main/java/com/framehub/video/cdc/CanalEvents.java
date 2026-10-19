package com.framehub.video.cdc;

import com.fasterxml.jackson.databind.*;
import com.framehub.video.config.VideoProperties;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class CanalEvents {
  public record Event(String eventId, String videoId, long userId) {}

  private final ObjectMapper json;
  private final VideoProperties p;

  public CanalEvents(ObjectMapper json, VideoProperties p) {
    this.json = json;
    this.p = p;
  }

  public List<Event> parse(String raw) {
    try {
      JsonNode root = json.readTree(raw);
      if (root == null || !root.isObject())
        throw new IllegalArgumentException("Expected Canal object");
      if (!p.database().equals(root.path("database").asText())
          || !"outbox_event".equals(root.path("table").asText())
          || root.path("isDdl").asBoolean()
          || !"INSERT".equals(root.path("type").asText())) return List.of();
      JsonNode data = root.path("data");
      if (!data.isArray() || data.isEmpty())
        throw new IllegalArgumentException("Missing Canal INSERT data");
      List<Event> result = new ArrayList<>();
      for (JsonNode row : data) {
        if (Set.of("ASSET_READY", "PAYMENT_SUCCEEDED", "REFUND_SUCCEEDED", "WITHDRAWAL_SUCCEEDED")
            .contains(row.path("event_type").asText())) continue;
        if (!"VIDEO_TRANSCODE_REQUESTED".equals(row.path("event_type").asText())
            || row.path("event_version").asInt() != 1)
          throw new IllegalArgumentException("Unsupported outbox event");
        String event = UUID.fromString(row.path("event_id").asText()).toString(),
            video = UUID.fromString(row.path("video_id").asText()).toString();
        long user = Long.parseLong(row.path("user_id").asText());
        if (user <= 0) throw new IllegalArgumentException("Invalid user");
        result.add(new Event(event, video, user));
      }
      return result;
    } catch (Exception e) {
      throw new InvalidEvent("Invalid Canal payload", e);
    }
  }

  public static class InvalidEvent extends RuntimeException {
    public InvalidEvent(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
