package com.logimarui.platform.security.iiam;

import com.logimarui.gateway.core.port.ManagedServiceProvider;
import com.logimarui.platform.security.filter.JwtAuthenticationFilter;
import com.logimarui.platform.security.jwt.JwtTokenParser;
import com.logimarui.platform.security.principal.IiamPrincipal;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IiamRequestAuthorizationTest {
    private final ManagedServiceProvider services = mock(ManagedServiceProvider.class);
    private final JwtTokenParser tokens = mock(JwtTokenParser.class);
    private final IiamPdpClient pdp = mock(IiamPdpClient.class);
    private final IiamPrincipal actor = new IiamPrincipal(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),Instant.now().plusSeconds(900));
    private JwtAuthenticationFilter filter;
    @BeforeEach void setup() {
        SecurityContextHolder.clearContext();
        when(services.findAll()).thenReturn(List.of());
        when(tokens.parseAndValidate("signed-access")).thenReturn(actor);
        filter=new JwtAuthenticationFilter(tokens,new IiamRequestPolicy(services),pdp,new IiamBrowserAccess(new org.springframework.mock.env.MockEnvironment()));
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    private MockHttpServletResponse perform(String method,String path,boolean bearer,AtomicBoolean called) throws Exception {
        var request=new MockHttpServletRequest(method,path);
        request.addHeader("X-Tenant-Id",UUID.randomUUID().toString());
        request.addHeader("X-Scope-Id",UUID.randomUUID().toString());
        if(bearer)request.addHeader("Authorization","Bearer signed-access");
        var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(req,res)->called.set(true));
        return response;
    }
    @Test void anonymousSupervisorCallStopsBeforeController() throws Exception {
        var called=new AtomicBoolean();
        assertEquals(401,perform("POST","/api/v1/admin/services/stop-all",false,called).getStatus());
        assertFalse(called.get());verifyNoInteractions(pdp);
    }
    @Test void supervisorResourceComesFromServerRoutingAndAllowsOnlyPdpGrant() throws Exception {
        when(pdp.decide("signed-access",actor,CorePermissionCatalog.SERVICES_MANAGE,"GATEWAY_SERVER","local")).thenReturn(true);
        var called=new AtomicBoolean();
        assertEquals(200,perform("POST","/api/v1/admin/services/start-all",true,called).getStatus());
        assertTrue(called.get());
        var authorities=SecurityContextHolder.getContext().getAuthentication().getAuthorities();
        assertEquals(CorePermissionCatalog.SERVICES_MANAGE,authorities.iterator().next().getAuthority());
        verify(pdp).decide("signed-access",actor,CorePermissionCatalog.SERVICES_MANAGE,"GATEWAY_SERVER","local");
    }
    @Test void denyNeverReachesController() throws Exception {
        var called=new AtomicBoolean();
        assertEquals(403,perform("GET","/api/v1/admin/services/overview",true,called).getStatus());
        assertFalse(called.get());assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
    @Test void unavailablePdpReturns503WithoutFallback() throws Exception {
        when(pdp.decide(anyString(),eq(actor),anyString(),anyString(),anyString())).thenThrow(new IiamPdpClient.Unavailable());
        var called=new AtomicBoolean();
        var response=perform("GET","/api/v1/admin/services/overview",true,called);
        assertEquals(503,response.getStatus());assertFalse(called.get());
        assertFalse(response.getContentAsString().contains("signed-access"));
    }
    @Test void unavailableSigningKeysReturn503BeforePdpOrController() throws Exception {
        when(tokens.parseAndValidate("signed-access")).thenThrow(new com.logimarui.platform.security.jwt.IiamJwtDecoder.KeysUnavailable());
        var called=new AtomicBoolean();
        var response=perform("GET","/api/v1/admin/services/overview",true,called);
        assertEquals(503,response.getStatus());assertFalse(called.get());verifyNoInteractions(pdp);
        assertTrue(response.getContentAsString().contains("IIAM_KEYS_UNAVAILABLE"));
    }
    @Test void receivablesUseOwnedPermissionAndUnsupportedWritesNeverGainRead() throws Exception {
        when(pdp.decide("signed-access",actor,CorePermissionCatalog.RECEIVABLES_READ,"CORE_MODULE","receivables")).thenReturn(true);
        var read=new AtomicBoolean();assertEquals(200,perform("GET","/api/v1/receivables/titles",true,read).getStatus());assertTrue(read.get());
        var write=new AtomicBoolean();assertEquals(403,perform("POST","/api/v1/receivables/titles",true,write).getStatus());assertFalse(write.get());
    }
    @Test void explicitIndependentCallbacksAndIiamTransportRemainOutsideCorePdp() throws Exception {
        for(String path:List.of("/api/iam/authentication/login","/webhook/my-workflow")){
            var called=new AtomicBoolean();assertEquals(200,perform("POST",path,false,called).getStatus());assertTrue(called.get());
        }
        var called=new AtomicBoolean();assertEquals(200,perform("PUT","/critica-pedidos/api/mock",false,called).getStatus());assertTrue(called.get());
        verifyNoInteractions(pdp,tokens);
    }
}
