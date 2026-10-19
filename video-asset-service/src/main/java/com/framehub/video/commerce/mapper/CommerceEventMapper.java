package com.framehub.video.commerce.mapper;

import java.time.Instant;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CommerceEventMapper {
  class Event {
    public String eventId, eventType, aggregateType, aggregateId, payload;
    public int eventVersion;
    public long userId;
  }

  class Failed {
    public String id, payload;
    public int attempts;
  }

  @Select("SELECT * FROM outbox_event WHERE event_id=#{id}")
  Event get(String id);

  @Insert(
      "INSERT INTO commerce_cdc_failure(id,topic,partition_id,offset_id,payload,error_type)"
          + " VALUES(#{id},#{topic},#{partition},#{offset},#{payload},#{error}) ON DUPLICATE KEY"
          + " UPDATE id=id")
  void fail(
      @Param("id") String id,
      @Param("topic") String topic,
      @Param("partition") int partition,
      @Param("offset") long offset,
      @Param("payload") String payload,
      @Param("error") String error);

  @Select(
      "SELECT * FROM commerce_cdc_failure WHERE status='PENDING' AND"
          + " next_retry_at<=CURRENT_TIMESTAMP(6) ORDER BY created_at LIMIT 20")
  List<Failed> due();

  @Update("UPDATE commerce_cdc_failure SET status='RESOLVED' WHERE id=#{id}")
  void resolved(String id);

  @Update(
      "UPDATE commerce_cdc_failure SET"
          + " attempts=attempts+1,next_retry_at=#{next},error_type=#{error} WHERE id=#{id} AND"
          + " status='PENDING'")
  void retry(@Param("id") String id, @Param("next") Instant next, @Param("error") String error);
}
