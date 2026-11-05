package com.logimarui.journey.core;

import java.time.LocalDate;
import java.util.Set;

public record JourneyQuery(
        LocalDate from,
        LocalDate to,
        String mode,
        Long map,
        Long employeeCode,
        String role,
        String expurge,
        JourneyViewFilters viewFilters
) {
    public JourneyQuery(LocalDate from, LocalDate to, String mode, Long map, Long employeeCode, String role, String expurge) {
        this(from, to, mode, map, employeeCode, role, expurge, null);
    }
    private static final Set<String> MODES = Set.of("ponto", "mpd");
    private static final Set<String> ROLES = Set.of("motorista", "ajudante");
    private static final Set<String> EXPURGE_FILTERS = Set.of("all", "expurged", "not_expurged");

    public JourneyQuery {
        if (from == null || to == null) throw new IllegalArgumentException("from and to are required");
        if (to.isBefore(from)) throw new IllegalArgumentException("to must be on or after from");
        if (mode != null && !MODES.contains(mode)) throw new IllegalArgumentException("Invalid mode: " + mode);
        if (map != null && map < 1) throw new IllegalArgumentException("map must be positive");
        if (employeeCode != null && employeeCode < 1) {
            throw new IllegalArgumentException("employeeCode must be positive");
        }
        if (role != null && !ROLES.contains(role)) throw new IllegalArgumentException("Invalid role: " + role);
        if (expurge == null) expurge = "all";
        if (!EXPURGE_FILTERS.contains(expurge)) {
            throw new IllegalArgumentException("Invalid expurge: " + expurge);
        }
    }

    public JourneyQuery requireMode() {
        if (mode == null) throw new IllegalArgumentException("mode is required");
        return this;
    }
}
