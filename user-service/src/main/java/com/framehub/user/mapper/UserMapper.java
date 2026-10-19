package com.framehub.user.mapper;

import com.framehub.user.entity.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface UserMapper {
  @Select("SELECT * FROM users WHERE user_id=#{id}")
  User find(long id);

  @Select("SELECT * FROM users WHERE user_id=#{id} FOR UPDATE")
  User lock(long id);

  @Select("SELECT * FROM users WHERE email=#{email}")
  User byEmail(String email);

  @Select("SELECT role FROM user_roles WHERE user_id=#{id} ORDER BY role")
  List<String> roles(long id);

  @Insert(
      "INSERT INTO users(user_id,email,password_hash,nickname,status)"
          + " VALUES(#{userId},#{email},#{passwordHash},#{nickname},'ACTIVE')")
  void insert(User user);

  @Insert("INSERT INTO user_roles(user_id,role) VALUES(#{id},#{role})")
  void addRole(@Param("id") long id, @Param("role") String role);

  @Update("UPDATE users SET nickname=#{nickname},bio=#{bio} WHERE user_id=#{userId}")
  void profile(User user);

  @Update("UPDATE users SET password_hash=#{hash},auth_version=auth_version+1 WHERE user_id=#{id}")
  void password(@Param("id") long id, @Param("hash") String hash);

  @Update("UPDATE users SET avatar_key=#{key} WHERE user_id=#{id}")
  void avatar(@Param("id") long id, @Param("key") String key);
}
