# IIAMS-154 — execução DEV

## Limites e estado inicial

Core e frontend usam a branch `IIAMS-154`. Alterações anteriores do usuário foram
preservadas. Patches binários e listagens iniciais estão no diretório local
`../.runtime/iiams-154-baseline`, fora dos commits deste trabalho.
O checkout IIAM permanece independente e não deve receber alterações concorrentes.

## IIAMS-161: JVM supervisionada

O serviço `iiam` usa tipo `JAVA`, prefixo proposto `/api/iam`, porta preferida 4105
em DEV e 4005 em produção. O registro inicia desabilitado; habilitar no ambiente DEV
somente após preparar banco, artefato, chaves e transporte na IIAMS-162/163/164.

Configuração do supervisor:

* `SERVICE_IIAM_ENABLED=true`, `SERVICE_IIAM_START_ON_BOOT=true` após provisionamento.
* `SERVICE_IIAM_ENV_FILE`: caminho absoluto de arquivo Java properties UTF-8 privado,
  por exemplo `.runtime/iiam/iiam.properties`, ignorado pelo Git.
* `SERVICE_IIAM_JAVA`: executável Java 17; padrão JVM que executa o Core.
* `SERVICE_IIAM_JAR`: artefato relativo ao diretório IIAM ou caminho absoluto.
* `SERVICE_IIAM_STARTUP_TIMEOUT_SECONDS`: padrão 120 segundos.

O arquivo privado contém exclusivamente as variáveis necessárias ao IIAM, incluindo
`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_PRIVATE_KEY_FILE` e demais configurações
V1. Usar caminhos absolutos com barras `/` no formato properties. O launcher não
carrega `.env` do Core e limpa a herança de configurações Spring/JVM/credenciais.
Preserva somente variáveis operacionais Windows e as declaradas explicitamente.
Injeta `SERVER_PORT` alocada e `SERVER_ADDRESS=127.0.0.1`.

Readiness aguarda JWKS HTTP 200 com chaves, após inicialização Spring/Flyway.
Se `SERVER_SSL_ENABLED=true`, usa HTTPS e a confiança TLS da JVM do Core;
não aceita certificados inválidos. TLS e confiança local serão provisionados nas
tarefas seguintes. Falha ou timeout encerra apenas o processo lançado.
Stop verifica que o PID ainda pertence à JVM IIAM antes de encerrá-lo.
O PostgreSQL nativo é externo ao lifecycle e não é encerrado pelo supervisor.

## Sequência inicial

Provisionar IIAM local; proxy seguro; JWT UUID/JWKS; PDP/catálogo; clientes;
retirada dos módulos legados; homologação do DEV. Só depois ler contas legadas
e abrir solicitações idempotentes no IIAM. Não aprovar solicitações, atribuir
classificação ou transferir senhas/hashes/grants automaticamente.

## IIAMS-162: PostgreSQL local e artefato

O script `scripts/initialize-iiam-dev.ps1` recebe uma conexão administrativa em
arquivo privado e cria somente role/banco `iiam_dev`, sem poderes de administração.
Recusa colisão com um banco/role existente e não redefine credenciais de aplicação
em reexecução. Gera RSA 3072 PKCS8 e certificado TLS localhost/127.0.0.1 em PKCS12;
mantém HTTPS, Secure e CSRF do IIAM. Senhas geradas são exclusivas deste ambiente.

Em 23/10/2026, houve autorização explícita do usuário para recuperar somente a
senha administrativa PostgreSQL local, preservando bancos. A configuração de
autenticação foi restaurada byte a byte após uma conexão temporária restrita a
postgres/postgres/127.0.0.1. O banco de aplicação usa credencial separada.

Fontes IIAM copiadas para snapshot privado de build, sem modificar o checkout
concorrente. O artefato compilado iniciou com Java 17, PostgreSQL 18 e TLS em
127.0.0.1:4105; Flyway aplicou V1–V25 com sucesso e Hibernate validou o schema.
Isso comprova o serviço standalone local; a integração completa Core/frontend e
a importação de solicitações continuam pendentes.

## IIAMS-167/168: catálogo e decisões reais

