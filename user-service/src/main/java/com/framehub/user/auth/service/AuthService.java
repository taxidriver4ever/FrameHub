package com.framehub.user.auth.service;

import com.framehub.user.auth.dto.AuthRequests.*;
import com.framehub.user.auth.vo.AuthViews;
import com.framehub.user.common.*;
import com.framehub.user.config.AppProperties;
import com.framehub.user.entity.User;
import com.framehub.user.mapper.UserMapper;
import com.framehub.user.security.*;
import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  public record LoginResult(AuthViews.Login body, String refreshToken) {}

  private final UserMapper users;
  private final PasswordEncoder passwords;
  private final RedisState state;
  private final SessionService sessions;
  private final JwtService jwt;
  private final Snowflake ids;
  private final MailSender mail;
  private final AppProperties p;
  private final String dummyHash;

  public AuthService(
      UserMapper users,
      PasswordEncoder passwords,
      RedisState state,
      SessionService sessions,
      JwtService jwt,
      Snowflake ids,
      MailSender mail,
      AppProperties p) {
    this.users = users;
    this.passwords = passwords;
    this.state = state;
    this.sessions = sessions;
    this.jwt = jwt;
    this.ids = ids;
    this.mail = mail;
    this.p = p;
    dummyHash = passwords.encode(Secrets.token());
  }

  public void emailCode(EmailCode req, String ip) {
    String email = InputRules.email(req.email()), key = Secrets.hash(email);
    state.limit("mail-ip:" + ip, 20, 3600);
    state.limit("mail-email:" + key, 5, 3600);
    if (!state.once("code-cooldown:" + key, "1", 60)) throw new BusinessException(429, "请60秒后重试");
    String code = Secrets.code();
    state.put("code:" + key, Secrets.hmac(p.codeSecret(), email + ":" + code), 300);
    state.delete("code-attempt:" + key);
    try {
      mail.send(email, "FrameHub 注册验证码", "验证码：" + code + "，5分钟内有效。");
    } catch (RuntimeException e) {
      state.delete("code:" + key);
      throw e;
    }
  }

  @Transactional
  public AuthViews.Registered register(Register req) {
    String email = InputRules.email(req.email());
    InputRules.password(req.password());
    String nickname = InputRules.text(req.nickname(), 64, false);
    state.verifyCode(
        Secrets.hash(email), Secrets.hmac(p.codeSecret(), email + ":" + req.verificationCode()));
    if (users.byEmail(email) != null) throw new BusinessException(409, "邮箱已注册");
    User u = new User();
    u.userId = ids.next();
    u.email = email;
    u.passwordHash = passwords.encode(req.password());
    u.nickname = nickname;
    users.insert(u);
    users.addRole(u.userId, "BUYER");
    return new AuthViews.Registered(
        "" + u.userId, email, nickname, java.util.List.of("BUYER"), "ACTIVE");
  }

  @Transactional
  public LoginResult login(Login req, String ip) {
    String email = InputRules.email(req.email());
    state.limit("login-ip:" + ip, 50, 300);
    state.limit("login-email:" + Secrets.hash(email), 10, 300);
    User found = users.byEmail(email);
    User u = found == null ? null : users.lock(found.userId);
    boolean valid = passwords.matches(req.password(), u == null ? dummyHash : u.passwordHash);
    if (!valid || u == null || !"ACTIVE".equals(u.status))
      throw new BusinessException(401, "邮箱或密码错误");
    return result(sessions.create(u.userId, u.authVersion), u);
  }

  @Transactional
  public LoginResult refresh(String token) {
    var s = sessions.inspect(token);
    User u = users.lock(s.userId());
    if (u == null || u.authVersion != s.version() || !"ACTIVE".equals(u.status)) {
      sessions.revoke(s.family());
      throw BusinessException.unauthorized();
    }
    return result(sessions.rotate(token, s), u);
  }

  private LoginResult result(SessionService.Issued issued, User u) {
    return new LoginResult(
        new AuthViews.Login(
            jwt.issue(issued.session()),
            "Bearer",
            p.accessSeconds(),
            new AuthViews.Summary("" + u.userId, u.nickname, users.roles(u.userId))),
        issued.token());
  }

  public void logout(CurrentUser current) {
    state.put(
        "jti:" + current.jti(),
        "1",
        Math.max(1, current.expiresAt() - Instant.now().getEpochSecond()));
    sessions.revoke(current.family());
  }

  public void forgot(Forgot req, String ip) {
    String email = InputRules.email(req.email());
    state.limit("forgot-ip:" + ip, 20, 3600);
    state.limit("forgot-email:" + Secrets.hash(email), 5, 3600);
    User u = users.byEmail(email);
    if (u != null && "ACTIVE".equals(u.status)) {
      String token = Secrets.token();
      state.put("reset:" + Secrets.hash(token), u.userId + ":" + u.authVersion, 900);
      try {
        mail.send(email, "FrameHub 重置密码", p.resetUrl() + "?token=" + token + "\n链接15分钟内有效，仅可使用一次。");
      } catch (RuntimeException e) {
        state.delete("reset:" + Secrets.hash(token));
        org.slf4j.LoggerFactory.getLogger(AuthService.class)
            .warn("Password reset email delivery failed");
      }
    }
  }

  @Transactional
  public void reset(Reset req, String ip) {
    state.limit("reset-ip:" + ip, 30, 300);
    InputRules.password(req.newPassword());
    String value = state.take("reset:" + Secrets.hash(req.resetToken()));
    if (value == null) throw new BusinessException(400, "重置凭证无效或已过期");
    String[] parts = value.split(":");
    User u = users.lock(Long.parseLong(parts[0]));
    if (u == null || u.authVersion != Long.parseLong(parts[1]) || !"ACTIVE".equals(u.status))
      throw new BusinessException(400, "重置凭证无效或已过期");
    users.password(u.userId, passwords.encode(req.newPassword()));
  }
}
