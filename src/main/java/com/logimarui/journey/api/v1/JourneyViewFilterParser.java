package com.logimarui.journey.api.v1;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logimarui.journey.core.JourneyViewFilters;
final class JourneyViewFilterParser {
    static JourneyViewFilters parse(String value) {
        if (value == null || value.isBlank()) return null;
        try { return new ObjectMapper().readValue(value, JourneyViewFilters.class); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid view filters"); }
    }
}
