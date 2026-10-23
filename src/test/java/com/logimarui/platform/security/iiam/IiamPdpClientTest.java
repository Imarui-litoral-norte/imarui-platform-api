package com.logimarui.platform.security.iiam;

import com.logimarui.gateway.core.application.ServiceRegistry;
import com.logimarui.platform.security.principal.IiamPrincipal;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IiamPdpClientTest {
    @TempDir Path directory;
    private HttpServer server;
    private IiamPdpClient client;
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicReference<Throwable> verificationFailure = new AtomicReference<>();
    private final Set<String> nonces = Collections.synchronizedSet(new HashSet<>());
    private final AtomicInteger status = new AtomicInteger(200);
    private boolean mismatch;
    private final IiamPrincipal actor = new IiamPrincipal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(900));

    @BeforeEach void setup() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var pair = generator.generateKeyPair();
        Path key = directory.resolve("private.pem");
        Files.writeString(key, "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n");
        UUID credential = UUID.randomUUID();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/authorization/service", exchange -> {
            byte[] request = exchange.getRequestBody().readAllBytes();
            byte[] body;
            try {
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                assertTrue(authorization.startsWith("Service "));
                var proof = Jwts.parser().verifyWith((RSAPublicKey)pair.getPublic()).build().parseSignedClaims(authorization.substring(8));
                assertEquals("iiam-service+jwt", proof.getHeader().getType());
                assertEquals(credential.toString(), proof.getHeader().getKeyId());
                var claims = proof.getPayload();
                assertEquals(CorePermissionCatalog.OWNER, claims.getSubject());
                assertEquals(CorePermissionCatalog.OWNER, claims.getIssuer());
                assertEquals(Set.of("imarui-iam:services"), claims.getAudience());
                assertEquals("POST", claims.get("htm"));
                assertEquals(exchange.getRequestURI().getPath(), claims.get("htu"));
                assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(request)), claims.get("body_sha256"));
                assertTrue(nonces.add(claims.getId()));
                var document = json.readTree(request);
                if (exchange.getRequestURI().getPath().endsWith("catalog")) {
                    assertEquals(CorePermissionCatalog.OWNER, document.path("owner").asText());
                    assertEquals(CorePermissionCatalog.OWNER, document.path("namespace").asText());
                    body = "{\"changed\":true}".getBytes();
                } else {
                    assertEquals("opaque-actor-token", document.path("actorToken").asText());
                    assertEquals(actor.identityId().toString(), document.path("identityId").asText());
                    assertEquals(claims.get("correlation_id"), document.path("correlationId").asText());
                    body = json.writeValueAsBytes(Map.of("allowed", true, "decisionId", UUID.randomUUID().toString(),
                            "correlationId", mismatch ? UUID.randomUUID().toString() : document.path("correlationId").asText()));
                }
            } catch (Throwable failure) {
                verificationFailure.set(failure); body = "{}".getBytes(); status.set(500);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        var services = mock(ServiceRegistry.class); when(services.findAll()).thenReturn(List.of());
        client = new IiamPdpClient(services, new MockEnvironment()
                .withProperty("IIAM_BASE_URI", "http://127.0.0.1:" + server.getAddress().getPort())
                .withProperty("IIAM_SERVICE_PRIVATE_KEY_FILE", key.toString())
                .withProperty("IIAM_SERVICE_KEY_ID", credential.toString()), json);
    }

    @AfterEach void cleanup() { server.stop(0); assertNull(verificationFailure.get()); }

    private boolean decide() { return client.decide("opaque-actor-token", actor, CorePermissionCatalog.SERVICES_READ, "GATEWAY_SERVER", "local"); }

    @Test void bindsProofToExactBodyPathAndCorrelationWithFreshNonce() {
        assertTrue(decide()); assertTrue(decide()); assertEquals(2, nonces.size());
    }
    @Test void catalogUsesAnOwnedExternalNamespace() { assertTrue(client.synchronizeCatalog()); }
    @Test void deniedAndRevokedActorsAreNeverAllowed() {
        status.set(403); assertFalse(decide()); status.set(401); assertFalse(decide());
    }
    @Test void unavailableOrMismatchedResponseNeverAllows() {
        status.set(503); assertThrows(IiamPdpClient.Unavailable.class, this::decide);
        status.set(200); mismatch = true; assertThrows(IiamPdpClient.Unavailable.class, this::decide);
    }
    @Test void unknownPermissionOrMissingActorDoesNotContactPdp() {
        assertFalse(client.decide("opaque-actor-token", actor, "iiam.superuser", "GATEWAY_SERVER", "local"));
        assertFalse(client.decide(null, actor, CorePermissionCatalog.SERVICES_READ, "GATEWAY_SERVER", "local"));
        assertEquals(0, nonces.size());
    }
}
