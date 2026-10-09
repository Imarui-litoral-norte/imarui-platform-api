package com.logimarui.journey.core;

import com.logimarui.journey.core.model.*;
import com.logimarui.operationalread.core.model.Expurge;
import com.logimarui.operationalread.core.model.OperationalContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Function;

@Service
public class JourneyHistoryService {
    private final JourneyService journey;
    private final Clock clock;

    @Autowired
    public JourneyHistoryService(JourneyService journey) {
        this(journey, Clock.systemDefaultZone());
    }

    JourneyHistoryService(JourneyService journey, Clock clock) {
        this.journey = journey;
        this.clock = clock;
    }

    public JourneyHistory history(String indicator, JourneyQuery query, String search,
                                  String scope, boolean includePrevious) {
        return history(indicator, query, search, scope, includePrevious, "motorista");
    }

    public JourneyHistory history(String indicator, JourneyQuery query, String search,
                                  String scope, boolean includePrevious, String populationRole) {
        if (!Set.of("tml", "tr", "ti", "jl").contains(indicator))
            throw new IllegalArgumentException("Invalid indicator");
        if (indicator.equals("tr") && query.mode() != null)
            throw new IllegalArgumentException("TR does not support mode");
        if (!indicator.equals("tr")) query.requireMode();
        if (!Set.of("all", "d0", "earlier").contains(scope))
            throw new IllegalArgumentException("Invalid scope");
        LocalDate today = LocalDate.now(clock);
        LocalDate monday = query.from().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate saturday = query.to().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays(5);
        LocalDate first = Collections.min(List.of(monday, query.from().withDayOfMonth(1)));
        LocalDate last = Collections.max(List.of(saturday, query.to().with(TemporalAdjusters.lastDayOfMonth())));
        if (!Set.of("motorista", "ajudante").contains(populationRole))
            throw new IllegalArgumentException("Invalid populationRole");
        String role = query.role() == null ? populationRole : query.role();
        boolean perMap = indicator.equals("tr") || indicator.equals("jl") && "mpd".equals(query.mode());
        List<Fact> readFacts = read(indicator, query, first, min(last, today), role, search, scope, today);
        List<Fact> current = perMap ? coherentMapFacts(readFacts) : readFacts;
        List<Fact> selected = current.stream().filter(f -> within(f.context.date(), query.from(), query.to())).toList();
        var people = new TreeMap<Long, List<Fact>>();
        selected.stream().filter(f -> f.context.employeeCode() != null)
                .forEach(f -> people.computeIfAbsent(f.context.employeeCode(), key -> new ArrayList<>()).add(f));
        List<JourneyHistory.Collaborator> collaborators = people.entrySet().stream().map(entry -> {
            var facts = entry.getValue();
            String name = facts.stream().map(f -> f.context.employeeName()).filter(Objects::nonNull)
                    .filter(n -> !n.isBlank()).reduce((a, b) -> b).orElse("Colaborador #" + entry.getKey());
            // One observation per person's map, even where the TR fact is replicated across the crew.
            return new JourneyHistory.Collaborator(entry.getKey(), name, role, counts(facts, false));
        }).toList();
        List<JourneyHistory.Day> previous = new ArrayList<>();
        if (includePrevious) {
            for (YearMonth month = YearMonth.from(query.from()); !month.isAfter(YearMonth.from(query.to())); month = month.plusMonths(1)) {
                LocalDate end = min(month.atEndOfMonth(), today);
                if (end.isBefore(month.atDay(1))) continue;
                LocalDate priorFirst = month.minusYears(1).atDay(1);
                LocalDate priorLast = end.minusYears(1); // LocalDate clamps February 29 safely.
                var facts = read(indicator, query, priorFirst, priorLast, role, search, scope, today);
                previous.addAll(days(facts, priorFirst, priorLast, perMap));
            }
        }
        LocalDateTime snapshot = current.stream().map(Fact::snapshot).filter(Objects::nonNull)
                .max(LocalDateTime::compareTo).orElse(null);
        return new JourneyHistory(query.from(), query.to(), first, last, today, snapshot,
                perMap ? "MAP" : "MAP_EMPLOYEE", role,
                current.stream().anyMatch(f -> "LIVE".equals(f.context.mapOrigin())),
                counts(selected, perMap), days(current, first, last, perMap), collaborators, List.copyOf(previous));
    }

