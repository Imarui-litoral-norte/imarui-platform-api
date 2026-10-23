package com.logimarui.platform.security.iiam;

import com.logimarui.gateway.core.application.ServiceRegistry;
import com.logimarui.platform.security.principal.IiamPrincipal;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.util.*;

/** Every call carries independent, single-use proof bound to method/path and the exact UTF-8 body. */
@Component
@RequiredArgsConstructor
public class IiamPdpClient {
    private final ServiceRegistry services;
    private final Environment configuration;
    private final JsonMapper json;

    public static final class Unavailable extends RuntimeException {
        public Unavailable() { super("IIAM authorization is unavailable"); }
    }

    public boolean decide(String actorToken, IiamPrincipal actor, String permission, String resourceType, String resourceId) {
        if (actor == null || actorToken == null || actorToken.isBlank() || !CorePermissionCatalog.owns(permission)) return false;
        UUID correlation = UUID.randomUUID();
        var request = Map.of("actorToken", actorToken, "identityId", actor.identityId(), "permission", permission,
                "resourceType", resourceType, "resourceId", resourceId, "correlationId", correlation);
        return post("/api/v1/authorization/service/decisions", request, correlation, true);
    }

    public boolean synchronizeCatalog() {
        return post("/api/v1/authorization/service/catalog", CorePermissionCatalog.manifest(), UUID.randomUUID(), false);
    }

    private boolean post(String path, Object request, UUID correlation, boolean decision) {
        try {
            byte[] bytes = json.writeValueAsBytes(request);
            String proof = proof(path, bytes, correlation);
            var transport = new org.springframework.http.client.JdkClientHttpRequestFactory(HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build());
            transport.setReadTimeout(Duration.ofSeconds(7));
            return RestClient.builder().requestFactory(transport).build().post().uri(baseUri() + path)
                    .contentType(new MediaType("application", "json", java.nio.charset.StandardCharsets.UTF_8))
                    .header(HttpHeaders.AUTHORIZATION, "Service " + proof).body(bytes)
                    .exchange((sent, response) -> {
                        if (decision && (response.getStatusCode().value() == 403 || response.getStatusCode().value() == 401)) return false;
                        if (response.getStatusCode().value() != 200) throw new Unavailable();
                        var result = json.readTree(response.getBody());
                        if (!decision) {
                            if (!result.has("changed") || !result.get("changed").isBoolean()) throw new Unavailable();
                            return result.get("changed").asBoolean();
                        }
                        if (!correlation.toString().equals(result.path("correlationId").asText())
                                || !result.path("allowed").isBoolean()) throw new Unavailable();
                        UUID.fromString(result.path("decisionId").asText());
                        return result.path("allowed").asBoolean();
                    });
        } catch (Unavailable unavailable) { throw unavailable; }
        catch (Exception failure) { throw new Unavailable(); } // Tokens, proofs and private keys never enter error messages.
    }

    private String baseUri() {
        String scheme = configuration.getProperty("SERVICE_IIAM_UPSTREAM_SCHEME", "https");
        return services.findAll().stream().filter(route -> route.getId().equals("iiam")).findFirst().map(route -> {
            URI uri = URI.create(route.getTargetUri());
            return scheme + "://" + uri.getHost() + ":" + uri.getPort();
        }).orElseGet(() -> configuration.getProperty("IIAM_BASE_URI", "https://localhost:4105"));
    }

    private String proof(String path, byte[] bytes, UUID correlation) throws Exception {
        String pem = Files.readString(Path.of(configuration.getRequiredProperty("IIAM_SERVICE_PRIVATE_KEY_FILE")));
        byte[] der = Base64.getDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", ""));
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        String service = configuration.getProperty("IIAM_SERVICE_CODE", CorePermissionCatalog.OWNER);
        String credential = configuration.getRequiredProperty("IIAM_SERVICE_KEY_ID");
        UUID.fromString(credential);
        Instant now = Instant.now();
        return Jwts.builder().header().type("iiam-service+jwt").keyId(credential).and()
                .issuer(service).subject(service).audience().add(configuration.getProperty("IIAM_AUDIENCE", "imarui-iam") + ":services").and()
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(45))).id(UUID.randomUUID().toString())
                .claim("htm", "POST").claim("htu", path).claim("correlation_id", correlation.toString())
                .claim("body_sha256", Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .signWith(key, Jwts.SIG.RS256).compact();
    }
}
