package com.logimarui.journey.core;

import com.logimarui.journey.core.model.*;
import java.math.*;
import java.text.Collator;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/** Facets and sorting always operate on the full selection, before pagination. */
public final class JourneyAuditTable {
    public static final List<String> COLUMNS = List.of("date", "map", "driver", "vehicle", "helpers", "departure", "entry", "tml", "tr", "ti", "jl", "deliveries", "result", "expurge");
    private static final String BLANK = "__blank__";
    private JourneyAuditTable() {}
    public static void validate(JourneyAudit.TableQuery query) {
        if (query == null) return;
        if (query.sortBy() != null && !COLUMNS.contains(query.sortBy())) throw new IllegalArgumentException("Invalid audit sort column");
        if (query.sortDirection() != null && !Set.of("asc", "desc").contains(query.sortDirection())) throw new IllegalArgumentException("Invalid audit sort direction");
        if (query.filters() != null) query.filters().forEach((key, values) -> {
            if (!COLUMNS.contains(key) || values == null || values.stream().anyMatch(Objects::isNull))
                throw new IllegalArgumentException("Invalid audit column filter");
        });
    }
    public static Map<String, List<JourneyAudit.Option>> emptyFacets() {
        Map<String, List<JourneyAudit.Option>> result = new LinkedHashMap<>(); COLUMNS.forEach(key -> result.put(key, List.of())); return result;
    }
    public static JourneyAudit page(LocalDate from, LocalDate to, String indicator, String mode, String role,
            LocalDateTime snapshot, int requestedPage, int pageSize, List<JourneyAudit.Row> source, JourneyAudit.TableQuery query) {
        validate(query);
        Map<String, List<String>> filters = query == null || query.filters() == null ? Map.of() : query.filters();
        Map<String, Set<String>> selection = new HashMap<>(); filters.forEach((key, values) -> selection.put(key, new HashSet<>(values)));
        List<JourneyAudit.Row> rows = source.stream().map(JourneyAuditTable::withCells).toList();
        Map<String, List<JourneyAudit.Option>> facets = new LinkedHashMap<>();
        for (String column : COLUMNS) {
            Map<String, List<JourneyAudit.Cell>> groups = rows.stream().filter(row -> matches(row, selection, column))
                    .map(row -> row.cells().get(column)).collect(Collectors.groupingBy(JourneyAudit.Cell::value));
            List<JourneyAudit.Cell> sorted = groups.values().stream().map(values -> values.get(0)).sorted(cellComparator(false)).toList();
            facets.put(column, sorted.stream().map(cell -> new JourneyAudit.Option(cell.value(), cell.label(), groups.get(cell.value()).size())).toList());
        }
        String sort = query == null || query.sortBy() == null ? "date" : query.sortBy();
        boolean descending = query == null || query.sortDirection() == null || query.sortDirection().equals("desc");
        Comparator<JourneyAudit.Row> comparator = Comparator.comparing(row -> row.cells().get(sort), cellComparator(descending));
        comparator = comparator.thenComparing(JourneyAudit.Row::date, Comparator.reverseOrder()).thenComparing(JourneyAudit.Row::map, Comparator.reverseOrder());
        List<JourneyAudit.Row> filtered = rows.stream().filter(row -> matches(row, selection, null)).sorted(comparator).toList();
        int total = filtered.size(), pages = Math.max(1, (total + pageSize - 1) / pageSize), page = Math.min(requestedPage, pages);
        int start = (page - 1) * pageSize;
        return new JourneyAudit(from, to, indicator, mode, role, snapshot, page, pageSize, total, pages,
                summary(filtered), filtered.subList(start, Math.min(start + pageSize, total)), rows.size(), facets);
    }
    private static boolean matches(JourneyAudit.Row row, Map<String, Set<String>> filters, String except) {
        return filters.entrySet().stream().allMatch(entry -> entry.getKey().equals(except) || entry.getValue().contains(row.cells().get(entry.getKey()).value()));
    }
    private static Comparator<JourneyAudit.Cell> cellComparator(boolean descending) {
        Collator collator = Collator.getInstance(Locale.forLanguageTag("pt-BR"));
        return (a, b) -> {
            if (a.value().equals(BLANK) || b.value().equals(BLANK)) return a.value().equals(b.value()) ? 0 : a.value().equals(BLANK) ? 1 : -1;
            int compared = a.number() != null && b.number() != null ? Double.compare(a.number(), b.number()) : collator.compare(a.value(), b.value());
            return descending ? -compared : compared;
        };
    }
    private static JourneyAudit.Cell cell(String value, String label, Double number) { return new JourneyAudit.Cell(value == null || value.isBlank() ? BLANK : value, value == null || value.isBlank() ? "(Vazios)" : label, number); }
    private static JourneyAudit.Cell text(String value) { return cell(value, value, null); }
    private static JourneyAudit.Cell number(Long value) { return value == null ? text(null) : cell(value.toString(), value.toString(), value.doubleValue()); }
    private static String hours(long value) { return BigDecimal.valueOf(value).divide(BigDecimal.valueOf(3600), 2, RoundingMode.HALF_UP).toPlainString().replace('.', ',') + " h"; }
    private static JourneyAudit.Cell metric(JourneyAudit.Row row, String indicator) {
        var seconds = row.members().stream().filter(JourneyAudit.Member::included).map(member -> switch (indicator) {
            case "tml" -> member.tml(); case "tr" -> member.tr(); case "ti" -> member.ti(); default -> member.jl(); })
                .filter(Objects::nonNull).filter(result -> !Boolean.TRUE.equals(result.temporalOrderAnomaly()) && result.seconds() != null && result.seconds() >= 0)
                .map(IndicatorResult::seconds).distinct().sorted().toList();
        if (seconds.isEmpty()) return text(null);
        String label = hours(seconds.get(0)) + (seconds.size() > 1 ? " – " + hours(seconds.get(seconds.size()-1)) : "");
        return cell(label, label, seconds.get(0).doubleValue());
    }
    private static JourneyAudit.Cell time(List<LocalDateTime> values) {
        String value = values.stream().map(LocalDateTime::toString).collect(Collectors.joining(" / "));
        String label = values.stream().map(t -> t.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))).collect(Collectors.joining(" / "));
        return cell(value, label, null);
    }
    private static JourneyAudit.Row withCells(JourneyAudit.Row row) {
        Map<String, JourneyAudit.Cell> cells = new LinkedHashMap<>();
        cells.put("date", cell(row.date().toString(), row.date().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")), null));
        cells.put("map", number(row.map())); cells.put("driver", text(String.join(" / ", row.driverNames())));
        cells.put("vehicle", text(String.join(" / ", row.vehicles()))); cells.put("helpers", number((long) row.helpers()));
        cells.put("departure", time(row.departures())); cells.put("entry", time(row.vehicleEntries()));
        for (String indicator : List.of("tml", "tr", "ti", "jl")) cells.put(indicator, metric(row, indicator));
        cells.put("deliveries", row.deliveries().conflict() ? text("Divergente") : number(row.deliveries().count()));
        var c = row.contribution();
        List<String> states = new ArrayList<>(); if (c.attained() > 0) states.add("Atingido"); if (c.notAttained() > 0) states.add("Não atingido");
        if (c.pending() > 0) states.add("Em andamento"); if (c.unavailable() > 0) states.add("Sem resultado");
        cells.put("result", text(String.join(" / ", states)));
        var members = row.members().stream().filter(JourneyAudit.Member::included).toList();
        long marked = members.stream().filter(m -> m.expurge() != null && Boolean.TRUE.equals(m.expurge().present())).count();
        cells.put("expurge", text(marked > 0 ? marked + " com expurgo" : members.stream().anyMatch(m -> m.expurge() == null || m.expurge().present() == null) ? "Não informado" : "Sem expurgo"));
        return new JourneyAudit.Row(row.date(), row.map(), row.origin(), row.snapshotAt(), row.driverNames(), row.vehicles(), row.helpers(), row.departures(), row.vehicleEntries(), row.deliveries(), row.contribution(), row.members(), cells);
    }
    private static JourneyHistory.Counts summary(List<JourneyAudit.Row> rows) {
        long total=0, attained=0, failed=0, pending=0, unknown=0, expurged=0, anomalies=0;
        for (var row : rows) { var c=row.contribution(); total+=c.total(); attained+=c.attained(); failed+=c.notAttained(); pending+=c.pending(); unknown+=c.unavailable(); expurged+=c.expurged(); anomalies+=c.anomalies(); }
        long evaluated=attained+failed;
        return new JourneyHistory.Counts(total,attained,failed,pending,unknown,evaluated,evaluated==0?null:attained*100.0/evaluated,rows.size(),expurged,anomalies);
    }
}
