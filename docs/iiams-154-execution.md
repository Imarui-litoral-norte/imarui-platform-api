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

## Sequência pendente

Provisionar IIAM local; proxy seguro; JWT UUID/JWKS; PDP/catálogo; clientes;
retirada dos módulos legados; homologação do DEV. Só depois ler contas legadas
e abrir solicitações idempotentes no IIAM. Não aprovar solicitações, atribuir
classificação ou transferir senhas/hashes/grants automaticamente.
