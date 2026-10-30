package com.logimarui.receivables.api.v1;

import com.logimarui.receivables.core.model.Receivables.Export;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** One row per title; UTF-8 BOM and semicolons for Brazilian spreadsheet applications. */
final class ReceivablesCsv {
    private ReceivablesCsv() {}
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("OVERDUE", "Vencido"), Map.entry("DUE_TODAY", "Vence hoje"),
            Map.entry("UPCOMING", "A vencer"), Map.entry("UNKNOWN_DUE", "Vencimento ausente"),
            Map.entry("PREVENTIVE", "Atenção preventiva"), Map.entry("DUE_SOON", "Próximo vencimento"),
            Map.entry("MONITOR", "Acompanhar"), Map.entry("DATA_REVIEW", "Revisar dados"),
            Map.entry("AVAILABLE", "Histórico disponível"), Map.entry("INSUFFICIENT", "Amostra insuficiente"),
            Map.entry("NO_HISTORY", "Sem histórico do cliente"),
            Map.entry("UNIT_UNMAPPED", "Unidade sem associação confirmada"),
            Map.entry("UNAVAILABLE", "Histórico indisponível"));

    static byte[] render(Export export) {
        StringBuilder csv = new StringBuilder("\uFEFF");
        row(csv, "Unidade", "Código do cliente", "Cliente", "Título", "Parcela", "Emissão", "Entrega",
                "Vencimento", "Valor original (R$)", "Saldo pendente (R$)", "Vendedor", "Supervisor",
                "Classe de risco da origem", "Situação de cobrança da origem", "Último contato",
                "Situação", "Dias em atraso", "Prioridade", "Motivos", "Histórico do cliente",
                "Eventos de pagamento", "Títulos distintos no histórico", "Pagamentos em atraso",
                "Títulos com atraso recente", "Pagamentos em atraso (%)", "Maior atraso (dias)",
                "Primeiro pagamento", "Último pagamento", "Histórico de", "Histórico até",
                "Data de referência", "Consultado em (UTC)");
        var metadata = export.metadata();
        for (var item : export.items()) {
            var title = item.title();
            var key = title.key();
            var history = item.history();
            row(csv, key.unit(), key.customer(), title.customerName(), key.title(), key.installment(),
                    title.issuedOn(), title.deliveredOn(), title.dueOn(), title.originalAmount(), title.pendingAmount(),
                    title.seller(), title.supervisor(), title.sourceRiskClass(), title.collectionStatus(), title.lastContact(),
                    label(item.status()), item.daysOverdue() == null ? null : Math.max(0, item.daysOverdue()),
                    "OVERDUE".equals(item.priority()) ? "Cobrar vencido" : label(item.priority()),
                    String.join(" | ", item.reasons()), label(history.status()), history.events(), history.distinctTitles(),
                    history.lateEvents(), history.recurringLateTitles(), history.latePercent(), history.maximumDelayDays(),
                    history.firstPaymentOn(), history.lastPaymentOn(), metadata.historyFrom(), metadata.historyTo(),
                    metadata.asOf(), metadata.queriedAt());
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String label(String value) { return LABELS.getOrDefault(value, value); }

    private static void row(StringBuilder csv, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) csv.append(';');
            Object value = values[i];
            String text = value == null ? "" : value instanceof LocalDate date ? DATE.format(date)
                    : value instanceof BigDecimal number ? number.toPlainString().replace('.', ',') : value.toString();
            // Quoting preserves delimiters/newlines; apostrophe prevents source text becoming a formula.
            if (value instanceof String && (text.matches("(?s)^[\\t\\r\\n].*")
                    || text.stripLeading().matches("(?s)^[=+@\\-].*"))) text = "'" + text;
            csv.append('"').append(text.replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }
}
