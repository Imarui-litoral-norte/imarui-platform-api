package com.logimarui.platform.security.iiam;

import com.logimarui.gateway.core.application.ServiceRegistry;
import com.logimarui.platform.security.jwt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;
import javax.net.ssl.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit opt-in: consumes only the synthetic DEV identity provisioned by the setup script. */
@EnabledIfEnvironmentVariable(named="IIAM_LIVE_TEST_RUNTIME", matches=".+")
class IiamLiveContractTest {
    @Test void realCatalogPdpAndLiveSessionHonorTheV1Contract() throws Exception {
        Path runtime = Path.of(System.getenv("IIAM_LIVE_TEST_RUNTIME"));
        var environment = new MockEnvironment();
        Properties settings = new Properties();
        try (var reader = Files.newBufferedReader(runtime.resolve("core-client.properties"))) { settings.load(reader); }
        settings.forEach((key,value) -> environment.setProperty(key.toString(), value.toString()));
        Properties account = new Properties();
        try (var reader = Files.newBufferedReader(runtime.resolve("core-operator.properties"))) { account.load(reader); }
        var trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null, null);
        try (var certificate = Files.newInputStream(runtime.resolve("localhost.crt"))) {
            trust.setCertificateEntry("iiam-dev", CertificateFactory.getInstance("X.509").generateCertificate(certificate));
        }
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); managers.init(trust);
        SSLContext tls = SSLContext.getInstance("TLS"); tls.init(null, managers.getTrustManagers(), null);
        SSLContext previous = SSLContext.getDefault();
        SSLSocketFactory previousFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
        try {
            SSLContext.setDefault(tls); HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());
            var http = HttpClient.newBuilder().sslContext(tls).cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
            var json = JsonMapper.builder().build();
            String base = settings.getProperty("IIAM_BASE_URI");
            var csrfResponse = http.send(HttpRequest.newBuilder(URI.create(base + "/authentication/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, csrfResponse.statusCode());
            String csrf = json.readTree(csrfResponse.body()).path("token").asText();
            byte[] credentials = json.writeValueAsBytes(Map.of("identityKind", account.getProperty("IDENTITY_KIND"),
                    "identifier", account.getProperty("IDENTIFIER"), "password", account.getProperty("PASSWORD")));
            var login = http.send(HttpRequest.newBuilder(URI.create(base + "/authentication/login"))
                    .header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrf)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(credentials)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, login.statusCode());
            String token = json.readTree(login.body()).path("accessToken").asText();
            var services = mock(ServiceRegistry.class); when(services.findAll()).thenReturn(List.of());
            var parser = new JwtTokenParser(new IiamJwtDecoder(services, environment));
            var principal = parser.parseAndValidate(token);
            var pdp = new IiamPdpClient(services, environment, json);
            assertFalse(pdp.synchronizeCatalog(), "The already provisioned canonical catalog must be unchanged");
            assertTrue(pdp.decide(token, principal, CorePermissionCatalog.SERVICES_READ, "GATEWAY_SERVER", "local"));
            assertThrows(IiamPdpClient.Unavailable.class,
                    () -> pdp.decide(token, principal, CorePermissionCatalog.SERVICES_READ, "GATEWAY_SERVER", "unregistered"),
                    "A missing trusted binding must fail closed as an integration/configuration failure");
            var logout = http.send(HttpRequest.newBuilder(URI.create(base + "/authentication/logout"))
                    .header("X-XSRF-TOKEN", csrf).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
            assertEquals(204, logout.statusCode());
            assertNotNull(parser.parseAndValidate(token), "Offline signature validation can last until exp");
            assertFalse(pdp.decide(token, principal, CorePermissionCatalog.SERVICES_READ, "GATEWAY_SERVER", "local"),
                    "PDP must reject the revoked live session");
        } finally {
            SSLContext.setDefault(previous); HttpsURLConnection.setDefaultSSLSocketFactory(previousFactory);
        }
    }
}