    /** Reads bounded monthly chunks, discards heavyweight DTOs and keeps only compact facts. */
    private List<Fact> read(String indicator, JourneyQuery filters, LocalDate from, LocalDate to,
                            String role, String search, String scope, LocalDate today) {
        Map<String, Fact> unique = new LinkedHashMap<>();
        long missingIdentity = 0;
        for (LocalDate start = from; !start.isAfter(to);) {
            LocalDate end = min(start.with(TemporalAdjusters.lastDayOfMonth()), to);
            // Search considers the complete crew before selecting the analytical population.
            var query = new JourneyQuery(start, end, filters.mode(), filters.map(), filters.employeeCode(), filters.role(), filters.expurge());
            List<Fact> chunk = switch (indicator) {
                case "tml" -> facts(journey.tml(query).items(), TmlItem::context, TmlItem::snapshotAt, TmlItem::tml, TmlItem::expurge);
                case "tr" -> facts(journey.tr(query).items(), TrItem::context, TrItem::snapshotAt, TrItem::tr, TrItem::expurge);
                case "ti" -> facts(journey.ti(query).items(), TiItem::context, TiItem::snapshotAt, TiItem::ti, TiItem::expurge);
                default -> facts(journey.jl(query).items(), JlItem::context, JlItem::snapshotAt, JlItem::jl, JlItem::expurge);
            };
            for (Fact f : chunk) {
                if (f.context.date() == null || !within(f.context.date(), start, end)) continue;
                String key = identity(f.context);
                if (f.context.map() == null || f.context.employeeCode() == null) key += ":missing:" + missingIdentity++;
                unique.merge(key, f, JourneyHistoryService::newest);
            }
            start = end.plusDays(1);
        }
        List<Fact> all = new ArrayList<>(unique.values());
        String term = normalize(search);
        Set<String> matchingMaps = new HashSet<>();
        if (!term.isEmpty()) all.stream().filter(f -> searchable(f.context).contains(term))
                .forEach(f -> matchingMaps.add(mapIdentity(f.context)));
        return all.stream().filter(f -> role.equalsIgnoreCase(f.context.role()))
                .filter(f -> term.isEmpty() || matchingMaps.contains(mapIdentity(f.context)))
                .filter(f -> scope.equals("all") || "LIVE".equals(f.context.mapOrigin()) &&
                        (scope.equals("d0") ? f.context.date().equals(today) : f.context.date().isBefore(today)))
                .toList();
    }

    private static <T> List<Fact> facts(List<T> items, Function<T, OperationalContext> context,
                                       Function<T, LocalDateTime> snapshot, Function<T, IndicatorResult> result,
                                       Function<T, Expurge> expurge) {
        return items.stream().map(item -> {
            var c = context.apply(item);
            var r = result.apply(item);
            Outcome outcome = Outcome.UNAVAILABLE;
            if (r != null && "EM_ANDAMENTO".equals(r.lifecycleStatus())) outcome = Outcome.PENDING;
            else if (r != null && Set.of("FINALIZADO", "CONCLUIDO").contains(Objects.toString(r.lifecycleStatus(), "")) && r.achieved() != null)
                outcome = r.achieved() ? Outcome.ATTAINED : Outcome.NOT_ATTAINED;
            if (c.map() == null || c.employeeCode() == null) outcome = Outcome.UNAVAILABLE;
            var e = expurge.apply(item);
            return new Fact(c, snapshot.apply(item), outcome, e != null && Boolean.TRUE.equals(e.present()),
                    r != null && Boolean.TRUE.equals(r.temporalOrderAnomaly()));
        }).toList();
    }

    private static Fact newest(Fact a, Fact b) {
        if (a.snapshot != null && b.snapshot != null && !a.snapshot.equals(b.snapshot))
            return a.snapshot.isAfter(b.snapshot) ? a : b;
        if (a.snapshot == null && b.snapshot != null) return b;
        if (b.snapshot == null && a.snapshot != null) return a;
        return combine(a, b);
    }

