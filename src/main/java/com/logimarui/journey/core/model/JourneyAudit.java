package com.logimarui.journey.core.model;

import com.logimarui.operationalread.core.model.Expurge;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** A page of unique date/map snapshots; contribution counts retain the analytical grain. */
public record JourneyAudit(LocalDate from, LocalDate to, String indicator, String mode, String role,
        LocalDateTime snapshotAt, int page, int pageSize, int totalElements, int totalPages,
        JourneyHistory.Counts summary, List<Row> items, int unfilteredTotal, java.util.Map<String, List<Option>> facets) {
    public record Key(LocalDate date, Long map) {}
    public record SelectedMap(Key key, String origin, LocalDateTime snapshotAt,
            JourneyHistory.Counts counts, List<Long> employeeCodes, List<SelectedMember> members) {}
    public record SelectedMember(Long employeeCode, IndicatorResult result, Expurge expurge) {}
    public record Selection(JourneyHistory.Counts summary, List<SelectedMap> maps) {}
    public record Deliveries(Long count, String source, boolean conflict) {}
    public record Row(LocalDate date, Long map, String origin, LocalDateTime snapshotAt,
            List<String> driverNames, List<String> vehicles, int helpers, List<LocalDateTime> departures,
            List<LocalDateTime> vehicleEntries, Deliveries deliveries,
            JourneyHistory.Counts contribution, List<Member> members, java.util.Map<String, Cell> cells) {}
    public record Cell(String value, String label, Double number) {}
    public record Option(String value, String label, long count) {}
    public record TableQuery(java.util.Map<String, List<String>> filters, String sortBy, String sortDirection) {}
    public record Member(Long employeeCode, String employeeName, String role, boolean included,
            IndicatorResult tml, IndicatorResult tr, IndicatorResult ti, IndicatorResult jl,
            Expurge expurge, boolean conflictingSnapshots) {}
}
