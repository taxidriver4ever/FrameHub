package com.framehub.user.auth.service;

import com.framehub.user.mapper.*;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class IdentityService {
  public record Identity(String userId, String creatorId, List<String> roles) {}

  private final UserMapper users;
  private final CreatorMapper creators;

  public IdentityService(UserMapper users, CreatorMapper creators) {
    this.users = users;
    this.creators = creators;
  }

  public Identity get(long id) {
    var creator = creators.byUser(id);
    var roles = new java.util.ArrayList<>(users.roles(id));
    if (creator == null || !"ACTIVE".equals(creator.status)) roles.remove("CREATOR");
    return new Identity("" + id, creator == null ? null : "" + creator.creatorId, roles);
  }
}
