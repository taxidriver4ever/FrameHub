package com.framehub.video.commerce.mapper;

import com.framehub.video.commerce.domain.SettlementEntities.*;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface LedgerMapper {
  @Insert(
      "INSERT INTO settlement_guard(user_id) VALUES(#{user}) ON DUPLICATE KEY UPDATE"
          + " user_id=user_id")
  void guard(long user);

  @Select("SELECT user_id FROM settlement_guard WHERE user_id=#{user} FOR UPDATE")
  long lockGuard(long user);

  @Insert(
      "INSERT INTO ledger_account(id,owner_id,bucket) VALUES(#{id},#{owner},#{bucket}) ON DUPLICATE"
          + " KEY UPDATE id=id")
  void ensure(@Param("id") String id, @Param("owner") long owner, @Param("bucket") String bucket);

  @Select("SELECT * FROM ledger_account WHERE id=#{id} FOR UPDATE")
  Account lock(String id);

  @Select("SELECT * FROM ledger_account WHERE owner_id=#{owner} ORDER BY bucket")
  List<Account> accounts(long owner);

  @Update(
      "UPDATE ledger_account SET balance_cent=#{balanceCent},version=version+1 WHERE id=#{id} AND"
          + " version=#{version}")
  int update(Account a);

  @Select("SELECT * FROM ledger_journal WHERE business_key=#{key}")
  Journal journal(String key);

  @Insert(
      "INSERT INTO ledger_journal(id,business_key,kind,reference_id,source_event,payload_hash)"
          + " VALUES(#{id},#{businessKey},#{kind},#{referenceId},#{sourceEvent},#{payloadHash})")
  void journalInsert(Journal j);

  @Insert(
      "INSERT INTO ledger_entry(journal_id,account_id,delta_cent,balance_after_cent)"
          + " VALUES(#{journal},#{account},#{delta},#{balance})")
  void entry(
      @Param("journal") String journal,
      @Param("account") String account,
      @Param("delta") long delta,
      @Param("balance") long balance);

  @Insert(
      "INSERT INTO earning_freeze(order_id,user_id,amount_cent,release_at,status)"
          + " VALUES(#{orderId},#{userId},#{amountCent},#{releaseAt},#{status})")
  void freeze(Freeze f);

  @Select("SELECT * FROM earning_freeze WHERE order_id=#{order}")
  Freeze freezeAt(String order);

  @Update(
      "UPDATE earning_freeze SET status=#{status},released_at=#{releasedAt} WHERE"
          + " order_id=#{orderId}")
  void freezeUpdate(Freeze f);

  @Select(
      "SELECT order_id FROM earning_freeze WHERE status='FROZEN' AND"
          + " release_at<=CURRENT_TIMESTAMP(6) ORDER BY release_at LIMIT 100")
  List<String> due();

  @Select(
      "SELECT j.id,j.kind,j.reference_id,e.delta_cent,e.balance_after_cent,a.bucket,j.created_at"
          + " FROM ledger_entry e JOIN ledger_journal j ON j.id=e.journal_id JOIN ledger_account a"
          + " ON a.id=e.account_id WHERE a.owner_id=#{user} ORDER BY j.created_at DESC,j.id,a.id"
          + " LIMIT #{limit} OFFSET #{offset}")
  List<Map<String, Object>> history(
      @Param("user") long user, @Param("limit") int limit, @Param("offset") long offset);

  @Select(
      "SELECT a.id,a.balance_cent,COALESCE(SUM(e.delta_cent),0) AS calculated_cent FROM"
          + " ledger_account a LEFT JOIN ledger_entry e ON e.account_id=a.id GROUP BY"
          + " a.id,a.balance_cent HAVING a.balance_cent<>COALESCE(SUM(e.delta_cent),0)")
  List<Map<String, Object>> mismatches();

  @Select(
      "SELECT journal_id,SUM(delta_cent) AS unbalanced_cent FROM ledger_entry GROUP BY journal_id"
          + " HAVING SUM(delta_cent)<>0")
  List<Map<String, Object>> unbalanced();
}
