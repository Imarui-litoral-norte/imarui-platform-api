package com.logimarui.gateway.infra.process;

import com.logimarui.gateway.core.application.ServiceLifecycleManager;
import com.logimarui.gateway.core.domain.model.*;
import com.logimarui.gateway.core.port.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ServiceLifecycleOwnershipTest {
    @Test void refusedIiamStopKeepsTheRegisteredRuntime() {
        var services=mock(ManagedServiceProvider.class);var repository=mock(ServiceRuntimeRepository.class);
        var runner=mock(ServiceProcessRunner.class);
        var service=new ManagedService("iiam","/api/iam",ServiceType.JAVA,".","java -jar",4105,false);
        var runtime=new ServiceRuntime("iiam",4105,123L,123L,ServiceStatus.RUNNING,Instant.now());
        when(services.findById("iiam")).thenReturn(Optional.of(service));when(runner.supports(service)).thenReturn(true);
        when(repository.findByServiceId("iiam")).thenReturn(Optional.of(runtime));when(repository.findAll()).thenReturn(List.of(runtime));
        doThrow(new IllegalStateException("ownership mismatch")).when(runner).stop(service,runtime);
        var manager=new ServiceLifecycleManager(services,repository,List.of(runner));
        assertThrows(IllegalStateException.class,()->manager.stopById("iiam"));
        verify(repository,never()).deleteByServiceId(anyString());
        manager.stopAll();verify(repository,never()).deleteByServiceId(anyString());
    }
}
