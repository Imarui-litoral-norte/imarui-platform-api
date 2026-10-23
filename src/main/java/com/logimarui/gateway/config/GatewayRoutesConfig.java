package com.logimarui.gateway.config;

import com.logimarui.gateway.core.application.ServiceRegistry;
import com.logimarui.gateway.core.domain.model.ServiceRoute;
import com.logimarui.gateway.infra.runtime.ManagedServiceIds;
import jakarta.servlet.ServletException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.web.servlet.function.RequestPredicates.path;
import static org.springframework.web.servlet.function.RouterFunctions.route;

@Configuration
public class GatewayRoutesConfig {

    private static final String BACKEND_API_PREFIX = "/api";
    private static final String CORE_API_PREFIX = "/api/v1";
    private static final String FRONTEND_SERVICE_ID = ManagedServiceIds.FRONTEND;
    private static final String DATABASE_MONITORING_SERVICE_ID = ManagedServiceIds.DATABASE_MONITORING;
    private static final String DATABASE_BACKUP_SERVICE_ID = ManagedServiceIds.DATABASE_BACKUP;
    private static final String DATABASE_PUBLIC_PREFIX = "/gerenciador-database";
    private static final String DATABASE_MONITORING_PUBLIC_PREFIX = DATABASE_PUBLIC_PREFIX + "/monitoring";
    private static final String DATABASE_BACKUP_PUBLIC_PREFIX = DATABASE_PUBLIC_PREFIX + "/backup";
    private static final String EVOLUTION_SERVICE_ID = ManagedServiceIds.EVOLUTION;
    private static final String EVOLUTION_PUBLIC_PREFIX = "/evolution";
    private static final String EVOLUTION_API_PUBLIC_PREFIX = "/evolution-api";
    private static final String N8N_SERVICE_ID = ManagedServiceIds.N8N;
    private static final String N8N_PUBLIC_PREFIX = "/n8n";
    private static final String N8N_REST_PREFIX = "/rest/n8n";
    private static final List<String> SPRING_OWNED_PATH_PREFIXES = List.of(
            "/actuator",
            BACKEND_API_PREFIX,
            "/docs",
            "/error",
            "/openapi-custom",
            "/swagger-ui",
            "/swagger-ui.html",
            "/v3/api-docs"
    );

