package com.logimarui.receivables.core.port;

import com.logimarui.receivables.core.model.Receivables.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public interface ReceivablesReadRepository {
    List<OpenTitle> portfolio();
    SourceHistory payments(Set<Integer> customers, LocalDate from, LocalDate to);
}

