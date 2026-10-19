package com.framehub.user.security;

import com.framehub.user.common.*;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisState {
  private final StringRedisTemplate redis;

  public RedisState(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public static String key(String suffix) {
    return "fh:user:" + suffix;
  }

  public void put(String key, String value, long ttl) {
    redis.opsForValue().set(key(key), value, Duration.ofSeconds(ttl));
  }

  public String get(String key) {
    return redis.opsForValue().get(key(key));
  }

  public String take(String key) {
    return redis.opsForValue().getAndDelete(key(key));
  }

  public void delete(String key) {
    redis.delete(key(key));
  }

  public boolean once(String key, String value, long ttl) {
    return Boolean.TRUE.equals(
        redis.opsForValue().setIfAbsent(key(key), value, Duration.ofSeconds(ttl)));
  }

  public long script(String source, List<String> keys, String... args) {
    Long n =
        redis.execute(
            new DefaultRedisScript<>(source, Long.class),
            keys.stream().map(RedisState::key).toList(),
            (Object[]) args);
    return n == null ? 0 : n;
  }

  public void limit(String name, int max, int seconds) {
    long n =
        script(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]);"
                + " end; return n",
            List.of("rate:" + name),
            "" + seconds);
    if (n > max) throw new BusinessException(429, "请求过于频繁，请稍后重试");
  }

  public void verifyCode(String email, String proof) {
    long result =
        script(
            "local v=redis.call('GET',KEYS[1]); if not v then return 0 end; local"
                + " n=redis.call('INCR',KEYS[2]); if n==1 then redis.call('EXPIRE',KEYS[2],300)"
                + " end; if n>5 then redis.call('DEL',KEYS[1]); return 0 end; if v~=ARGV[1] then"
                + " return 0 end; redis.call('DEL',KEYS[1]); return 1",
            List.of("code:" + email, "code-attempt:" + email),
            proof);
    if (result != 1) throw new BusinessException(400, "验证码无效或已过期");
  }
}