    private static final Set<HttpMethod> FRONTEND_FALLBACK_METHODS = Set.of(
            HttpMethod.GET,
            HttpMethod.HEAD,
            HttpMethod.OPTIONS,
            HttpMethod.POST,
            HttpMethod.PUT,
            HttpMethod.PATCH,
            HttpMethod.DELETE
    );

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "host",
            "content-length",
            "accept-encoding"
    );

    private static final List<String> PROXIED_WEB_APP_ABSOLUTE_PATH_PREFIXES = List.of(
            "/api",
            "/assets",
            "/auth",
            "/call",
            "/chat",
            "/chatwoot",
            "/console",
            "/credentials",
            "/css",
            "/dashboard",
            "/e",
            "/event",
            "/executions",
            "/favicon",
            "/fonts",
            "/form",
            "/form-test",
            "/group",
            "/healthz",
            "/icons",
            "/instance",
            "/js",
            "/label",
            "/legacy-dashboard",
            "/locales",
            "/manager",
            "/message",
            "/metrics",
            "/mfa",
            "/oauth2",
            "/profile",
            "/projects",
            "/proxy",
            "/rabbitmq",
            "/rest",
            "/saml",
            "/send",
            "/settings",
            "/signin",
            "/signup",
            "/socket.io",
            "/sqs",
            "/static",
            "/template",
            "/templates",
            "/typebot",
            "/types",
            "/users",
            "/variables",
            "/wa",
            "/webhook",
            "/webhook-test",
            "/websocket",
            "/workflow",
            "/workflows"
    );

    private final RestClient restClient = RestClient.builder()
            .requestFactory(clientHttpRequestFactory())
            .build();
    private final String frontendTargetUri;

    public GatewayRoutesConfig(
            @Value("${app.frontend.url:http://127.0.0.1:8091}") String frontendTargetUri
    ) {
        this.frontendTargetUri = normalizeBaseUri(frontendTargetUri);
    }

    @Bean
    public RouterFunction<ServerResponse> gatewayRoutes(ServiceRegistry serviceRegistry) {
        return route()
                .route(
                        request -> isExternalServiceApiRequest(request, serviceRegistry),
                        request -> proxyToExternalService(request, serviceRegistry)
                )
                .route(path("/rest"), request -> proxyToN8nRootEndpoint(request, serviceRegistry))
                .route(path("/rest/**"), request -> proxyToN8nRootEndpoint(request, serviceRegistry))
                .route(path("/webhook"), request -> proxyToN8nRootEndpoint(request, serviceRegistry))
                .route(path("/webhook/**"), request -> proxyToN8nRootEndpoint(request, serviceRegistry))
                .route(path("/webhook-test"), request -> proxyToN8nRootEndpoint(request, serviceRegistry))
                .route(path("/webhook-test/**"), request -> proxyToN8nRootEndpoint(request, serviceRegistry))
                .route(path("/form"), request -> proxyToN8nRootEndpoint(request, serviceRegistry))
                .route(path("/form/**"), request -> proxyToN8nRootEndpoint(request, serviceRegistry))
                .route(path("/form-test"), request -> proxyToN8nRootEndpoint(request, serviceRegistry))
                .route(path("/form-test/**"), request -> proxyToN8nRootEndpoint(request, serviceRegistry))
                .route(path(DATABASE_PUBLIC_PREFIX), request -> redirectTo(DATABASE_MONITORING_PUBLIC_PREFIX + "/"))
                .route(path(DATABASE_PUBLIC_PREFIX + "/"), request -> redirectTo(DATABASE_MONITORING_PUBLIC_PREFIX + "/"))
                .route(path(DATABASE_MONITORING_PUBLIC_PREFIX), request -> proxyToDatabaseMonitoring(request, serviceRegistry))
                .route(path(DATABASE_MONITORING_PUBLIC_PREFIX + "/**"), request -> proxyToDatabaseMonitoring(request, serviceRegistry))
                .route(path(DATABASE_BACKUP_PUBLIC_PREFIX), request -> proxyToDatabaseBackup(request, serviceRegistry))
                .route(path(DATABASE_BACKUP_PUBLIC_PREFIX + "/**"), request -> proxyToDatabaseBackup(request, serviceRegistry))
                .route(path("/evolution"), request -> proxyToEvolutionManager(request, serviceRegistry))
                .route(path("/evolution/**"), request -> proxyToEvolutionManager(request, serviceRegistry))
                .route(path("/evolution-api"), request -> proxyToEvolutionApi(request, serviceRegistry))
                .route(path("/evolution-api/**"), request -> proxyToEvolutionApi(request, serviceRegistry))
                .route(path("/n8n"), request -> proxyToN8n(request, serviceRegistry))
                .route(path("/n8n/**"), request -> proxyToN8n(request, serviceRegistry))
                .route(this::isFrontendRequest, request -> proxyToFrontend(request, serviceRegistry))
                .build();
    }

    private ServerResponse proxyToExternalService(ServerRequest request, ServiceRegistry serviceRegistry) {
        ServiceRoute serviceRoute = findMatchingExternalServiceApiRoute(request, serviceRegistry);

        if (serviceRoute == null) {
            return ServerResponse.notFound().build();
        }

        URI targetUri = buildTargetUri(serviceRoute, request);

        try {
            GatewayProxyResponse response = rewriteHtmlBodyIfNeeded(
                    forwardRequest(request, targetUri),
                    serviceRoute
            );

            ServerResponse.BodyBuilder responseBuilder =
                    ServerResponse.status(response.statusCode());

            copyResponseHeaders(response.headers(), responseBuilder, serviceRoute);

            if (request.method() == HttpMethod.HEAD) {
                return responseBuilder.build();
            }

            return responseBuilder.body(response.body());

        } catch (RestClientException exception) {
            return ServerResponse
                    .status(502)
                    .body("Erro ao encaminhar requisicao para servico: " + serviceRoute.getId());
        } catch (IOException | ServletException exception) {
            return ServerResponse
                    .status(400)
                    .body("Erro ao ler corpo da requisicao.");
        }
    }

    private ServerResponse proxyToEvolutionManager(
            ServerRequest request,
            ServiceRegistry serviceRegistry
    ) {
        String requestPath = request.path();

        if (requestPath.equals(EVOLUTION_PUBLIC_PREFIX) || requestPath.equals(EVOLUTION_PUBLIC_PREFIX + "/")) {
            return redirectTo(EVOLUTION_PUBLIC_PREFIX + "/manager/");
        }

        return proxyToPrefixedWebApp(
                request,
                serviceRegistry,
                EVOLUTION_SERVICE_ID,
                EVOLUTION_PUBLIC_PREFIX,
                "Evolution Manager",
                true
        );
    }

    private ServerResponse proxyToEvolutionApi(
            ServerRequest request,
            ServiceRegistry serviceRegistry
    ) {
        ServiceRoute serviceRoute = findRouteById(serviceRegistry, EVOLUTION_SERVICE_ID);

        if (serviceRoute == null) {
            return ServerResponse.notFound().build();
        }

        URI targetUri = appendPathAndQuery(
                serviceRoute.getTargetUri(),
                normalizeEvolutionApiUpstreamPath(request.path()),
                request.uri().getRawQuery()
        );

        try {
            GatewayProxyResponse response = forwardRequest(
                    request,
                    targetUri,
                    EVOLUTION_API_PUBLIC_PREFIX
            );

            ServerResponse.BodyBuilder responseBuilder =
                    ServerResponse.status(response.statusCode());

            copyPrefixedWebAppResponseHeaders(
                    response.headers(),
                    responseBuilder,
                    serviceRoute,
                    EVOLUTION_API_PUBLIC_PREFIX
            );

            if (request.method() == HttpMethod.HEAD) {
                return responseBuilder.build();
            }

            return responseBuilder.body(response.body());

        } catch (RestClientException exception) {
            return ServerResponse
                    .status(502)
                    .body("Erro ao encaminhar requisicao para Evolution API.");
        } catch (IOException | ServletException exception) {
            return ServerResponse
                    .status(400)
                    .body("Erro ao ler corpo da requisicao.");
        }
    }

    private ServerResponse proxyToDatabaseMonitoring(
            ServerRequest request,
            ServiceRegistry serviceRegistry
    ) {
        if (request.path().equals(DATABASE_MONITORING_PUBLIC_PREFIX)) {
            return redirectTo(DATABASE_MONITORING_PUBLIC_PREFIX + "/");
        }

        return proxyToPrefixedWebApp(
                request,
                serviceRegistry,
                DATABASE_MONITORING_SERVICE_ID,
                DATABASE_MONITORING_PUBLIC_PREFIX,
                "Gerenciador Database Monitoring",
                true
        );
    }

    private ServerResponse proxyToDatabaseBackup(
            ServerRequest request,
            ServiceRegistry serviceRegistry
    ) {
        if (request.path().equals(DATABASE_BACKUP_PUBLIC_PREFIX)) {
            return redirectTo(DATABASE_BACKUP_PUBLIC_PREFIX + "/");
        }

        return proxyToPrefixedWebApp(
                request,
                serviceRegistry,
                DATABASE_BACKUP_SERVICE_ID,
                DATABASE_BACKUP_PUBLIC_PREFIX,
                "Gerenciador Database Backup",
                true
        );
    }

    private ServerResponse proxyToN8n(
            ServerRequest request,
            ServiceRegistry serviceRegistry
    ) {
        if (request.path().equals("/n8n")) {
            return redirectTo("/n8n/");
        }

        return proxyToPrefixedWebApp(
                request,
                serviceRegistry,
                N8N_SERVICE_ID,
                N8N_PUBLIC_PREFIX,
                "n8n",
                true
        );
    }

    private ServerResponse redirectTo(String location) {
        return ServerResponse
                .status(302)
                .header(HttpHeaders.LOCATION, location)
                .build();
    }

    private ServerResponse proxyToN8nRootEndpoint(
            ServerRequest request,
            ServiceRegistry serviceRegistry
    ) {
        ServiceRoute serviceRoute = findRouteById(serviceRegistry, N8N_SERVICE_ID);

        if (serviceRoute == null) {
            return ServerResponse.notFound().build();
        }

        URI targetUri = appendPathAndQuery(
                serviceRoute.getTargetUri(),
                normalizeN8nUpstreamPath(N8N_SERVICE_ID, request.path()),
                request.uri().getRawQuery()
        );

        try {
            GatewayProxyResponse response = rewritePrefixedWebAppBodyIfNeeded(
                    forwardRequest(request, targetUri, N8N_PUBLIC_PREFIX),
                    N8N_PUBLIC_PREFIX
            );

            ServerResponse.BodyBuilder responseBuilder =
                    ServerResponse.status(response.statusCode());

            copyPrefixedWebAppResponseHeaders(
                    response.headers(),
                    responseBuilder,
                    serviceRoute,
                    N8N_PUBLIC_PREFIX
            );

            if (request.method() == HttpMethod.HEAD) {
                return responseBuilder.build();
            }

            return responseBuilder.body(response.body());

        } catch (RestClientException exception) {
            return ServerResponse
                    .status(502)
                    .body("Erro ao encaminhar requisicao para n8n.");
        } catch (IOException | ServletException exception) {
            return ServerResponse
                    .status(400)
                    .body("Erro ao ler corpo da requisicao.");
        }
    }

    private ServerResponse proxyToFrontend(
            ServerRequest request,
            ServiceRegistry serviceRegistry
    ) {
        String frontendTarget = frontendTargetUri;
        ServiceRoute frontendRuntimeRoute = findRouteById(serviceRegistry, FRONTEND_SERVICE_ID);
        if (frontendRuntimeRoute != null) {
            frontendTarget = normalizeBaseUri(frontendRuntimeRoute.getTargetUri());
        }

        URI targetUri = appendPathAndQuery(
                frontendTarget,
                request.path(),
                request.uri().getRawQuery()
        );

        try {
            GatewayProxyResponse response = forwardRequest(request, targetUri);
            ServerResponse.BodyBuilder responseBuilder =
                    ServerResponse.status(response.statusCode());

            copyFrontendResponseHeaders(response.headers(), responseBuilder, frontendTarget);

            if (request.method() == HttpMethod.HEAD) {
                return responseBuilder.build();
            }

            return responseBuilder.body(response.body());
        } catch (RestClientException exception) {
            return ServerResponse
                    .status(502)
                    .body("Erro ao encaminhar requisicao para logimarui-frontend.");
        } catch (IOException | ServletException exception) {
            return ServerResponse
                    .status(400)
                    .body("Erro ao ler corpo da requisicao.");
        }
    }

    private ServerResponse proxyToPrefixedWebApp(
            ServerRequest request,
            ServiceRegistry serviceRegistry,
            String serviceId,
            String publicPrefix,
            String displayName,
            boolean stripPublicPrefix
    ) {
        ServiceRoute serviceRoute = findRouteById(serviceRegistry, serviceId);

        if (serviceRoute == null) {
            return ServerResponse.notFound().build();
        }

        URI targetUri = buildPrefixedWebAppTargetUri(
                serviceRoute,
                request,
                publicPrefix,
                stripPublicPrefix
        );

        try {
            GatewayProxyResponse response = rewritePrefixedWebAppBodyIfNeeded(
                    forwardRequest(request, targetUri, publicPrefix),
                    publicPrefix
            );

            ServerResponse.BodyBuilder responseBuilder =
                    ServerResponse.status(response.statusCode());

            copyPrefixedWebAppResponseHeaders(
                    response.headers(),
                    responseBuilder,
                    serviceRoute,
                    publicPrefix
            );

            if (request.method() == HttpMethod.HEAD) {
                return responseBuilder.build();
            }

            return responseBuilder.body(response.body());

        } catch (RestClientException exception) {
            return ServerResponse
                    .status(502)
                    .body("Erro ao encaminhar requisicao para " + displayName + ".");
        } catch (IOException | ServletException exception) {
            return ServerResponse
                    .status(400)
                    .body("Erro ao ler corpo da requisicao.");
        }
    }

    private ServiceRoute findRouteById(ServiceRegistry serviceRegistry, String serviceId) {
        return serviceRegistry.findAll()
                .stream()
                .filter(route -> route.getId().equals(serviceId))
                .findFirst()
                .orElse(null);
    }

    private boolean isExternalServiceApiRequest(
            ServerRequest request,
            ServiceRegistry serviceRegistry
    ) {
        if (matchesPath(request.path(), "/api/iam")) {
            return false; // Explicit IIAM protocol controller owns these requests.
        }

        if (matchesPath(request.path(), CORE_API_PREFIX)) {
            return false;
        }

        return findMatchingExternalServiceApiRoute(request, serviceRegistry) != null;
    }

    private ServiceRoute findMatchingExternalServiceApiRoute(
            ServerRequest request,
            ServiceRegistry serviceRegistry
    ) {
        String requestPath = request.path();

        return serviceRegistry.findAll()
                .stream()
                .filter(this::isExternalServiceApiRoute)
                .filter(route -> matchesPath(requestPath, route.getPathPrefix()))
                .max(Comparator.comparingInt(route -> route.getPathPrefix().length()))
                .orElse(null);
    }

    private boolean isExternalServiceApiRoute(ServiceRoute route) {
        String pathPrefix = route.getPathPrefix();

        return !FRONTEND_SERVICE_ID.equals(route.getId())
                && pathPrefix != null
                && pathPrefix.startsWith(BACKEND_API_PREFIX + "/")
                && !matchesPath(pathPrefix, CORE_API_PREFIX);
    }

    private boolean matchesPath(String requestPath, String pathPrefix) {
        return requestPath.equals(pathPrefix)
                || requestPath.startsWith(pathPrefix + "/");
    }

    private boolean isFrontendRequest(ServerRequest request) {
        HttpMethod method = request.method();

        if (!FRONTEND_FALLBACK_METHODS.contains(method)) {
            return false;
        }

        String requestPath = request.path();

        return SPRING_OWNED_PATH_PREFIXES.stream()
                .noneMatch(prefix -> matchesPath(requestPath, prefix));
    }

    private URI buildTargetUri(ServiceRoute serviceRoute, ServerRequest request) {
        String pathPrefix = serviceRoute.getPathPrefix();
        String requestPath = request.path();

        String remainingPath = requestPath.equals(pathPrefix)
                ? "/"
                : requestPath.substring(pathPrefix.length());

        return appendPathAndQuery(
                serviceRoute.getTargetUri(),
                remainingPath,
                request.uri().getRawQuery()
        );
    }

    private URI buildPrefixedWebAppTargetUri(
            ServiceRoute serviceRoute,
            ServerRequest request,
            String publicPrefix,
            boolean stripPublicPrefix
    ) {
        String requestPath = request.path();

        String upstreamPath;

        if (stripPublicPrefix) {
            upstreamPath = stripRepeatedPublicPrefix(requestPath, publicPrefix);
        } else {
            upstreamPath = requestPath;
        }

        upstreamPath = normalizeN8nUpstreamPath(serviceRoute.getId(), upstreamPath);

        if (upstreamPath.isBlank()) {
            upstreamPath = "/";
        }

        return appendPathAndQuery(
                serviceRoute.getTargetUri(),
                upstreamPath,
                request.uri().getRawQuery()
        );
    }

    private String stripRepeatedPublicPrefix(String requestPath, String publicPrefix) {
        if (requestPath == null || requestPath.isBlank()) {
            return "/";
        }

        String normalizedPath = requestPath;
        String prefixedPath = publicPrefix + "/";

        while (normalizedPath.equals(publicPrefix) || normalizedPath.startsWith(prefixedPath)) {
            normalizedPath = normalizedPath.equals(publicPrefix)
                    ? "/"
                    : normalizedPath.substring(publicPrefix.length());
        }

        return normalizedPath.isBlank() ? "/" : normalizedPath;
    }

    private URI appendPathAndQuery(
            String targetUri,
            String remainingPath,
            String rawQuery
    ) {
        if (remainingPath == null || remainingPath.isBlank()) {
            remainingPath = "/";
        }

        if (!remainingPath.startsWith("/")) {
            remainingPath = "/" + remainingPath;
        }

        if (targetUri.endsWith("/") && remainingPath.startsWith("/")) {
            targetUri = targetUri.substring(0, targetUri.length() - 1);
        }

        if (rawQuery != null && !rawQuery.isBlank()) {
            return URI.create(targetUri + remainingPath + "?" + rawQuery);
        }

        return URI.create(targetUri + remainingPath);
    }

    private GatewayProxyResponse forwardRequest(ServerRequest request, URI targetUri)
            throws IOException, ServletException {
        return forwardRequest(request, targetUri, null);
    }

    private GatewayProxyResponse forwardRequest(
            ServerRequest request,
            URI targetUri,
            String forwardedPrefix
    ) throws IOException, ServletException {

        RestClient.RequestBodySpec requestSpec = restClient
                .method(request.method())
                .uri(targetUri)
                .headers(headers -> {
                    copyRequestHeaders(request.headers().asHttpHeaders(), headers);
                    addForwardedHeaders(request, headers, forwardedPrefix);
                });

        RestClient.RequestHeadersSpec<?> headersSpec;

        if (methodCanHaveBody(request.method())) {
            byte[] requestBody = request.body(byte[].class);
            headersSpec = requestSpec.body(requestBody);
        } else {
            headersSpec = requestSpec;
        }

        try {
            if (request.method() == HttpMethod.HEAD) {
                ResponseEntity<Void> responseEntity = headersSpec
                        .retrieve()
                        .toBodilessEntity();

                return new GatewayProxyResponse(
                        responseEntity.getStatusCode(),
                        responseEntity.getHeaders(),
                        new byte[0]
                );
            }

            ResponseEntity<byte[]> responseEntity = headersSpec
                    .retrieve()
                    .toEntity(byte[].class);

            byte[] responseBody = responseEntity.getBody() == null
                    ? new byte[0]
                    : responseEntity.getBody();

            return new GatewayProxyResponse(
                    responseEntity.getStatusCode(),
                    responseEntity.getHeaders(),
                    responseBody
            );
        } catch (RestClientResponseException exception) {
            HttpHeaders headers = new HttpHeaders();

            if (exception.getResponseHeaders() != null) {
                headers.putAll(exception.getResponseHeaders());
            }

            byte[] responseBody = exception.getResponseBodyAsByteArray();

            if (responseBody == null) {
                responseBody = new byte[0];
            }

            return new GatewayProxyResponse(
                    exception.getStatusCode(),
                    headers,
                    responseBody
            );
        }
    }

    private boolean methodCanHaveBody(HttpMethod method) {
        return method == HttpMethod.POST
                || method == HttpMethod.PUT
                || method == HttpMethod.PATCH
                || method == HttpMethod.DELETE;
    }

    private void copyRequestHeaders(HttpHeaders source, HttpHeaders target) {
        source.forEach((name, values) -> {
            if ("cookie".equalsIgnoreCase(name)) {
                List<String> filtered = values.stream().map(value -> java.util.Arrays.stream(value.split(";"))
                        .map(String::trim).filter(cookie -> !cookie.startsWith("LG_SERVICE_ACCESS="))
                        .collect(java.util.stream.Collectors.joining("; "))).filter(value -> !value.isBlank()).toList();
                if (!filtered.isEmpty()) target.put(name, filtered);
                return;
            }
            if (!isHopByHopHeader(name)) {
                target.put(name, values);
            }
        });
    }

    private void addForwardedHeaders(
            ServerRequest request,
            HttpHeaders target,
            String forwardedPrefix
    ) {
        String host = request.headers().asHttpHeaders().getFirst(HttpHeaders.HOST);

        if (host != null && !host.isBlank()) {
            target.set("X-Forwarded-Host", host);
        }

        String scheme = request.uri().getScheme();

        if (scheme != null && !scheme.isBlank()) {
            target.set("X-Forwarded-Proto", scheme);
        }

        if (forwardedPrefix != null && !forwardedPrefix.isBlank()) {
            target.set("X-Forwarded-Prefix", forwardedPrefix);
        }

        String forwardedFor = request.headers().asHttpHeaders().getFirst("X-Forwarded-For");

        if (forwardedFor == null || forwardedFor.isBlank()) {
            forwardedFor = request.servletRequest().getRemoteAddr();
        }

        if (forwardedFor != null && !forwardedFor.isBlank()) {
            target.set("X-Forwarded-For", forwardedFor);
        }
    }

    private void copyResponseHeaders(
            HttpHeaders source,
            ServerResponse.BodyBuilder target,
            ServiceRoute serviceRoute
    ) {
        source.forEach((name, values) -> {
            if (isHopByHopHeader(name)) {
                return;
            }

            if ("location".equalsIgnoreCase(name)) {
                values.stream()
                        .map(location -> rewriteLocationHeader(location, serviceRoute))
                        .forEach(location -> target.header(name, location));
                return;
            }

            target.header(name, values.toArray(String[]::new));
        });
    }

    private void copyFrontendResponseHeaders(
            HttpHeaders source,
            ServerResponse.BodyBuilder target,
            String frontendTarget
    ) {
        source.forEach((name, values) -> {
            if (isHopByHopHeader(name)) {
                return;
            }

            if ("location".equalsIgnoreCase(name)) {
                values.stream()
                        .map(location -> rewriteFrontendLocationHeader(location, frontendTarget))
                        .forEach(location -> target.header(name, location));
                return;
            }

            target.header(name, values.toArray(String[]::new));
        });
    }

    private String rewriteLocationHeader(String location, ServiceRoute serviceRoute) {
        if (location == null || location.isBlank()) {
            return location;
        }

        String targetUri = serviceRoute.getTargetUri();
        String pathPrefix = serviceRoute.getPathPrefix();

        if (location.startsWith(targetUri)) {
            return pathPrefix + location.substring(targetUri.length());
        }

        if (location.startsWith("/")) {
            return pathPrefix + location;
        }

        return location;
    }

    private String rewriteFrontendLocationHeader(String location, String frontendTarget) {
        if (location == null || location.isBlank()) {
            return location;
        }

        if (location.startsWith(frontendTarget)) {
            String rewrittenLocation = location.substring(frontendTarget.length());
            return rewrittenLocation.isBlank() ? "/" : rewrittenLocation;
        }

        return location;
    }

    private void copyPrefixedWebAppResponseHeaders(
            HttpHeaders source,
            ServerResponse.BodyBuilder target,
            ServiceRoute serviceRoute,
            String publicPrefix
    ) {
        source.forEach((name, values) -> {
            if (isHopByHopHeader(name)) {
                return;
            }

            if ("location".equalsIgnoreCase(name)) {
                values.stream()
                        .map(location -> rewritePrefixedWebAppLocationHeader(
                                location,
                                serviceRoute,
                                publicPrefix
                        ))
                        .forEach(location -> target.header(name, location));
                return;
            }

            if ("set-cookie".equalsIgnoreCase(name)) {
                values.stream()
                        .map(cookie -> rewriteSetCookiePath(cookie, publicPrefix))
                        .forEach(cookie -> target.header(name, cookie));
                return;
            }

            target.header(name, values.toArray(String[]::new));
        });
    }

    private String rewritePrefixedWebAppLocationHeader(
            String location,
            ServiceRoute serviceRoute,
            String publicPrefix
    ) {
        if (location == null || location.isBlank()) {
            return location;
        }

        if (location.equals(publicPrefix) || location.startsWith(publicPrefix + "/")) {
            return location;
        }

        String targetUri = serviceRoute.getTargetUri();

        if (location.startsWith(targetUri)) {
            return prependPublicPrefix(
                    publicPrefix,
                    location.substring(targetUri.length())
            );
        }

        if (location.startsWith("/")) {
            return prependPublicPrefix(publicPrefix, location);
        }

        return location;
    }

    private String rewriteSetCookiePath(String setCookie, String publicPrefix) {
        if (setCookie == null || setCookie.isBlank()) {
            return setCookie;
        }

        Pattern pattern = Pattern.compile("(?i)(;\\s*Path=)([^;]*)");
        Matcher matcher = pattern.matcher(setCookie);

        if (!matcher.find()) {
            return setCookie;
        }

        String cookiePath = matcher.group(2);

        if (cookiePath.equals(publicPrefix) || cookiePath.startsWith(publicPrefix + "/")) {
            return setCookie;
        }

        return matcher.replaceFirst(
                Matcher.quoteReplacement(matcher.group(1) + prependPublicPrefix(publicPrefix, cookiePath))
        );
    }

    private String prependPublicPrefix(String publicPrefix, String path) {
        if (path == null || path.isBlank()) {
            return publicPrefix + "/";
        }

        if (path.equals(publicPrefix) || path.startsWith(publicPrefix + "/")) {
            return path;
        }

        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        if (path.equals("/")) {
            return publicPrefix + "/";
        }

        return publicPrefix + path;
    }

    private String normalizeN8nUpstreamPath(String serviceId, String upstreamPath) {
        if (!N8N_SERVICE_ID.equals(serviceId) || upstreamPath == null || upstreamPath.isBlank()) {
            return upstreamPath;
        }

        if (upstreamPath.equals(N8N_REST_PREFIX)) {
            return "/rest";
        }

        if (upstreamPath.startsWith(N8N_REST_PREFIX + "/")) {
            return "/rest" + upstreamPath.substring(N8N_REST_PREFIX.length());
        }

        return upstreamPath;
    }

    private String normalizeEvolutionApiUpstreamPath(String requestPath) {
        String upstreamPath = stripRepeatedPublicPrefix(
                requestPath,
                EVOLUTION_API_PUBLIC_PREFIX
        );

        if (upstreamPath.equals("/evolution")) {
            return "/";
        }

        if (upstreamPath.startsWith("/evolution/")) {
            return upstreamPath.substring("/evolution".length());
        }

        return upstreamPath;
    }

    private boolean isHopByHopHeader(String headerName) {
        return HOP_BY_HOP_HEADERS.contains(headerName.toLowerCase());
    }

    private SimpleClientHttpRequestFactory clientHttpRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();

        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));

        return factory;
    }

    private String normalizeBaseUri(String uri) {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("app.frontend.url nao pode ser vazio.");
        }

        if (uri.endsWith("/")) {
            return uri.substring(0, uri.length() - 1);
        }

        return uri;
    }

    private record GatewayProxyResponse(
            HttpStatusCode statusCode,
            HttpHeaders headers,
            byte[] body
    ) {
    }

    private GatewayProxyResponse rewriteHtmlBodyIfNeeded(
            GatewayProxyResponse response,
            ServiceRoute serviceRoute
    ) {
        String contentType = response.headers().getFirst(HttpHeaders.CONTENT_TYPE);

        if (contentType == null || !contentType.toLowerCase().contains("text/html")) {
            return response;
        }

        if (response.body() == null || response.body().length == 0) {
            return response;
        }

        String html = new String(response.body(), StandardCharsets.UTF_8);
        String pathPrefix = serviceRoute.getPathPrefix();

        html = html
                .replace("src=\"/assets/", "src=\"" + pathPrefix + "/assets/")
                .replace("href=\"/assets/", "href=\"" + pathPrefix + "/assets/")
                .replace("src='/assets/", "src='" + pathPrefix + "/assets/")
                .replace("href='/assets/", "href='" + pathPrefix + "/assets/");

        return new GatewayProxyResponse(
                response.statusCode(),
                rewrittenBodyHeaders(response.headers()),
                html.getBytes(StandardCharsets.UTF_8)
        );
    }

    private GatewayProxyResponse rewritePrefixedWebAppBodyIfNeeded(
            GatewayProxyResponse response,
            String publicPrefix
    ) {
        String contentType = response.headers().getFirst(HttpHeaders.CONTENT_TYPE);

        if (!shouldRewriteWebAppBody(contentType)) {
            return response;
        }

        if (response.body() == null || response.body().length == 0) {
            return response;
        }

        String body = new String(response.body(), StandardCharsets.UTF_8);

        body = rewriteHtmlAbsoluteAttributes(body, publicPrefix);
        body = rewriteCssAbsoluteUrls(body, publicPrefix);
        body = rewriteQuotedAbsolutePathPrefixes(body, publicPrefix);

        return new GatewayProxyResponse(
                response.statusCode(),
                rewrittenBodyHeaders(response.headers()),
                body.getBytes(StandardCharsets.UTF_8)
        );
    }

    private boolean shouldRewriteWebAppBody(String contentType) {
        if (contentType == null) {
            return false;
        }

        String normalizedContentType = contentType.toLowerCase();

        return normalizedContentType.contains("text/html")
                || normalizedContentType.contains("application/javascript")
                || normalizedContentType.contains("text/javascript")
                || normalizedContentType.contains("text/css")
                || normalizedContentType.contains("application/json")
                || normalizedContentType.contains("application/manifest+json")
                || normalizedContentType.contains("image/svg+xml");
    }

    private HttpHeaders rewrittenBodyHeaders(HttpHeaders source) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(source);
        headers.remove(HttpHeaders.CONTENT_LENGTH);
        headers.remove(HttpHeaders.CONTENT_ENCODING);
        headers.remove(HttpHeaders.ETAG);
        return headers;
    }

    private String rewriteHtmlAbsoluteAttributes(String body, String publicPrefix) {
        String publicSegment = Pattern.quote(publicPrefix.substring(1));
        Pattern pattern = Pattern.compile(
                "(?i)(\\b(?:src|href|action|poster|data-src|data-href|content)=([\"']))/" +
                        "(?!/|" + publicSegment + "(?:/|\\?|#|[\"']))"
        );

        return pattern
                .matcher(body)
                .replaceAll("$1" + Matcher.quoteReplacement(publicPrefix + "/"));
    }

    private String rewriteCssAbsoluteUrls(String body, String publicPrefix) {
        String publicSegment = Pattern.quote(publicPrefix.substring(1));
        Pattern pattern = Pattern.compile(
                "(?i)(url\\(\\s*(?:[\"'])?)/(?!/|" + publicSegment + "(?:/|\\?|#|[\"']))"
        );

        return pattern
                .matcher(body)
                .replaceAll("$1" + Matcher.quoteReplacement(publicPrefix + "/"));
    }

    private String rewriteQuotedAbsolutePathPrefixes(String body, String publicPrefix) {
        String rewrittenBody = body;

        for (String pathPrefix : PROXIED_WEB_APP_ABSOLUTE_PATH_PREFIXES) {
            Pattern pattern = Pattern.compile(
                    "([\"'`])" + Pattern.quote(pathPrefix) + "(?=[/?#\"'`])"
            );

            rewrittenBody = pattern
                    .matcher(rewrittenBody)
                    .replaceAll("$1" + Matcher.quoteReplacement(publicPrefix + pathPrefix));
        }

        return rewrittenBody;
    }
}
