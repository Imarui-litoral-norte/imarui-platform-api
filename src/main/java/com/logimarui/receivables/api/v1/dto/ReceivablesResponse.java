package com.logimarui.receivables.api.v1.dto;

import com.logimarui.receivables.core.model.Receivables;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Public projections: named HTTP contract, independent of physical report columns. */
public final class ReceivablesResponse {
    private ReceivablesResponse() {}
    public record History(String status, int events, int distinctTitles, int lateEvents,
            int recurringLateTitles, BigDecimal latePercent, Integer maximumDelayDays,
            LocalDate firstPaymentOn, LocalDate lastPaymentOn) {}
    public record Title(int unit, int customer, long title, String installment, String customerName,
            LocalDate issuedOn, LocalDate deliveredOn, LocalDate dueOn, BigDecimal originalAmount,
            BigDecimal pendingAmount, Integer seller, Integer supervisor, Integer sourceRiskClass,
            String collectionStatus, String lastContact, String status, Integer daysOverdue,
            String priority, List<String> reasons, History history) {}
    public record Summary(int titles, int customers, BigDecimal pendingAmount, int overdueTitles,
            BigDecimal overdueAmount, int dueTodayTitles, BigDecimal dueTodayAmount, int upcomingTitles,
            BigDecimal upcomingAmount, int preventiveTitles, int unknownDueTitles) {}
    public record Coverage(int titles, LocalDate firstIssuedOn, LocalDate lastIssuedOn, LocalDate firstDueOn, LocalDate lastDueOn) {}
    public record Policy(int upcomingDays, int recentDays, int minimumHistoryTitles, int recurringLateTitles) {}
    public record Metadata(Instant queriedAt, Instant sourceUpdatedAt, LocalDate asOf, LocalDate historyFrom,
            LocalDate historyTo, String historyStatus, int paymentEvents, int duplicatePaymentRows,
            int invalidPaymentEvents, List<Integer> historyUnits, List<String> warnings, Coverage portfolioCoverage, Policy policy) {}
    public record Filters(List<Integer> units, List<Integer> sellers, List<Integer> supervisors) {}
    public record Dashboard(Summary summary, List<Title> items, int page, int size, int total,
            int totalPages, Metadata metadata, Filters filters) {}
    public record SummaryResult(Summary summary, Metadata metadata) {}
    public record Payment(long title, String installment, LocalDate dueOn, LocalDate paidOn,
            BigDecimal amount, BigDecimal interest, BigDecimal netAmount, Integer daysLate, int duplicateRows) {}
    public record Detail(Title selected, List<Title> customerTitles, List<Payment> payments,
            int paymentPage, int paymentSize, int totalPayments, Metadata metadata) {}

    public static Dashboard dashboard(Receivables.Report r) {
        var s = r.summary();
        var summary = new Summary(s.titles(), s.customers(), s.pendingAmount(), s.overdueTitles(), s.overdueAmount(),
                s.dueTodayTitles(), s.dueTodayAmount(), s.upcomingTitles(), s.upcomingAmount(), s.preventiveTitles(), s.unknownDueTitles());
        return new Dashboard(summary, r.items().stream().map(ReceivablesResponse::title).toList(),
                r.page(), r.size(), r.total(), r.totalPages(), metadata(r.metadata()),
                new Filters(r.filters().units(), r.filters().sellers(), r.filters().supervisors()));
    }
    public static Detail detail(Receivables.Detail d) {
        return new Detail(title(d.selected()), d.customerTitles().stream().map(ReceivablesResponse::title).toList(),
                d.payments().stream().map(p -> new Payment(p.title(), p.installment(), p.dueOn(), p.paidOn(), p.amount(),
                        p.interest(), p.netAmount(), p.dueOn() == null || p.paidOn() == null ? null :
                        (int) java.time.temporal.ChronoUnit.DAYS.between(p.dueOn(), p.paidOn()), p.duplicateRows())).toList(),
                d.paymentPage(), d.paymentSize(), d.totalPayments(), metadata(d.metadata()));
    }
    private static Title title(Receivables.Item i) {
        var t = i.title(); var h = i.history();
        return new Title(t.key().unit(), t.key().customer(), t.key().title(), t.key().installment(), t.customerName(),
                t.issuedOn(), t.deliveredOn(), t.dueOn(), t.originalAmount(), t.pendingAmount(), t.seller(), t.supervisor(),
                t.sourceRiskClass(), t.collectionStatus(), t.lastContact(), i.status(), i.daysOverdue(), i.priority(),
                i.reasons(), new History(h.status(), h.events(), h.distinctTitles(), h.lateEvents(), h.recurringLateTitles(),
                        h.latePercent(), h.maximumDelayDays(), h.firstPaymentOn(), h.lastPaymentOn()));
    }
    private static Metadata metadata(Receivables.Metadata m) {
        var c = m.portfolioCoverage(); var p = m.policy();
        return new Metadata(m.queriedAt(), m.sourceUpdatedAt(), m.asOf(), m.historyFrom(), m.historyTo(), m.historyStatus(),
                m.paymentEvents(), m.duplicatePaymentRows(), m.invalidPaymentEvents(), m.historyUnits(), m.warnings(),
                new Coverage(c.titles(), c.firstIssuedOn(), c.lastIssuedOn(), c.firstDueOn(), c.lastDueOn()),
                new Policy(p.upcomingDays(), p.recentDays(), p.minimumHistoryTitles(), p.recurringLateTitles()));
    }
}

