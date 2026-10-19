package com.framehub.video.messaging;

import org.apache.ibatis.annotations.*;

@Mapper
public interface EventMapper {
  @Insert(
      "INSERT INTO"
          + " outbox_event(event_id,video_id,user_id,event_type,event_version,payload,aggregate_type,aggregate_id,trace_id)"
          + " VALUES(#{event},NULL,#{user},#{type},#{version},#{payload},#{aggregateType},#{aggregate},#{event})"
          + " ON DUPLICATE KEY UPDATE event_id=event_id")
  void emit(
      @Param("event") String event,
      @Param("user") long user,
      @Param("type") String type,
      @Param("version") int version,
      @Param("payload") String payload,
      @Param("aggregateType") String aggregateType,
      @Param("aggregate") String aggregate);

  @Insert("INSERT IGNORE INTO event_inbox(consumer_name,event_id) VALUES(#{consumer},#{event})")
  int inbox(@Param("consumer") String consumer, @Param("event") String event);

  @Select(
      "SELECT COUNT(*) FROM outbox_event WHERE event_id=#{event} AND aggregate_id=#{aggregate} AND"
          + " event_type=#{type}")
  int exists(
      @Param("event") String event,
      @Param("aggregate") String aggregate,
      @Param("type") String type);
}
