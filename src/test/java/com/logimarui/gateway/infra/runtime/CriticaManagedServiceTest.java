package com.logimarui.gateway.infra.runtime;

import com.logimarui.gateway.core.domain.model.ServiceType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CriticaManagedServiceTest {
    @Test
    void criticaStartsBeforeFrontendFromItsOwnRepository() {
        var provider = new StaticManagedServiceProvider(new MockEnvironment()
                .withProperty("LOGIMARUI_PROFILE", "dev")
                .withProperty("LOGIMARUI_ROOT", Path.of("workspace").toAbsolutePath().toString()));
        var critica = provider.findById(ManagedServiceIds.CRITICA_PEDIDOS).orElseThrow();

        assertThat(critica.getType()).isEqualTo(ServiceType.PYTHON);
        assertThat(critica.getWorkingDirectory()).endsWith(Path.of("logimarui_services", "critica-pedidos").toString());
        assertThat(critica.getStartCommand()).isEqualTo("python logimarui_service.py");
        assertThat(critica.getPort()).isEqualTo(4104);
        assertThat(critica.getPortEnvironmentVariable()).isEqualTo("PORT");
        assertThat(critica.isEnabled()).isTrue();
        assertThat(critica.isStartOnBoot()).isTrue();
        var order = provider.findAll().stream().map(service -> service.getId()).toList();
        assertThat(order.indexOf(ManagedServiceIds.CRITICA_PEDIDOS)).isLessThan(order.indexOf(ManagedServiceIds.FRONTEND));
    }

    @Test
    void criticaSupportsProfilePortAndDeploymentOverrides() {
        var local = new StaticManagedServiceProvider(new MockEnvironment());
        assertThat(local.findById(ManagedServiceIds.CRITICA_PEDIDOS).orElseThrow().getPort()).isEqualTo(4004);

        var configured = new StaticManagedServiceProvider(new MockEnvironment()
                .withProperty("SERVICE_CRITICA_PREFERRED_PORT", "4204")
                .withProperty("SERVICE_CRITICA_ENABLED", "false")
                .withProperty("SERVICE_CRITICA_START_ON_BOOT", "false")
                .withProperty("SERVICE_CRITICA_WORKDIR", "independent-checkout"));
        var critica = configured.findById(ManagedServiceIds.CRITICA_PEDIDOS).orElseThrow();
        assertThat(critica.getPort()).isEqualTo(4204);
        assertThat(critica.getWorkingDirectory()).isEqualTo("independent-checkout");
        assertThat(critica.isEnabled()).isFalse();
        assertThat(critica.isStartOnBoot()).isFalse();
    }
}
