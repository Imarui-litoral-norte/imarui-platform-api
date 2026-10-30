package com.logimarui.receivables.api.v1;

import com.logimarui.receivables.api.v1.dto.ReceivablesFilterRequest;
import com.logimarui.receivables.api.v1.dto.ReceivablesResponse;
import com.logimarui.receivables.core.ReceivablesService;
import com.logimarui.receivables.core.model.Receivables.Key;
import com.logimarui.platform.openapi.security.RequiredPermission;
import com.logimarui.platform.web.exception.ApiErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/receivables")
@Tag(name = "Receivables", description = "Carteira de boletos e priorização explicável de cobrança")
@PreAuthorize("hasAuthority('logimarui-core.receivables.read')")
@RequiredPermission("logimarui-core.receivables.read")
@ApiResponse(responseCode = "400", description = "Filtros inválidos", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
@ApiResponse(responseCode = "401", description = "Autenticação necessária", content = @Content)
@ApiResponse(responseCode = "403", description = "Permissão logimarui-core.receivables.read necessária", content = @Content)
@ApiResponse(responseCode = "503", description = "Carteira indisponível", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
public class ReceivablesController {
    private final ReceivablesService service;
    public ReceivablesController(ReceivablesService service) { this.service = service; }

    @GetMapping("/titles")
    @Operation(summary = "Consultar títulos paginados com resumo dos mesmos filtros e cobertura")
    public ReceivablesResponse.Dashboard titles(@ParameterObject @ModelAttribute ReceivablesFilterRequest filters) {
        return ReceivablesResponse.dashboard(service.report(filters.query(service.today())));
    }

    @GetMapping(value = "/titles/export", produces = "text/csv;charset=UTF-8")
    @Operation(summary = "Exportar todos os títulos dos filtros e ordenação aplicados, sem paginação")
    public ResponseEntity<byte[]> export(@ParameterObject @ModelAttribute ReceivablesFilterRequest filters) {
        var result = service.export(filters.query(service.today()));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"boletos-" + result.metadata().asOf() + ".csv\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ReceivablesCsv.render(result));
    }

    @GetMapping("/summary")
    @Operation(summary = "Consultar resumo de toda a carteira filtrada, independente da página")
    public ReceivablesResponse.SummaryResult summary(@ParameterObject @ModelAttribute ReceivablesFilterRequest filters) {
        var report = titles(filters);
        return new ReceivablesResponse.SummaryResult(report.summary(), report.metadata());
    }

    @GetMapping("/filters")
    @Operation(summary = "Consultar unidades, vendedores e supervisores da carteira elegível")
    public ReceivablesResponse.Filters filters(@ParameterObject @ModelAttribute ReceivablesFilterRequest filters) {
        return titles(filters).filters();
    }

    @GetMapping("/titles/{unit}/{customer}/{title}/{installment}")
    @Operation(summary = "Consultar título, demais títulos do cliente e pagamentos paginados na mesma unidade")
    @ApiResponse(responseCode = "404", description = "Título elegível não encontrado",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ReceivablesResponse.Detail detail(@PathVariable int unit, @PathVariable int customer,
            @PathVariable long title, @PathVariable String installment,
            @ParameterObject @ModelAttribute ReceivablesFilterRequest filters,
            @RequestParam(defaultValue = "0") int paymentPage,
            @RequestParam(defaultValue = "20") int paymentSize) {
        if (unit < 0 || customer < 0 || title < 0 || !installment.matches("[A-Za-z0-9]{1,5}"))
            throw new IllegalArgumentException("Identificação do título inválida.");
        return ReceivablesResponse.detail(service.detail(new Key(unit, customer, title,
                installment.matches("[0-9]+") ? installment.replaceFirst("^0+(?!$)", "") : installment),
                filters.query(service.today()), paymentPage, paymentSize));
    }
}
