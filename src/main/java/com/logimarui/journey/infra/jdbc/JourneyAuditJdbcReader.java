package com.logimarui.journey.infra.jdbc;

import com.logimarui.journey.core.model.JourneyAudit;
import com.logimarui.journey.core.port.JourneyAuditReadRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Date;
import java.util.*;

@Repository
public class JourneyAuditJdbcReader implements JourneyAuditReadRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public JourneyAuditJdbcReader(@Qualifier("readNamedParameterJdbcTemplate") NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Map<JourneyAudit.Key, JourneyAudit.Deliveries> deliveries(List<JourneyAudit.SelectedMap> maps) {
        Map<JourneyAudit.Key, JourneyAudit.Deliveries> result = new HashMap<>();
        for (boolean live : List.of(false, true)) {
            var selection = maps.stream().filter(m -> live == "LIVE".equals(m.origin())).toList();
            if (selection.isEmpty()) continue;
            String table = live ? "v_03_11_40" : "03_08_05";
            String date = live ? "dt_entrega" : "data";
            var from = selection.stream().map(m -> m.key().date()).min(Comparator.naturalOrder()).orElseThrow();
            var to = selection.stream().map(m -> m.key().date()).max(Comparator.naturalOrder()).orElseThrow();
            Set<JourneyAudit.Key> keys = new HashSet<>(); selection.forEach(m -> keys.add(m.key()));
            String sql = "SELECT " + date + " AS date, mapa, COUNT(*) AS rows_count, COUNT(entregas) AS known_count, "
                    + "MIN(entregas) AS min_count, MAX(entregas) AS max_count FROM diretorio.`" + table
                    + "` WHERE " + date + " BETWEEN :from AND :to AND mapa IN (:maps) GROUP BY " + date + ", mapa";
            jdbc.query(sql, Map.of("from", Date.valueOf(from), "to", Date.valueOf(to),
                    "maps", selection.stream().map(m -> m.key().map()).distinct().toList()), rs -> {
                var key = new JourneyAudit.Key(rs.getDate("date").toLocalDate(), rs.getLong("mapa"));
                Long min = rs.getObject("min_count") == null ? null : rs.getLong("min_count");
                Long max = rs.getObject("max_count") == null ? null : rs.getLong("max_count");
                boolean conflict = !Objects.equals(min, max) || min != null && min < 0 ||
                        rs.getLong("known_count") > 0 && rs.getLong("rows_count") != rs.getLong("known_count");
                if (keys.contains(key)) result.put(key, new JourneyAudit.Deliveries(
                        !conflict && min != null && min >= 0 ? min : null, table, conflict));
            });
        }
        return result;
    }
}
