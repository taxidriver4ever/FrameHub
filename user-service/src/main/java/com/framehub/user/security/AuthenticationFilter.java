package com.framehub.user.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.framehub.user.common.ApiResponse;
import com.framehub.user.mapper.UserMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class AuthenticationFilter extends OncePerRequestFilter {
  private final JwtService jwt;
  private final RedisState state;
  private final UserMapper users;
  private final SessionService sessions;
  private final ObjectMapper json;

  public AuthenticationFilter(
      JwtService jwt,
      RedisState state,
      UserMapper users,
      SessionService sessions,
      ObjectMapper json) {
    this.jwt = jwt;
    this.state = state;
    this.users = users;
    this.sessions = sessions;
    this.json = json;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header != null) {
      try {
        if (!header.startsWith("Bearer ")) throw new IllegalArgumentException();
        CurrentUser principal = jwt.verify(header.substring(7));
        var user = users.find(principal.id());
        if (user == null
            || !"ACTIVE".equals(user.status)
            || user.authVersion != principal.version()
            || state.get("jti:" + principal.jti()) != null
            || !sessions.active(principal.family())) throw new IllegalArgumentException();
        var auth =
            new UsernamePasswordAuthenticationToken(
                principal,
                null,
                users.roles(user.userId).stream()
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                    .toList());
        SecurityContextHolder.getContext().setAuthentication(auth);
      } catch (org.springframework.security.oauth2.jwt.JwtException | IllegalArgumentException e) {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getOutputStream(), new ApiResponse<>(401, "凭证无效或已过期", null));
        return;
      } catch (Exception e) {
        response.setStatus(500);
        response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getOutputStream(), new ApiResponse<>(500, "认证服务暂时不可用", null));
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
