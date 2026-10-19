package com.framehub.video.commerce.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class CommerceConsumer {
  private final CommerceEvents events;

  public CommerceConsumer(CommerceEvents events) {
    this.events = events;
  }

  @KafkaListener(
      topics = "${video.topic}",
      groupId = "${COMMERCE_CONSUMER_GROUP:commerce-events-v1}",
      containerFactory = "commerceKafkaFactory",
      autoStartup = "${commerce.consumer-enabled:false}")
  public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
    events.accept(record.value());
    ack.acknowledge();
  }
}
