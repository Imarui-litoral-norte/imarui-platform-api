# IIAM na intranet — publicação de 24/10/2026

Entrada: https://logistica.imaruileste.dominiolocal/ e https://localhost/.
O Core publica HTTPS em 443 e redireciona HTTP 80 para HTTPS, preservando o host.
O certificado local inclui ambos os nomes; foi instalado manualmente pelo operador.
Outros computadores precisam confiar no certificado da intranet para navegar sem erro TLS.

## Dados e ambientes

PROD: `C:/Users/lucas.l/Documents/LogImarui`, PostgreSQL `iiam_prod`, usuário `iiam_prod`.
DEV continua usando `iiam_dev`, gateway 8443, frontend 8191 e IIAM 4105.
PROD usa frontend 8091 e IIAM HTTPS restrito a loopback 4005.
Os módulos de negócio continuam usando a configuração MySQL existente de PROD, com DDL validate.

O banco IIAM de PROD foi restaurado de um dump consistente do DEV. Contagens verificadas:
7 solicitações pendentes, 2 aprovadas (uma sintética e uma pessoa já aprovada no DEV),
1 Identity PERSON pendente, 2 SERVICE ativas, tenants IMARUI/CORE_DEV ativos e 27 migrations.
UUIDs, credenciais, vínculos e autorizações vieram da cópia; nenhuma solicitação foi aprovada nesta publicação.
As aprovações restantes e os convites de usuários ficam com o operador.
Esta é uma cópia pontual; mudanças futuras de dados não são sincronizadas entre os bancos.

PROD tem chave de assinatura e issuer próprios (`imarui-iam-production`). Sessões,
refresh tokens ativos e desafios não consumidos copiados do DEV foram invalidados somente no PROD.
O cliente técnico do Core e os operadores existentes foram preservados na cópia.
Credenciais e backup estão apenas em `.runtime/iiam`, com ACL privada; nunca devem ser versionados.

## Operação

Os comandos existentes `executar-core.bat` e `logimarui-core-api-lifecycle.ps1 start|stop|restart`
passam pelo launcher `scripts/start-iiam-integrated-prod.ps1`. O instalador é
`scripts/install-iiam-prod-launcher.ps1`; o launcher anterior foi salvo no runtime privado.
O launcher usa o JAR já compilado, verifica ownership antes de parar processos e deixa PostgreSQL ativo.

Build frontend isolado antes da troca: `LOGIMARUI_PROFILE=local`,
`LOGIMARUI_NEXT_DIST_DIR=.next-iiam-production`,
`LOGIMARUI_TSCONFIG_PATH=tsconfig.iiam-production.json` e variáveis NEXT_PUBLIC de URL vazias
para resolver o gateway na própria origem. Executar `npm run build -- --webpack`.
A configuração separada de TypeScript impede que tipos antigos de `.next` contaminem o build novo.
Build Core: Java 17, `mvnw.cmd -DskipTests package`.

## Evidências

- Core e frontend compilados no checkout PROD.
- Core: 110 testes, zero falhas/erros, 5 skips de integração nesta rodada.
- Frontend: `npm test` aprovado e TypeScript aprovado no build.
- HTTP 302 aponta para HTTPS no mesmo nome de rede; HTTPS retorna 200 com certificado validado.
- Login administrativo e leitura das 7 solicitações pendentes aprovados via gateway.
- Login do consumidor, contexto Core, leitura dos 7 serviços ativos, refresh e logout aprovados.
- Supervisor: IIAM, Extração, Monitoring, Backup, SAVI, Crítica e frontend RUNNING.
- DEV HTTPS 8443 permaneceu respondendo 200 após a publicação.
- Artefato IIAM vem de main `7ad6269`, SHA256
  `2879A948ABAFEE2BEE22CFE0E84318C7CCF6FA510002AAA8FA80D26F2068B186`.

Alterações prévias de PROD estão preservadas em stashes e arquivos privados de checkpoint.
Os fontes de DEV foram registrados antes da promoção; runtimes, logs e ZIPs originais fornecidos
não entraram nos novos commits. Os checkouts Core/frontend de PROD avançaram por fast-forward local.
Não houve push remoto nem operação em VPS. SMTP e navegação a partir de outro computador
da rede não foram homologados nesta publicação; os fluxos operacionais foram verificados por HTTPS/API.

## Recuperação

O backup inicial, configurações anteriores e manifesto dos commits de origem ficam em
`.runtime/iiam`. Parar a instância atual pelo launcher integrado antes de qualquer recuperação.
Restaurar o banco somente após conferir alterações e aprovações feitas desde a publicação.
O launcher antigo pertence à autenticação anterior e não deve ser reativado isoladamente;
uma reversão completa exige restaurar conjuntamente os fontes, build frontend e configuração anterior.
