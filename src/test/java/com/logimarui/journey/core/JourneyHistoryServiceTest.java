package com.logimarui.journey.core;

import com.logimarui.journey.core.model.*;
import com.logimarui.journey.core.port.JourneyReadRepository;
import com.logimarui.operationalread.core.model.Expurge;
import com.logimarui.operationalread.core.model.OperationalContext;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JourneyHistoryServiceTest {
    private final JourneyReadRepository reader = mock(JourneyReadRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-07T15:00:00Z"), ZoneId.of("America/Sao_Paulo"));
    private final JourneyHistoryService service = new JourneyHistoryService(new JourneyService(reader), clock);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 6);

    @Test void officialClosedOutcomesExcludePendingAndNullAndDeduplicateSnapshots() {
        var success = tr(DAY, 1L, 100L, "motorista", true, "FINALIZADO", false, false);
        var failed = tr(DAY, 2L, 100L, "motorista", false, "FINALIZADO", true, true);
        var open = tr(DAY, 3L, 100L, "motorista", false, "EM_ANDAMENTO", false, false);
        var unknown = tr(DAY, 4L, 100L, "motorista", null, "FINALIZADO", false, false);
        var newer = new TrItem(success.context(), null, null, DAY.atTime(21, 0), null,
                success.tr(), null, success.expurge());
        stubTr(List.of(success, success, newer, failed, open, unknown));
        var result = service.history("tr", query(DAY, DAY, null, null, "all"), "", "all", false);
        assertThat(result.summary().total()).isEqualTo(4);
        assertThat(result.summary().evaluated()).isEqualTo(2);
        assertThat(result.summary().adherence()).isEqualTo(50.0);
        assertThat(result.summary().pending()).isEqualTo(1);
        assertThat(result.summary().unavailable()).isEqualTo(1);
        assertThat(result.summary().expurged()).isEqualTo(1);
        assertThat(result.summary().anomalies()).isEqualTo(1);
        assertThat(result.collaborators().get(0).counts().adherence()).isEqualTo(50.0);
        assertThat(result.snapshotAt()).isEqualTo(DAY.atTime(21, 0));
        assertThat(result.daily().stream().filter(d -> d.date().equals(LocalDate.of(2026, 10, 8))).findFirst().orElseThrow().counts().adherence()).isNull();
    }

    @Test void replicatedTrCountsOncePerMapWhileHelpersHaveTheirOwnParticipation() {
        stubTr(List.of(tr(DAY, 1L, 200L, "ajudante", true, "FINALIZADO", false, false),
                tr(DAY, 1L, 201L, "ajudante", true, "FINALIZADO", true, false),
                tr(DAY, 2L, 200L, "ajudante", false, "FINALIZADO", false, false)));
        var result = service.history("tr", query(DAY, DAY, null, "ajudante", "all"), "", "all", false);
        assertThat(result.population()).isEqualTo("MAP");
        assertThat(result.summary().total()).isEqualTo(2);
        assertThat(result.summary().adherence()).isEqualTo(50.0);
        assertThat(result.collaborators()).hasSize(2);
        assertThat(result.collaborators().get(0).counts().total()).isEqualTo(2);
        assertThat(result.collaborators().get(0).counts().adherence()).isEqualTo(50.0);
    }

    @Test void conflictingReplicasAndMissingIdentitiesAreUnavailable() {
        stubTr(List.of(tr(DAY, 1L, 200L, "ajudante", true, "FINALIZADO", false, false),
                tr(DAY, 1L, 201L, "ajudante", false, "FINALIZADO", false, false),
                tr(DAY, null, 202L, "ajudante", true, "FINALIZADO", false, false)));
        var result = service.history("tr", query(DAY, DAY, null, "ajudante", "all"), "", "all", false);
        assertThat(result.summary().evaluated()).isZero();
        assertThat(result.summary().adherence()).isNull();
        assertThat(result.summary().unavailable()).isEqualTo(2);
        assertThat(result.summary().distinctMaps()).isEqualTo(1);
        assertThat(result.collaborators()).allMatch(person -> person.counts().adherence() == null);
    }

    @Test void searchMatchesAnotherCrewMemberAndExpurgeRemainsExplicit() {
        var driver = tr(DAY, 1L, 100L, "motorista", true, "FINALIZADO", false, false);
        var helper = tr(DAY, 1L, 200L, "ajudante", true, "FINALIZADO", true, false);
        var c = helper.context();
        helper = new TrItem(new OperationalContext(c.date(), c.map(), c.mapOrigin(), null, c.employeeCode(), c.role(),
                "João Souza", null, null, null, 100L, null, null), null, null, helper.snapshotAt(), null, helper.tr(), null, helper.expurge());
        stubTr(List.of(driver, helper));
        assertThat(service.history("tr", query(DAY, DAY, null, null, "all"), "joao", "all", false).summary().total()).isEqualTo(1);
        assertThat(service.history("tr", query(DAY, DAY, null, "motorista", "all"), "joao", "all", false).summary().total()).isZero();
        assertThat(service.history("tr", query(DAY, DAY, null, null, "all"), "", "all", false, "ajudante").role()).isEqualTo("ajudante");
        assertThat(service.history("tr", query(DAY, DAY, null, null, "expurged"), "", "all", false).summary().total()).isZero();
        assertThat(service.history("tr", query(DAY, DAY, null, "ajudante", "expurged"), "", "all", false).summary().expurged()).isEqualTo(1);
    }

    @Test void contextCrossesMonthAndAnnualComparisonUsesSamePartialMonth() {
        stubTr(List.of());
        var result = service.history("tr", query(LocalDate.of(2026, 9, 30), DAY, null, null, "all"), "", "all", true);
        assertThat(result.contextFrom()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(result.contextTo()).isEqualTo(LocalDate.of(2026, 10, 31));
        verify(reader).tr(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        verify(reader).tr(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7));
        verify(reader).tr(LocalDate.of(2025, 9, 1), LocalDate.of(2025, 9, 30));
        verify(reader).tr(LocalDate.of(2025, 10, 1), LocalDate.of(2025, 10, 7));
        assertThat(result.previousYearDaily()).hasSize(37);
    }

    @Test void twelveMonthsUseBoundedReadsAndKeepFutureDaysEmpty() {
        stubTr(List.of());
        var result = service.history("tr", query(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null, null, "all"), "", "all", false);
        verify(reader, times(11)).tr(any(), any()); // Includes the previous December's boundary week.
        assertThat(result.daily()).allMatch(d -> d.counts().adherence() == null);
        verify(reader, never()).tr(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
    }

    @Test void leapDayPriorYearClampsAndSundayWeekCrossesYear() {
        var leapClock = Clock.fixed(Instant.parse("2024-02-29T15:00:00Z"), ZoneOffset.UTC);
        stubTr(List.of());
        var leap = new JourneyHistoryService(new JourneyService(reader), leapClock)
                .history("tr", query(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 2, 29), null, null, "all"), "", "all", true);
        assertThat(leap.previousYearDaily().get(leap.previousYearDaily().size() - 1).date()).isEqualTo(LocalDate.of(2023, 2, 28));
        var sunday = service.history("tr", query(LocalDate.of(2026, 1, 4), LocalDate.of(2026, 1, 4), null, null, "all"), "", "all", false);
        assertThat(sunday.contextFrom()).isEqualTo(LocalDate.of(2025, 12, 29));
    }

    @Test void liveScopeAndEmployeeFilterAreHonored() {
        stubTr(List.of(tr(DAY, 1L, 100L, "motorista", true, "FINALIZADO", false, false),
                tr(DAY.plusDays(1), 2L, 101L, "motorista", true, "FINALIZADO", false, false)));
        var query = query(DAY, DAY.plusDays(1), null, null, "all");
        assertThat(service.history("tr", query, "", "d0", false).summary().distinctMaps()).isEqualTo(1);
        assertThat(service.history("tr", query, "", "earlier", false).summary().distinctMaps()).isEqualTo(1);
        var person = new JourneyQuery(query.from(), query.to(), null, null, 100L, null, "all");
        assertThat(service.history("tr", person, "", "all", false).collaborators()).hasSize(1);
    }

    @Test void allOtherIndicatorsUseTheSameOfficialOutcomeAggregation() {
        var c = tr(DAY, 1L, 100L, "motorista", true, "FINALIZADO", false, false);
        when(reader.tml(any(), any(), any())).thenReturn(List.of(new TmlItem(c.context(), "ponto", null,
                null, null, null, null, null, c.snapshotAt(), c.tr(), null, null, null, c.expurge())));
        when(reader.ti(any(), any(), any())).thenReturn(List.of(new TiItem(c.context(), "ponto", null,
                null, null, null, null, null, null, c.snapshotAt(), null, null, null, c.tr(), null, null, null, c.expurge())));
        when(reader.jl(any(), any(), any())).thenReturn(List.of(new JlItem(c.context(), "ponto", null, null,
                null, null, null, null, null, null, null, null, null, c.snapshotAt(), null,
                null, null, null, null, null, c.tr(), null, null, c.expurge())));
        for (String indicator : List.of("tml", "ti", "jl")) {
            var result = service.history(indicator, query(DAY, DAY, "ponto", null, "all"), "", "all", false);
            assertThat(result.summary().adherence()).isEqualTo(100.0);
            assertThat(result.population()).isEqualTo("MAP_EMPLOYEE");
        }
        assertThat(service.history("jl", query(DAY, DAY, "mpd", null, "all"), "", "all", false).population()).isEqualTo("MAP");
        assertThatThrownBy(() -> service.history("tr", query(DAY, DAY, "mpd", null, "all"), "", "all", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.history("tml", query(DAY, DAY, null, null, "all"), "", "all", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.history("bad", query(DAY, DAY, null, null, "all"), "", "all", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.history("tr", query(DAY, DAY, null, null, "all"), "", "bad", false)).isInstanceOf(IllegalArgumentException.class);
    }

    private void stubTr(List<TrItem> rows) {
        when(reader.tr(any(), any())).thenAnswer(invocation -> rows.stream().filter(r ->
                !r.context().date().isBefore(invocation.getArgument(0, LocalDate.class)) &&
                !r.context().date().isAfter(invocation.getArgument(1, LocalDate.class))).toList());
    }
    private static JourneyQuery query(LocalDate from, LocalDate to, String mode, String role, String expurge) {
        return new JourneyQuery(from, to, mode, null, null, role, expurge);
    }
    private static TrItem tr(LocalDate date, Long map, Long code, String role, Boolean achieved, String state, boolean expurged, boolean anomaly) {
        return new TrItem(new OperationalContext(date, map, "LIVE", null, code, role, "Pessoa " + code,
                1L, "ABC1234", null, 100L, null, null), null, null, date.atTime(20, 0), null,
                new IndicatorResult(null, 999999L, 33600L, state, "ESTOURADO", achieved, anomaly), null,
                new Expurge(expurged, null, null, null, null, null, null));
    }
}
