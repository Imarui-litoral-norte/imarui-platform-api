package com.logimarui.receivables.core;

import com.logimarui.receivables.core.model.Receivables.*;
import com.logimarui.receivables.core.port.ReceivablesReadRepository;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ReceivablesServiceTest {
    private final LocalDate today = LocalDate.of(2026, 10, 15);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-15T02:00:00Z"), ZoneOffset.UTC);
    private OpenTitle title(int unit, int customer, long number, LocalDate due, String amount) {
        return new OpenTitle(new Key(unit, customer, number, "0"), "Cliente " + customer,
                today.minusDays(10), null, due, new BigDecimal(amount), new BigDecimal(amount), 7, 9, null, null, null);
    }
    private ReceivablesQuery query(String status, String priority, int page, int size) {
        return new ReceivablesQuery("", null, null, null, null, null, null, null, status, priority, "PRIORITY",
                page, size, LocalDate.of(2026, 1, 1), today.minusDays(1));
    }
    private ReceivablesService service(List<OpenTitle> titles, SourceHistory history) {
        return new ReceivablesService(new ReceivablesReadRepository() {
            public List<OpenTitle> portfolio() { return titles; }
            public SourceHistory payments(Set<Integer> c, LocalDate f, LocalDate t) { return history; }
        }, clock, new Policy(7, 90, 3, 2));
    }
    private Payment payment(int unit, long number, int lateDays, int paidAgo) {
        LocalDate paid = today.minusDays(paidAgo);
        return new Payment(unit, 10, number, "0", paid.minusDays(lateDays), paid,
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, 0);
    }

    @Test void usesSaoPauloDateAndReconcilesAllPagesWithMissingHistory() {
        // 02:00Z is still Oct 14 in São Paulo.
        var s = service(List.of(title(1, 10, 1, today.minusDays(2), "10.10"),
                title(1, 20, 2, today.minusDays(1), "20.20"), title(1, 30, 3, today.plusDays(2), "30.30"),
                title(1, 40, 4, null, "5.00")), new SourceHistory("EMPTY_PERIOD", List.of()));
        var first = s.report(query("ALL", "ALL", 0, 1));
        assertThat(first.metadata().asOf()).isEqualTo(today.minusDays(1));
        assertThat(first.summary().pendingAmount()).isEqualByComparingTo("65.60");
        assertThat(first.total()).isEqualTo(4);
        assertThat(first.totalPages()).isEqualTo(4);
        assertThat(first.summary().overdueTitles()).isEqualTo(1);
        assertThat(first.summary().dueTodayTitles()).isEqualTo(1);
        assertThat(first.summary().unknownDueTitles()).isEqualTo(1);
        assertThat(first.items().get(0).history().status()).isEqualTo("NO_HISTORY");
        assertThat(s.report(query("ALL", "ALL", 1, 1)).summary()).isEqualTo(first.summary());
        assertThat(s.report(query("OVERDUE", "ALL", 0, 25)).summary().pendingAmount()).isEqualByComparingTo("10.10");
        assertThat(first.metadata().sourceUpdatedAt()).isNull();
    }

    @Test void recurrenceUsesDistinctTitlesAndNeverCrossesUnits() {
        var events = List.of(payment(1, 100, 2, 10), payment(1, 101, 3, 8), payment(1, 102, 0, 5));
        var s = service(List.of(title(1, 10, 1, today.plusDays(2), "100"), title(2, 10, 2, today.plusDays(2), "200")),
                new SourceHistory("AVAILABLE", events));
        var result = s.report(query("ALL", "ALL", 0, 25));
        assertThat(result.items().get(0).priority()).isEqualTo("PREVENTIVE");
        assertThat(result.items().get(0).history().latePercent()).isEqualByComparingTo("66.67");
        assertThat(result.items().get(1).priority()).isEqualTo("DUE_SOON");
        assertThat(result.items().get(1).history().status()).isEqualTo("UNIT_UNMAPPED");
        assertThat(result.summary().preventiveTitles()).isEqualTo(1);
        assertThat(result.metadata().warnings()).anyMatch(w -> w.contains("outra unidade"));
    }

    @Test void partialPaymentsOnSameTitleAreNotRecurrenceAndSmallSamplesAreUnknown() {
        var payments = List.of(payment(1, 100, 2, 10), payment(1, 100, 3, 8), payment(1, 100, 1, 5));
        var s = service(List.of(title(1, 10, 1, today, "100")), new SourceHistory("AVAILABLE", payments));
        var item = s.report(query("ALL", "ALL", 0, 10)).items().get(0);
        assertThat(item.history().events()).isEqualTo(3);
        assertThat(item.history().distinctTitles()).isEqualTo(1);
        assertThat(item.history().recurringLateTitles()).isEqualTo(1);
        assertThat(item.history().status()).isEqualTo("INSUFFICIENT");
        assertThat(item.priority()).isEqualTo("DUE_SOON");
    }

    @Test void detailIsIdentityScopedAndPaginatedWithSafeNotFound() {
        var s = service(List.of(title(1, 10, 1, today, "100"), title(1, 10, 2, today, "200"),
                        title(2, 10, 3, today, "300")),
                new SourceHistory("AVAILABLE", List.of(payment(1, 100, 1, 5), payment(1, 101, 0, 4))));
        var detail = s.detail(new Key(1, 10, 1, "0"), query("ALL", "ALL", 0, 10), 1, 1);
        assertThat(detail.customerTitles()).hasSize(2);
        assertThat(detail.totalPayments()).isEqualTo(2);
        assertThat(detail.payments()).hasSize(1);
        assertThatThrownBy(() -> s.detail(new Key(9, 10, 1, "0"), query("ALL","ALL",0,10),0,20))
                .isInstanceOf(ReceivableNotFoundException.class);
    }

    @Test void exportUsesEveryFilterAndSortWithoutPagination() {
        var first = title(1, 10, 1, today.minusDays(2), "10.10");
        var second = title(1, 10, 2, today.minusDays(2), "20.20");
        var base = service(List.of(first, second, title(2, 10, 3, today.minusDays(2), "50"),
                title(1, 20, 4, today.minusDays(2), "60"), title(1, 10, 5, today.plusDays(2), "70")),
                new SourceHistory("EMPTY_PERIOD", List.of()));
        var query = new ReceivablesQuery("Cliente 10", 1, 7, 9, today.minusDays(3), today.minusDays(1),
                today.minusDays(11), today.minusDays(9), "OVERDUE", "OVERDUE", "AMOUNT_DESC", 1, 1,
                LocalDate.of(2026, 1, 1), today.minusDays(1));
        var report = base.report(query);
        var export = base.export(query);
        assertThat(report.total()).isEqualTo(2);
        assertThat(report.items()).hasSize(1);
        assertThat(export.items()).extracting(i -> i.title().key().title()).containsExactly(2L, 1L);
        assertThat(export.items().get(1)).isEqualTo(report.items().get(0));
        assertThat(export.metadata()).isEqualTo(report.metadata());
        for (var excluded : List.of(
                new ReceivablesQuery("absent", 1, 7, 9, null, null, null, null, "ALL", "ALL", null, 0, 25, query.historyFrom(), query.historyTo()),
                new ReceivablesQuery("", 1, 8, 9, null, null, null, null, "ALL", "ALL", null, 0, 25, query.historyFrom(), query.historyTo()),
                new ReceivablesQuery("", 1, 7, 8, null, null, null, null, "ALL", "ALL", null, 0, 25, query.historyFrom(), query.historyTo()),
                new ReceivablesQuery("", 1, 7, 9, null, null, today, today, "ALL", "ALL", null, 0, 25, query.historyFrom(), query.historyTo()))) {
            assertThat(base.export(excluded).items()).isEmpty();
        }
    }

    @Test void validatesDatesLimitsAndTreatsHistoryFailureAsUnavailable() {
        assertThatThrownBy(() -> query("INVALID", "ALL", 0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query("ALL", "ALL", 0, 101)).isInstanceOf(IllegalArgumentException.class);
        var report = service(List.of(title(1, 10, 1, today, "10")), new SourceHistory("UNAVAILABLE", List.of()))
                .report(query("ALL", "ALL", 0, 10));
        assertThat(report.items().get(0).history().status()).isEqualTo("UNAVAILABLE");
        assertThat(report.summary().titles()).isEqualTo(1);
    }
}

