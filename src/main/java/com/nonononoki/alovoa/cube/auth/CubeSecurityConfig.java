package com.nonononoki.alovoa.cube.auth;

import com.nonononoki.alovoa.cube.service.CubeAccountService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * CubeOS: a separate, stateless security filter chain for the Cube App API ({@code /api/v1/cube/**}).
 * It runs before Alovoa's own chain (which keeps handling the web UI with sessions, CSRF settings,
 * form login and OAuth unchanged).
 */
@Configuration
public class CubeSecurityConfig {

    public static final String API_PREFIX = "/api/v1/cube";

    @Bean
    @Order(1)
    SecurityFilterChain cubeApiFilterChain(HttpSecurity http, CubeIdentityClient identityClient,
                                           CubeAccountService accountService) throws Exception {
        http.securityMatcher(API_PREFIX + "/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .rememberMe(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> {
                            res.setStatus(401);
                            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            res.getWriter().write("{\"error\":\"Sign in to Cube App to use dating.\"}");
                        })
                        .accessDeniedHandler((req, res, ex) -> {
                            res.setStatus(403);
                            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            res.getWriter().write("{\"error\":\"You don't have access to this.\"}");
                        }))
                .addFilterBefore(new CubemailBearerFilter(identityClient, accountService), AnonymousAuthenticationFilter.class);
        return http.build();
    }
}