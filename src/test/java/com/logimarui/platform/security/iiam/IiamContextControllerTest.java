package com.logimarui.platform.security.iiam;

import com.logimarui.gateway.api.IiamContextController;
import com.logimarui.gateway.core.port.ManagedServiceProvider;
import com.logimarui.platform.security.principal.IiamPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IiamContextControllerTest {
    @Test void ownContextUsesSignedPrincipalAndPdpRatherThanClientIdentityOrClaimedPermissions() throws Exception {
        var actor=new IiamPrincipal(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),Instant.now().plusSeconds(900));
        var pdp=mock(IiamPdpClient.class);var services=mock(ManagedServiceProvider.class);
        when(services.findAll()).thenReturn(List.of());
        when(pdp.currentIdentity("actor-access",actor)).thenReturn(JsonMapper.builder().build().readTree(
                "{\"id\":\""+actor.identityId()+"\",\"nickname\":\"account\"}"));
        when(pdp.decide("actor-access",actor,CorePermissionCatalog.SERVICES_READ,"GATEWAY_SERVER","local")).thenReturn(true);
        var controller=new IiamContextController(pdp,services,new IiamBrowserAccess(new MockEnvironment()));
        var request=new MockHttpServletRequest("GET","/api/v1/gateway/me");
        request.addHeader("Authorization","Bearer actor-access");request.addHeader("X-Identity-Id",UUID.randomUUID().toString());
        var response=controller.me(actor,request);
        assertEquals(actor.identityId(),response.getBody().get("identityId"));
        assertEquals(List.of(CorePermissionCatalog.SERVICES_READ),response.getBody().get("authorities"));
        assertEquals("no-store",response.getHeaders().getCacheControl());
        verify(pdp).currentIdentity("actor-access",actor);
    }
    @Test void insecureBrowserContextNeverWritesCredentialCookies() {
        var pdp=mock(IiamPdpClient.class);var services=mock(ManagedServiceProvider.class);
        var controller=new IiamContextController(pdp,services,new IiamBrowserAccess(new MockEnvironment()));
        var response=new MockHttpServletResponse();
        assertEquals(403,controller.establish(null,new MockHttpServletRequest("POST","/api/v1/gateway/browser-context"),response).getStatusCode().value());
        assertTrue(response.getHeaders("Set-Cookie").isEmpty());verifyNoInteractions(pdp);
    }

    @Test void loggedInUserGetsServiceCookiesWithoutServicePermission() {
        var actor=new IiamPrincipal(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),Instant.now().plusSeconds(900));
        var pdp=mock(IiamPdpClient.class);var services=mock(ManagedServiceProvider.class);
        when(services.findAll()).thenReturn(List.of(new com.logimarui.gateway.core.domain.model.ManagedService(
                "gerenciador-extracao","/api/extrator",com.logimarui.gateway.core.domain.model.ServiceType.PYTHON,".","python app.py",4000,true)));
        var controller=new IiamContextController(pdp,services,new IiamBrowserAccess(new MockEnvironment()));
        var request=new MockHttpServletRequest("POST","/api/v1/gateway/browser-context");request.setSecure(true);
        request.addHeader("Authorization","Bearer actor-access");
        var response=new MockHttpServletResponse();
        assertEquals(204,controller.establish(actor,request,response).getStatusCode().value());
        assertTrue(response.getHeaders("Set-Cookie").stream().anyMatch(c->c.startsWith("LG_SERVICE_ACCESS=actor-access;") && c.contains("Path=/api/extrator;")));
        verify(pdp).currentIdentity("actor-access",actor);
        verify(pdp,never()).decide(anyString(),any(),anyString(),anyString(),anyString());
    }
}
