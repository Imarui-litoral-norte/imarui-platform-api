package com.logimarui.gateway.core.application;

import com.logimarui.gateway.core.domain.model.ManagedService;
import com.logimarui.gateway.core.domain.model.ManagedServiceStatusSnapshot;
import com.logimarui.gateway.core.domain.model.ServiceRuntime;
import com.logimarui.gateway.core.port.ManagedServiceProvider;
import com.logimarui.gateway.core.port.ServiceProcessRunner;
import com.logimarui.gateway.core.port.ServiceRuntimeRepository;
import com.logimarui.gateway.infra.runtime.ManagedServiceIds;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
@Service
public class ServiceLifecycleManager {

    private final ManagedServiceProvider managedServiceProvider;
    private final ServiceRuntimeRepository serviceRuntimeRepository;
    private final List<ServiceProcessRunner> processRunners;

    public ServiceRuntime startById(String serviceId) {
        String canonicalServiceId = ManagedServiceIds.toCanonical(serviceId);
        ManagedService service = findManagedService(canonicalServiceId);

        findRuntimeByAnyAlias(canonicalServiceId)
                .ifPresent(existingRuntime -> {
                    throw new IllegalStateException(
                            "Servico ja possui runtime registrado: " + existingRuntime.getServiceId() +
                                    " rootPid=" + existingRuntime.getRootPid() +
                                    " listenerPid=" + existingRuntime.getListenerPid()
                    );
                });

        ServiceProcessRunner runner = findRunner(service);

        ServiceRuntime runtime = runner.start(service);
        serviceRuntimeRepository.save(runtime);

        return runtime;
    }

    public void stopById(String serviceId) {
        String canonicalServiceId = ManagedServiceIds.toCanonical(serviceId);
        ManagedService service = findManagedService(canonicalServiceId);

        ServiceRuntime runtime = findRuntimeByAnyAlias(canonicalServiceId)
                .orElseThrow(() -> new IllegalStateException(
                        "Servico nao possui runtime registrado: " + canonicalServiceId
                ));

        ServiceProcessRunner runner = findRunner(service);

        try {
            runner.stop(service, runtime);
        } catch (RuntimeException | Error failure) {
            if (!ManagedServiceIds.IIAM.equals(canonicalServiceId)) deleteRuntimeByAnyAlias(canonicalServiceId);
            throw failure;
        }
        deleteRuntimeByAnyAlias(canonicalServiceId);
    }

    public ServiceRuntime restartById(String serviceId) {
        findRuntimeByAnyAlias(serviceId)
                .ifPresent(runtime -> stopById(serviceId));

        return startById(serviceId);
    }

    public List<ServiceRuntime> startAll() {
        return managedServiceProvider.findAll()
                .stream()
                .filter(ManagedService::isEnabled)
                .map(service -> startById(service.getId()))
                .toList();
    }

    public void stopAll() {
        List<ServiceRuntime> runtimes = serviceRuntimeRepository.findAll();

        log.info("[Supervisor] stopAll iniciado. {} runtime(s) encontrado(s).", runtimes.size());

        for (ServiceRuntime runtime : runtimes) {
            try {
                log.info(
                        "[Supervisor] Parando servico {} rootPid={} listenerPid={} port={}",
                        runtime.getServiceId(),
                        runtime.getRootPid(),
                        runtime.getListenerPid(),
                        runtime.getPort()
                );

                stopById(runtime.getServiceId());

                log.info("[Supervisor] Servico {} parado.", runtime.getServiceId());
            } catch (Exception exception) {
                log.error("[Supervisor] Erro ao parar servico {}.", runtime.getServiceId(), exception);
                if (!ManagedServiceIds.IIAM.equals(runtime.getServiceId()))
                    serviceRuntimeRepository.deleteByServiceId(runtime.getServiceId());
            }
        }
    }

    public List<ServiceRuntime> refreshAll() {
        stopAll();
        return startAll();
    }

    public List<ServiceRuntime> findAllRuntimes() {
        return serviceRuntimeRepository.findAll();
    }

    public List<ManagedServiceStatusSnapshot> findAllManagedServiceStatuses() {
        return managedServiceProvider.findAll()
                .stream()
                .map(service -> {
                    Optional<ServiceRuntime> runtime = findRuntimeByAnyAlias(service.getId());

                    return new ManagedServiceStatusSnapshot(
                            service,
                            runtime.isPresent(),
                            runtime.orElse(null)
                    );
                })
                .toList();
    }

    @PreDestroy
    public void shutdown() {
        log.info("[Supervisor] Shutdown iniciado. Parando servicos gerenciados.");

        try {
            stopAll();
            log.info("[Supervisor] Shutdown finalizado. Servicos gerenciados foram parados.");
        } catch (Exception exception) {
            log.error("[Supervisor] Erro durante shutdown dos servicos gerenciados.", exception);
        }
    }

    private ManagedService findManagedService(String serviceId) {
        return managedServiceProvider.findById(serviceId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Servico nao encontrado: " + serviceId
                ));
    }

    private ServiceProcessRunner findRunner(ManagedService service) {
        return processRunners.stream()
                .filter(candidate -> candidate.supports(service))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Nenhum runner encontrado para o servico: " + service.getId()
                ));
    }

    private Optional<ServiceRuntime> findRuntimeByAnyAlias(String serviceId) {
        for (String candidateServiceId : ManagedServiceIds.aliasesFor(serviceId)) {
            Optional<ServiceRuntime> runtime =
                    serviceRuntimeRepository.findByServiceId(candidateServiceId);

            if (runtime.isPresent()) {
                return runtime;
            }
        }

        return Optional.empty();
    }

    private void deleteRuntimeByAnyAlias(String serviceId) {
        for (String candidateServiceId : ManagedServiceIds.aliasesFor(serviceId)) {
            serviceRuntimeRepository.deleteByServiceId(candidateServiceId);
        }
    }
}
