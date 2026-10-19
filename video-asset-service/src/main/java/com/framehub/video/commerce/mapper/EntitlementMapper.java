package com.framehub.video.commerce.mapper;

import com.framehub.video.commerce.domain.CommerceEntities.Entitlement;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface EntitlementMapper {
  @Insert(
      "INSERT INTO"
          + " entitlement(id,order_id,user_id,material_id,material_version,status,license_snapshot,source_key,expires_at)"
          + " VALUES(#{id},#{orderId},#{userId},#{materialId},#{materialVersion},#{status},#{licenseSnapshot},#{sourceKey},#{expiresAt})")
  void insert(Entitlement e);

  @Select("SELECT * FROM entitlement WHERE order_id=#{id}")
  Entitlement byOrder(String id);

  @Select("SELECT * FROM entitlement WHERE id=#{id}")
  Entitlement get(String id);

  @Select(
      "SELECT * FROM entitlement WHERE user_id=#{user} ORDER BY created_at DESC,id LIMIT #{limit}"
          + " OFFSET #{offset}")
  List<Entitlement> mine(
      @Param("user") long user, @Param("limit") int limit, @Param("offset") long offset);

  @Update(
      "UPDATE entitlement SET"
          + " status='REVOKED',revoked_at=CURRENT_TIMESTAMP(6),revoke_reason=#{reason} WHERE"
          + " order_id=#{order} AND status='ACTIVE'")
  void revoke(@Param("order") String order, @Param("reason") String reason);
}
