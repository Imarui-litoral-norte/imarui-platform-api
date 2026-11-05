package com.logimarui.journey.core;

import com.logimarui.journey.core.model.*;
import com.logimarui.journey.core.port.JourneyAuditReadRepository;
import com.logimarui.operationalread.core.model.Expurge;
import com.logimarui.operationalread.core.model.OperationalContext;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

@Service
public class JourneyAuditService {
    private final JourneyHistoryService history;
    private final JourneyService journey;
    private final JourneyAuditReadRepository metadata;
    public JourneyAuditService(JourneyHistoryService history, JourneyService journey, JourneyAuditReadRepository metadata) {
        this.history = history; this.journey = journey; this.metadata = metadata;
    }
    public JourneyAudit audit(String indicator, JourneyQuery query, String search, String scope, String populationRole,
                              int requestedPage, int pageSize) {
        return audit(indicator, query, search, scope, populationRole, requestedPage, pageSize, "all");
    }
    public JourneyAudit audit(String indicator, JourneyQuery query, String search, String scope, String populationRole,
                              int requestedPage, int pageSize, String auditFilter) {
        return audit(indicator, query, search, scope, populationRole, requestedPage, pageSize, auditFilter, null);
    }
    public JourneyAudit audit(String indicator, JourneyQuery query, String search, String scope, String populationRole,
                              int requestedPage, int pageSize, String auditFilter, JourneyAudit.TableQuery tableQuery) {
        JourneyAuditTable.validate(tableQuery);
        if (!Set.of("all", "not_expurged", "evaluated_not_expurged").contains(auditFilter))
            throw new IllegalArgumentException("Invalid audit filter");
        if (!auditFilter.equals("all")) query = new JourneyQuery(query.from(), query.to(), query.mode(), query.map(),
                query.employeeCode(), query.role(), "not_expurged", query.viewFilters());
        if (requestedPage < 1 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("Invalid audit pagination");
        var selection = auditFilter.equals("evaluated_not_expurged")
                ? history.auditSelection(indicator, query, search, scope, populationRole, true)
                : history.auditSelection(indicator, query, search, scope, populationRole);
        int total = selection.maps().size(), pages = Math.max(1, (total + pageSize - 1) / pageSize);
        int page = Math.min(requestedPage, pages);
        var maps = selection.maps();
        String role = query.role() == null ? populationRole : query.role();
        String mode = query.mode() == null ? "ponto" : query.mode();
        LocalDateTime snapshot = selection.maps().stream().map(JourneyAudit.SelectedMap::snapshotAt)
                .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
        if (maps.isEmpty()) return new JourneyAudit(query.from(), query.to(), indicator, mode, role, snapshot,
                page, pageSize, total, pages, selection.summary(), List.of(), total, JourneyAuditTable.emptyFacets());
        LocalDate from = maps.stream().map(m -> m.key().date()).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate to = maps.stream().map(m -> m.key().date()).max(Comparator.naturalOrder()).orElseThrow();
        // Enrichment reads the complete crew. The selected contributors are kept separately.
        var enrichment = new JourneyQuery(from, to, mode, null, null, null, "all");
        var tml = index(read(enrichment, journey::tml), TmlItem::context, TmlItem::snapshotAt);
        var tr = index(read(new JourneyQuery(from, to, null, null, null, null, "all"), journey::tr), TrItem::context, TrItem::snapshotAt);
        var ti = index(read(enrichment, journey::ti), TiItem::context, TiItem::snapshotAt);
        var jl = index(read(enrichment, journey::jl), JlItem::context, JlItem::snapshotAt);
        var deliveries = metadata.deliveries(maps);
        List<JourneyAudit.Row> rows = maps.stream().map(map -> {
            List<Person> crew = Stream.of(tml, tr, ti, jl).flatMap(index -> index.keySet().stream())
                    .filter(key -> key.map.equals(map.key())).distinct()
                    .sorted(Comparator.comparing((Person p) -> p.employeeCode, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
            List<JourneyAudit.Member> members = crew.stream().map(person -> {
                var a = tml.get(person); var b = tr.get(person); var c = ti.get(person); var d = jl.get(person);
                OperationalContext context = d != null ? d.context : c != null ? c.context : b != null ? b.context : a.context;
                Expurge expurge = switch (indicator) { case "tml" -> a == null ? null : a.item == null ? null : a.item.expurge();
                    case "tr" -> b == null ? null : b.item == null ? null : b.item.expurge();
                    case "ti" -> c == null ? null : c.item == null ? null : c.item.expurge();
                    default -> d == null ? null : d.item == null ? null : d.item.expurge(); };
                var selected = map.members().stream().filter(m -> Objects.equals(m.employeeCode(), context.employeeCode())).findFirst().orElse(null);
                if (selected != null) expurge = selected.expurge();
                return new JourneyAudit.Member(context.employeeCode(), context.employeeName(), context.role(),
                        map.employeeCodes().contains(context.employeeCode()),
                        selected != null && indicator.equals("tml") ? selected.result() : a == null || a.item == null ? null : a.item.tml(),
                        selected != null && indicator.equals("tr") ? selected.result() : b == null || b.item == null ? null : b.item.tr(),
                        selected != null && indicator.equals("ti") ? selected.result() : c == null || c.item == null ? null : c.item.ti(),
                        selected != null && indicator.equals("jl") ? selected.result() : d == null || d.item == null ? null : d.item.jl(), expurge,
                        Stream.of(a, b, c, d).filter(Objects::nonNull).anyMatch(value -> value.conflict));
            }).toList();
            List<String> drivers = members.stream().filter(m -> "motorista".equalsIgnoreCase(m.role()))
                    .map(m -> Objects.toString(m.employeeName(), "Colaborador #" + m.employeeCode())).distinct().toList();
            List<String> vehicles = crew.stream().map(person -> {
                var a = tml.get(person); var b = tr.get(person); var c = ti.get(person); var d = jl.get(person);
                var ctx = d != null ? d.context : c != null ? c.context : b != null ? b.context : a.context;
                return Stream.of(Objects.toString(ctx.vehicle(), ""), Objects.toString(ctx.plate(), ""))
                        .filter(value -> !value.isBlank()).reduce((x, y) -> x + " · " + y).orElse("");
            }).filter(value -> !value.isBlank()).distinct().sorted().toList();
            int helpers = (int) members.stream().filter(m -> "ajudante".equalsIgnoreCase(m.role()) && m.employeeCode() != null).count();
            var departures = crew.stream().map(tr::get).filter(Objects::nonNull).filter(v -> v.item != null)
                    .map(v -> v.item.mapDepartureAt()).filter(Objects::nonNull).distinct().sorted().toList();
            var entries = crew.stream().map(ti::get).filter(Objects::nonNull).filter(v -> v.item != null)
                    .map(v -> v.item.vehicleEntryAt()).filter(Objects::nonNull).distinct().sorted().toList();
            return new JourneyAudit.Row(map.key().date(), map.key().map(), map.origin(), map.snapshotAt(), drivers, vehicles,
                    helpers, departures, entries, deliveries.getOrDefault(map.key(),
                    new JourneyAudit.Deliveries(null, "LIVE".equals(map.origin()) ? "v_03_11_40" : "03_08_05", false)), map.counts(), members, Map.of());
        }).toList();
        return JourneyAuditTable.page(query.from(), query.to(), indicator, mode, role, snapshot,
                requestedPage, pageSize, rows, tableQuery);
    }
    private static <T> List<T> read(JourneyQuery query, Function<JourneyQuery, JourneyPeriod<T>> reader) {
        List<T> rows = new ArrayList<>();
        for (LocalDate from = query.from(); !from.isAfter(query.to());) {
            LocalDate end = from.withDayOfMonth(from.lengthOfMonth());
            if (end.isAfter(query.to())) end = query.to();
            rows.addAll(reader.apply(new JourneyQuery(from, end, query.mode(), null, null, null, "all")).items());
            from = end.plusDays(1);
        }
        return rows;
    }
    private record Person(JourneyAudit.Key map, Long employeeCode) {}
    private record Value<T>(T item, OperationalContext context, LocalDateTime snapshot, boolean conflict) {}
    private static <T> Map<Person, Value<T>> index(List<T> items, Function<T, OperationalContext> context,
                                                 Function<T, LocalDateTime> snapshot) {
        Map<Person, Value<T>> result = new LinkedHashMap<>();
        for (T item : items) {
            var c = context.apply(item); if (c.map() == null) continue;
            var next = new Value<>(item, c, snapshot.apply(item), false);
            result.merge(new Person(new JourneyAudit.Key(c.date(), c.map()), c.employeeCode()), next, (a, b) -> {
                if (a.snapshot == null && b.snapshot != null) return b;
                if (a.snapshot != null && b.snapshot == null) return a;
                if (a.snapshot != null && !a.snapshot.equals(b.snapshot)) return a.snapshot.isAfter(b.snapshot) ? a : b;
                return Objects.equals(a.item, b.item) ? a : new Value<>(null, a.context, a.snapshot, true);
            });
        }
        return result;
    }
}
