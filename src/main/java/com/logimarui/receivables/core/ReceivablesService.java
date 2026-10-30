package com.logimarui.receivables.core;

import com.logimarui.receivables.core.model.Receivables.*;
import com.logimarui.receivables.core.port.ReceivablesReadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
@Transactional(transactionManager = "readTransactionManager", readOnly = true)
public class ReceivablesService {
    private final ReceivablesReadRepository repository;
    private final Clock clock;
    private final Policy policy;
    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    public ReceivablesService(ReceivablesReadRepository repository, Clock clock, Policy policy) {
        this.repository = repository; this.clock = clock; this.policy = policy;
    }

    public LocalDate today() { return LocalDate.now(clock.withZone(ZONE)); }

    public Report report(ReceivablesQuery query) {
        Snapshot snapshot = snapshot(query);
        List<Item> filtered = filteredItems(snapshot, query);
        int start = (int) Math.min((long) query.page() * query.size(), filtered.size());
        var items = filtered.subList(start, Math.min(start + query.size(), filtered.size()));
        return new Report(summary(filtered), List.copyOf(items), query.page(), query.size(), filtered.size(),
                (filtered.size() + query.size() - 1) / query.size(), snapshot.metadata(), snapshot.filters());
    }

    public Export export(ReceivablesQuery query) {
        Snapshot snapshot = snapshot(query);
        return new Export(filteredItems(snapshot, query), snapshot.metadata());
    }

    private List<Item> filteredItems(Snapshot snapshot, ReceivablesQuery query) {
        return snapshot.items().stream().filter(matches(query)).sorted(comparator(query.sort())).toList();
    }

