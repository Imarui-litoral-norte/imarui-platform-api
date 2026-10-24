package com.logimarui.receivables.infra.jdbc;

import com.logimarui.receivables.core.model.Receivables.*;
import com.logimarui.receivables.core.port.ReceivablesReadRepository;
import com.logimarui.receivables.infra.config.HistoryUnitMappings;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;

@Repository
public class JdbcReceivablesReadRepository implements ReceivablesReadRepository {
    private static final Logger LOG = LoggerFactory.getLogger(JdbcReceivablesReadRepository.class);
    private final NamedParameterJdbcTemplate jdbc;
    private final HistoryUnitMappings unitMappings;

    public JdbcReceivablesReadRepository(@Qualifier("readNamedParameterJdbcTemplate") NamedParameterJdbcTemplate jdbc,
                                        HistoryUnitMappings unitMappings) {
        this.jdbc = jdbc;
        this.unitMappings = unitMappings;
    }

    @Override
    public List<OpenTitle> portfolio() {
        List<OpenTitle> titles = jdbc.query("""
            SELECT unb, cliente, titulo, parcela, nome, data_emissao, data_entrega, data_vencto,
                   valor_original, valor_pendente, vendedor, superv, classe_de_risco_atual,
                   status_da_cobranca, historico_ultimo_atendimento
              FROM `12_06_01`
             WHERE (indicador_ca_prz IS NULL OR TRIM(indicador_ca_prz) = '')
               AND valor_pendente > 0
             ORDER BY unb, cliente, titulo, parcela
            """, (rs, n) -> new OpenTitle(new Key(rs.getInt("unb"), rs.getInt("cliente"),
                rs.getLong("titulo"), normalizeInstallment(rs.getString("parcela"))),
                rs.getString("nome") == null ? "Cliente " + rs.getInt("cliente") : rs.getString("nome").trim(), date(rs, "data_emissao"), date(rs, "data_entrega"),
                date(rs, "data_vencto"), rs.getBigDecimal("valor_original"), rs.getBigDecimal("valor_pendente"),
                rs.getObject("vendedor", Integer.class), rs.getObject("superv", Integer.class),
                rs.getObject("classe_de_risco_atual", Integer.class),
                text(rs.getString("status_da_cobranca")), text(rs.getString("historico_ultimo_atendimento"))));
        if (titles.stream().map(OpenTitle::key).distinct().count() != titles.size())
            throw new org.springframework.dao.DataRetrievalFailureException("Ambiguous normalized receivable identity");
        return titles;
    }

    @Override
    public SourceHistory payments(Set<Integer> customers, LocalDate from, LocalDate to) {
        if (customers.isEmpty()) return new SourceHistory("NO_CUSTOMERS", List.of());
        try {
            // Group on the entire source business payload, excluding only surrogate id.
            // Partial payments on different dates/amounts remain separate events.
            var result = new ArrayList<Payment>();
            var codes = new ArrayList<>(customers);
            for (int offset = 0; offset < codes.size(); offset += 500) {
                var params = Map.of("customers", codes.subList(offset, Math.min(offset + 500, codes.size())),
                        "from", from, "until", to.plusDays(1));
                result.addAll(jdbc.query("""
                    SELECT unb, cliente, titulo, parcela, vencto, pagto, vlr_pagtos, vlr_juros, total_liquido,
                           COUNT(*) - 1 AS duplicates
                      FROM `12_06_06`
                     WHERE cliente IN (:customers) AND pagto >= :from AND pagto < :until
                     GROUP BY unb, unb_2, cliente, nome, titulo, parcela, especie, portador, nr_banco,
                              emissao, vencto, contabil, pagto, vlr_pagtos, vlr_juros, vlr_desctos,
                              vlr_adiant, vlr_tarifa, total_liquido, titulo_aluguel, tipo_vale,
                              tipo_refugo, tipo_quebras, juros_renegociacao, portador_origem, portador_transacao
                     ORDER BY pagto DESC, unb, cliente, titulo, parcela, vlr_pagtos, vencto, contabil
                    """, params, (rs, n) -> new Payment(unitMappings.canonicalUnit(rs.getInt("unb")), rs.getInt("cliente"),
                        rs.getLong("titulo"), normalizeInstallment(rs.getString("parcela")), date(rs, "vencto"),
                        date(rs, "pagto"), rs.getBigDecimal("vlr_pagtos"), rs.getBigDecimal("vlr_juros"),
                        rs.getBigDecimal("total_liquido"), rs.getInt("duplicates"))));
            }
            return new SourceHistory(result.isEmpty() ? "EMPTY_PERIOD" : "AVAILABLE", List.copyOf(result));
        } catch (DataAccessException exception) {
            LOG.warn("Receivables payment history unavailable ({})", exception.getClass().getSimpleName());
            return new SourceHistory("UNAVAILABLE", List.of());
        }
    }

    public static String normalizeInstallment(String value) {
        if (value == null || value.isBlank()) return "0";
        String trimmed = value.trim();
        return trimmed.matches("[0-9]+") ? trimmed.replaceFirst("^0+(?!$)", "") : trimmed;
    }
    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }
    private static String text(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}

