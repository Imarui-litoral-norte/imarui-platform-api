package com.logimarui.journey.core;
import com.logimarui.operationalread.core.model.*;
import java.util.*;
public record JourneyViewFilters(String origin, List<String> excludedVehicles, List<String> excludedEmployees,
        List<String> includedEmployees, String expurgeType, String expurgeReason, String expurgeObservation) {
    public JourneyViewFilters {
        if (origin != null && !Set.of("all", "LIVE", "HISTORICO").contains(origin)) throw new IllegalArgumentException("Invalid origin");
    }
    public static String value(Object value) { return value == null ? "__blank__" : value.toString(); }
    private static boolean contains(List<String> values, String value) { return values != null && values.contains(value); }
    private static boolean text(String value, String term) { return term == null || term.isBlank() || value != null && value.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT)); }
    public boolean matches(OperationalContext context, Expurge expurge) {
        return (origin == null || origin.equals("all") || (origin.equals("LIVE") ? "LIVE".equals(context.mapOrigin()) : context.mapOrigin() != null && !"LIVE".equals(context.mapOrigin())))
            && !contains(excludedVehicles, value(context.vehicle()))
            && !contains(excludedEmployees, value(context.employeeCode()))
            && (includedEmployees == null || includedEmployees.isEmpty() || contains(includedEmployees, value(context.employeeCode())))
            && text(expurge == null ? null : expurge.types(), expurgeType)
            && text(expurge == null ? null : expurge.reasons(), expurgeReason)
            && text(expurge == null ? null : expurge.observations(), expurgeObservation);
    }
}
