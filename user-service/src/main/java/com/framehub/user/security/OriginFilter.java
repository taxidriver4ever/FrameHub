package com.framehub.user.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.framehub.user.common.ApiResponse;
import com.framehub.user.config.AppProperties;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Cookie-mutating authentication endpoints require an explicit trusted Origin, including
 * non-browser clients.
 */
public class OriginFilter extends OncePerRequestFilter {
  private final AppProperties p;
  private final ObjectMapper json;

  public OriginFilter(AppProperties p, ObjectMapper json) {
    this.p = p;
    this.json = json;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    if ("POST".equals(req.getMethod())
        && java.util.Set.of("/auth/login", "/auth/refresh", "/auth/logout")
            .contains(req.getRequestURI().substring(req.getContextPath().length()))
        && !p.allowedOrigins().contains(req.getHeader("Origin"))) {
      res.setStatus(403);
      res.setContentType("application/json;charset=UTF-8");
      json.writeValue(res.getOutputStream(), new ApiResponse<>(403, "请求来源不受信任", null));
      return;
    }
    chain.doFilter(req, res);
  }
}
