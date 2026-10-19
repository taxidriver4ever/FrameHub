package com.framehub.video.commerce.mapper;

import com.framehub.video.commerce.domain.SettlementEntities.*;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WithdrawalMapper {
  @Insert(
      "INSERT INTO withdrawal_account(user_id,display_name,provider_account)"
          + " VALUES(#{userId},#{displayName},#{providerAccount}) ON DUPLICATE KEY UPDATE"
          + " display_name=VALUES(display_name),provider_account=VALUES(provider_account)")
  void accountSave(WithdrawalAccount a);

  @Select("SELECT * FROM withdrawal_account WHERE user_id=#{user}")
  WithdrawalAccount account(long user);

  @Select("SELECT * FROM withdrawal WHERE user_id=#{user} AND idempotency_key=#{key}")
  Withdrawal idempotent(@Param("user") long user, @Param("key") String key);

  @Insert(
      "INSERT INTO"
          + " withdrawal(id,user_id,idempotency_key,request_hash,provider,account_snapshot,amount_cent,rule_version,status)"
          + " VALUES(#{id},#{userId},#{idempotencyKey},#{requestHash},#{provider},#{accountSnapshot},#{amountCent},#{ruleVersion},#{status})")
  void insert(Withdrawal w);

  @Select("SELECT * FROM withdrawal WHERE id=#{id}")
  Withdrawal get(String id);

  @Select("SELECT * FROM withdrawal WHERE id=#{id} FOR UPDATE")
  Withdrawal lock(String id);

  @Update(
      "UPDATE withdrawal SET status=#{status},channel_id=#{channelId},last_error=#{lastError} WHERE"
          + " id=#{id}")
  void update(Withdrawal w);

  @Select(
      "SELECT * FROM withdrawal WHERE user_id=#{user} ORDER BY created_at DESC,id LIMIT #{limit}"
          + " OFFSET #{offset}")
  List<Withdrawal> mine(
      @Param("user") long user, @Param("limit") int limit, @Param("offset") long offset);

  @Select(
      "SELECT id FROM withdrawal WHERE status IN('PENDING','PROCESSING') ORDER BY updated_at LIMIT"
          + " 100")
  List<String> pending();

  class MockWithdrawal {
    public String withdrawalId, status, channelId, accountHash;
    public long amountCent;
  }

  @Insert(
      "INSERT INTO"
          + " mock_channel_withdrawal(withdrawal_id,amount_cent,status,channel_id,account_hash)"
          + " VALUES(#{withdrawalId},#{amountCent},#{status},#{channelId},#{accountHash}) ON"
          + " DUPLICATE KEY UPDATE withdrawal_id=withdrawal_id")
  void mockInsert(MockWithdrawal w);

  @Select("SELECT * FROM mock_channel_withdrawal WHERE withdrawal_id=#{id} FOR UPDATE")
  MockWithdrawal mockLock(String id);

  @Select("SELECT * FROM mock_channel_withdrawal WHERE withdrawal_id=#{id}")
  MockWithdrawal mockGet(String id);

  @Update("UPDATE mock_channel_withdrawal SET status=#{status} WHERE withdrawal_id=#{withdrawalId}")
  void mockUpdate(MockWithdrawal w);
}
