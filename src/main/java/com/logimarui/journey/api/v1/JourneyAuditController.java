package com.logimarui.journey.api.v1;

import com.logimarui.journey.core.JourneyAuditService;
import com.logimarui.journey.core.JourneyQuery;
import com.logimarui.journey.core.model.JourneyAudit;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/journey/history")
public class JourneyAuditController {
    private final JourneyAuditService service;
    public JourneyAuditController(JourneyAuditService service) { this.service = service; }
    @RequestMapping(value = "/{indicator}/maps", method = {RequestMethod.GET, RequestMethod.POST})
    public JourneyAudit maps(@PathVariable String indicator,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String mode, @RequestParam(required = false) Long map,
            @RequestParam(required = false) Long employeeCode, @RequestParam(required = false) String role,
            @RequestParam(required = false) String expurge, @RequestParam(required = false) String viewFilters, @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "all") String scope, @RequestParam(defaultValue = "motorista") String populationRole,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "25") int pageSize,
            @RequestParam(defaultValue = "all") String auditFilter,
            @RequestBody(required = false) JourneyAudit.TableQuery tableQuery) {
        return service.audit(indicator, new JourneyQuery(from, to, mode, map, employeeCode, role, expurge, JourneyViewFilterParser.parse(viewFilters)),
                search, scope, populationRole, page, pageSize, auditFilter, tableQuery);
    }
}
