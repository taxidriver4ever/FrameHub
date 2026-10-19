package com.framehub.video.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
public class DomainOutbox {
  private final EventMapper events;
  private final ObjectMapper json;

  public DomainOutbox(EventMapper events, ObjectMapper json) {
    this.events = events;
    this.json = json;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void emit(
      String aggregateType, String id, String type, int version, long user, Object payload) {
    try {
      events.emit(
          UUID.randomUUID().toString(),
          user,
          type,
          version,
          json.writeValueAsString(payload),
          aggregateType,
          id);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException(e);
    }
  }
}