    private static Fact combine(Fact a, Fact b) {
        return new Fact(a.context, a.snapshot, a.outcome == b.outcome ? a.outcome : Outcome.UNAVAILABLE,
                a.expurged || b.expurged, a.anomaly || b.anomaly);
    }

    /** A replicated map verdict must also stay consistent in each person's analytical series. */
    private static List<Fact> coherentMapFacts(List<Fact> facts) {
        Map<String, Outcome> outcomes = new HashMap<>();
        facts.stream().filter(f -> f.context.map() != null && f.context.employeeCode() != null).forEach(f ->
                outcomes.merge(mapIdentity(f.context), f.outcome, (a, b) -> a == b ? a : Outcome.UNAVAILABLE));
        return facts.stream().map(f -> f.context.map() == null || f.context.employeeCode() == null ? f :
                new Fact(f.context, f.snapshot, outcomes.get(mapIdentity(f.context)), f.expurged, f.anomaly)).toList();
    }

    private static JourneyHistory.Counts counts(List<Fact> source, boolean perMap) {
        Map<String, Fact> units = new LinkedHashMap<>();
        int missing = 0;
        for (Fact f : source) {
            String key = perMap ? mapIdentity(f.context) : identity(f.context);
            if (f.context.map() == null || f.context.employeeCode() == null) key += ":missing:" + missing++;
            units.merge(key, f, JourneyHistoryService::combine);
        }
        long attained = 0, failed = 0, pending = 0, unavailable = 0, expurged = 0, anomalies = 0;
        Set<String> maps = new HashSet<>();
        for (Fact f : units.values()) {
            switch (f.outcome) {
                case ATTAINED -> attained++;
                case NOT_ATTAINED -> failed++;
                case PENDING -> pending++;
                case UNAVAILABLE -> unavailable++;
            }
            if (f.context.map() != null) maps.add(mapIdentity(f.context));
            if (f.expurged) expurged++;
            if (f.anomaly) anomalies++;
        }
        long evaluated = attained + failed;
        return new JourneyHistory.Counts(units.size(), attained, failed, pending, unavailable, evaluated,
                evaluated == 0 ? null : attained * 100.0 / evaluated, maps.size(), expurged, anomalies);
    }

    private static List<JourneyHistory.Day> days(List<Fact> facts, LocalDate from, LocalDate to, boolean perMap) {
        Map<LocalDate, List<Fact>> grouped = new HashMap<>();
        facts.forEach(f -> grouped.computeIfAbsent(f.context.date(), key -> new ArrayList<>()).add(f));
        List<JourneyHistory.Day> result = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1))
            result.add(new JourneyHistory.Day(date, counts(grouped.getOrDefault(date, List.of()), perMap)));
        return List.copyOf(result);
    }

    private static boolean within(LocalDate date, LocalDate from, LocalDate to) {
        return !date.isBefore(from) && !date.isAfter(to);
    }
    private static LocalDate min(LocalDate a, LocalDate b) { return a.isBefore(b) ? a : b; }
    private static String mapIdentity(OperationalContext c) { return c.date() + ":" + c.map(); }
    private static String identity(OperationalContext c) { return mapIdentity(c) + ":" + c.employeeCode(); }
    private static String normalize(String value) {
        return Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }
    private static String searchable(OperationalContext c) {
        return normalize(String.join(" ", Objects.toString(c.map(), ""), Objects.toString(c.date(), ""),
                Objects.toString(c.vehicle(), ""), Objects.toString(c.plate(), ""), Objects.toString(c.fleet(), ""),
                Objects.toString(c.employeeName(), ""), Objects.toString(c.employeeCode(), ""), Objects.toString(c.mapDriverCode(), "")));
    }
    private enum Outcome { ATTAINED, NOT_ATTAINED, PENDING, UNAVAILABLE }
    private record Fact(OperationalContext context, LocalDateTime snapshot, Outcome outcome, boolean expurged, boolean anomaly) {}
}