    public Detail detail(Key key, ReceivablesQuery query, int paymentPage, int paymentSize) {
        if (paymentPage < 0 || paymentPage > 100000 || paymentSize < 1 || paymentSize > 100)
            throw new IllegalArgumentException("Paginação de pagamentos inválida.");
        Snapshot snapshot = snapshot(query);
        Item selected = snapshot.items().stream().filter(i -> i.title().key().equals(key))
                .findFirst().orElseThrow(ReceivableNotFoundException::new);
        var titles = snapshot.items().stream().filter(i -> i.title().key().unit() == key.unit()
                && i.title().key().customer() == key.customer()).sorted(comparator("PRIORITY")).toList();
        var payments = snapshot.payments().stream().filter(p -> p.unit() == key.unit() && p.customer() == key.customer())
                .sorted(Comparator.comparing(Payment::paidOn, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparingLong(Payment::title).thenComparing(Payment::installment)
                        .thenComparing(Payment::dueOn, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Payment::amount, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Payment::netAmount, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        int start = (int) Math.min((long) paymentPage * paymentSize, payments.size());
        return new Detail(selected, titles, payments.subList(start, Math.min(start + paymentSize, payments.size())),
                paymentPage, paymentSize, payments.size(), snapshot.metadata());
    }

    private Snapshot snapshot(ReceivablesQuery query) {
        LocalDate asOf = today();
        if (query.historyTo().isAfter(asOf)) throw new IllegalArgumentException("Histórico não pode incluir datas futuras.");
        List<OpenTitle> portfolio = repository.portfolio();
        Set<Integer> customers = portfolio.stream().map(t -> t.key().customer()).collect(Collectors.toSet());
        SourceHistory source = repository.payments(customers, query.historyFrom(), query.historyTo());
        Map<String, List<Payment>> perCustomer = source.payments().stream().collect(
                Collectors.groupingBy(p -> p.unit() + ":" + p.customer()));
        Set<Integer> historyCustomers = source.payments().stream().map(Payment::customer).collect(Collectors.toSet());
        Map<String, History> histories = new HashMap<>();
        var items = new ArrayList<Item>();
        for (OpenTitle title : portfolio) {
            String customerKey = title.key().unit() + ":" + title.key().customer();
            History history = histories.computeIfAbsent(customerKey, ignored -> history(
                    perCustomer.getOrDefault(customerKey, List.of()), source.status(),
                    historyCustomers.contains(title.key().customer()), asOf));
            items.add(classify(title, history, asOf));
        }
        var warnings = new ArrayList<String>();
        warnings.add("Cobertura limitada às cargas disponíveis; data de atualização da fonte desconhecida.");
        warnings.add("Atraso medido em dias corridos após o vencimento, sem ajuste de feriados; não é probabilidade de inadimplência.");
        if (!"AVAILABLE".equals(source.status())) warnings.add("Histórico: " + source.status() + ".");
        if (items.stream().anyMatch(i -> "UNIT_UNMAPPED".equals(i.history().status())))
            warnings.add("Há pagamentos do cliente em outra unidade: associação não confirmada, histórico não aplicado.");
        int duplicates = source.payments().stream().mapToInt(Payment::duplicateRows).sum();
        int invalid = (int) source.payments().stream().filter(p -> !validPayment(p)).count();
        if (duplicates > 0) warnings.add("Linhas idênticas de pagamentos foram consolidadas; eventos distintos preservados.");
        if (invalid > 0) warnings.add("Pagamentos sem datas/valor válido foram excluídos dos indicadores.");
        Metadata metadata = new Metadata(clock.instant(), null, asOf, query.historyFrom(), query.historyTo(),
                source.status(), source.payments().size(), duplicates, invalid,
                source.payments().stream().map(Payment::unit).distinct().sorted().toList(), List.copyOf(warnings),
                coverage(portfolio), policy);
        Filters filters = new Filters(portfolio.stream().map(t -> t.key().unit()).distinct().sorted().toList(),
                portfolio.stream().map(OpenTitle::seller).filter(Objects::nonNull).distinct().sorted().toList(),
                portfolio.stream().map(OpenTitle::supervisor).filter(Objects::nonNull).distinct().sorted().toList());
        return new Snapshot(List.copyOf(items), source.payments(), metadata, filters);
    }

    private History history(List<Payment> payments, String sourceStatus, boolean otherUnit, LocalDate today) {
        List<Payment> valid = payments.stream().filter(ReceivablesService::validPayment).toList();
        if (valid.isEmpty()) return new History("UNAVAILABLE".equals(sourceStatus) ? "UNAVAILABLE" :
                otherUnit && payments.isEmpty() ? "UNIT_UNMAPPED" : "NO_HISTORY",
                0, 0, 0, 0, null, null, null, null);
        int totalTitles = (int) valid.stream().map(p -> p.title() + ":" + p.installment()).distinct().count();
        int late = (int) valid.stream().filter(p -> p.paidOn().isAfter(p.dueOn())).count();
        int recentLateTitles = (int) valid.stream().filter(p -> p.paidOn().isAfter(p.dueOn())
                && !p.paidOn().isBefore(today.minusDays(policy.recentDays())))
                .map(p -> p.title() + ":" + p.installment()).distinct().count();
        return new History(totalTitles >= policy.minimumHistoryTitles() ? "AVAILABLE" : "INSUFFICIENT",
                valid.size(), totalTitles, late, recentLateTitles,
                BigDecimal.valueOf(late).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(valid.size()), 2, RoundingMode.HALF_UP),
                valid.stream().mapToInt(p -> (int) Math.max(0, ChronoUnit.DAYS.between(p.dueOn(), p.paidOn()))).max().orElse(0),
                valid.stream().map(Payment::paidOn).min(LocalDate::compareTo).orElse(null),
                valid.stream().map(Payment::paidOn).max(LocalDate::compareTo).orElse(null));
    }

    private Item classify(OpenTitle title, History history, LocalDate today) {
        Integer days = title.dueOn() == null ? null : (int) ChronoUnit.DAYS.between(title.dueOn(), today);
        String status = days == null ? "UNKNOWN_DUE" : days > 0 ? "OVERDUE" : days == 0 ? "DUE_TODAY" : "UPCOMING";
        String priority;
        var reasons = new ArrayList<String>();
        if (days == null) { priority = "DATA_REVIEW"; reasons.add("Vencimento ausente; revisar cadastro."); }
        else if (days > 0) { priority = "OVERDUE"; reasons.add("Título vencido há " + days + " dia(s)."); }
        else if (-days <= policy.upcomingDays() && "AVAILABLE".equals(history.status())
                && history.recurringLateTitles() >= policy.recurringLateTitles()) {
            priority = "PREVENTIVE";
            reasons.add("Próximo vencimento e " + history.recurringLateTitles()
                    + " títulos com pagamento após vencimento na janela recente.");
        } else if (-days <= policy.upcomingDays()) {
            priority = "DUE_SOON"; reasons.add(days == 0 ? "Vence hoje." : "Vence nos próximos " + policy.upcomingDays() + " dias.");
        } else { priority = "MONITOR"; reasons.add("Acompanhar próximos vencimentos."); }
        if (!"AVAILABLE".equals(history.status())) reasons.add("Histórico " + history.status() + "; risco não estimado.");
        return new Item(title, status, days, priority, List.copyOf(reasons), history);
    }

    private Predicate<Item> matches(ReceivablesQuery q) {
        return item -> {
            OpenTitle t = item.title();
            String search = q.search().toLowerCase(Locale.ROOT);
            return (search.isEmpty() || (t.customerName() + " " + t.key().customer() + " " + t.key().title()).toLowerCase(Locale.ROOT).contains(search))
                    && (q.unit() == null || q.unit() == t.key().unit())
                    && (q.seller() == null || q.seller().equals(t.seller()))
                    && (q.supervisor() == null || q.supervisor().equals(t.supervisor()))
                    && inRange(t.dueOn(), q.dueFrom(), q.dueTo()) && inRange(t.issuedOn(), q.issuedFrom(), q.issuedTo())
                    && ("ALL".equals(q.status()) || q.status().equals(item.status()))
                    && ("ALL".equals(q.priority()) || q.priority().equals(item.priority()));
        };
    }

    private static boolean inRange(LocalDate date, LocalDate from, LocalDate to) {
        return (from == null && to == null) || date != null && (from == null || !date.isBefore(from))
                && (to == null || !date.isAfter(to));
    }
    private static boolean validPayment(Payment p) {
        return p.dueOn() != null && p.paidOn() != null && p.amount() != null && p.amount().signum() > 0;
    }
    private static Comparator<Item> comparator(String sort) {
        Comparator<Item> due = Comparator.comparing(i -> i.title().dueOn(), Comparator.nullsLast(Comparator.naturalOrder()));
        Comparator<Item> amount = Comparator.comparing((Item i) -> i.title().pendingAmount()).reversed();
        Comparator<Item> primary = switch (sort) {
            case "DUE_ASC" -> due;
            case "AMOUNT_DESC" -> amount;
            case "CUSTOMER_ASC" -> Comparator.comparing(i -> i.title().customerName(), String.CASE_INSENSITIVE_ORDER);
            default -> Comparator.comparingInt((Item i) -> List.of("OVERDUE", "PREVENTIVE", "DUE_SOON", "DATA_REVIEW", "MONITOR").indexOf(i.priority()))
                    .thenComparing(due).thenComparing(amount);
        };
        return primary.thenComparingInt(i -> i.title().key().unit()).thenComparingInt(i -> i.title().key().customer())
                .thenComparingLong(i -> i.title().key().title()).thenComparing(i -> i.title().key().installment());
    }
    private static Summary summary(List<Item> items) {
        var overdue = items.stream().filter(i -> i.status().equals("OVERDUE")).toList();
        var today = items.stream().filter(i -> i.status().equals("DUE_TODAY")).toList();
        var upcoming = items.stream().filter(i -> i.status().equals("UPCOMING")).toList();
        return new Summary(items.size(), (int) items.stream().map(i -> i.title().key().unit() + ":" + i.title().key().customer()).distinct().count(),
                sum(items), overdue.size(), sum(overdue), today.size(), sum(today), upcoming.size(), sum(upcoming),
                (int) items.stream().filter(i -> i.priority().equals("PREVENTIVE")).count(),
                (int) items.stream().filter(i -> i.status().equals("UNKNOWN_DUE")).count());
    }
    private static BigDecimal sum(List<Item> items) {
        return items.stream().map(i -> i.title().pendingAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    private static Coverage coverage(List<OpenTitle> rows) {
        var issued = rows.stream().map(OpenTitle::issuedOn).filter(Objects::nonNull).sorted().toList();
        var due = rows.stream().map(OpenTitle::dueOn).filter(Objects::nonNull).sorted().toList();
        return new Coverage(rows.size(), issued.isEmpty() ? null : issued.get(0), issued.isEmpty() ? null : issued.get(issued.size()-1),
                due.isEmpty() ? null : due.get(0), due.isEmpty() ? null : due.get(due.size()-1));
    }
    private record Snapshot(List<Item> items, List<Payment> payments, Metadata metadata, Filters filters) {}
}

