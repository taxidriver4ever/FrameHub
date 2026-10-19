package com.framehub.video.commerce.mapper;

import com.framehub.video.commerce.domain.CommerceEntities.*;
import com.framehub.video.commerce.domain.SettlementEntities.*;
import java.time.*;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ReconciliationMapper {
  @Insert(
      "INSERT INTO reconciliation_batch(id,provider,bill_date,bill_hash,status,source,created_by)"
          + " VALUES(#{id},#{provider},#{billDate},#{billHash},#{status},#{source},#{createdBy}) ON"
          + " DUPLICATE KEY UPDATE id=id")
  void create(Batch b);

  @Select(
      "SELECT * FROM reconciliation_batch WHERE provider=#{provider} AND bill_date=#{day} AND"
          + " bill_hash=#{hash}")
  Batch find(
      @Param("provider") String provider, @Param("day") LocalDate day, @Param("hash") String hash);

  @Update(
      "UPDATE reconciliation_batch SET status=#{status},completed_at=CASE WHEN #{status}='RUNNING'"
          + " THEN NULL ELSE CURRENT_TIMESTAMP(6) END WHERE id=#{id}")
  void status(@Param("id") String id, @Param("status") String status);

  @Insert(
      "INSERT INTO"
          + " reconciliation_difference(id,batch_id,record_type,reference_id,kind,status,details,resolution)"
          + " VALUES(#{id},#{batchId},#{recordType},#{referenceId},#{kind},#{status},#{details},#{resolution})"
          + " ON DUPLICATE KEY UPDATE id=id")
  void difference(Difference d);

  @Update(
      "UPDATE reconciliation_difference SET status=#{status},resolution=#{resolution} WHERE"
          + " batch_id=#{batch} AND record_type=#{type} AND reference_id=#{reference} AND"
          + " kind=#{kind}")
  void resolution(
      @Param("batch") String batch,
      @Param("type") String type,
      @Param("reference") String reference,
      @Param("kind") String kind,
      @Param("status") String status,
      @Param("resolution") String resolution);

  @Select(
      "SELECT * FROM reconciliation_batch ORDER BY created_at DESC LIMIT #{limit} OFFSET #{offset}")
  List<Batch> batches(@Param("limit") int limit, @Param("offset") long offset);

  @Select("SELECT * FROM reconciliation_difference WHERE batch_id=#{id} ORDER BY created_at,id")
  List<Difference> differences(String id);

  @Select(
      "SELECT * FROM payment WHERE provider=#{provider} AND status='SUCCEEDED' AND paid_at>=#{from}"
          + " AND paid_at<#{to}")
  List<Payment> payments(
      @Param("provider") String provider, @Param("from") Instant from, @Param("to") Instant to);

  @Select(
      "SELECT r.* FROM refund r JOIN payment p ON p.id=r.payment_id WHERE p.provider=#{provider}"
          + " AND r.status='SUCCEEDED' AND r.updated_at>=#{from} AND r.updated_at<#{to}")
  List<Refund> refunds(
      @Param("provider") String provider, @Param("from") Instant from, @Param("to") Instant to);

  @Update("UPDATE payment SET channel_fee_actual_cent=#{fee} WHERE id=#{id}")
  void actualFee(@Param("id") String id, @Param("fee") long fee);

  @Select("SELECT * FROM reconciliation_difference WHERE id=#{id} FOR UPDATE")
  Difference lockDifference(String id);

  @Update(
      "UPDATE reconciliation_difference SET status='ACKNOWLEDGED',resolution=#{resolution} WHERE"
          + " id=#{id}")
  void acknowledge(@Param("id") String id, @Param("resolution") String resolution);
}
