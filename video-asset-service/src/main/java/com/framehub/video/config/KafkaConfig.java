package com.framehub.video.config;

import com.framehub.video.cdc.*;
import com.framehub.video.service.CheckpointTransactions;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {
  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
      ConsumerFactory<String, String> consumerFactory, VideoProperties p, CdcRecovery recovery) {
    var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
    factory.setConsumerFactory(consumerFactory);
    factory.setConcurrency(p.consumerConcurrency());
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    var handler =
        new DefaultErrorHandler(
            (record, error) -> recovery.persist(record, error),
            new FixedBackOff(p.retryBackoff().toMillis(), p.maxAttempts() - 1));
    handler.setBackOffFunction(
        (record, error) -> {
          Throwable cause = error;
          while (cause != null) {
            if (cause instanceof CheckpointTransactions.Busy)
              return new FixedBackOff(p.retryBackoff().toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS);
            cause = cause.getCause();
          }
          return new FixedBackOff(p.retryBackoff().toMillis(), p.maxAttempts() - 1);
        });
    handler.addNotRetryableExceptions(
        CanalEvents.InvalidEvent.class, CheckpointTransactions.Terminal.class);
    handler.setCommitRecovered(true);
    handler.setResetStateOnExceptionChange(false);
    factory.setCommonErrorHandler(handler);
    return factory;
  }
}
