package com.logimarui.gateway.api;

import com.logimarui.gateway.core.application.ServiceRegistry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import java.io.IOException;
import java.net.URI;
import java.util.*;

/** Protocol proxy: never rewrite IIAM JSON or authorize in place of the upstream. */
@RestController
@RequestMapping("/api/iam")
@RequiredArgsConstructor
public class IiamProxyController {
    private static final String PREFIX = "/api/iam";
    private static final Set<String> HOP = Set.of("connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length", "accept-encoding");
    private final ServiceRegistry registry;
    private final Environment configuration;

    @RequestMapping({"", "/**"})
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) throws IOException {
        var route = registry.findAll().stream().filter(r -> "iiam".equals(r.getId())).findFirst();
        if (route.isEmpty()) return unavailable();
        String suffix = request.getRequestURI().substring(PREFIX.length());
        if (suffix.isEmpty()) suffix = "/";
        // Registry supplies the private runtime address. Query/path are never interpreted as a target host.
        URI base = URI.create(route.get().getTargetUri());
        String scheme = configuration.getProperty("SERVICE_IIAM_UPSTREAM_SCHEME", "https");
        if (!Set.of("https", "http").contains(scheme)) throw new IllegalStateException("Invalid IIAM transport");
        URI uri = URI.create(scheme + "://" + base.getHost() + ":" + base.getPort() + suffix
                + (request.getQueryString() == null ? "" : "?" + request.getQueryString()));
        HttpHeaders headers = new HttpHeaders();
        Set<String> hopHeaders = new HashSet<>(HOP);
        Collections.list(request.getHeaders("Connection")).forEach(value ->
                Arrays.stream(value.split(",")).forEach(h -> hopHeaders.add(h.trim().toLowerCase(Locale.ROOT))));
        Collections.list(request.getHeaderNames()).forEach(name -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!hopHeaders.contains(lower) && !lower.equals("forwarded") && !lower.startsWith("x-forwarded-"))
                headers.put(name, Collections.list(request.getHeaders(name)));
        });
        // IIAM V1 does not trust forwarded headers. Transport/TLS is configured independently.
        headers.set("X-Forwarded-For", request.getRemoteAddr());
        headers.set("X-Forwarded-Proto", request.isSecure() ? "https" : "http");
        headers.set("X-Forwarded-Prefix", PREFIX);
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(java.time.Duration.ofSeconds(30));
        try {
            return RestClient.builder().requestFactory(factory).build().method(HttpMethod.valueOf(request.getMethod()))
                    .uri(uri).headers(h -> h.addAll(headers)).body(request.getInputStream().readAllBytes())
                    .exchange((sent, response) -> {
                        HttpHeaders outgoing = new HttpHeaders();
                        response.getHeaders().forEach((name, values) -> {
                            if (!HOP.contains(name.toLowerCase(Locale.ROOT))) outgoing.put(name,
                                    name.equalsIgnoreCase("Set-Cookie") ? values.stream().map(IiamProxyController::cookiePath).toList()
                                    : name.equalsIgnoreCase("Location") ? values.stream().map(value -> publicLocation(value, uri)).toList() : values);
                        });
                        byte[] original = response.getBody().readAllBytes();
                        byte[] body = documentationBody(uri.getPath(), original);
                        if (!Arrays.equals(original, body)) { outgoing.remove(HttpHeaders.ETAG); outgoing.remove(HttpHeaders.CONTENT_ENCODING); }
                        return new ResponseEntity<>(body, outgoing, response.getStatusCode());
                    });
        } catch (org.springframework.web.client.RestClientException failure) {
            return unavailable();
        }
    }

    static String cookiePath(String cookie) {
        return cookie.replaceAll("(?i)(;\\s*Path=)(/)(?!api/iam(?:/|;|$))", "$1/api/iam/");
    }

    static String publicLocation(String value, URI upstream) {
        URI location = upstream.resolve(value);
        if (location.getHost() == null || location.getPort() != upstream.getPort()
                || !List.of("localhost", "127.0.0.1", upstream.getHost()).contains(location.getHost())) return value;
        String path = location.getRawPath();
        return (path.equals(PREFIX) || path.startsWith(PREFIX + "/") ? path : PREFIX + path)
                + (location.getRawQuery() == null ? "" : "?" + location.getRawQuery())
                + (location.getRawFragment() == null ? "" : "#" + location.getRawFragment());
    }

    /** Only documentation payloads are adapted; authentication and domain JSON remain byte-for-byte. */
    static byte[] documentationBody(String path, byte[] body) {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        if (path.equals("/v3/api-docs")) {
            try {
                var node = mapper.readTree(body);
                if (node instanceof tools.jackson.databind.node.ObjectNode object && object.has("openapi")) {
                    object.set("servers", mapper.readTree("[{\"url\":\"/api/iam\"}]"));
                    return mapper.writeValueAsBytes(object);
                }
            } catch (RuntimeException invalid) { return body; }
        }
        if (path.equals("/v3/api-docs/swagger-config")) {
            try {
                var node = mapper.readTree(body);
                if (node instanceof tools.jackson.databind.node.ObjectNode object) {
                    object.put("configUrl", PREFIX + "/v3/api-docs/swagger-config");
                    object.put("url", PREFIX + "/v3/api-docs");
                    object.put("oauth2RedirectUrl", PREFIX + "/swagger-ui/oauth2-redirect.html");
                    return mapper.writeValueAsBytes(object);
                }
            } catch (RuntimeException invalid) { return body; }
        }
        if (path.equals("/docs") || path.equals("/docs/") || path.equals("/swagger-ui/index.html")
                || path.equals("/swagger-ui/swagger-initializer.js")) {
            return new String(body, java.nio.charset.StandardCharsets.UTF_8)
                    .replaceAll("([\"'])(/(?:swagger-ui/|openapi-custom/|v3/api-docs))", "$1" + PREFIX + "$2")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        return body;
    }

    private ResponseEntity<byte[]> unavailable() {
        return ResponseEntity.status(503).contentType(MediaType.APPLICATION_PROBLEM_JSON).cacheControl(CacheControl.noStore())
                .body("{\"type\":\"about:blank\",\"title\":\"IIAM unavailable\",\"status\":503}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
