package com.logimarui.journey.core;

import com.logimarui.journey.core.model.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class JourneyAuditTableTest {
    final LocalDate day = LocalDate.of(2026,10,1);
    JourneyAudit.Row row(long map, String driver, String vehicle, Long seconds) {
        var counts = new JourneyHistory.Counts(1,1,0,0,0,1,100.0,1,0,0);
        var metric = new IndicatorResult(null,seconds,null,"FINALIZADO",null,true,false);
        var member = new JourneyAudit.Member(map,driver,"motorista",true,metric,metric,metric,metric,null,false);
        return new JourneyAudit.Row(day.plusDays(map % 2),map,"HISTORICO",null,List.of(driver),List.of(vehicle),2,List.of(),List.of(),
                new JourneyAudit.Deliveries(map,"03_08_05",false), counts,List.of(member),Map.of());
    }
    JourneyAudit page(List<JourneyAudit.Row> rows, int page, int size, Map<String,List<String>> filters, String sort, String direction) {
        return JourneyAuditTable.page(day,day.plusDays(1),"ti","ponto","motorista",null,page,size,rows,new JourneyAudit.TableQuery(filters,sort,direction));
    }
    @Test void facetsCoverEveryPageAndCrossFiltersIgnoreTheirOwnColumn() {
        var rows = List.of(row(1,"Ana","1 · AAA",3600L),row(2,"Bruno","2 · BBB",7200L),row(3,"Ana","2 · BBB",1800L));
        var initial = page(rows,1,1,Map.of(),"map","asc");
        assertThat(initial.items()).hasSize(1); assertThat(initial.facets().get("driver")).hasSize(2);
        assertThat(initial.facets().get("vehicle")).extracting(JourneyAudit.Option::label).containsExactly("1 · AAA","2 · BBB");
        var filtered = page(rows,1,1,Map.of("driver",List.of("Ana")),"map","asc");
        assertThat(filtered.totalElements()).isEqualTo(2); assertThat(filtered.unfilteredTotal()).isEqualTo(3);
        assertThat(filtered.summary().total()).isEqualTo(2);
        assertThat(filtered.facets().get("driver")).hasSize(2);
        assertThat(filtered.facets().get("vehicle")).extracting(JourneyAudit.Option::count).containsExactly(1L,1L);
        var crossed = page(rows,1,25,Map.of("driver",List.of("Ana"),"vehicle",List.of("2 · BBB")),"map","asc");
        assertThat(crossed.items()).extracting(JourneyAudit.Row::map).containsExactly(3L);
        assertThat(crossed.facets().get("driver")).extracting(JourneyAudit.Option::label).containsExactly("Ana","Bruno");
        assertThat(page(rows,2,1,Map.of("driver",List.of("Ana")),"map","asc").items()).extracting(JourneyAudit.Row::map).containsExactly(3L);
    }
    @Test void numericSortingIsGlobalAndBlanksRemainLastInBothDirections() {
        var rows = List.of(row(10,"Ana","1",36000L),row(2,"Bruno","2",7200L),row(4,"Cris","3",null));
        assertThat(page(rows,1,25,Map.of(),"map","asc").items()).extracting(JourneyAudit.Row::map).containsExactly(2L,4L,10L);
        assertThat(page(rows,1,25,Map.of(),"ti","desc").items()).extracting(JourneyAudit.Row::map).containsExactly(10L,2L,4L);
        assertThat(page(rows,1,25,Map.of(),"ti","asc").items()).extracting(JourneyAudit.Row::map).containsExactly(2L,10L,4L);
        assertThat(page(rows,1,25,Map.of("ti",List.of("__blank__")),"map","asc").items()).extracting(JourneyAudit.Row::map).containsExactly(4L);
    }
    @Test void emptySelectionDoesNotBecomeAllAndUnknownColumnsAreRejected() {
        var rows = List.of(row(1,"Ana","1",3600L));
        var empty = page(rows,999,25,Map.of("driver",List.of()),"map","asc");
        assertThat(empty.totalElements()).isZero(); assertThat(empty.page()).isEqualTo(1);
        assertThat(empty.facets().get("driver")).hasSize(1);
        assertThatThrownBy(() -> page(rows,1,25,Map.of("unknown",List.of("x")),"map","asc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> page(rows,1,25,Map.of(),"unknown","asc")).isInstanceOf(IllegalArgumentException.class);
    }
}
