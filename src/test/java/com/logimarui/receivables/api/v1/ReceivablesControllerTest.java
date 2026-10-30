package com.logimarui.receivables.api.v1;

import com.logimarui.receivables.core.*;
import com.logimarui.receivables.core.model.Receivables.*;
import com.logimarui.receivables.core.port.ReceivablesReadRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReceivablesControllerTest {
    private final ReceivablesReadRepository repository = mock(ReceivablesReadRepository.class);
    private final ReceivablesService service = new ReceivablesService(repository,
            Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC), new Policy(7, 90, 5, 2));
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new ReceivablesController(service)).setControllerAdvice(new ReceivablesExceptionHandler()).build();

    @Test void bindsOptionalFiltersAndSerializesConsistentEmptyContract() throws Exception {
        when(repository.portfolio()).thenReturn(List.of());
        when(repository.payments(anySet(), any(), any())).thenReturn(new SourceHistory("NO_CUSTOMERS", List.of()));
        mvc.perform(get("/api/v1/receivables/titles"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.titles").value(0))
                .andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.metadata.sourceUpdatedAt").isEmpty())
                .andExpect(jsonPath("$.metadata.historyFrom").value("2026-01-01"));
        mvc.perform(get("/api/v1/receivables/summary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.pendingAmount").value(0));
        mvc.perform(get("/api/v1/receivables/filters")).andExpect(status().isOk()).andExpect(jsonPath("$.units").isArray());
    }
    @Test void exportsCompleteFilteredCsvWithBrazilianFormattingAndEscapedSourceText() throws Exception {
        when(repository.portfolio()).thenReturn(List.of(
                csvTitle(1, "=HYPERLINK(\"bad\");José\nLoja", "10.10"),
                csvTitle(2, "Cliente B", "20.20")));
        when(repository.payments(anySet(), any(), any())).thenReturn(new SourceHistory("EMPTY_PERIOD", List.of()));
        var response = mvc.perform(get("/api/v1/receivables/titles/export").param("page", "99")
                        .param("size", "1").param("status", "OVERDUE").param("sort", "AMOUNT_DESC"))
                .andExpect(status().isOk()).andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"boletos-2026-10-15.csv\""))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
        String csv = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFF\"Unidade\";\"Código do cliente\"");
        assertThat(csv).contains("\"10,10\"", "\"14/10/2026\"", "\"Cobrar vencido\"", "\"Sem histórico do cliente\"");
        assertThat(csv).contains("\"'=HYPERLINK(\"\"bad\"\");José\nLoja\"");
        assertThat(csv.indexOf("Cliente B")).isLessThan(csv.indexOf("HYPERLINK"));
        verify(repository, times(1)).portfolio();
        verify(repository, times(1)).payments(anySet(), any(), any());
    }

    private OpenTitle csvTitle(long number, String name, String amount) {
        return new OpenTitle(new Key(1, 10, number, "0"), name, LocalDate.of(2026, 10, 1), null,
                LocalDate.of(2026, 10, 14), null, new BigDecimal(amount), 7, 9, null, null, null);
    }

    @Test void exportEmptyFiltersAndFailuresKeepTheHttpContract() throws Exception {
        when(repository.portfolio()).thenReturn(List.of());
        when(repository.payments(anySet(), any(), any())).thenReturn(new SourceHistory("NO_CUSTOMERS", List.of()));
        String empty = mvc.perform(get("/api/v1/receivables/titles/export")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(empty.split("\r\n")).hasSize(1);
        mvc.perform(get("/api/v1/receivables/titles/export").accept("text/csv, application/json")
                .param("dueFrom", "invalid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/receivables/titles/export").param("historyTo", "2026-10-16")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/receivables/titles/export").param("priority", "invalid")).andExpect(status().isBadRequest());
        when(repository.portfolio()).thenThrow(new DataAccessResourceFailureException("sensitive database detail"));
        mvc.perform(get("/api/v1/receivables/titles/export").accept("text/csv, application/json")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RECEIVABLES_READ_UNAVAILABLE"));
    }

    @Test void spreadsheetFormulaPrefixesAreProtectedInSourceText() throws Exception {
        var prefixes = List.of("=1+1", "+1+1", "-1+1", "@SUM(1)", "  =1+1", "\tCliente", "\rCliente", "\nCliente");
        when(repository.portfolio()).thenReturn(java.util.stream.IntStream.range(0, prefixes.size())
                .mapToObj(i -> csvTitle(i + 1, prefixes.get(i), "10.10")).toList());
        when(repository.payments(anySet(), any(), any())).thenReturn(new SourceHistory("EMPTY_PERIOD", List.of()));
        String csv = mvc.perform(get("/api/v1/receivables/titles/export")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (String prefix : prefixes) assertThat(csv).contains("\"'" + prefix + "\"");
    }

    @Test void rejectsInvalidDatesPaginationAndUnknownSort() throws Exception {
        mvc.perform(get("/api/v1/receivables/titles").param("dueFrom","invalid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/receivables/titles").param("size","101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/receivables/titles").param("sort","SQL")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/receivables/titles").param("dueFrom","2026-10-20").param("dueTo","2026-10-01")).andExpect(status().isBadRequest());
    }
    @Test void masksSqlFailures() throws Exception {
        when(repository.portfolio()).thenThrow(new DataAccessResourceFailureException("sensitive database detail"));
        mvc.perform(get("/api/v1/receivables/titles")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RECEIVABLES_READ_UNAVAILABLE"));
    }
}

