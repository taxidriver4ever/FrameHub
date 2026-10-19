package com.framehub.video.commerce.mapper;

import com.framehub.video.commerce.domain.CommerceEntities.*;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CatalogMapper {
  @Insert(
      "INSERT INTO material(id,owner_id,video_id,current_version,status)"
          + " VALUES(#{id},#{ownerId},#{videoId},#{currentVersion},#{status})")
  void insert(Material m);

  @Select("SELECT * FROM material WHERE id=#{id}")
  Material material(String id);

  @Select("SELECT * FROM material WHERE id=#{id} FOR UPDATE")
  Material lock(String id);

  @Insert(
      "INSERT INTO"
          + " material_version(material_id,version,title,description,category,tags,price_cent,license_id,source_key,source_version)"
          + " VALUES(#{materialId},#{version},#{title},#{description},#{category},#{tags},#{priceCent},#{licenseId},#{sourceKey},#{sourceVersion})")
  void version(MaterialVersion v);

  @Select("SELECT * FROM material_version WHERE material_id=#{id} AND version=#{version}")
  MaterialVersion versionAt(@Param("id") String id, @Param("version") int version);

  @Insert(
      "INSERT INTO license_version(id,material_id,version,terms)"
          + " VALUES(#{id},#{materialId},#{version},#{terms})")
  void license(License l);

  @Select("SELECT * FROM license_version WHERE id=#{id}")
  License licenseAt(String id);

  @Update("UPDATE material SET current_version=#{version},status=#{status} WHERE id=#{id}")
  void state(@Param("id") String id, @Param("version") int version, @Param("status") String status);

  @Select(
      "SELECT * FROM material WHERE owner_id=#{user} ORDER BY created_at DESC LIMIT #{limit} OFFSET"
          + " #{offset}")
  List<Material> owned(
      @Param("user") long user, @Param("limit") int limit, @Param("offset") long offset);

  @Select(
      "<script>SELECT m.* FROM material m JOIN material_version v ON m.id=v.material_id AND"
          + " m.current_version=v.version WHERE m.status='PUBLISHED'<if test='category!=null'> AND"
          + " v.category=#{category}</if><if test='query!=null'> AND v.title LIKE"
          + " CONCAT('%',#{query},'%')</if> ORDER BY m.created_at DESC,m.id LIMIT #{limit} OFFSET"
          + " #{offset}</script>")
  List<Material> market(
      @Param("category") String category,
      @Param("query") String query,
      @Param("limit") int limit,
      @Param("offset") long offset);

  @Select(
      "SELECT * FROM fee_rule WHERE effective_at<=CURRENT_TIMESTAMP(6) ORDER BY effective_at"
          + " DESC,version DESC LIMIT 1")
  FeeRule currentRule();

  @Select("SELECT * FROM fee_rule WHERE version=#{version}")
  FeeRule rule(int version);

  @Select("SELECT * FROM fee_rule ORDER BY version DESC LIMIT 1 FOR UPDATE")
  FeeRule lastRule();

  @Insert(
      "INSERT INTO"
          + " fee_rule(id,version,listing_fee_cent,commission_bps,channel_fee_bps,minimum_withdrawal_cent,freeze_seconds,effective_at,created_by)"
          + " VALUES(#{id},#{version},#{listingFeeCent},#{commissionBps},#{channelFeeBps},#{minimumWithdrawalCent},#{freezeSeconds},#{effectiveAt},#{createdBy})")
  void ruleInsert(FeeRule r);

  @Insert(
      "INSERT INTO audit_log(id,actor_id,action,aggregate_id,details)"
          + " VALUES(#{id},#{actor},#{action},#{aggregate},#{details})")
  void audit(
      @Param("id") String id,
      @Param("actor") long actor,
      @Param("action") String action,
      @Param("aggregate") String aggregate,
      @Param("details") String details);
}
