package com.framehub.user.profile.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.framehub.user.common.*;
import com.framehub.user.entity.User;
import com.framehub.user.mapper.UserMapper;
import com.framehub.user.profile.dto.ProfileRequests.*;
import com.framehub.user.profile.vo.ProfileView;
import com.framehub.user.security.CurrentUser;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
  private final UserMapper users;
  private final AvatarService avatars;
  private final PasswordEncoder passwords;

  public UserService(UserMapper users, AvatarService avatars, PasswordEncoder passwords) {
    this.users = users;
    this.avatars = avatars;
    this.passwords = passwords;
  }

  public ProfileView get(long id) {
    User u = users.find(id);
    if (u == null) throw new BusinessException(404, "用户不存在");
    return view(u);
  }

  private ProfileView view(User u) {
    return new ProfileView(
        "" + u.userId,
        u.email,
        u.nickname,
        u.bio,
        avatars.url(u.avatarKey),
        users.roles(u.userId),
        u.status,
        u.createdAt);
  }

  @Transactional
  public ProfileView patch(long id, JsonNode patch) {
    PatchFields.validate(patch, "nickname", "bio");
    User u = users.lock(id);
    if (patch.has("nickname")) u.nickname = PatchFields.text(patch, "nickname", 64, false);
    if (patch.has("bio")) u.bio = PatchFields.text(patch, "bio", 1000, true);
    users.profile(u);
    return view(u);
  }

  @Transactional
  public void password(CurrentUser current, Password req) {
    InputRules.password(req.newPassword());
    User u = users.lock(current.id());
    if (u.authVersion != current.version()
        || !passwords.matches(req.currentPassword(), u.passwordHash))
      throw new BusinessException(401, "当前密码错误或登录已失效");
    users.password(u.userId, passwords.encode(req.newPassword()));
  }

  public AvatarService.UploadView upload(long id, Upload req) throws Exception {
    return avatars.upload(id, req);
  }

  @Transactional(rollbackFor = Exception.class)
  public ProfileView avatar(long id, String key) throws Exception {
    users.lock(id);
    String confirmed = avatars.confirm(id, key);
    users.avatar(id, confirmed);
    return get(id);
  }
}
