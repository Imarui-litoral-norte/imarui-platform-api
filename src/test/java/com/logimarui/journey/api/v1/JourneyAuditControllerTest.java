package com.logimarui.journey.api.v1;
import com.logimarui.journey.core.JourneyAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import com.logimarui.journey.core.model.JourneyAudit;
import java.util.Map;
import java.util.List;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class JourneyAuditControllerTest {
    @Test void forwardsPaginationAndSharedPopulationFilters() throws Exception {
        var service = mock(JourneyAuditService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new JourneyAuditController(service)).setControllerAdvice(new JourneyExceptionHandler()).build();
        mvc.perform(get("/api/v1/journey/history/ti/maps").param("from","2026-10-01").param("to","2026-10-31")
                .param("mode","ponto").param("populationRole","ajudante").param("search","Equipe").param("page","2").param("pageSize","10"))
                .andExpect(status().isOk());
        verify(service).audit(eq("ti"), any(), eq("Equipe"), eq("all"), eq("ajudante"), eq(2), eq(10), eq("all"), isNull());
        mvc.perform(get("/api/v1/journey/history/ti/maps").param("from","2026-10-31").param("to","2026-10-01"))
                .andExpect(status().isBadRequest());
    }
    @Test void acceptsReadOnlyColumnSelectionWithoutPuttingLongValueListsInTheUrl() throws Exception {
        var service = mock(JourneyAuditService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new JourneyAuditController(service)).setControllerAdvice(new JourneyExceptionHandler()).build();
        mvc.perform(post("/api/v1/journey/history/ti/maps").param("from","2026-10-01").param("to","2026-10-31").param("mode","ponto")
                .contentType("application/json").content("{\"filters\":{\"driver\":[\"Pessoa 100\"]},\"sortBy\":\"map\",\"sortDirection\":\"asc\"}"))
                .andExpect(status().isOk());
        verify(service).audit(eq("ti"), any(), eq(""), eq("all"), eq("motorista"), eq(1), eq(25), eq("all"),
                eq(new JourneyAudit.TableQuery(Map.of("driver", List.of("Pessoa 100")), "map", "asc")));
    }
}
