package com.framehub.video.cdc;

import com.framehub.video.worker.TaskIngestion;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class VideoConsumer {
  private final CanalEvents parser;
  private final TaskIngestion ingestion;

  public VideoConsumer(CanalEvents parser, TaskIngestion ingestion) {
    this.parser = parser;
    this.ingestion = ingestion;
  }

  @KafkaListener(
      topics = "${video.topic}",
      groupId = "${video.group}",
      autoStartup = "${video.consumer-enabled}")
  public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
    ingestion.accept(parser.parse(record.value()));
    ack.acknowledge();
  }
}
