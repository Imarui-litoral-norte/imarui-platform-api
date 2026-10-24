package com.logimarui.receivables.core;

import java.time.LocalDate;
import java.util.Set;

public record ReceivablesQuery(String search, Integer unit, Integer seller, Integer supervisor,
        LocalDate dueFrom, LocalDate dueTo, LocalDate issuedFrom, LocalDate issuedTo,
        String status, String priority, String sort, int page, int size,
        LocalDate historyFrom, LocalDate historyTo) {
    public ReceivablesQuery {
        search = search == null ? "" : search.trim();
        status = status == null || status.isBlank() ? "ALL" : status;
        priority = priority == null || priority.isBlank() ? "ALL" : priority;
        sort = sort == null || sort.isBlank() ? "PRIORITY" : sort;
        if (search.length() > 100 || page < 0 || page > 100000 || size < 1 || size > 100)
            throw new IllegalArgumentException("Pesquisa ou paginação inválida.");
        if (!Set.of("ALL", "OVERDUE", "DUE_TODAY", "UPCOMING", "UNKNOWN_DUE").contains(status)
                || !Set.of("ALL", "OVERDUE", "PREVENTIVE", "DUE_SOON", "MONITOR", "DATA_REVIEW").contains(priority)
                || !Set.of("PRIORITY", "DUE_ASC", "AMOUNT_DESC", "CUSTOMER_ASC").contains(sort))
            throw new IllegalArgumentException("Filtro ou ordenação inválida.");
        if ((dueFrom != null && dueTo != null && dueFrom.isAfter(dueTo))
                || (issuedFrom != null && issuedTo != null && issuedFrom.isAfter(issuedTo))
                || historyFrom == null || historyTo == null || historyFrom.isAfter(historyTo)
                || historyFrom.plusYears(5).isBefore(historyTo))
            throw new IllegalArgumentException("Período inválido (histórico máximo de 5 anos).");
        if ((unit != null && unit < 0) || (seller != null && seller < 0) || (supervisor != null && supervisor < 0))
            throw new IllegalArgumentException("Código inválido.");
    }
}

