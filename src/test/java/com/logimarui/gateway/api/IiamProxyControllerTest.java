package com.logimarui.gateway.api;

import com.logimarui.gateway.core.application.ServiceRegistry;
import com.logimarui.gateway.core.domain.model.ServiceRoute;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class IiamProxyControllerTest {
    @Test
    void transportsCookiesAndErrorsWithoutTrustingForwardedInput() throws Exception {
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var observed = new AtomicReference<String>();
        upstream.createContext("/authentication/refresh", exchange -> {
            observed.set(exchange.getRequestHeaders().getFirst("X-Forwarded-For"));
            exchange.getResponseHeaders().add("Set-Cookie", "IIAM_REFRESH=opaque; Path=/authentication; Secure; HttpOnly; SameSite=Lax");
            exchange.getResponseHeaders().add("Set-Cookie", "XSRF-TOKEN=csrf; Path=/; Secure; SameSite=Lax");
            exchange.getResponseHeaders().set("Content-Type", "application/problem+json");
            byte[] body = "{\"status\":403}".getBytes();
            exchange.sendResponseHeaders(403, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        try {
            var registry = mock(ServiceRegistry.class);
            when(registry.findAll()).thenReturn(List.of(new ServiceRoute("iiam", "/api/iam",
                    "http://127.0.0.1:" + upstream.getAddress().getPort(), false)));
            var controller = new IiamProxyController(registry,
                    new MockEnvironment().withProperty("SERVICE_IIAM_UPSTREAM_SCHEME", "http"));
            var request = new MockHttpServletRequest("POST", "/api/iam/authentication/refresh");
            request.setRemoteAddr("127.0.0.2");
            request.addHeader("X-Forwarded-For", "attacker");
            request.addHeader("Forwarded", "for=attacker;proto=https");
            var response = controller.proxy(request);
            assertEquals(403, response.getStatusCode().value());
            assertEquals("127.0.0.2", observed.get());
            assertArrayEquals("{\"status\":403}".getBytes(), response.getBody());
            var cookies = response.getHeaders().get("Set-Cookie");
            assertEquals(2, cookies.size());
            assertTrue(cookies.stream().anyMatch(c -> c.contains("Path=/api/iam/authentication; Secure; HttpOnly")));
            assertTrue(cookies.stream().anyMatch(c -> c.contains("Path=/api/iam/; Secure")));
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void missingRuntimeFailsClosedAndDoesNotCacheFailure() throws Exception {
        var registry = mock(ServiceRegistry.class);
        when(registry.findAll()).thenReturn(List.of());
        var response = new IiamProxyController(registry, new MockEnvironment())
                .proxy(new MockHttpServletRequest("GET", "/api/iam/.well-known/jwks.json"));
        assertEquals(503, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }

    @Test
    void cookieRewritingIsIdempotent() {
        String cookie = "IIAM_REFRESH=opaque; Path=/api/iam/authentication; Secure; HttpOnly";
        assertEquals(cookie, IiamProxyController.cookiePath(cookie));
    }
}
