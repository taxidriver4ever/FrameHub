package com.framehub.user.security;

import com.framehub.user.common.*;
import com.framehub.user.config.AppProperties;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SessionService {
  public record Session(long userId, long version, String family) {}

  public record Issued(String token, Session session) {}

  private final RedisState state;
  private final AppProperties props;

  public SessionService(RedisState state, AppProperties props) {
    this.state = state;
    this.props = props;
  }

  public Issued create(long userId, long version) {
    String family = Secrets.token(), token = Secrets.token(), hash = Secrets.hash(token);
    Session session = new Session(userId, version, family);
    state.script(
        "redis.call('SET',KEYS[1],ARGV[1],'EX',ARGV[3]);"
            + " redis.call('SET',KEYS[2],ARGV[2],'EX',ARGV[3]); return 1",
        List.of("refresh:" + hash, "family:" + family),
        encode(session),
        hash,
        "" + props.refreshSeconds());
    return new Issued(token, session);
  }

  public Session inspect(String token) {
    if (token == null || token.length() > 128) throw BusinessException.unauthorized();
    String value = state.get("refresh:" + Secrets.hash(token));
    if (value == null) throw BusinessException.unauthorized();
    String[] parts = value.split(":");
    return new Session(Long.parseLong(parts[0]), Long.parseLong(parts[1]), parts[2]);
  }

  public Issued rotate(String token, Session session) {
    String hash = Secrets.hash(token), next = Secrets.token(), nextHash = Secrets.hash(next);
    // Keep spent token metadata for the family lifetime, enabling replay detection.
    long result =
        state.script(
            "local current=redis.call('GET',KEYS[2]); if not current then return 0 end; if"
                + " current~=ARGV[1] then redis.call('DEL',KEYS[2]); return -1 end; local"
                + " ttl=redis.call('TTL',KEYS[2]); if ttl<=0 then return 0 end;"
                + " redis.call('SET',KEYS[2],ARGV[2],'EX',ttl);"
                + " redis.call('SET',KEYS[3],ARGV[3],'EX',ttl); return 1",
            List.of("refresh:" + hash, "family:" + session.family(), "refresh:" + nextHash),
            hash,
            nextHash,
            encode(session));
    if (result != 1) throw BusinessException.unauthorized();
    return new Issued(next, session);
  }

  public void revoke(String family) {
    state.delete("family:" + family);
  }

  public boolean active(String family) {
    return state.get("family:" + family) != null;
  }

  private String encode(Session s) {
    return s.userId() + ":" + s.version() + ":" + s.family();
  }
}
