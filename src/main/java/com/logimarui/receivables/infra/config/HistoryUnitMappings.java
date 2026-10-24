package com.logimarui.receivables.infra.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;

/** Only operator-confirmed report-unit aliases; no implicit 0 -> 1 fallback. */
@Component
public class HistoryUnitMappings {
    private final Map<Integer, Integer> units;
    public HistoryUnitMappings(@Value("${app.receivables.history-unit-mappings:}") String configuration) {
        var parsed = new HashMap<Integer, Integer>();
        if (configuration != null && !configuration.isBlank()) {
            for (String entry : configuration.split(",")) {
                String[] pair = entry.trim().split("=");
                if (pair.length != 2) throw new IllegalArgumentException("Invalid history unit mapping");
                int source = Integer.parseInt(pair[0].trim()), target = Integer.parseInt(pair[1].trim());
                if (source < 0 || target < 0 || parsed.putIfAbsent(source, target) != null)
                    throw new IllegalArgumentException("Duplicate or invalid history unit mapping");
            }
        }
        units = Map.copyOf(parsed);
    }
    public int canonicalUnit(int source) { return units.getOrDefault(source, source); }
}

