package com.logimarui.journey.core;

import com.logimarui.journey.core.model.*;
import com.logimarui.journey.core.port.JourneyAuditReadRepository;
import com.logimarui.operationalread.core.model.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class JourneyAuditServiceTest {
    final JourneyHistoryService history = mock(JourneyHistoryService.class);
    final JourneyService journey = mock(JourneyService.class);
    final JourneyAuditReadRepository metadata = mock(JourneyAuditReadRepository.class);
    final JourneyAuditService service = new JourneyAuditService(history, journey, metadata);
    final LocalDate day = LocalDate.of(2026, 10, 1);
    final JourneyHistory.Counts counts = new JourneyHistory.Counts(1, 1, 0, 0, 0, 1, 100.0, 1, 0, 0);
    final IndicatorResult official = new IndicatorResult(null, 7200L, 9000L, "FINALIZADO", "NO_PRAZO", true, false);
    JourneyQuery query() { return new JourneyQuery(day, day, null, null, null, null, "all"); }
    OperationalContext context(long employee, String role) { return new OperationalContext(day, 10L, "LIVE", null,
            employee, role, "Pessoa " + employee, 1L, null, null, 100L, null, null); }
    @Test void enrichesOneMapWithWholeCrewButKeepsOnlySelectedContributionAndOriginalSnapshotResult() {
        var key = new JourneyAudit.Key(day, 10L);
        var selected = new JourneyAudit.SelectedMap(key, "LIVE", day.atTime(20, 0), counts, List.of(100L),
                List.of(new JourneyAudit.SelectedMember(100L, official, new Expurge(false, null, null, null, null, null, null))));
        when(history.auditSelection(anyString(), any(), anyString(), anyString(), anyString())).thenReturn(new JourneyAudit.Selection(counts, List.of(selected)));
        List<TmlItem> tml = new ArrayList<>(); List<TrItem> tr = new ArrayList<>(); List<TiItem> ti = new ArrayList<>(); List<JlItem> jl = new ArrayList<>();
        for (long employee : List.of(100L, 200L, 201L)) {
            var c = context(employee, employee == 100 ? "motorista" : "ajudante");
            var a = mock(TmlItem.class); when(a.context()).thenReturn(c); when(a.tml()).thenReturn(official); tml.add(a);
            var b = mock(TrItem.class); when(b.context()).thenReturn(c); when(b.tr()).thenReturn(new IndicatorResult(null, 9000L, null, "EM_ANDAMENTO", null, null, false));
            when(b.mapDepartureAt()).thenReturn(day.atTime(8, 0)); tr.add(b); tr.add(b);
            var d = mock(TiItem.class); when(d.context()).thenReturn(c); when(d.ti()).thenReturn(official); when(d.vehicleEntryAt()).thenReturn(day.atTime(17, 0)); ti.add(d);
            var e = mock(JlItem.class); when(e.context()).thenReturn(c); when(e.jl()).thenReturn(official); jl.add(e);
        }
        when(journey.tml(any())).thenReturn(new JourneyPeriod<>(day, day, null, tml));
        when(journey.tr(any())).thenReturn(new JourneyPeriod<>(day, day, null, tr));
        when(journey.ti(any())).thenReturn(new JourneyPeriod<>(day, day, null, ti));
        when(journey.jl(any())).thenReturn(new JourneyPeriod<>(day, day, null, jl));
        when(metadata.deliveries(any())).thenReturn(Map.of(key, new JourneyAudit.Deliveries(19L, "v_03_11_40", false)));
        var result = service.audit("tr", query(), "", "all", "motorista", 1, 25);
        assertThat(result.totalElements()).isEqualTo(1);
        var row = result.items().get(0);
        assertThat(row.helpers()).isEqualTo(2);
        assertThat(row.members()).hasSize(3).filteredOn(JourneyAudit.Member::included).hasSize(1);
        assertThat(row.members().get(0).tr()).isEqualTo(official); // Never replace selected verdict with a newer enrichment read.
        assertThat(row.driverNames()).containsExactly("Pessoa 100");
        assertThat(row.departures()).containsExactly(day.atTime(8, 0));
        assertThat(row.vehicleEntries()).containsExactly(day.atTime(17, 0));
        assertThat(row.deliveries().count()).isEqualTo(19L);
        assertThat(row.contribution()).isEqualTo(counts);
    }
    @Test void clampsEmptyPaginationAndSkipsEveryEnrichmentRead() {
        when(history.auditSelection(anyString(), any(), anyString(), anyString(), anyString())).thenReturn(new JourneyAudit.Selection(new JourneyHistory.Counts(0,0,0,0,0,0,null,0,0,0), List.of()));
        var result = service.audit("tr", query(), "", "all", "motorista", 999, 25);
        assertThat(result.page()).isEqualTo(1); assertThat(result.totalPages()).isEqualTo(1); assertThat(result.items()).isEmpty();
        verifyNoInteractions(journey, metadata);
        assertThatThrownBy(() -> service.audit("tr", query(), "", "all", "motorista", 0, 25)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.audit("tr", query(), "", "all", "motorista", 1, 101)).isInstanceOf(IllegalArgumentException.class);
    }
}
