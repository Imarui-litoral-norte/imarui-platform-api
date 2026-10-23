package com.logimarui.platform.security.iiam;

import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

/** Supplemental transport of an unmodified IIAM access token, only for proxied web applications. */
@Component
@RequiredArgsConstructor
public class IiamBrowserAccess {
    public static final String COOKIE = "LG_SERVICE_ACCESS";
    private final Environment configuration;
    public static final Map<String,List<String>> PATHS = Map.of(
            "gerenciador-extracao",List.of("/api/extrator"),
            "gerenciador-database-monitoring",List.of("/api/monitoring","/gerenciador-database"),
            "gerenciador-database-backup",List.of("/api/backup","/gerenciador-database/backup"),
            "imarui_savi_automation_service",List.of("/api/savi"),
            "n8n-interno",List.of("/api/n8n","/n8n","/rest"),
            "evolution-interno",List.of("/api/evolution-api","/evolution","/evolution-api"));
    public static final class Rejected extends RuntimeException {}

    public String token(HttpServletRequest request,IiamRequestPolicy.Decision decision) {
        if (decision == null || !"GATEWAY_SERVICE".equals(decision.resourceType()) || !request.isSecure()) return null;
        if (request.getCookies() == null) return null;
        String token=Arrays.stream(request.getCookies()).filter(c -> c.getName().equals(COOKIE)).map(Cookie::getValue).findFirst().orElse(null);
        if (token != null && !Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())) {
            String expected=configuration.getProperty("CORE_PUBLIC_ORIGIN");
            if (expected == null || !expected.equals(request.getHeader("Origin"))) throw new Rejected();
        }
        return token;
    }

    public void write(HttpServletResponse response,String token,Instant expiresAt,Set<String> allowedServices) {
        long seconds=Math.max(0,Math.min(900,Duration.between(Instant.now(),expiresAt).getSeconds()));
        PATHS.forEach((service,paths) -> paths.forEach(path -> response.addHeader(HttpHeaders.SET_COOKIE,
                ResponseCookie.from(COOKIE,allowedServices.contains(service)?token:"").path(path).secure(true).httpOnly(true)
                        .sameSite("Strict").maxAge(allowedServices.contains(service)?seconds:0).build().toString())));
    }
    public void clear(HttpServletResponse response) { write(response,"",Instant.EPOCH,Set.of()); }
}
