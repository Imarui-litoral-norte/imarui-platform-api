package com.logimarui.gateway.infra.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class JavaServiceProcessRunnerTest {
    @TempDir Path directory;

    @Test
    void doesNotInheritCoreSecretsOrJvmInjectionOptions() throws Exception {
        Path key = Files.writeString(directory.resolve("key.pem"), "test-key-placeholder");
        Path env = Files.writeString(directory.resolve("iiam.properties"),
                "DB_URL=jdbc:postgresql://localhost:5432/isolated_test\nDB_USERNAME=iiam\nDB_PASSWORD=test-only\n"
                        + "JWT_PRIVATE_KEY_FILE=" + key.toString().replace('\\', '/') + "\n");
        var runner = new JavaServiceProcessRunner(null, null,
                new MockEnvironment().withProperty("SERVICE_IIAM_ENV_FILE", env.toString()));
        var child = runner.isolatedEnvironment(Map.of("SystemRoot", "C:/Windows", "DB_PASSWORD", "core-secret",
                "JWT_SECRET", "core-signing-secret", "JAVA_TOOL_OPTIONS", "-javaagent:unexpected.jar"));
        assertEquals("test-only", child.get("DB_PASSWORD"));
        assertEquals("C:/Windows", child.get("SystemRoot"));
        assertFalse(child.containsKey("JWT_SECRET"));
        assertFalse(child.containsKey("JAVA_TOOL_OPTIONS"));
    }

    @Test
    void missingConfigurationDoesNotFallBackToParentDatabase() throws Exception {
        Path env = Files.writeString(directory.resolve("empty.properties"), "");
        var runner = new JavaServiceProcessRunner(null, null,
                new MockEnvironment().withProperty("SERVICE_IIAM_ENV_FILE", env.toString()));
        assertThrows(IllegalStateException.class, () -> runner.isolatedEnvironment(
                Map.of("DB_URL", "jdbc:mysql://legacy", "DB_USERNAME", "legacy", "DB_PASSWORD", "legacy")));
    }
}
