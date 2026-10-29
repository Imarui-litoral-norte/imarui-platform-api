package com.logimarui.gateway.infra.process;

import com.logimarui.gateway.core.domain.model.ManagedService;
import com.logimarui.gateway.infra.runtime.ManagedServiceIds;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Resolves the watch directory in the supervisor's session, before starting Node. */
final class DatabaseMonitoringLaunchEnvironment {
    private static final String DEFAULT_S_ROOT = "\\\\192.168.0.213\\Files";
    private static final String DEFAULT_MONITOR_PATH = DEFAULT_S_ROOT + "\\Logistica\\0.DPO\\Diretórios_SQL";

    private DatabaseMonitoringLaunchEnvironment() { }

    static void configure(ManagedService service, ProcessBuilder builder) throws IOException {
        if (!ManagedServiceIds.DATABASE_MONITORING.equals(ManagedServiceIds.toCanonical(service.getId()))) return;
        Path workdir = Path.of(service.getWorkingDirectory()).toAbsolutePath().normalize();
        String resolved = resolve(builder.environment(), workdir);
        assertReadableDirectory(Path.of(resolved));
        // dotenv preserves an explicit process environment value. The service's
        // .env stays untouched and interactive npm run dev remains independent.
        builder.environment().put("MONITOR_PATH", resolved);
    }

    static String resolve(Map<String, String> environment, Path workdir) throws IOException {
        String configured = firstNonBlank(environment.get("SERVICE_MONITORING_MONITOR_PATH"),
                environment.get("MONITOR_PATH"));
        if (configured == null) configured = readDotenvValue(workdir.resolve(".env"), "MONITOR_PATH");
        if (configured == null) configured = DEFAULT_MONITOR_PATH;
        configured = configured.trim();

        // Drive mappings are per logon session, including the two UAC tokens of
        // the same user. Never depend on S: being visible to elevated Java/Node.
        if (configured.matches("(?is)^[a-z]:.*")) {
            if (!configured.matches("(?is)^[a-z]:[\\\\/].*")) {
                throw new IllegalStateException("Monitoring: MONITOR_PATH must be absolute; drive-relative paths are unsupported.");
            }
            String drive = configured.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
            String root = firstNonBlank(environment.get("SERVICE_MONITORING_DRIVE_" + drive + "_ROOT"));
            if (root == null && drive.equals("S")) root = DEFAULT_S_ROOT;
            if (root != null) {
                root = root.trim().replace('/', '\\').replaceAll("\\\\+$", "");
                if (!root.matches("^\\\\\\\\[^\\\\]+\\\\[^\\\\]+.*")) {
                    throw new IllegalStateException("Monitoring: drive override must be a UNC share (SERVICE_MONITORING_DRIVE_" + drive + "_ROOT).");
                }
                configured = root + "\\" + configured.substring(3).replace('/', '\\');
            }
        }
        Path path = Path.of(configured);
        if (!path.isAbsolute()) path = workdir.resolve(path);
        return path.normalize().toString();
    }

    static void assertReadableDirectory(Path directory) throws IOException {
        // isDirectory alone can hide permission errors. Enumerate in the actual
        // Gateway token, without writing a probe file into a production dataset.
        try (var entries = Files.newDirectoryStream(directory)) {
            entries.iterator().hasNext();
        } catch (IOException | SecurityException failure) {
            throw new IOException("Monitoring: cannot read MONITOR_PATH in the Gateway session: " + directory
                    + ". Configure an accessible UNC path; an HTTP port alone does not prove the watcher is ready.", failure);
        }
    }

    private static String readDotenvValue(Path file, String key) throws IOException {
        if (!Files.exists(file)) return null;
        String value = null;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.replace("\uFEFF", "").trim();
            if (line.startsWith("export ")) line = line.substring(7).trim();
            int separator = line.indexOf('=');
            if (separator < 0 || !line.substring(0, separator).trim().equals(key)) continue;
            String candidate = line.substring(separator + 1).trim();
            if (candidate.startsWith("\"") || candidate.startsWith("'")) {
                char quote = candidate.charAt(0);
                int end = candidate.indexOf(quote, 1);
                if (end < 0) throw new IOException("Monitoring: unclosed MONITOR_PATH quote in .env.");
                candidate = candidate.substring(1, end);
            } else {
                int comment = candidate.indexOf('#');
                if (comment >= 0) candidate = candidate.substring(0, comment).trim();
            }
            value = firstNonBlank(candidate);
        }
        return value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }
}
