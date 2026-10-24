# Validação da Gestão Boletos — 15/10/2026

## Entrega

- IFW-36 implementada no DEV; IFW-40–44 cobertas por decisões, contratos, backend, frontend e validação.
- Backend em com.logimarui.receivables; frontend em features/receivables, View SPO e rota /spo/financeiro/gestao-boletos.
- Código e documentação locais; nenhum commit/push/merge executado nesta entrega.
- Alterações locais preexistentes de Jornada, DTO, customerlabel, occurrence e infraestrutura preservadas.

## Evidências

- Maven: 63 cenários descobertos, 0 falhas, 0 erros; 2 integrações opcionais preexistentes sem credenciais foram ignoradas.
- Recorte receivables: 12 testes executados, nenhum ignorado, incluindo leitura do MySQL real em sessão READ ONLY.
- npm test: DTO, Journey e Receivables passaram.
- npm run lint e npm run typecheck passaram.
- npm run build passou, incluindo geração da rota SPO / Financeiro / Gestão Boletos.
- HTTP real: quatro superfícies GET, resumo/lista reconciliados, detalhe, filtros, 400 inválido, 404 ausente e 403 em escrita.
- OpenAPI real contém as quatro rotas e x-required-permissions = RECEIVABLES_READ.
- Edge oculto: desktop 1440px e mobile 390px; catálogo, paginação, filtro de vencidos, detalhe, histórico vazio, busca sem resultados e recuperação após falha 503 passaram; nenhum pageerror.
- Falha no refresh manteve as 25 linhas anteriores, seguida de recuperação.
- Responsividade corrigida para impedir overflow no documento e sobreposição pelo cabeçalho mobile.

## Reconciliação SQL / API / tela

- Carteira elegível: 398 títulos, 218 clientes, R$ 3.088.037,81.
- Referência 15/10/2026: 4 vencidos (R$ 14.635,01), 34 vencendo hoje (R$ 278.205,46), 360 a vencer (R$ 2.795.197,34).
- Fonte de pagamentos vazia => EMPTY_PERIOD; nenhum alerta preventivo por recorrência produzido artificialmente.
- Cenários com histórico, parcialidades, recorrência, unidade diferente e amostra insuficiente validados com fixtures de domínio.
- Nenhuma escrita nas fontes 12_06_01/12_06_06 foi necessária.
- Carga inicial somente outubro; fonte não informa timestamp de carga.

## Operação local

- Validação inicial em portas temporárias 8182/8292, depois encerradas.
- A inicialização temporária executou o mecanismo padrão de sincronização de permissões e limpeza de runtimes desativados do supervisor.
- O DEV foi recomposto pelo logimarui-core-api-lifecycle.ps1 start com .env original.
- Inicializador confirmou startup COMPLETED, core porta 81 e frontend porta 8191; SAVI iniciado pela configuração original.
- Validação funcional completa repetida na porta padrão 8191, consumindo backend 81 e MySQL reais.
- Arquivo temporário com sessão autenticada removido após os testes; screenshots/evidências não incluem tokens.

## Limites

- V1 usa regras operacionais explicáveis e configuráveis; não modelo preditivo de inadimplência.
- Histórico real ainda vazio. Unidade diferente não é equiparada automaticamente; aliases exigem configuração confirmada.
- Evolução histórica de saldos exige snapshots que não existem na fonte atual.

