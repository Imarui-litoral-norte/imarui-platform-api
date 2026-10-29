package com.logimarui.gateway.infra.process;

import com.logimarui.gateway.core.domain.model.ManagedService;
import com.logimarui.gateway.core.domain.model.ServiceRuntime;
import com.logimarui.gateway.core.domain.model.ServiceStatus;
import com.logimarui.gateway.core.domain.model.ServiceType;
import com.logimarui.gateway.core.port.ServiceProcessRunner;
import com.logimarui.gateway.core.port.ServiceRuntimeRepository;
import com.logimarui.gateway.infra.runtime.ManagedServiceIds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class NodeServiceProcessRunner implements ServiceProcessRunner {

    private static final long NODE_STARTUP_TIMEOUT_MILLIS = 180_000;

    private final ProcessTreeTerminator processTreeTerminator;
    private final WindowsPortInspector windowsPortInspector;
    private final ProcessPortAllocator processPortAllocator;
    private final ServiceRuntimeRepository serviceRuntimeRepository;

    @Override
    public boolean supports(ManagedService service) {
        return service.getType() == ServiceType.NODE;
    }

    @Override
    public ServiceRuntime start(ManagedService service) {
        Process process = null;
        int allocatedPort = processPortAllocator.allocateNearestAvailablePort(service.getPort());

        try {
            File logFile = resolveLogFile(service);

            log.info(
                    "[Supervisor] Preparando servico Node: id={} workdir={} portaPreferida={} portaAlocada={} log={}",
                    service.getId(),
                    service.getWorkingDirectory(),
                    service.getPort(),
                    allocatedPort,
                    logFile.getAbsolutePath()
            );

            ProcessBuilder processBuilder = new ProcessBuilder()
                    .command("cmd.exe", "/c", service.getStartCommand())
                    .directory(new File(service.getWorkingDirectory()))
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                    .redirectError(ProcessBuilder.Redirect.appendTo(logFile));

            injectPortEnvironment(service, allocatedPort, processBuilder);
            DatabaseMonitoringLaunchEnvironment.configure(service, processBuilder);
            if (ManagedServiceIds.DATABASE_MONITORING.equals(service.getId())) {
                log.info("[Supervisor] Monitoring: diretorio validado na sessao do Gateway: {}",
                        processBuilder.environment().get("MONITOR_PATH"));
            }

            process = processBuilder.start();
            log.info(
                    "[Supervisor] Processo Node iniciado: id={} pid={} porta={}",
                    service.getId(),
                    process.pid(),
                    allocatedPort
            );

            Long listenerPid = waitForListenerPid(service, process, allocatedPort, logFile);

            log.info(
                    "[Supervisor] Porta do servico Node detectada: id={} pid={} listenerPid={} porta={}",
                    service.getId(),
                    process.pid(),
                    listenerPid,
                    allocatedPort
            );

            return new ServiceRuntime(
                    service.getId(),
                    allocatedPort,
                    process.pid(),
                    listenerPid,
                    ServiceStatus.RUNNING,
                    Instant.now()
            );

        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Erro ao iniciar serviço Node: " + service.getId(),
                    exception
            );
        } catch (RuntimeException exception) {
            if (process != null) {
                log.warn(
                        "[Supervisor] Encerrando processo Node apos falha: id={} pid={} mensagem={}",
                        service.getId(),
                        process.pid(),
                        exception.getMessage()
                );
                processTreeTerminator.terminate(process.pid());
            }

            throw exception;
        }
    }

    @Override
    public void stop(ManagedService service, ServiceRuntime runtime) {
        processTreeTerminator.terminate(runtime.getListenerPid());
        processTreeTerminator.terminate(runtime.getRootPid());
    }

    private Long waitForListenerPid(
            ManagedService service,
            Process process,
            int port,
            File logFile
    ) {
        long deadline = System.currentTimeMillis() + NODE_STARTUP_TIMEOUT_MILLIS;

        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                int exitCode = process.exitValue();
                throw new IllegalStateException(
                        "Servico Node encerrou antes de abrir a porta esperada: " +
                                service.getId() +
                                " port=" + port +
                                " exitCode=" + exitCode +
                                " log=" + logFile.getAbsolutePath()
                );
            }

            Optional<Long> pid = windowsPortInspector.findListeningPidByPort(port);

            if (pid.isPresent()) {
                return pid.get();
            }

            sleep(300);
        }

        throw new IllegalStateException(
                "Servico Node iniciou, mas nao abriu a porta esperada dentro do timeout: " +
                        service.getId() +
                        " port=" + port +
                        " processPid=" + process.pid() +
                        " processAlive=" + process.isAlive() +
                        " log=" + logFile.getAbsolutePath()
        );
    }

    private File resolveLogFile(ManagedService service) {
        File logsDirectory = new File(".runtime", "logs");

        if (!logsDirectory.exists() && !logsDirectory.mkdirs()) {
            throw new IllegalStateException(
                    "Não foi possível criar diretório de logs: " + logsDirectory.getAbsolutePath()
            );
        }

        return new File(logsDirectory, service.getId() + ".log");
    }

    private void injectPortEnvironment(
            ManagedService service,
            int allocatedPort,
            ProcessBuilder processBuilder
    ) {
        String portEnvName = service.getPortEnvironmentVariable();

        if (portEnvName == null || portEnvName.isBlank()) {
            portEnvName = "PORT";
        }

        processBuilder.environment().put(portEnvName, String.valueOf(allocatedPort));
        processBuilder.environment().put("PORT", String.valueOf(allocatedPort));
        processBuilder.environment().put("SERVICE_HOST", "0.0.0.0");
        if (ManagedServiceIds.FRONTEND.equals(service.getId())) {
            serviceRuntimeRepository.findByServiceId(ManagedServiceIds.CRITICA_PEDIDOS)
                    .ifPresent(runtime -> processBuilder.environment().put(
                            "CRITICA_SERVICE_URL", "http://127.0.0.1:" + runtime.getPort()
                    ));
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Thread interrompida ao aguardar porta do serviço.", exception);
        }
    }
}
