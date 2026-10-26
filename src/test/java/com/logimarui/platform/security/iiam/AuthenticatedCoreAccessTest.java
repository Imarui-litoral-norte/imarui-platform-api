package com.logimarui.platform.security.iiam;

import com.logimarui.gateway.core.port.ManagedServiceProvider;
import com.logimarui.platform.security.config.SecurityConfig;
import com.logimarui.platform.security.filter.JwtAuthenticationFilter;
import com.logimarui.platform.security.jwt.JwtTokenParser;
import com.logimarui.platform.security.principal.IiamPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.*;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthenticatedCoreAccessTest {
    @Configuration @EnableWebSecurity @EnableWebMvc
    @Import({SecurityConfig.class, CoreAccessPolicy.class})
    static class Config {
        @Bean IiamPrincipal actor() {
            return new IiamPrincipal(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),Instant.now().plusSeconds(600));
        }
        @Bean IiamPdpClient pdp() { return mock(IiamPdpClient.class); }
        @Bean JwtAuthenticationFilter filter(CoreAccessPolicy policy,IiamPdpClient pdp,IiamPrincipal actor) {
            var tokens=mock(JwtTokenParser.class);
            when(tokens.parseAndValidate("valid-access")).thenReturn(actor);
            when(tokens.parseAndValidate("invalid-access")).thenThrow(new org.springframework.security.oauth2.jwt.JwtException("invalid"));
            var services=mock(ManagedServiceProvider.class);
            when(services.findAll()).thenReturn(List.of());
            return new JwtAuthenticationFilter(tokens,new IiamRequestPolicy(services),pdp,
                    new IiamBrowserAccess(new MockEnvironment()),policy);
        }
        @Bean Probe probe() { return new Probe(); }
    }
    @RestController
    static class Probe {
        @GetMapping({"/api/v1/admin/services/overview","/api/v1/journey/tml","/api/v1/customers/1/label"})
        String read() { return "ok"; }
        @PostMapping("/api/v2/occurrences") String write() { return "ok"; }
        @GetMapping("/api/v1/receivables/titles")
        @PreAuthorize("hasAuthority('logimarui-core.receivables.read')")
        public String receivables() { return "ok"; }
    }
    private AnnotationConfigWebApplicationContext context(boolean permissionsRequired) {
        var context=new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "app.security.permissions-required="+permissionsRequired);
        context.register(Config.class);context.refresh();return context;
    }
    @Test void loginOnlyModeWorksThroughActualFilterChainAndMethodSecurity() throws Exception {
        try(var context=context(false)) {
            var mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            for(String path:List.of("/api/v1/admin/services/overview","/api/v1/journey/tml",
                    "/api/v1/customers/1/label","/api/v1/receivables/titles")) {
                mvc.perform(get(path).header("Authorization","Bearer valid-access"))
                        .andExpect(status().isOk()).andExpect(content().string("ok"));
                mvc.perform(get(path)).andExpect(status().isUnauthorized());
            }
            mvc.perform(post("/api/v2/occurrences").header("Authorization","Bearer valid-access"))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/journey/tml").header("Authorization","Bearer invalid-access"))
                    .andExpect(status().isUnauthorized());
            var pdp=context.getBean(IiamPdpClient.class);
            verify(pdp,never()).decide(anyString(),any(),anyString(),anyString(),anyString());
            when(pdp.currentIdentity(anyString(),any())).thenThrow(new IiamPdpClient.ActorInvalid());
            mvc.perform(get("/api/v1/receivables/titles").header("Authorization","Bearer valid-access"))
                    .andExpect(status().isUnauthorized());
        }
    }
    @Test void permissionModeStillRejectsMissingGrants() throws Exception {
        try(var context=context(true)) {
            var mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            mvc.perform(get("/api/v1/receivables/titles").header("Authorization","Bearer valid-access"))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/services/overview").header("Authorization","Bearer valid-access"))
                    .andExpect(status().isForbidden());
        }
    }
}
