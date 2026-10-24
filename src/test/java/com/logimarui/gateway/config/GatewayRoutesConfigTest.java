package com.logimarui.gateway.config;

import com.logimarui.gateway.core.application.ServiceRegistry;
import com.logimarui.gateway.core.domain.model.ServiceRoute;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayRoutesConfigTest {

    private final ServiceRegistry serviceRegistry = new ServiceRegistry(() -> List.of(
            new ServiceRoute(
                    "gerenciador-extracao",
                    "/api/extrator",
                    "http://127.0.0.1:4000",
                    true
            ),
            new ServiceRoute(
                    "imarui_savi_automation_service",
                    "/api/savi",
                    "http://127.0.0.1:4003",
                    true
            ),
            new ServiceRoute(
                    "frontend",
                    "/",
                    "http://127.0.0.1:8091",
                    true
            )
    ));

    private final RouterFunction<ServerResponse> routes =
            new GatewayRoutesConfig("http://127.0.0.1:8091")
                    .gatewayRoutes(serviceRegistry);

    @Test
    void localCoreApiIsNeverInterceptedByTheGateway() {
        assertThat(hasGatewayHandler(HttpMethod.GET, "/api/v1"))
                .isFalse();
        assertThat(hasGatewayHandler(HttpMethod.POST, "/api/v1/authentication/login"))
                .isFalse();
        assertThat(hasGatewayHandler(HttpMethod.GET, "/api/v1/authentication/me"))
                .isFalse();
        assertThat(hasGatewayHandler(HttpMethod.GET, "/api/v1/authorization/roles"))
                .isFalse();
        assertThat(hasGatewayHandler(HttpMethod.GET, "/api/v1/admin/services/overview"))
                .isFalse();
        assertThat(hasGatewayHandler(HttpMethod.GET, "/api/v2/occurrences"))
                .isFalse();
    }

    @Test
    void registeredExternalServiceApisRemainProxied() {
        assertThat(hasGatewayHandler(HttpMethod.GET, "/api/extrator/health"))
                .isTrue();
        assertThat(hasGatewayHandler(HttpMethod.POST, "/api/savi/api/v1/planos-de-acao"))
                .isTrue();
        assertThat(hasGatewayHandler(HttpMethod.GET, "/api/unknown"))
                .isFalse();
        assertThat(hasGatewayHandler(HttpMethod.POST, "/api/unknown"))
                .isFalse();
    }

    @Test
    void nextOwnsCurrentAndFutureUiRoutes() {
        assertThat(hasGatewayHandler(HttpMethod.GET, "/app/extrator"))
                .isTrue();
        assertThat(hasGatewayHandler(HttpMethod.GET, "/authorization"))
                .isTrue();
        assertThat(hasGatewayHandler(HttpMethod.GET, "/admin"))
                .isTrue();
        assertThat(hasGatewayHandler(HttpMethod.GET, "/rota-next-criada-amanha?aba=1"))
                .isTrue();
        assertThat(hasGatewayHandler(HttpMethod.HEAD, "/rota-next-inexistente"))
                .isTrue();
        assertThat(hasGatewayHandler(HttpMethod.OPTIONS, "/rota-next-inexistente"))
                .isTrue();
        assertThat(hasGatewayHandler(HttpMethod.POST, "/rota-next-criada-amanha"))
                .isTrue();
        assertThat(hasGatewayHandler(HttpMethod.PUT, "/rota-next-criada-amanha"))
                .isTrue();
        assertThat(hasGatewayHandler(HttpMethod.PATCH, "/rota-next-criada-amanha"))
                .isTrue();
        assertThat(hasGatewayHandler(HttpMethod.DELETE, "/rota-next-criada-amanha"))
                .isTrue();
        assertThat(hasGatewayHandler(HttpMethod.TRACE, "/rota-next-criada-amanha"))
                .isFalse();
        assertThat(hasGatewayHandler(HttpMethod.valueOf("CONNECT"), "/rota-next-criada-amanha"))
                .isFalse();
    }

    @Test
    void apiNamespaceNeverFallsBackToNext() {
        for (HttpMethod method : List.of(
                HttpMethod.GET,
                HttpMethod.HEAD,
                HttpMethod.OPTIONS,
                HttpMethod.POST,
                HttpMethod.PUT,
                HttpMethod.PATCH,
                HttpMethod.DELETE
        )) {
            assertThat(hasGatewayHandler(method, "/api"))
                    .as("%s /api", method)
                    .isFalse();
            assertThat(hasGatewayHandler(method, "/api/unknown"))
                    .as("%s /api/unknown", method)
                    .isFalse();
        }
    }

    @Test
    void frontendServiceRouteIsNeverRegisteredAsExternalApi() {
        ServiceRegistry registryWithFrontendApiPrefix = new ServiceRegistry(() -> List.of(
                new ServiceRoute(
                        "frontend",
                        "/api/frontend",
                        "http://127.0.0.1:8091",
                        true
                )
        ));
        RouterFunction<ServerResponse> routesWithFrontendApiPrefix =
                new GatewayRoutesConfig("http://127.0.0.1:8091")
                        .gatewayRoutes(registryWithFrontendApiPrefix);

        assertThat(routesWithFrontendApiPrefix.route(
                serverRequest(HttpMethod.GET, "/api/frontend/dashboard")
        )).isEmpty();
    }

    @Test
    void springInfrastructureAndExplicitExternalFrontendsStayOutsideTheNextFallback() {
        assertThat(hasGatewayHandler(HttpMethod.GET, "/docs"))
                .isFalse();
        assertThat(hasGatewayHandler(HttpMethod.GET, "/swagger-ui/index.html"))
                .isFalse();
        assertThat(hasGatewayHandler(HttpMethod.GET, "/swagger-ui.html"))
                .isFalse();
        assertThat(hasGatewayHandler(HttpMethod.GET, "/actuator/health"))
                .isFalse();
        assertThat(hasGatewayHandler(HttpMethod.GET, "/openapi-custom/config"))
                .isFalse();
        assertThat(hasGatewayHandler(HttpMethod.GET, "/v3/api-docs"))
                .isFalse();
        assertThat(hasGatewayHandler(HttpMethod.GET, "/error"))
                .isFalse();
        assertThat(hasGatewayHandler(HttpMethod.GET, "/n8n/"))
                .isTrue();
        assertThat(hasGatewayHandler(HttpMethod.GET, "/evolution/"))
                .isTrue();
    }

    @Test
    void explicitExternalRoutesKeepPrecedenceOverNextFallback() throws Exception {
        assertThat(gatewayStatus(HttpMethod.POST, "/webhook/orders"))
                .isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(gatewayStatus(HttpMethod.PUT, "/n8n/rest/settings"))
                .isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(gatewayStatus(HttpMethod.PATCH, "/evolution/manager/instance"))
                .isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(gatewayStatus(HttpMethod.DELETE, "/evolution-api/instance/1"))
                .isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(gatewayStatus(HttpMethod.POST, "/gerenciador-database/monitoring/jobs"))
                .isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void nextFallbackPreservesPathQueryAndForwardedHeaders() throws Exception {
        AtomicReference<String> receivedTarget = new AtomicReference<>();
        AtomicReference<String> forwardedHost = new AtomicReference<>();
        AtomicReference<String> forwardedProto = new AtomicReference<>();
        AtomicReference<String> forwardedFor = new AtomicReference<>();
        HttpServer frontendServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        frontendServer.createContext("/", exchange -> {
            receivedTarget.set(exchange.getRequestURI().toString());
            forwardedHost.set(exchange.getRequestHeaders().getFirst("X-Forwarded-Host"));
            forwardedProto.set(exchange.getRequestHeaders().getFirst("X-Forwarded-Proto"));
            forwardedFor.set(exchange.getRequestHeaders().getFirst("X-Forwarded-For"));
            exchange.sendResponseHeaders(HttpStatus.NO_CONTENT.value(), -1);
            exchange.close();
        });
        frontendServer.start();

        try {
            String frontendTarget = "http://127.0.0.1:" + frontendServer.getAddress().getPort();
            ServiceRegistry registryWithoutRuntime = new ServiceRegistry(() -> List.of());
            RouterFunction<ServerResponse> fallbackRoutes =
                    new GatewayRoutesConfig(frontendTarget).gatewayRoutes(registryWithoutRuntime);
            ServerRequest request = serverRequest(
                    HttpMethod.GET,
                    "/rota-next-criada-amanha?aba=detalhes&ordem=desc"
            );

            HandlerFunction<ServerResponse> handler = fallbackRoutes.route(request).orElseThrow();
            ServerResponse response = handler.handle(request);

            assertThat(response.statusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(receivedTarget.get())
                    .isEqualTo("/rota-next-criada-amanha?aba=detalhes&ordem=desc");
            assertThat(forwardedHost.get()).isEqualTo("logimarui.local");
            assertThat(forwardedProto.get()).isEqualTo("http");
            assertThat(forwardedFor.get()).isEqualTo("192.0.2.10");
        } finally {
            frontendServer.stop(0);
        }
    }

    @Test
    void criticaProxyUsesItsOwnRuntimeAndPreservesOriginalHtml() throws Exception {
        byte[] original = "<!DOCTYPE html><a href=\"/assets/raw\">Carlos</a><script>fetch('/api/local')</script>\r\n".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> receivedTarget = new AtomicReference<>();
        HttpServer criticaServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        criticaServer.createContext("/", exchange -> {
            receivedTarget.set(exchange.getRequestURI().toString());
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, original.length);
            exchange.getResponseBody().write(original);
            exchange.close();
        });
        criticaServer.start();
        try {
            var registry = new ServiceRegistry(() -> List.of(new ServiceRoute(
                    "critica-pedidos", "/critica-pedidos",
                    "http://127.0.0.1:" + criticaServer.getAddress().getPort(), false)));
            var criticaRoutes = new GatewayRoutesConfig("http://127.0.0.1:1").gatewayRoutes(registry);
            var request = serverRequest(HttpMethod.GET, "/critica-pedidos/index.html?teste=1");
            var response = criticaRoutes.route(request).orElseThrow().handle(request);
            var output = new MockHttpServletResponse();
            response.writeTo(request.servletRequest(), output, () -> List.of(new ByteArrayHttpMessageConverter()));
            assertThat(output.getStatus()).isEqualTo(200);
            assertThat(output.getContentAsByteArray()).isEqualTo(original);
            assertThat(receivedTarget.get()).isEqualTo("/index.html?teste=1");

            var unavailable = serverRequest(HttpMethod.GET, "/critica-pedidos");
            assertThat(routes.route(unavailable).orElseThrow().handle(unavailable).statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        } finally {
            criticaServer.stop(0);
        }
    }

    private boolean hasGatewayHandler(HttpMethod method, String rawPath) {
        return routes.route(serverRequest(method, rawPath)).isPresent();
    }

    private int gatewayStatus(HttpMethod method, String rawPath) throws Exception {
        ServerRequest request = serverRequest(method, rawPath);
        HandlerFunction<ServerResponse> handler = routes.route(request).orElseThrow();
        return handler.handle(request).statusCode().value();
    }

    private ServerRequest serverRequest(HttpMethod method, String rawPath) {
        int querySeparator = rawPath.indexOf('?');
        String path = querySeparator >= 0
                ? rawPath.substring(0, querySeparator)
                : rawPath;
        String query = querySeparator >= 0
                ? rawPath.substring(querySeparator + 1)
                : null;

        MockHttpServletRequest servletRequest = new MockHttpServletRequest(method.name(), path);
        servletRequest.setRequestURI(path);
        servletRequest.setQueryString(query);
        servletRequest.setScheme("http");
        servletRequest.setRemoteAddr("192.0.2.10");
        servletRequest.addHeader("Host", "logimarui.local");

        return ServerRequest.create(servletRequest, List.of());
    }
}
