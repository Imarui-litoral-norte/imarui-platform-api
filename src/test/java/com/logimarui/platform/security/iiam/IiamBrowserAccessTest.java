package com.logimarui.platform.security.iiam;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import java.time.Instant;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class IiamBrowserAccessTest {
    private final IiamBrowserAccess browser=new IiamBrowserAccess(new MockEnvironment().withProperty("CORE_PUBLIC_ORIGIN","https://localhost:8443"));
    private final IiamRequestPolicy.Decision service=new IiamRequestPolicy.Decision(CorePermissionCatalog.SERVICES_USE,"GATEWAY_SERVICE","gerenciador-database-backup");
    private MockHttpServletRequest request(String method) {
        var request=new MockHttpServletRequest(method,"/api/backup/jobs");request.setSecure(true);
        request.setCookies(new Cookie(IiamBrowserAccess.COOKIE,"signed-iiam-access"));return request;
    }
    @Test void cookieIsNeverAnAuthenticationSourceForCoreApisOrInsecureTransport() {
        assertNull(browser.token(request("GET"),new IiamRequestPolicy.Decision(CorePermissionCatalog.SERVICES_MANAGE,"GATEWAY_SERVER","local")));
        var insecure=request("GET");insecure.setSecure(false);assertNull(browser.token(insecure,service));
        assertEquals("signed-iiam-access",browser.token(request("GET"),service));
    }
    @Test void cookieMutationsRequireExactConfiguredOrigin() {
        assertThrows(IiamBrowserAccess.Rejected.class,()->browser.token(request("POST"),service));
        var foreign=request("POST");foreign.addHeader("Origin","https://attacker.invalid");
        assertThrows(IiamBrowserAccess.Rejected.class,()->browser.token(foreign,service));
        var valid=request("POST");valid.addHeader("Origin","https://localhost:8443");
        assertEquals("signed-iiam-access",browser.token(valid,service));
    }
    @Test void issuedCookiesStayHttpOnlySecureScopedAndBoundedToOriginalAccessLifetime() {
        var response=new MockHttpServletResponse();
        browser.write(response,"signed-iiam-access",Instant.now().plusSeconds(120),Set.of("gerenciador-database-backup"));
        var cookies=response.getHeaders("Set-Cookie");
        assertFalse(cookies.isEmpty());
        assertTrue(cookies.stream().allMatch(c -> c.contains("Secure") && c.contains("HttpOnly") && c.contains("SameSite=Strict") && !c.contains("Path=/;") && !c.contains("Domain=")));
        var active=cookies.stream().filter(c -> c.startsWith(IiamBrowserAccess.COOKIE+"=signed-iiam-access;")).toList();
        assertEquals(2,active.size());assertTrue(active.stream().allMatch(c -> c.contains("Max-Age=119") || c.contains("Max-Age=120")));
        browser.clear(response);
        assertTrue(response.getHeaders("Set-Cookie").stream().anyMatch(c -> c.contains("Max-Age=0")));
    }
    @Test void gatewayCookieNeverReachesTheProxiedService() {
        var incoming=new org.springframework.http.HttpHeaders();
        incoming.add("Cookie","LG_SERVICE_ACCESS=private-context; application-session=keep");
        var outgoing=new org.springframework.http.HttpHeaders();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(new com.logimarui.gateway.config.GatewayRoutesConfig("http://127.0.0.1:8191"),
                "copyRequestHeaders",incoming,outgoing);
        assertEquals("application-session=keep",outgoing.getFirst("Cookie"));
        assertTrue(incoming.getFirst("Cookie").contains("private-context"));
    }
}
