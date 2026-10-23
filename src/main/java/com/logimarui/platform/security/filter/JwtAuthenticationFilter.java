package com.logimarui.platform.security.filter;

import com.logimarui.platform.security.jwt.JwtTokenParser;
import com.logimarui.platform.security.iiam.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenParser tokenParser;
    private final IiamRequestPolicy requestPolicy;
    private final IiamPdpClient pdp;
    private final IiamBrowserAccess browser;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/api/iam") || path.startsWith("/api/iam/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        var decision = requestPolicy.resolve(request);
        String header = request.getHeader("Authorization");
        try {
            if (header == null) {
                String cookie = browser.token(request, decision);
                if (cookie != null) header = "Bearer " + cookie;
            }
        } catch (IiamBrowserAccess.Rejected rejected) {
            problem(response,403,"ORIGIN_REQUIRED");return;
        }

        if (header == null || !header.startsWith("Bearer ")) {
            if (decision != null) {
                problem(response, 401, "AUTHENTICATION_REQUIRED");
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);

        try {
            var principal = tokenParser.parseAndValidate(token);
            if (decision != null && !pdp.decide(token, principal, decision.permission(), decision.resourceType(), decision.resourceId())) {
                problem(response, 403, "ACCESS_DENIED");
                return;
            }

            Authentication authentication =
                    new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            decision == null ? List.of() : List.of(new SimpleGrantedAuthority(decision.permission()))
                    );

            SecurityContextHolder.getContext()
                    .setAuthentication(authentication);

        } catch (IiamPdpClient.Unavailable unavailable) {
            SecurityContextHolder.clearContext();
            problem(response, 503, "IIAM_AUTHORIZATION_UNAVAILABLE");
            return;
        } catch (com.logimarui.platform.security.jwt.IiamJwtDecoder.KeysUnavailable unavailable) {
            problem(response,503,"IIAM_KEYS_UNAVAILABLE");
            return;
        } catch (JwtException | IllegalArgumentException e) {
            SecurityContextHolder.clearContext();
            problem(response,401,"INVALID_ACCESS_TOKEN");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void problem(HttpServletResponse response, int status, String code) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        response.getWriter().write("{\"type\":\"about:blank\",\"status\":" + status + ",\"code\":\"" + code + "\"}");
    }
}
