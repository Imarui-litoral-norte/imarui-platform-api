package com.logimarui.platform.security.config;

import com.logimarui.platform.security.filter.JwtAuthenticationFilter;
import jakarta.servlet.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CriticaUploadSecurityTest {
    @Configuration @EnableWebSecurity @EnableWebMvc @Import(SecurityConfig.class)
    static class Config {
        @Bean JwtAuthenticationFilter jwtFilter() throws Exception {
            var filter = mock(JwtAuthenticationFilter.class);
            doAnswer(call -> {
                ((FilterChain) call.getArgument(2)).doFilter(call.getArgument(0), call.getArgument(1));
                return null;
            }).when(filter).doFilter(any(ServletRequest.class), any(ServletResponse.class), any(FilterChain.class));
            return filter;
        }
        @Bean Probe probe() { return new Probe(); }
    }
    @RestController
    static class Probe {
        @RequestMapping("/critica-pedidos/api/mock") String mock() { return "forwarded"; }
        @PutMapping("/critica-pedidos/api/other") String other() { return "private"; }
    }
    @Test void allowsOnlyTheStandaloneUploadWriteContract() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            org.springframework.test.context.support.TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "security.jwt.secret=isolated-test-secret-with-at-least-thirty-two-characters",
                    "security.jwt.issuer=critica-test", "security.jwt.access-token-expiration-seconds=3600");
            context.register(Config.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            mvc.perform(put("/critica-pedidos/api/mock")).andExpect(status().isOk());
            mvc.perform(post("/critica-pedidos/api/mock")).andExpect(status().isUnauthorized());
            mvc.perform(delete("/critica-pedidos/api/mock")).andExpect(status().isUnauthorized());
            mvc.perform(put("/critica-pedidos/api/other")).andExpect(status().isUnauthorized());
        }
    }
}
