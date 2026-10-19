package com.framehub.video.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.framehub.video.common.*;
import com.framehub.video.security.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableConfigurationProperties(VideoProperties.class)
public class SecurityConfig {
  @Bean
  SecurityFilterChain chain(HttpSecurity http, IdentityClient identity, ObjectMapper json)
      throws Exception {
    http.csrf(c -> c.disable())
        .cors(c -> c.disable())
        .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    http.authorizeHttpRequests(
        a ->
            a.requestMatchers("/marketplace/**", "/payment-notifications/wechat")
                .permitAll()
                .requestMatchers("/admin/**")
                .hasRole("ADMIN")
                .requestMatchers("/orders/**", "/payments/**", "/entitlements/**", "/dev/**")
                .authenticated()
                .requestMatchers(
                    "/api/v1/videos/**",
                    "/assets/**",
                    "/materials/**",
                    "/creators/me/materials",
                    "/earnings/**",
                    "/withdrawals/**")
                .hasRole("CREATOR")
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                .permitAll()
                .anyRequest()
                .denyAll());
    http.exceptionHandling(
        e ->
            e.authenticationEntryPoint((q, r, x) -> write(r, json, 401, "请先登录"))
                .accessDeniedHandler((q, r, x) -> write(r, json, 403, "需要创作者权限")));
    http.addFilterBefore(
        new OncePerRequestFilter() {
          protected void doFilterInternal(
              HttpServletRequest req, HttpServletResponse res, FilterChain chain)
              throws IOException, ServletException {
            String header = req.getHeader("Authorization");
            if (header != null) {
              try {
                if (!header.startsWith("Bearer ")) throw new ApiException(401, "凭证无效");
                Identity user = identity.verify(header);
                var auth =
                    new UsernamePasswordAuthenticationToken(
                        user,
                        null,
                        user.roles().stream()
                            .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                            .toList());
                SecurityContextHolder.getContext().setAuthentication(auth);
              } catch (ApiException e) {
                write(res, json, e.status, e.getMessage());
                return;
              }
            }
            chain.doFilter(req, res);
          }
        },
        UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  static void write(HttpServletResponse r, ObjectMapper json, int status, String message)
      throws IOException {
    r.setStatus(status);
    r.setContentType("application/json;charset=UTF-8");
    json.writeValue(r.getOutputStream(), new ApiResponse<>(status, message, null));
  }
}
