package com.logimarui.journey.api.v1;

import com.logimarui.journey.core.JourneyHistoryService;
import com.logimarui.journey.core.JourneyQuery;
import com.logimarui.journey.core.model.JourneyHistory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/journey/history")
@Tag(name = "Journey")
public class JourneyHistoryController {
    private final JourneyHistoryService service;
    public JourneyHistoryController(JourneyHistoryService service) { this.service = service; }

    @GetMapping("/{indicator}")
    @Operation(summary = "Análise compacta por dia e colaborador, com contexto semanal, mensal e ano anterior")
    public JourneyHistory history(
            @PathVariable String indicator,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) Long map,
            @RequestParam(required = false) Long employeeCode,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String expurge,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "true") boolean includePrevious,
            @RequestParam(defaultValue = "motorista") String populationRole
    ) {
        return service.history(indicator, new JourneyQuery(from, to, mode, map, employeeCode, role, expurge),
                search, scope, includePrevious, populationRole);
    }
}