`CorePermissionCatalog` declara namespace próprio `logimarui-core`, sem aliases no
catálogo interno do IIAM. `IiamPdpClient` assina prova técnica RS256 independente do
ator, vinculada ao método, caminho, SHA256 do corpo, correlação e nonce de uso único.
O filtro resolve recursos por rotas e registro local, sem usar Tenant/Scope de headers.
O supervisor exige permissões de leitura/gestão; módulos e serviços protegidos usam
PDP vivo. Negação interrompe o controlador; indisponibilidade responde 503.

O setup `configure-iiam-dev-client.ps1` usa APIs administrativas V1 e mantém estado
privado para retomada. O operador ROOT de governança não recebe autoatribuição:
uma identidade SERVICE sintética distinta recebe o papel de uso do Core, respeitando
a proteção contra autoelevação. Nenhuma conta legada foi aprovada ou classificada.
Bindings são imutáveis no IIAM; retomada de uma operação cuja resposta se perdeu
exige inspeção do binding, sem recriá-lo ou trocar seu Scope silenciosamente.

O teste opt-in `IiamLiveContractTest`, habilitado somente por
`IIAM_LIVE_TEST_RUNTIME`, usa o certificado local confiável e identidades sintéticas.
Comprova catálogo idempotente, ALLOW real, DENY imediato com revogação de limpeza,
recusa de binding desconhecido e rejeição de sessão pelo PDP após logout, enquanto
a validação criptográfica offline continua válida até a expiração do access.

## IIAMS-169/170: remoção e regressão

Retirados authentication, authorization, iam, catálogo compartilhado legado,
bootstrap de administrador/sincronização local e principal numérico. Os 229 arquivos
de código/teste anteriores estão preservados no arquivo local
`../.runtime/iiams-154-baseline/removed-legacy`, com manifesto SHA256 verificado.
As tabelas de autenticação antigas não foram apagadas nem migradas.
JPA do Core passa a validar o schema por padrão; alterações exigem configuração
explícita, preservando o banco que continua atendendo aos módulos de negócio.

`clean test` de 23/10/2026: 93 testes, zero falhas/erros, três ignorados por ausência
de configuração READ_DB. Inclui teste real IIAM/PDP e verificação das fronteiras e
pacotes retidos. O contrato REST passou a examinar somente classes de produção,
excluindo fixtures de teste, e reconhece exclusivamente o proxy `/api/iam` como
transporte externo fora das raízes REST versionadas do Core.

Ainda faltam homologação completa do proxy HTTPS/cookies, clientes frontend,
execução integrada do projeto DEV e abertura das solicitações de contas antigas.

## IIAMS-173: contexto próprio e navegação de aplicações web

`GET /api/v1/gateway/me` consulta a Identity própria no IIAM e monta somente
capacidades de interface a partir de PDP para recursos fixos. Não cria User, Role,
grants ou sessão persistida no Core. Introspecção administrativa do IIAM permanece
restrita ao respectivo permissionamento; usuários comuns não ganham AUDIT_READ.

O cliente JavaScript mantém access somente em memória e o refresh somente no cookie
HttpOnly do IIAM. Para navegações/iframes de aplicações publicadas, que não carregam
o Bearer da memória JS, há transporte suplementar do mesmo access IIAM em
`LG_SERVICE_ACCESS`: Secure, HttpOnly, SameSite Strict, host-only e limitado aos
prefixos explícitos de serviços. Nunca utiliza Path raiz nem estende o vencimento
original do access (máximo 900 segundos). O cookie não autentica APIs locais do Core
ou operações do supervisor; estas continuam exigindo Bearer.

Cada uso do cookie continua validando assinatura e PDP vivo. Escritas com cookie
exigem Origin exatamente igual a `CORE_PUBLIC_ORIGIN`. Tokens de navegação são
removidos dos headers enviados aos serviços publicados; cookies próprios dessas
aplicações são preservados. Login/refresh publicam contexto e logout limpa os
prefixos; nenhum refresh, assinatura JWT ou domínio de autenticação é criado no Core.
Este transporte suplementar é uma adaptação do gateway, sem alterar o protocolo V1
ou o processo standalone do IIAM.
