package com.logimarui.receivables.api.v1.dto;

import com.logimarui.receivables.core.ReceivablesQuery;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;

public record ReceivablesFilterRequest(String search, Integer unit, Integer seller, Integer supervisor,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueTo,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issuedFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issuedTo,
        String status, String priority, String sort, Integer page, Integer size,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate historyFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate historyTo) {
    public ReceivablesQuery query(LocalDate today) {
        return new ReceivablesQuery(search, unit, seller, supervisor, dueFrom, dueTo, issuedFrom, issuedTo,
                status, priority, sort, page == null ? 0 : page, size == null ? 25 : size,
                historyFrom == null ? today.withDayOfYear(1) : historyFrom, historyTo == null ? today : historyTo);
    }
}

