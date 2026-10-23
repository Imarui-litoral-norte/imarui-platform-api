package com.logimarui.platform.security.config;

import com.logimarui.platform.security.filter.JwtAuthenticationFilter;
import com.logimarui.platform.security.jwt.JwtProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.config.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.List;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {
    private static final List<String> NON_FRONTEND_PATH_PREFIXES = List.of(
            "/actuator",
            "/api",
            "/docs",
            "/error",
            "/evolution",
            "/evolution-api",
            "/form",
            "/form-test",
            "/gerenciador-database",
            "/n8n",
            "/openapi-custom",
            "/rest",
            "/swagger-ui",
            "/swagger-ui.html",
            "/v3/api-docs",
            "/webhook",
            "/webhook-test"
    );

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {

        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm ->
                        sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/favicon.ico",
                                "/css/**",
                                "/js/**",
                                "/assets/**",
                                "/images/**",
                                "/webjars/**"
                        ).permitAll()

                        .requestMatchers(
                                "/api/iam/**",
                                "/api/v1/authentication/login",
                                "/api/v1/authentication/register",
                                "/api/v1/authentication/refresh",
                                "/api/v1/authentication/login/password-change",
                                "/api/v1/authentication/password-recovery/requests",
                                "/api/v1/authentication/password-recovery/requests/email-token",
                                "/api/v1/authentication/password-recovery/requests/token/reset",

                                "/error"
                        ).permitAll()
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/openapi-custom/**",
                                "/docs/**"
                        ).permitAll()
                        .requestMatchers("/api/v1/admin/services/**").permitAll()
                        .requestMatchers(
                                "/api/extrator/**",
                                "/api/backup/**",
                                "/api/monitoring/**",
                                "/api/n8n/**",
                                "/api/evolution-api/**",
                                "/rest",
                                "/rest/**",
                                "/webhook",
                                "/webhook/**",
                                "/webhook-test",
                                "/webhook-test/**",
                                "/form",
                                "/form/**",
                                "/form-test",
                                "/form-test/**",
                                "/gerenciador-database",
                                "/gerenciador-database/**",
                                "/evolution",
                                "/evolution/**",
                                "/evolution-api",
                                "/evolution-api/**",
                                "/n8n",
                                "/n8n/**"
                        ).permitAll()
                        .requestMatchers(frontendRequestMatcher()).permitAll()

                        .anyRequest().authenticated()
                )
                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }

    private RequestMatcher frontendRequestMatcher() {
        return this::isFrontendRequest;
    }

    boolean isFrontendRequest(HttpServletRequest request) {
        String method = request.getMethod();

        if (!HttpMethod.GET.matches(method)
                && !HttpMethod.HEAD.matches(method)
                && !HttpMethod.OPTIONS.matches(method)) {
            return false;
        }

        String requestUri = request.getRequestURI();

        return NON_FRONTEND_PATH_PREFIXES.stream()
                .noneMatch(prefix -> matchesPath(requestUri, prefix));
    }

    private boolean matchesPath(String requestUri, String prefix) {
        return requestUri.equals(prefix)
                || requestUri.startsWith(prefix + "/");
    }
}
