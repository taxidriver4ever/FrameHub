package com.framehub.video.commerce.mapper;

import com.framehub.video.commerce.domain.CommerceEntities.*;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TradeMapper {
  @Insert(
      "INSERT INTO commerce_user_guard(user_id) VALUES(#{id}) ON DUPLICATE KEY UPDATE"
          + " user_id=user_id")
  void ensureUser(long id);

  @Select("SELECT * FROM commerce_user_guard WHERE user_id=#{id} FOR UPDATE")
  UserGuard lockUser(long id);

  @Update(
      "UPDATE commerce_user_guard SET cancel_day=#{cancelDay},cancel_count=#{cancelCount} WHERE"
          + " user_id=#{userId}")
  void cancellation(UserGuard g);

  @Select(
      "SELECT COUNT(*) FROM business_order WHERE user_id=#{id} AND status"
          + " IN('PENDING','PAYING','CLOSING')")
  int pending(long id);

  @Select("SELECT * FROM business_order WHERE user_id=#{user} AND idempotency_key=#{key}")
  Order idempotent(@Param("user") long user, @Param("key") String key);

  @Select("SELECT * FROM business_order WHERE id=#{id}")
  Order order(String id);

  @Select("SELECT * FROM business_order WHERE id=#{id} FOR UPDATE")
  Order lockOrder(String id);

  @Select(
      "SELECT * FROM business_order WHERE material_id=#{id} AND material_version=#{version} AND"
          + " order_type='LISTING' AND status IN('PENDING','PAYING','CLOSING','PAID') ORDER BY"
          + " created_at DESC LIMIT 1")
  Order listing(@Param("id") String id, @Param("version") int version);

  @Insert(
      "INSERT INTO"
          + " business_order(id,user_id,creator_id,order_type,material_id,material_version,status,idempotency_key,request_hash,amount_cent,commission_cent,channel_fee_cent,creator_cent,rule_version,fee_snapshot,license_snapshot,license_confirmed_at,expires_at)"
          + " VALUES(#{id},#{userId},#{creatorId},#{orderType},#{materialId},#{materialVersion},#{status},#{idempotencyKey},#{requestHash},#{amountCent},#{commissionCent},#{channelFeeCent},#{creatorCent},#{ruleVersion},#{feeSnapshot},#{licenseSnapshot},#{licenseConfirmedAt},#{expiresAt})")
  void insert(Order o);

  @Update(
      "UPDATE business_order SET status=#{status},paid_at=#{paidAt},cancel_reason=#{cancelReason}"
          + " WHERE id=#{id}")
  void update(Order o);

  @Select(
      "SELECT * FROM business_order WHERE user_id=#{user} ORDER BY created_at DESC,id LIMIT"
          + " #{limit} OFFSET #{offset}")
  List<Order> mine(
      @Param("user") long user, @Param("limit") int limit, @Param("offset") long offset);

  @Select(
      "SELECT * FROM business_order ORDER BY created_at DESC,id LIMIT #{limit} OFFSET #{offset}")
  List<Order> all(@Param("limit") int limit, @Param("offset") long offset);

  @Select(
      "SELECT id FROM business_order WHERE status='CLOSING' OR (status IN('PENDING','PAYING') AND"
          + " expires_at<CURRENT_TIMESTAMP(6)) ORDER BY expires_at LIMIT 100")
  List<String> expired();

  @Insert(
      "INSERT INTO payment(id,order_id,provider,status,amount_cent,currency)"
          + " VALUES(#{id},#{orderId},#{provider},#{status},#{amountCent},#{currency})")
  void insertPayment(Payment p);

  @Select("SELECT * FROM payment WHERE order_id=#{id}")
  Payment paymentForOrder(String id);

  @Select("SELECT * FROM payment WHERE id=#{id}")
  Payment payment(String id);

  @Update(
      "UPDATE payment SET"
          + " status=#{status},channel_id=#{channelId},checkout_url=#{checkoutUrl},paid_at=#{paidAt},last_error=#{lastError}"
          + " WHERE id=#{id}")
  void paymentUpdate(Payment p);

  @Select(
      "SELECT id FROM payment WHERE status IN('CREATED','PENDING') ORDER BY updated_at LIMIT 100")
  List<String> pendingPayments();

  @Insert(
      "INSERT IGNORE INTO payment_notification(provider,notification_id,payload_hash,payment_id)"
          + " VALUES(#{provider},#{id},#{hash},#{payment})")
  int notification(
      @Param("provider") String provider,
      @Param("id") String id,
      @Param("hash") String hash,
      @Param("payment") String payment);

  @Select(
      "SELECT payload_hash FROM payment_notification WHERE provider=#{provider} AND"
          + " notification_id=#{id}")
  String notificationHash(@Param("provider") String provider, @Param("id") String id);

  @Insert(
      "INSERT INTO"
          + " refund(id,order_id,payment_id,requested_by,idempotency_key,amount_cent,status,reason)"
          + " VALUES(#{id},#{orderId},#{paymentId},#{requestedBy},#{idempotencyKey},#{amountCent},#{status},#{reason})")
  void insertRefund(Refund r);

  @Select("SELECT * FROM refund WHERE order_id=#{id}")
  Refund refundForOrder(String id);

  @Select("SELECT * FROM refund WHERE id=#{id}")
  Refund refund(String id);

  @Update(
      "UPDATE refund SET status=#{status},channel_id=#{channelId},last_error=#{lastError} WHERE"
          + " id=#{id}")
  void refundUpdate(Refund r);

  @Select(
      "SELECT id FROM refund WHERE status IN('PENDING','PROCESSING') ORDER BY updated_at LIMIT 100")
  List<String> pendingRefunds();
}
