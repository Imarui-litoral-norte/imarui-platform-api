package com.logimarui.receivables.infra.jdbc;

import com.logimarui.receivables.core.*;
import com.logimarui.receivables.core.model.Receivables.*;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JdbcReceivablesReadRepositoryIntegrationTest {
    @Test void realReadOnlyMysqlPortfolioReconcilesWithApiProjection() {
        String url = System.getenv("RECEIVABLES_TEST_DB_URL");
        assumeTrue(url != null, "Optional read-only MySQL credentials not supplied");
        try (var ds = new HikariDataSource()) {
            ds.setJdbcUrl(url);
            ds.setUsername(System.getenv("RECEIVABLES_TEST_DB_USERNAME"));
            ds.setPassword(System.getenv("RECEIVABLES_TEST_DB_PASSWORD"));
            ds.setMaximumPoolSize(1);
            ds.setReadOnly(true);
            ds.setConnectionInitSql("SET SESSION TRANSACTION READ ONLY");
            var jdbc = new NamedParameterJdbcTemplate(ds);
            var repository = new JdbcReceivablesReadRepository(jdbc,
                    new com.logimarui.receivables.infra.config.HistoryUnitMappings(""));
            var service = new ReceivablesService(repository, Clock.systemUTC(), new Policy(7,90,5,2));
            LocalDate today = service.today();
            var query = new ReceivablesQuery("",null,null,null,null,null,null,null,
                    "ALL","ALL","PRIORITY",0,25,today.withDayOfYear(1),today);
            var report = service.report(query);
            var expected = jdbc.getJdbcTemplate().queryForMap("""
                SELECT COUNT(*) n, COALESCE(SUM(valor_pendente),0) amount FROM `12_06_01`
                 WHERE (indicador_ca_prz IS NULL OR TRIM(indicador_ca_prz)='')
                   AND valor_pendente > 0
                """);
            assertThat(report.total()).isEqualTo(((Number) expected.get("n")).intValue());
            assertThat(report.summary().pendingAmount()).isEqualByComparingTo(expected.get("amount").toString());
            assertThat(report.items()).hasSize(Math.min(25, report.total()));
            if (!report.items().isEmpty()) {
                var selected = report.items().get(0);
                assertThat(service.detail(selected.title().key(),query,0,20).selected()).isEqualTo(selected);
            }
            assertThat(jdbc.getJdbcTemplate().queryForObject("SELECT @@session.transaction_read_only",Integer.class)).isEqualTo(1);
        }
    }

    @Test void normalizesNumericInstallmentsWithoutConflatingOtherCodes() {
        assertThat(JdbcReceivablesReadRepository.normalizeInstallment("00000")).isEqualTo("0");
        assertThat(JdbcReceivablesReadRepository.normalizeInstallment(" 01 ")).isEqualTo("1");
        assertThat(JdbcReceivablesReadRepository.normalizeInstallment("A1")).isEqualTo("A1");
    }
}

