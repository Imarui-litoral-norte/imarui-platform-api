package com.logimarui.gateway.infra.process;

import com.logimarui.gateway.core.domain.model.ManagedService;
import com.logimarui.gateway.core.domain.model.ServiceRuntime;
import com.logimarui.gateway.core.domain.model.ServiceStatus;
import com.logimarui.gateway.core.domain.model.ServiceType;
import com.logimarui.gateway.core.port.ServiceRuntimeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CriticaFrontendEnvironmentTest {
    @Test
    void frontendReceivesActualCriticaPortWhenPreferredPortIsUnavailable() {
        var repository = mock(ServiceRuntimeRepository.class);
        when(repository.findByServiceId("critica-pedidos")).thenReturn(Optional.of(
                new ServiceRuntime("critica-pedidos", 4204, 1L, 2L, ServiceStatus.RUNNING, Instant.now())));
        var inspector = new WindowsPortInspector();
        var runner = new NodeServiceProcessRunner(new ProcessTreeTerminator(), inspector,
                new ProcessPortAllocator(inspector), repository);
        var frontend = new ManagedService("frontend", "/", ServiceType.NODE, ".", "npm start", 8191, true);
        var process = new ProcessBuilder();
        process.environment().put("CRITICA_SERVICE_URL", "http://127.0.0.1:4104");

        ReflectionTestUtils.invokeMethod(runner, "injectPortEnvironment", frontend, 8192, process);

        assertThat(process.environment()).containsEntry("CRITICA_SERVICE_URL", "http://127.0.0.1:4204");
        assertThat(process.environment()).containsEntry("PORT", "8192");

        clearInvocations(repository);
        var other = new ManagedService("outro-node", "/other", ServiceType.NODE, ".", "npm start", 4200, true);
        ReflectionTestUtils.invokeMethod(runner, "injectPortEnvironment", other, 4200, new ProcessBuilder());
        verifyNoInteractions(repository);
    }
}
