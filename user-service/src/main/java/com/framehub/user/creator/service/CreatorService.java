package com.framehub.user.creator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.framehub.user.common.*;
import com.framehub.user.creator.vo.CreatorViews;
import com.framehub.user.entity.*;
import com.framehub.user.mapper.*;
import com.framehub.user.profile.service.AvatarService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreatorService {
  private final UserMapper users;
  private final CreatorMapper creators;
  private final Snowflake ids;
  private final AvatarService avatars;

  public CreatorService(
      UserMapper users, CreatorMapper creators, Snowflake ids, AvatarService avatars) {
    this.users = users;
    this.creators = creators;
    this.ids = ids;
    this.avatars = avatars;
  }

  @Transactional
  public CreatorViews.Own activate(long id) {
    User user = users.lock(id);
    Creator c = creators.byUser(id);
    if (c == null) {
      c = new Creator();
      c.creatorId = ids.next();
      c.userId = id;
      c.displayName = user.nickname;
      creators.insert(c);
      users.addRole(id, "CREATOR");
    }
    return own(id);
  }

  public CreatorViews.Own own(long id) {
    Creator c = required(id);
    return new CreatorViews.Own(
        "" + c.creatorId,
        "" + c.userId,
        c.displayName,
        c.bio,
        avatars.url(users.find(id).avatarKey),
        c.status,
        c.createdAt);
  }

  private Creator required(long id) {
    Creator c = creators.byUser(id);
    if (c == null || !"ACTIVE".equals(c.status)) throw new BusinessException(403, "创作者身份不可用");
    return c;
  }

  @Transactional
  public CreatorViews.Own patch(long id, JsonNode patch) {
    PatchFields.validate(patch, "displayName", "bio");
    users.lock(id);
    Creator c = required(id);
    if (patch.has("displayName")) c.displayName = PatchFields.text(patch, "displayName", 64, false);
    if (patch.has("bio")) c.bio = PatchFields.text(patch, "bio", 1000, true);
    creators.update(c);
    return own(id);
  }

  public CreatorViews.Public homepage(long id) {
    Creator c = creators.find(id);
    if (c == null) throw new BusinessException(404, "创作者不存在");
    User u = users.find(c.userId);
    if (!"ACTIVE".equals(u.status)) throw new BusinessException(404, "创作者不存在");
    return new CreatorViews.Public(
        "" + c.creatorId, c.displayName, c.bio, avatars.url(u.avatarKey));
  }
}
