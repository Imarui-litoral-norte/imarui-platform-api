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
        configureMode(true);
    }
    private void configureMode(boolean permissionsRequired) {
        filter=new JwtAuthenticationFilter(tokens,new IiamRequestPolicy(services),pdp,new IiamBrowserAccess(new org.springframework.mock.env.MockEnvironment()),new CoreAccessPolicy(permissionsRequired));
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

    private void registerExtrator() {
        when(services.findAll()).thenReturn(List.of(new com.logimarui.gateway.core.domain.model.ManagedService(
                "gerenciador-extracao","/api/extrator",com.logimarui.gateway.core.domain.model.ServiceType.PYTHON,".","python app.py",4000,true)));
    }
    @Test void gatewayServiceRequiresLiveLoginWithoutPermissionGrant() throws Exception {
        configureMode(false);
        registerExtrator();
        var called=new AtomicBoolean();
        assertEquals(200,perform("GET","/api/extrator/status",true,called).getStatus());
        assertTrue(called.get());verify(pdp).currentIdentity("signed-access",actor);
        verify(pdp,never()).decide(anyString(),any(),anyString(),anyString(),anyString());
    }
    @Test void gatewayServiceStillRejectsAnonymousAndRevokedSessions() throws Exception {
        configureMode(false);
        registerExtrator();
        var anonymous=new AtomicBoolean();
        assertEquals(401,perform("GET","/api/extrator/status",false,anonymous).getStatus());
        assertFalse(anonymous.get());
        when(pdp.currentIdentity("signed-access",actor)).thenThrow(new IiamPdpClient.ActorInvalid());
        var revoked=new AtomicBoolean();
        assertEquals(401,perform("GET","/api/extrator/status",true,revoked).getStatus());
        assertFalse(revoked.get());
    }

    @Test void authenticatedModeAllowsAllCoreModulesAndSupervisorWithoutStoredGrants() throws Exception {
        configureMode(false);
        for (String path:List.of("/api/v1/admin/services/overview","/api/v1/journey/tml",
                "/api/v1/customers/1/label","/api/v1/receivables/filters","/api/v1/vehicles",
                "/api/v2/occurrences","/api/v1/memberships","/api/v1/replenishments",
                "/api/v1/operationalload")) {
            var called=new AtomicBoolean();
            assertEquals(200,perform("GET",path,true,called).getStatus(),path);
            assertTrue(called.get(),path);
        }
        assertEquals(9,mockingDetails(pdp).getInvocations().size());
        verify(pdp,never()).decide(anyString(),any(),anyString(),anyString(),anyString());
    }

    @Test void authenticatedModeStillRejectsAnonymousInvalidAndRevokedCoreSessions() throws Exception {
        configureMode(false);
        var called=new AtomicBoolean();
        assertEquals(401,perform("GET","/api/v1/receivables/filters",false,called).getStatus());
        assertFalse(called.get());verifyNoInteractions(pdp);
        when(pdp.currentIdentity("signed-access",actor)).thenThrow(new IiamPdpClient.ActorInvalid());
        assertEquals(401,perform("GET","/api/v1/receivables/filters",true,called).getStatus());
        assertFalse(called.get());assertNull(SecurityContextHolder.getContext().getAuthentication());
        when(tokens.parseAndValidate("signed-access")).thenThrow(new org.springframework.security.oauth2.jwt.JwtException("invalid"));
        assertEquals(401,perform("GET","/api/v1/journey/tml",true,called).getStatus());
        assertFalse(called.get());
    }

    @Test void authenticatedModeDoesNotAllowAccessWhenSessionValidationIsUnavailable() throws Exception {
        configureMode(false);
        when(pdp.currentIdentity("signed-access",actor)).thenThrow(new IiamPdpClient.Unavailable());
        var called=new AtomicBoolean();
        assertEquals(503,perform("GET","/api/v1/journey/tml",true,called).getStatus());
        assertFalse(called.get());
    }
}
