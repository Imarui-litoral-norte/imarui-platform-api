package com.logimarui.receivables.api.v1;

import com.logimarui.platform.security.config.SecurityConfig;
import com.logimarui.platform.security.filter.JwtAuthenticationFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReceivablesSecurityTest {
    @Configuration @EnableWebSecurity @EnableWebMvc @Import(SecurityConfig.class)
    static class TestConfig {
        @Bean JwtAuthenticationFilter jwtFilter() throws Exception {
            var filter = mock(JwtAuthenticationFilter.class);
            doAnswer(call -> {
                ((FilterChain) call.getArgument(2)).doFilter(call.getArgument(0), call.getArgument(1)); return null;
            }).when(filter).doFilter(any(ServletRequest.class), any(ServletResponse.class), any(FilterChain.class));
            return filter;
        }
        @Bean Probe probe() { return new Probe(); }
    }
    @RestController
    @RequestMapping("/api/v1/receivables")
    static class Probe {
        @GetMapping("/titles") String titles() { return "ok"; }
        @PostMapping("/titles") String write() { return "not allowed"; }
    }

    @Test void actualSecurityChainRequiresFinancePermissionAndRejectsWrites() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            org.springframework.test.context.support.TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "security.jwt.secret=isolated-test-secret-with-at-least-thirty-two-characters",
                    "security.jwt.issuer=receivables-test", "security.jwt.access-token-expiration-seconds=3600");
            context.register(TestConfig.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            mvc.perform(get("/api/v1/receivables/titles")).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/receivables/titles").with(user("without-permission")))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/receivables/titles").with(user("reader")
                    .authorities(new SimpleGrantedAuthority("logimarui-core.receivables.read"))))
                    .andExpect(status().isOk()).andExpect(content().string("ok"));
            mvc.perform(post("/api/v1/receivables/titles").with(user("reader")
                    .authorities(new SimpleGrantedAuthority("logimarui-core.receivables.read")))).andExpect(status().isForbidden());
        }
    }
}
