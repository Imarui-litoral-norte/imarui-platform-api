package com.logimarui.gateway.infra.process;

import com.logimarui.gateway.core.domain.model.ManagedService;
import com.logimarui.gateway.core.domain.model.ServiceType;
import com.logimarui.gateway.infra.runtime.ManagedServiceIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class DatabaseMonitoringLaunchEnvironmentTest {
    @TempDir Path directory;

    private ManagedService service(String id) {
        return new ManagedService(id, "/api/monitoring", ServiceType.NODE,
                directory.toString(), "npm start", 4001, true);
    }

    @Test void resolvesMappedPathFromDotenvWithoutLosingBackslashesOrAccents() throws Exception {
        Files.writeString(directory.resolve(".env"), "DB_PASSWORD=not-for-child-logs\nMONITOR_PATH=S:\\Logistica\\0.DPO\\Diretórios_SQL\n", StandardCharsets.UTF_8);
        assertThat(DatabaseMonitoringLaunchEnvironment.resolve(Map.of(), directory))
                .isEqualTo(Path.of("\\\\192.168.0.213\\Files\\Logistica\\0.DPO\\Diretórios_SQL").normalize().toString());
    }

    @Test void explicitDeploymentPathWinsOverInheritedAndDotenv() throws Exception {
        Files.writeString(directory.resolve(".env"), "MONITOR_PATH=S:\\old\n");
        Map<String, String> env = Map.of("SERVICE_MONITORING_MONITOR_PATH", directory.toString(),
                "MONITOR_PATH", "S:\\inherited");
        assertThat(DatabaseMonitoringLaunchEnvironment.resolve(env, directory)).isEqualTo(directory.toString());
    }

    @Test void inheritedPathKeepsDotenvPrecedenceAndCanOverrideShareRoot() throws Exception {
        Files.writeString(directory.resolve(".env"), "MONITOR_PATH=S:\\old\n");
        assertThat(DatabaseMonitoringLaunchEnvironment.resolve(Map.of("MONITOR_PATH", "s:/new/path",
                "SERVICE_MONITORING_DRIVE_S_ROOT", "\\\\server\\share\\"), directory))
                .isEqualTo(Path.of("\\\\server\\share\\new\\path").normalize().toString());
    }

    @Test void parsesBomExportQuotesCommentsAndLastDotenvValue() throws Exception {
        Files.writeString(directory.resolve(".env"), "\uFEFFMONITOR_PATH=S:\\old\nexport MONITOR_PATH = 'S:\\carga anual' # comment\n");
        assertThat(DatabaseMonitoringLaunchEnvironment.resolve(Map.of(), directory))
                .isEqualTo(Path.of("\\\\192.168.0.213\\Files\\carga anual").normalize().toString());
    }

    @Test void supportsExplicitLocalDirectoriesAndRejectsUnavailableDirectoryBeforeLaunch() throws Exception {
        var builder = new ProcessBuilder();
        builder.environment().put("SERVICE_MONITORING_MONITOR_PATH", directory.toString());
        builder.environment().put("PORT", "4201");
        DatabaseMonitoringLaunchEnvironment.configure(service(ManagedServiceIds.DATABASE_MONITORING), builder);
        assertThat(builder.environment()).containsEntry("MONITOR_PATH", directory.toString()).containsEntry("PORT", "4201");
        builder.environment().put("SERVICE_MONITORING_MONITOR_PATH", directory.resolve("missing").toString());
        assertThatThrownBy(() -> DatabaseMonitoringLaunchEnvironment.configure(service(ManagedServiceIds.DATABASE_MONITORING), builder))
                .hasMessageContaining("cannot read MONITOR_PATH");
    }

    @Test void refusesDriveRelativePathsAndNonUncShareOverrides() {
        assertThatThrownBy(() -> DatabaseMonitoringLaunchEnvironment.resolve(Map.of("MONITOR_PATH", "S:relative"), directory))
                .hasMessageContaining("drive-relative");
        assertThatThrownBy(() -> DatabaseMonitoringLaunchEnvironment.resolve(Map.of("MONITOR_PATH", "S:\\data",
                "SERVICE_MONITORING_DRIVE_S_ROOT", "C:\\other"), directory)).hasMessageContaining("UNC share");
    }

    @Test void doesNotInjectOtherDotenvValuesOrChangeOtherNodeServices() throws Exception {
        Files.writeString(directory.resolve(".env"), "DB_PASSWORD=not-inherited\nMONITOR_PATH=" + directory + "\n");
        var builder = new ProcessBuilder();
        builder.environment().remove("MONITOR_PATH");
        builder.environment().remove("SERVICE_MONITORING_MONITOR_PATH");
        builder.environment().remove("DB_PASSWORD");
        var original = new HashMap<>(builder.environment());
        DatabaseMonitoringLaunchEnvironment.configure(service(ManagedServiceIds.DATABASE_BACKUP), builder);
        assertThat(builder.environment()).isEqualTo(original);
        DatabaseMonitoringLaunchEnvironment.configure(service("database-monitoring"), builder);
        assertThat(builder.environment()).doesNotContainKey("DB_PASSWORD").containsEntry("MONITOR_PATH", directory.toString());
    }
}
