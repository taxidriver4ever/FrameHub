package com.framehub.video.commerce.consumer;

import com.fasterxml.jackson.databind.*;
import com.framehub.video.commerce.mapper.CommerceEventMapper;
import com.framehub.video.commerce.service.SettlementService;
import com.framehub.video.config.VideoProperties;
import com.framehub.video.messaging.EventMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommerceEvents {
  private final ObjectMapper json;
  private final VideoProperties p;
  private final CommerceEventMapper outbox;
  private final EventMapper inbox;
  private final SettlementService settlement;

  public CommerceEvents(
      ObjectMapper json,
      VideoProperties p,
      CommerceEventMapper outbox,
      EventMapper inbox,
      SettlementService settlement) {
    this.json = json;
    this.p = p;
    this.outbox = outbox;
    this.inbox = inbox;
    this.settlement = settlement;
  }

  @Transactional
  public void accept(String raw) {
    try {
      JsonNode root = json.readTree(raw);
      if (root == null || !root.isObject())
        throw new IllegalArgumentException("Invalid Canal object");
      if (!p.database().equals(root.path("database").asText())
          || !root.path("table").asText().equals("outbox_event")
          || root.path("isDdl").asBoolean()
          || !root.path("type").asText().equals("INSERT")) return;
      JsonNode rows = root.path("data");
      if (!rows.isArray() || rows.isEmpty())
        throw new IllegalArgumentException("Missing Canal rows");
      for (JsonNode row : rows) {
        String type = row.path("event_type").asText();
        if (Set.of("VIDEO_TRANSCODE_REQUESTED", "ASSET_READY", "WITHDRAWAL_SUCCEEDED")
            .contains(type)) continue;
        if (!Set.of("PAYMENT_SUCCEEDED", "REFUND_SUCCEEDED").contains(type)
            || row.path("event_version").asInt() != 1
            || !row.path("aggregate_type").asText().equals("ORDER"))
          throw new IllegalArgumentException("Unsupported commerce event");
        String id = UUID.fromString(row.path("event_id").asText()).toString(),
            order = UUID.fromString(row.path("aggregate_id").asText()).toString();
        var source = outbox.get(id);
        if (source == null
            || !source.eventType.equals(type)
            || !source.aggregateId.equals(order)
            || !source.aggregateType.equals("ORDER")
            || source.eventVersion != 1)
          throw new IllegalArgumentException("Event does not match committed outbox");
        if (inbox.inbox("commerce-v1", id) == 0) continue;
        if (type.equals("PAYMENT_SUCCEEDED")) settlement.paid(order, id);
        else settlement.refunded(order, id);
      }
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid Canal JSON", e);
    }
  }
}
