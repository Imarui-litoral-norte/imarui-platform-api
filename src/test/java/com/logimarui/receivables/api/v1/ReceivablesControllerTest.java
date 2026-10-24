package com.logimarui.receivables.api.v1;

import com.logimarui.receivables.core.*;
import com.logimarui.receivables.core.model.Receivables.*;
import com.logimarui.receivables.core.port.ReceivablesReadRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.*;
import java.util.List;
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

