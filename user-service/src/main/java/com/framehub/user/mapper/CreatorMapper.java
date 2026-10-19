package com.framehub.user.mapper;

import com.framehub.user.entity.Creator;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CreatorMapper {
  @Select("SELECT * FROM creator_profiles WHERE user_id=#{id}")
  Creator byUser(long id);

  @Select("SELECT * FROM creator_profiles WHERE creator_id=#{id} AND status='ACTIVE'")
  Creator find(long id);

  @Insert(
      "INSERT INTO creator_profiles(creator_id,user_id,display_name,status)"
          + " VALUES(#{creatorId},#{userId},#{displayName},'ACTIVE')")
  void insert(Creator c);

  @Update(
      "UPDATE creator_profiles SET display_name=#{displayName},bio=#{bio} WHERE"
          + " creator_id=#{creatorId}")
  void update(Creator c);
}
