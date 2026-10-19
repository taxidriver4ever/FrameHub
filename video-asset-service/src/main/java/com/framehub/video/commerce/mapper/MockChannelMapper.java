package com.framehub.video.commerce.mapper;

import java.time.*;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MockChannelMapper {
  class ChannelPayment {
    public String paymentId, status, channelId;
    public long amountCent;
    public Instant paidAt, createdAt;
  }

  class ChannelRefund {
    public String refundId, paymentId, status, channelId;
    public long amountCent;
    public Instant createdAt;
  }

  @Insert(
      "INSERT INTO mock_channel_payment(payment_id,amount_cent,status,channel_id)"
          + " VALUES(#{id},#{amount},'PENDING',#{channel})"
          + " ON DUPLICATE KEY UPDATE payment_id=payment_id")
  void create(
      @Param("id") String id, @Param("amount") long amount, @Param("channel") String channel);

  @Select("SELECT * FROM mock_channel_payment WHERE payment_id=#{id} FOR UPDATE")
  ChannelPayment lock(String id);

  @Select("SELECT * FROM mock_channel_payment WHERE payment_id=#{id}")
  ChannelPayment payment(String id);

  @Update(
      "UPDATE mock_channel_payment SET status=#{status},paid_at=#{paidAt} WHERE"
          + " payment_id=#{paymentId}")
  void update(ChannelPayment p);

  @Insert(
      "INSERT INTO mock_channel_refund(refund_id,payment_id,amount_cent,status,channel_id)"
          + " VALUES(#{refundId},#{paymentId},#{amountCent},#{status},#{channelId})")
  void refund(ChannelRefund r);

  @Select("SELECT * FROM mock_channel_refund WHERE refund_id=#{id}")
  ChannelRefund refundAt(String id);

  @Select(
      "SELECT COALESCE(SUM(amount_cent),0) FROM mock_channel_refund WHERE payment_id=#{id} AND"
          + " status='SUCCEEDED'")
  long refunded(String id);

  @Select(
      "SELECT * FROM mock_channel_payment WHERE paid_at>=#{from} AND paid_at<#{to} AND"
          + " status='SUCCEEDED'")
  List<ChannelPayment> payments(@Param("from") Instant from, @Param("to") Instant to);

  @Select(
      "SELECT * FROM mock_channel_refund WHERE created_at>=#{from} AND created_at<#{to} AND"
          + " status='SUCCEEDED'")
  List<ChannelRefund> refunds(@Param("from") Instant from, @Param("to") Instant to);
}
