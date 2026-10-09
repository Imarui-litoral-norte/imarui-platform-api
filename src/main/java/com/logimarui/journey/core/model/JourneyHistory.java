package com.logimarui.journey.core.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Compact analytical projection; official outcomes remain owned by the V2 readers. */
public record JourneyHistory(
        LocalDate from, LocalDate to, LocalDate contextFrom, LocalDate contextTo,
        LocalDate asOf, LocalDateTime snapshotAt, String population, String role,
        boolean hasLiveOrigin, Counts summary, List<Day> daily,
        List<Collaborator> collaborators, List<Day> previousYearDaily
) {
    public record Counts(long total, long attained, long notAttained, long pending,
                         long unavailable, long evaluated, Double adherence,
                         long distinctMaps, long expurged, long anomalies) {}
    public record Day(LocalDate date, Counts counts) {}
    public record Collaborator(Long employeeCode, String employeeName, String role, Counts counts) {}
}
