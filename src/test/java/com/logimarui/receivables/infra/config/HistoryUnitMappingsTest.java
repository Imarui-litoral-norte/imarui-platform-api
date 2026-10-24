package com.logimarui.receivables.infra.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HistoryUnitMappingsTest {
    @Test void onlyAppliesExplicitlyConfiguredAssociations() {
        assertThat(new HistoryUnitMappings("").canonicalUnit(0)).isZero();
        var mapping = new HistoryUnitMappings("0=1, 3=2");
        assertThat(mapping.canonicalUnit(0)).isEqualTo(1);
        assertThat(mapping.canonicalUnit(3)).isEqualTo(2);
        assertThat(mapping.canonicalUnit(7)).isEqualTo(7);
        assertThatThrownBy(() -> new HistoryUnitMappings("0=1,0=2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HistoryUnitMappings("-1=2")).isInstanceOf(IllegalArgumentException.class);
    }
}

