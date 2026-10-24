# Gestão Boletos — IFW-36

Ferramenta de acompanhamento diário, somente leitura, em SPO / Financeiro.
Backend: `com.logimarui.receivables` (api / core / infra); fonte pelo datasource READ existente.
Frontend: `features/receivables`, composição em `views/spo`, rota `/spo/financeiro/gestao-boletos`.
A integração de autenticação é injetada pela View; não há import entre features irmãs.

## Fontes e identidade

- Carteira: `diretorio.12_06_01`, CA/PRZ NULL/vazio/espaços e saldo pendente positivo. A/I excluídos.
- PK da carteira: unb + cliente + titulo + parcela. Parcela numérica normalizada removendo zeros à esquerda.
- Histórico: `diretorio.12_06_06`; eventos de pagamento do período. Apenas unidade e código do cliente exatamente correspondentes participam dos indicadores.
- Não equiparar UNB 0 e 1 nem unb/unb_2 automaticamente. Pagamentos em outra unidade => UNIT_UNMAPPED, até associação de origem comprovada.
- Associações de unidade confirmadas podem ser configuradas por app.receivables.history-unit-mappings (pares origem=carteira separados por vírgula). Padrão vazio; nenhum alias foi presumido na instalação inicial.
- Cada linha de pagamento representa evento, não prova quitação integral. Eventos em datas/valores diferentes preservados. Linhas com todo o payload de negócio idêntico, excluindo somente id técnico, consolidadas e contadas em duplicatePaymentRows.
- Eventos sem vencimento/pagamento ou valor pago positivo são excluídos dos indicadores, mantendo contagem de inválidos. Pagamentos com pagto NULL não pertencem a uma janela de pagamento.
- Taxa de atraso = eventos válidos pagos após vencimento / eventos válidos observados. Não é taxa de inadimplência nem probabilidade preditiva. Parcelas distintas contam como títulos distintos.
- Classe de risco da origem é exibida como informação sem interpretar códigos não documentados.
- Carga inicial de outubro não limita o código a outubro. Cobertura real de emissão/vencimento é retornada; sem informação de carga, sourceUpdatedAt é NULL. queriedAt é horário de consulta.
- Sem snapshots não há curva histórica de saldo, nem inferência de pagamento por desaparecimento de título.

## Regras operacionais v1

Referência: data corrente de America/Sao_Paulo. Comparações de datas civis, dias corridos, sem ajuste de finais de semana/feriados.
Vencido: vencimento anterior à referência; hoje: igual; a vencer: posterior. Sem vencimento => UNKNOWN_DUE.
Saldo de carteira é SUM(valor_pendente), sem recalcular correção monetária.
Prioridade determinística e explicável, sem pesos:
1. OVERDUE: vencido; vencimento mais antigo primeiro.
2. PREVENTIVE: vence em até upcoming-days, histórico com minimum-history-titles e ao menos recurring-late-titles títulos com eventos em atraso na janela recent-days.
3. DUE_SOON: vence hoje ou na janela próxima, sem evidência suficiente para alerta preventivo.
4. DATA_REVIEW: vencimento ausente.
5. MONITOR: demais títulos.
Desempates: vencimento, saldo decrescente e chave completa. Histórico insuficiente não implica baixo risco.
Padrões configuráveis (propriedades Spring): app.receivables.upcoming-days=7, recent-days=90, minimum-history-titles=5, recurring-late-titles=2.
Estes são parâmetros operacionais iniciais revisáveis, expostos na resposta e na tela; não modelo estatístico.
Histórico recente é relativo à referência de hoje, dentro da janela histórica consultada.

## Contrato HTTP

Permissão RECEIVABLES_READ, módulo FINANCE, sincronizada pelo catálogo de permissões existente.
Atribuição a roles pelo fluxo de autorização vigente; bootstrap DEV mantém o comportamento do projeto para ADMIN.
Proteção no SecurityFilterChain por URL e método GET; demais métodos recusados. RequiredPermission documenta OpenAPI.
- GET /api/v1/receivables/titles: itens paginados + resumo de todo o filtro + metadados + opções.
- GET /api/v1/receivables/summary: resumo e metadados.
- GET /api/v1/receivables/filters: unidades, vendedores, supervisores elegíveis na carteira.
- GET /api/v1/receivables/titles/{unit}/{customer}/{title}/{installment}: título, outros títulos do mesmo cliente/unidade e pagamentos paginados.
Filtros: search (cliente/nome/título), unit, seller, supervisor, dueFrom/dueTo, issuedFrom/issuedTo, status, priority.
Histórico: historyFrom/historyTo, padrão início do ano até hoje; máximo cinco anos, sem datas futuras.
Página: page (zero), size (1–100, padrão 25); sort = PRIORITY, DUE_ASC, AMOUNT_DESC ou CUSTOMER_ASC.
Detalhe: paymentPage (zero), paymentSize (1–100, padrão 20). Filtros da carteira não escondem outros títulos do cliente no detalhe; apenas janela histórica é aplicada aos pagamentos.
400 filtro inválido, 403 acesso negado, 404 título elegível inexistente, 503 carteira indisponível. Histórico com falha => UNAVAILABLE sem derrubar carteira.
Resposta HTTP usa projeções próprias, sem entidades/tabelas físicas. BigDecimal preserva precisão no backend.
Resumo, classificação, prioridade e paginação derivam da mesma leitura transacional; sem totais da página confundidos com carteira.
A carteira elegível é projetada em memória para suportar regras de prioridade e totais; pagamentos em lotes de até 500 códigos, sem N+1. Não há truncamento silencioso.
Para volumes maiores, medir memória/latência e avaliar projeção SQL/índice em histórico (cliente, pagto, unb). Nenhum índice foi criado automaticamente.

## Atualização e validação

Botão Atualizar relê o banco via API. Mantém filtros/últimos dados durante falha e distingue erro de carteira vazia/histórico ausente.
Não há importador, baixa, envio de cobrança, cadastro de atendimento ou alteração das fontes nesta feature.
Testes: ReceivablesServiceTest, ReceivablesControllerTest, ReceivablesSecurityTest e JdbcReceivablesReadRepositoryIntegrationTest.
Integração MySQL é opcional por RECEIVABLES_TEST_DB_URL, RECEIVABLES_TEST_DB_USERNAME, RECEIVABLES_TEST_DB_PASSWORD, conexão read-only. Sem credenciais, apenas esse cenário é ignorado.
Frontend: npm run test:receivables; npm run typecheck; lint; npm run build.

