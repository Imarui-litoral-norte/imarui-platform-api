package com.logimarui.gateway.infra.runtime;

import java.util.List;
import java.util.Map;

public final class ManagedServiceIds {

    public static final String EXTRACTION = "gerenciador-extracao";
    public static final String DATABASE_MONITORING = "gerenciador-database-monitoring";
    public static final String DATABASE_BACKUP = "gerenciador-database-backup";
    public static final String SAVI = "imarui_savi_automation_service";
    public static final String N8N = "n8n-interno";
    public static final String EVOLUTION = "evolution-interno";
    public static final String IIAM = "iiam";
    public static final String FRONTEND = "frontend";

    private static final Map<String, String> LEGACY_TO_CANONICAL = Map.of(
            "extrator-manager", EXTRACTION,
            "database-monitoring", DATABASE_MONITORING,
            "backup-manager", DATABASE_BACKUP,
            "automacao-savi", SAVI,
            "n8n", N8N,
            "evolution-api", EVOLUTION
    );

    private static final Map<String, List<String>> CANONICAL_TO_ALIASES = Map.of(
            EXTRACTION, List.of(EXTRACTION, "extrator-manager"),
            DATABASE_MONITORING, List.of(DATABASE_MONITORING, "database-monitoring"),
            DATABASE_BACKUP, List.of(DATABASE_BACKUP, "backup-manager"),
            SAVI, List.of(SAVI, "automacao-savi"),
            N8N, List.of(N8N, "n8n"),
            EVOLUTION, List.of(EVOLUTION, "evolution-api"),
            FRONTEND, List.of(FRONTEND)
    );

    private ManagedServiceIds() {
    }

    public static String toCanonical(String serviceId) {
        if (serviceId == null || serviceId.isBlank()) {
            return serviceId;
        }

        return LEGACY_TO_CANONICAL.getOrDefault(serviceId, serviceId);
    }

    public static List<String> aliasesFor(String serviceId) {
        String canonicalId = toCanonical(serviceId);

        return CANONICAL_TO_ALIASES.getOrDefault(canonicalId, List.of(canonicalId));
    }
}
