package com.logimarui.platform.security.jwt;

import com.logimarui.gateway.core.application.ServiceRegistry;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.JwtException;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IiamJwtDecoderTest {
    private HttpServer server;
    private KeyPair first, second;
    private final AtomicReference<String> jwks = new AtomicReference<>();
    private IiamJwtDecoder decoder;

    @BeforeEach void setup() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        first = generator.generateKeyPair(); second = generator.generateKeyPair();
        publish(false);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/.well-known/jwks.json", exchange -> {
            byte[] body = jwks.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        var registry = mock(ServiceRegistry.class); when(registry.findAll()).thenReturn(List.of());
        decoder = new IiamJwtDecoder(registry, new MockEnvironment().withProperty("IIAM_JWKS_URI",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/.well-known/jwks.json"));
    }

    @AfterEach void cleanup() { server.stop(0); }

    private void publish(boolean overlap) throws Exception {
        var keys = new ArrayList<Map<String,Object>>();
        keys.add(new RSAKey.Builder((RSAPublicKey)first.getPublic()).keyID("first").build().toJSONObject());
        if (overlap) keys.add(new RSAKey.Builder((RSAPublicKey)second.getPublic()).keyID("second").build().toJSONObject());
        jwks.set(JsonMapper.builder().build().writeValueAsString(Map.of("keys", keys)));
    }

    private JwtBuilder valid(String kid) {
        Instant issued = Instant.now().minusSeconds(2);
        return Jwts.builder().header().keyId(kid).type("at+jwt").and().issuer("imarui-iam-service")
                .audience().add("imarui-iam").and().subject(UUID.randomUUID().toString())
                .claim("sid", UUID.randomUUID().toString()).id(UUID.randomUUID().toString())
                .issuedAt(Date.from(issued)).expiration(Date.from(issued.plusSeconds(900)));
    }

    private String sign(JwtBuilder builder, KeyPair pair) { return builder.signWith(pair.getPrivate(), Jwts.SIG.RS256).compact(); }

    @Test void validContractCreatesUuidPrincipal() {
        var principal = new JwtTokenParser(decoder).parseAndValidate(sign(valid("first"), first));
        assertNotNull(principal.identityId()); assertNotNull(principal.sessionId()); assertNotNull(principal.tokenId());
    }

    @Test void signingKeyRotationFetchesNewKidAndPreservesOverlap() throws Exception {
        String original = sign(valid("first"), first);
        assertNotNull(decoder.decode(original));
        publish(true);
        assertNotNull(decoder.decode(sign(valid("second"), second)));
        assertNotNull(decoder.decode(original));
    }

    @Test void invalidSignatureAndUnknownKidFailClosed() {
        assertFalse(assertThrows(JwtException.class, () -> decoder.decode(sign(valid("first"), second))) instanceof IiamJwtDecoder.KeysUnavailable);
        assertFalse(assertThrows(JwtException.class, () -> decoder.decode(sign(valid("unknown"), first))) instanceof IiamJwtDecoder.KeysUnavailable);
    }

    @Test void issuerAudienceTypeAndLegacyNumericIdentityAreRejected() {
        assertThrows(JwtException.class, () -> decoder.decode(sign(valid("first").issuer("legacy-core"), first)));
        assertThrows(JwtException.class, () -> decoder.decode(sign(valid("first").audience().clear().add("other").and(), first)));
        assertThrows(JwtException.class, () -> decoder.decode(sign(valid("first").header().type("JWT").and(), first)));
        assertThrows(JwtException.class, () -> decoder.decode(sign(valid("first").subject("42"), first)));
        assertThrows(JwtException.class, () -> decoder.decode(sign(valid("first").claim("sid", 42L), first)));
    }

    @Test void invalidTimeAndMissingOrNoncanonicalIdsAreRejected() {
        assertThrows(JwtException.class, () -> decoder.decode(sign(valid("first").expiration(Date.from(Instant.now().minusSeconds(1))), first)));
        assertThrows(JwtException.class, () -> decoder.decode(sign(valid("first").expiration(Date.from(Instant.now().plusSeconds(1000))), first)));
        assertThrows(JwtException.class, () -> decoder.decode(sign(valid("first").issuedAt(Date.from(Instant.now().plusSeconds(10))), first)));
        assertThrows(JwtException.class, () -> decoder.decode(sign(valid("first").id(null), first)));
        assertThrows(JwtException.class, () -> decoder.decode(sign(valid("first").subject("1-1-1-1-1"), first)));
    }

    @Test void unavailableJwksDoesNotAcceptAnUnverifiedToken() {
        server.stop(0);
        assertThrows(IiamJwtDecoder.KeysUnavailable.class, () -> decoder.decode(sign(valid("first"), first)));
    }
}
