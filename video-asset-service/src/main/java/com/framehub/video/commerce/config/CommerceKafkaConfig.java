package com.framehub.video.commerce.config;

import com.framehub.video.commerce.mapper.CommerceEventMapper;
import java.util.UUID;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class CommerceKafkaConfig {
  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, String> commerceKafkaFactory(
      ConsumerFactory<String, String> consumerFactory, CommerceEventMapper failures) {
    var f = new ConcurrentKafkaListenerContainerFactory<String, String>();
    f.setConsumerFactory(consumerFactory);
    f.setConcurrency(3);
    f.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    var handler =
        new DefaultErrorHandler(
            (record, error) ->
                failures.fail(
                    UUID.randomUUID().toString(),
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    String.valueOf(record.value()),
                    error.getClass().getSimpleName()),
            new FixedBackOff(1000, 4));
    handler.setCommitRecovered(true);
    f.setCommonErrorHandler(handler);
    return f;
  }
}
