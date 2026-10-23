package com.logimarui.gateway.infra.runtime;

import com.logimarui.gateway.core.domain.model.ManagedService;
import com.logimarui.gateway.core.domain.model.ServiceType;
import com.logimarui.gateway.core.port.ManagedServiceProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
public class StaticManagedServiceProvider implements ManagedServiceProvider {

    private static final String SAVI_SERVICE_NAME = ManagedServiceIds.SAVI;
    private static final String SAVI_SERVICE_ENV_PREFIX =
            SAVI_SERVICE_NAME.toUpperCase(Locale.ROOT);
    private static final String SAVI_SERVICE_DIRECTORY =
            SAVI_SERVICE_NAME.replace('_', '-');
    private static final String SAVI_SERVICE_COMMAND =
            "python -m " + SAVI_SERVICE_NAME + " api --host 127.0.0.1";

    private record ServiceDefinition(
            String environmentPrefix,
            String defaultId,
            String pathPrefix,
            ServiceType type,
            Path workingDirectory,
            String startCommand,
            String stopCommand,
            int developmentPort,
            int productionPort,
            boolean requiresAuthentication,
            String portEnvironmentVariable,
            boolean startOnBoot,
            boolean enabled
    ) {
    }

    private final List<ManagedService> services;

    public StaticManagedServiceProvider(Environment environment) {
        boolean developmentProfile = isDevelopmentProfile(environment);
        Path workspaceRoot = resolveWorkspaceRoot(environment.getProperty("LOGIMARUI_ROOT", ""));
        Path servicesRoot = resolveServicesRoot(
                environment.getProperty("LOGIMARUI_SERVICES_ROOT", ""),
                workspaceRoot
        );
        Path frontendRoot = workspaceRoot.resolve("logImarui-frontend").normalize();

        this.services = List.of(
                new ServiceDefinition("SERVICE_IIAM", ManagedServiceIds.IIAM, "/api/iam", ServiceType.JAVA, servicesRoot.resolve("imarui-identity-and-access-manager-service"), "java -jar", null, 4105, 4005, false, "SERVER_PORT", false, false),
                new ServiceDefinition("SERVICE_EXTRATOR", ManagedServiceIds.EXTRACTION, "/api/extrator", ServiceType.PYTHON, servicesRoot.resolve("extrator-manager"), "python app.py", null, 4100, 4000, true, "PORT", true, true),
                new ServiceDefinition("SERVICE_MONITORING", ManagedServiceIds.DATABASE_MONITORING, "/api/monitoring", ServiceType.NODE, servicesRoot.resolve("database-monitoring"), "npm start", null, 4101, 4001, true, "PORT", true, true),
                new ServiceDefinition("SERVICE_BACKUP", ManagedServiceIds.DATABASE_BACKUP, "/api/backup", ServiceType.NODE, servicesRoot.resolve("banco-de-dados").resolve("backup-runner"), "npm start", null, 4102, 4002, true, "PORT", true, true),
                new ServiceDefinition(SAVI_SERVICE_ENV_PREFIX, SAVI_SERVICE_NAME, "/api/savi", ServiceType.PYTHON, servicesRoot.resolve(SAVI_SERVICE_DIRECTORY), SAVI_SERVICE_COMMAND, null, 4103, 4003, true, "PORT", true, true),
                new ServiceDefinition("SERVICE_N8N", ManagedServiceIds.N8N, "/api/n8n", ServiceType.DOCKER, servicesRoot.resolve("n8n"), "docker compose up -d", "docker compose down", 5679, 5678, true, "N8N_LOCAL_PORT", false, false),
                new ServiceDefinition("SERVICE_EVOLUTION", ManagedServiceIds.EVOLUTION, "/api/evolution-api", ServiceType.DOCKER, servicesRoot.resolve("evolution"), "docker compose --env-file .env -p evolution -f docker-compose.yaml up -d", "docker compose --env-file .env -p evolution -f docker-compose.yaml down", 4081, 4080, true, "EVOLUTION_API_PORT", false, false),
                new ServiceDefinition("SERVICE_FRONTEND", ManagedServiceIds.FRONTEND, "/", ServiceType.NODE, frontendRoot, "npm run build-start", null, 8191, 8091, true, "FRONTEND_PORT", true, true)
        ).stream().map(definition -> buildService(environment, definition, developmentProfile)).toList();
    }

