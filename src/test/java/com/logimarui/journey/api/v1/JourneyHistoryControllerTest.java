package com.logimarui.journey.api.v1;

import com.logimarui.journey.core.JourneyHistoryService;
import com.logimarui.journey.core.model.JourneyHistory;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.LocalDate;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class JourneyHistoryControllerTest {
    private final JourneyHistoryService service = mock(JourneyHistoryService.class);
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new JourneyHistoryController(service)).setControllerAdvice(new JourneyExceptionHandler()).build();
    @Test void serializesCompactNullableContractAndForwardsSharedFilters() throws Exception {
        var day = LocalDate.of(2026, 10, 6);
        var empty = new JourneyHistory.Counts(0, 0, 0, 0, 0, 0, null, 0, 0, 0);
        when(service.history(anyString(), any(), anyString(), anyString(), anyBoolean(), anyString())).thenReturn(
                new JourneyHistory(day, day, day, day, day, null, "MAP", "motorista", false, empty, List.of(), List.of(), List.of()));
        mvc.perform(get("/api/v1/journey/history/tr").param("from", "2026-10-06").param("to", "2026-10-06")
                .param("search", "joao").param("scope", "earlier").param("includePrevious", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.adherence").isEmpty())
                .andExpect(jsonPath("$.previousYearDaily").isArray());
        verify(service).history(eq("tr"), any(), eq("joao"), eq("earlier"), eq(false), eq("motorista"));
    }
    @Test void validatesBindingAndReturnsSafeReadFailures() throws Exception {
        mvc.perform(get("/api/v1/journey/history/tr").param("to", "2026-10-06")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/journey/history/tr").param("from", "2026-10-07").param("to", "2026-10-06"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_JOURNEY_REQUEST"));
        when(service.history(anyString(), any(), anyString(), anyString(), anyBoolean(), anyString())).thenThrow(new DataAccessResourceFailureException("secret"));
        mvc.perform(get("/api/v1/journey/history/tr").param("from", "2026-10-06").param("to", "2026-10-06"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.message").value("Unable to read journey data"));
    }
}
