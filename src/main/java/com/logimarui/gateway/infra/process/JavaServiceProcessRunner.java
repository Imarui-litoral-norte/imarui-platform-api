package com.logimarui.gateway.infra.process;

import com.logimarui.gateway.core.domain.model.*;
import com.logimarui.gateway.core.port.ServiceProcessRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

/** IIAM owns its database and signing material. Only explicitly configured values enter its JVM. */
@Component
@RequiredArgsConstructor
public class JavaServiceProcessRunner implements ServiceProcessRunner {
    private final ProcessTreeTerminator terminator;
    private final ProcessPortAllocator ports;
    private final Environment configuration;

    @Override
    public boolean supports(ManagedService service) {
        return service.getType() == ServiceType.JAVA && "iiam".equals(service.getId());
    }

    @Override
    public ServiceRuntime start(ManagedService service) {
        if (!supports(service)) throw new IllegalArgumentException("Unsupported JVM service");
        Process process = null;
        try {
            Path workingDirectory = Path.of(service.getWorkingDirectory()).toAbsolutePath().normalize();
            Path artifact = workingDirectory.resolve(configuration.getProperty("SERVICE_IIAM_JAR",
                    "target/imarui-iam-service-0.0.1-SNAPSHOT.jar")).normalize();
            if (!Files.isRegularFile(artifact)) throw new IllegalStateException("Build the IIAM artifact before starting it");
            Map<String, String> environment = isolatedEnvironment(System.getenv());
            int port = ports.allocateNearestAvailablePort(service.getPort());
            environment.put("SERVER_PORT", Integer.toString(port));
            environment.put("SERVER_ADDRESS", "127.0.0.1");
            String java = configuration.getProperty("SERVICE_IIAM_JAVA",
                    Path.of(System.getProperty("java.home"), "bin", "java.exe").toString());
            Path log = Path.of(".runtime", "logs", service.getId() + ".log").toAbsolutePath();
            Files.createDirectories(log.getParent());
            ProcessBuilder builder = new ProcessBuilder(java, "-jar", artifact.toString())
                    .directory(workingDirectory.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                    .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
            builder.environment().clear();
            builder.environment().putAll(environment);
            process = builder.start();
            awaitReady(process, port, environment);
            return new ServiceRuntime(service.getId(), port, process.pid(), process.pid(),
                    ServiceStatus.RUNNING, Instant.now());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (process != null) terminator.terminate(process.pid());
            throw new IllegalStateException("Interrupted while starting IIAM", interrupted);
        } catch (IOException | RuntimeException failure) {
            if (process != null) terminator.terminate(process.pid());
            // Do not attach child environment or HTTP response bodies to exceptions.
            throw new IllegalStateException("IIAM startup failed; inspect its private runtime log", failure);
        }
    }

    Map<String, String> isolatedEnvironment(Map<String, String> parent) throws IOException {
        Map<String, String> child = new HashMap<>();
        for (String key : List.of("SystemRoot", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "PATH", "Path")) {
            if (parent.containsKey(key)) child.put(key, parent.get(key));
        }
        String file = configuration.getRequiredProperty("SERVICE_IIAM_ENV_FILE");
        Properties explicit = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(file), StandardCharsets.UTF_8)) {
            explicit.load(reader);
        }
        for (String key : explicit.stringPropertyNames()) child.put(key, explicit.getProperty(key));
        for (String key : List.of("DB_URL", "DB_USERNAME", "DB_PASSWORD", "JWT_PRIVATE_KEY_FILE")) {
            if (child.getOrDefault(key, "").isBlank()) throw new IllegalStateException("Missing IIAM configuration: " + key);
        }
        if (!child.get("DB_URL").startsWith("jdbc:postgresql:"))
            throw new IllegalStateException("IIAM requires a dedicated PostgreSQL database");
        if (!Files.isRegularFile(Path.of(child.get("JWT_PRIVATE_KEY_FILE"))))
            throw new IllegalStateException("IIAM signing key is not available");
        return child;
    }

    private void awaitReady(Process process, int port, Map<String, String> environment)
            throws IOException, InterruptedException {
        String scheme = Boolean.parseBoolean(environment.getOrDefault("SERVER_SSL_ENABLED", "false")) ? "https" : "http";
        URI uri = URI.create(scheme + "://localhost:" + port + "/.well-known/jwks.json");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        Instant deadline = Instant.now().plusSeconds(configuration.getProperty("SERVICE_IIAM_STARTUP_TIMEOUT_SECONDS", Long.class, 120L));
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) throw new IllegalStateException("IIAM exited before readiness");
            try {
                var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    var keys = JsonMapper.builder().build().readTree(response.body()).get("keys");
                    if (keys != null && keys.isArray() && !keys.isEmpty()) return;
                }
            } catch (IOException notReady) {
                // Includes TLS validation failure: never replace trust validation with a permissive client.
            }
            Thread.sleep(300);
        }
        throw new IllegalStateException("IIAM did not become ready within the configured timeout");
    }

    @Override
    public void stop(ManagedService service, ServiceRuntime runtime) {
        // JVM is the root process; do not terminate an unrelated listener or the PostgreSQL service.
        ProcessHandle.of(runtime.getRootPid()).ifPresent(handle -> {
            String executable = handle.info().command().orElse("");
            String[] args = handle.info().arguments().orElse(new String[0]);
            Path expectedArtifact = Path.of(service.getWorkingDirectory()).toAbsolutePath().resolve(
                    configuration.getProperty("SERVICE_IIAM_JAR", "target/imarui-iam-service-0.0.1-SNAPSHOT.jar")).normalize();
            if (!Path.of(executable.isBlank() ? "unknown" : executable).getFileName().toString().equalsIgnoreCase("java.exe")
                    || Arrays.stream(args).noneMatch(arg -> arg.equals(expectedArtifact.toString())))
                throw new IllegalStateException("Refusing to terminate a process without IIAM JVM ownership");
            terminator.terminate(handle.pid());
        });
    }
}