    private boolean isDevelopmentProfile(Environment environment) {
        String profile = normalizeString(environment.getProperty("LOGIMARUI_PROFILE"));

        if (!profile.isBlank()) {
            return "dev".equalsIgnoreCase(profile) || "development".equalsIgnoreCase(profile);
        }

        return parseBoolean(environment.getProperty("LOGIMARUI_DEV_MODE"), false);
    }

    @Override
    public List<ManagedService> findAll() {
        return services;
    }

    @Override
    public Optional<ManagedService> findById(String id) {
        String canonicalId = ManagedServiceIds.toCanonical(id);

        return services.stream()
                .filter(service -> service.getId().equals(canonicalId))
                .findFirst();
    }

    private ManagedService buildService(
            Environment environment,
            ServiceDefinition definition,
            boolean developmentProfile
    ) {
        String envPrefix = definition.environmentPrefix();
        String rawServiceId = normalizeString(environment.getProperty(envPrefix + "_ID"));
        String resolvedServiceId = rawServiceId.isBlank()
                ? definition.defaultId()
                : ManagedServiceIds.toCanonical(rawServiceId);

        return new ManagedService(
                resolvedServiceId,
                environment.getProperty(envPrefix + "_PATH_PREFIX", definition.pathPrefix()),
                parseServiceType(environment.getProperty(envPrefix + "_TYPE"), definition.type()),
                environment.getProperty(
                        envPrefix + "_WORKDIR",
                        definition.workingDirectory().toString()
                ),
                environment.getProperty(envPrefix + "_COMMAND", definition.startCommand()),
                normalizeNullableString(environment.getProperty(envPrefix + "_STOP_COMMAND"), definition.stopCommand()),
                parseInteger(environment.getProperty(envPrefix + "_PREFERRED_PORT"), developmentProfile ? definition.developmentPort() : definition.productionPort()),
                parseBoolean(
                        environment.getProperty(envPrefix + "_REQUIRES_AUTHENTICATION"),
                        definition.requiresAuthentication()
                ),
                environment.getProperty(
                        envPrefix + "_PORT_ENV_VAR",
                        definition.portEnvironmentVariable()
                ),
                parseBoolean(
                        environment.getProperty(envPrefix + "_START_ON_BOOT"),
                        definition.startOnBoot()
                ),
                parseBoolean(
                        environment.getProperty(envPrefix + "_ENABLED"),
                        definition.enabled()
                )
        );
    }

    private Path resolveWorkspaceRoot(String configuredWorkspaceRoot) {
        if (configuredWorkspaceRoot != null && !configuredWorkspaceRoot.isBlank()) {
            return Path.of(configuredWorkspaceRoot).normalize();
        }

        Path currentDirectory = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path currentDirectoryName = currentDirectory.getFileName();

        if (currentDirectoryName != null && "logimarui-core-api".equalsIgnoreCase(currentDirectoryName.toString())) {
            Path parent = currentDirectory.getParent();

            if (parent != null) {
                return parent.normalize();
            }
        }

        return currentDirectory;
    }

    private Path resolveServicesRoot(String configuredServicesRoot, Path workspaceRoot) {
        if (configuredServicesRoot != null && !configuredServicesRoot.isBlank()) {
            return Path.of(configuredServicesRoot).normalize();
        }

        return workspaceRoot.resolve("logimarui_services").normalize();
    }

    private ServiceType parseServiceType(String rawValue, ServiceType fallback) {
        String normalizedValue = normalizeString(rawValue);

        if (normalizedValue.isBlank()) {
            return fallback;
        }

        try {
            return ServiceType.valueOf(normalizedValue.toUpperCase());
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private int parseInteger(String rawValue, int fallback) {
        String normalizedValue = normalizeString(rawValue);

        if (normalizedValue.isBlank()) {
            return fallback;
        }

        try {
            return Integer.parseInt(normalizedValue);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private boolean parseBoolean(String rawValue, boolean fallback) {
        String normalizedValue = normalizeString(rawValue);

        if (normalizedValue.isBlank()) {
            return fallback;
        }

        return Boolean.parseBoolean(normalizedValue);
    }

    private String normalizeString(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeNullableString(String value, String fallback) {
        String normalizedValue = normalizeString(value);
        return normalizedValue.isBlank() ? fallback : normalizedValue;
    }
}
