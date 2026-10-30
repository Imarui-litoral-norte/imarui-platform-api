package com.logimarui.receivables.core.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Financial read projections; no persistence entities or framework types. */
public final class Receivables {
    private Receivables() {}
    public record Key(int unit, int customer, long title, String installment) {}
    public record OpenTitle(Key key, String customerName, LocalDate issuedOn, LocalDate deliveredOn,
            LocalDate dueOn, BigDecimal originalAmount, BigDecimal pendingAmount, Integer seller,
            Integer supervisor, Integer sourceRiskClass, String collectionStatus, String lastContact) {}
    public record Payment(int unit, int customer, long title, String installment, LocalDate dueOn,
            LocalDate paidOn, BigDecimal amount, BigDecimal interest, BigDecimal netAmount,
            int duplicateRows) {}
    public record History(String status, int events, int distinctTitles, int lateEvents,
            int recurringLateTitles, BigDecimal latePercent, Integer maximumDelayDays,
            LocalDate firstPaymentOn, LocalDate lastPaymentOn) {}
    public record Item(OpenTitle title, String status, Integer daysOverdue, String priority,
            List<String> reasons, History history) {}
    public record Summary(int titles, int customers, BigDecimal pendingAmount,
            int overdueTitles, BigDecimal overdueAmount, int dueTodayTitles, BigDecimal dueTodayAmount,
            int upcomingTitles, BigDecimal upcomingAmount, int preventiveTitles, int unknownDueTitles) {}
    public record Coverage(int titles, LocalDate firstIssuedOn, LocalDate lastIssuedOn,
            LocalDate firstDueOn, LocalDate lastDueOn) {}
    public record Policy(int upcomingDays, int recentDays, int minimumHistoryTitles, int recurringLateTitles) {
        public Policy {
            if (upcomingDays < 1 || upcomingDays > 90 || recentDays < 1 || recentDays > 730
                    || minimumHistoryTitles < 1 || recurringLateTitles < 1)
                throw new IllegalArgumentException("Invalid receivables priority policy");
        }
    }
    public record Metadata(Instant queriedAt, Instant sourceUpdatedAt, LocalDate asOf,
            LocalDate historyFrom, LocalDate historyTo, String historyStatus,
            int paymentEvents, int duplicatePaymentRows, int invalidPaymentEvents,
            List<Integer> historyUnits, List<String> warnings, Coverage portfolioCoverage, Policy policy) {}
    public record Filters(List<Integer> units, List<Integer> sellers, List<Integer> supervisors) {}
    public record Report(Summary summary, List<Item> items, int page, int size, int total,
            int totalPages, Metadata metadata, Filters filters) {}
    public record Export(List<Item> items, Metadata metadata) {}
    public record Detail(Item selected, List<Item> customerTitles, List<Payment> payments,
            int paymentPage, int paymentSize, int totalPayments, Metadata metadata) {}
    public record SourceHistory(String status, List<Payment> payments) {}
}

