package com.framehub.user.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.framehub.user.common.ApiResponse;
import com.framehub.user.mapper.UserMapper;
import com.framehub.user.security.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;

@Configuration
public class SecurityConfig {
  @Bean
  SecurityFilterChain security(
      HttpSecurity http,
      AppProperties p,
      JwtService jwt,
      RedisState state,
      UserMapper users,
      SessionService sessions,
      ObjectMapper json)
      throws Exception {
    http.csrf(c -> c.disable())
        .cors(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    http.authorizeHttpRequests(
        a ->
            a.requestMatchers(
                    HttpMethod.POST,
                    "/auth/register",
                    "/auth/login",
                    "/auth/email-code",
                    "/auth/password/forgot",
                    "/auth/password/reset",
                    "/auth/refresh")
                .permitAll()
                .requestMatchers(HttpMethod.POST, "/creators/me/activate")
                .authenticated()
                .requestMatchers("/creators/me", "/creators/me/**")
                .hasRole("CREATOR")
                .requestMatchers(HttpMethod.GET, "/creators/{creatorId}")
                .permitAll()
                .requestMatchers("/auth/logout", "/users/me", "/users/me/**", "/internal/auth/identity")
                .authenticated()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                .permitAll()
                .anyRequest()
                .denyAll());
    http.exceptionHandling(
        e ->
            e.authenticationEntryPoint(
                    (req, res, ex) -> {
                      res.setStatus(401);
                      res.setContentType("application/json;charset=UTF-8");
                      json.writeValue(res.getOutputStream(), new ApiResponse<>(401, "请先登录", null));
                    })
                .accessDeniedHandler(
                    (req, res, ex) -> {
                      res.setStatus(403);
                      res.setContentType("application/json;charset=UTF-8");
                      json.writeValue(res.getOutputStream(), new ApiResponse<>(403, "无权限", null));
                    }));
    http.addFilterBefore(new OriginFilter(p, json), UsernamePasswordAuthenticationFilter.class);
    http.addFilterBefore(
        new AuthenticationFilter(jwt, state, users, sessions, json),
        UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
}
