# IAM no DEV

Implementado em 16/10/2026. As três áreas são contratos separados, compostos
no frontend por um console IAM. O backend atual continua responsável por suas
persistências e pelos fluxos de autenticação existentes.

## Navegação e responsabilidades

O menu lateral possui IAM e um painel com Identidade, Autenticação e Autorização.
No celular, o painel ocupa a área de navegação e oferece retorno aos módulos.

| Área | Rota do frontend | Gestão disponível |
| --- | --- | --- |
| Identidade | `/iam/identidade` | Diretório paginado, busca por nome/e-mail/ID, consulta e edição de nome, e-mail, nascimento e telefone |
| Autenticação | `/iam/autenticacao` | Sessão atual, renovação, estado da conta, tentativas sem sucesso, datas de login/senha, sessões paginadas, revogação individual e recuperação administrativa |
| Autorização | `/iam/autorizacao` | Cadastro/edição/ativação/desativação/exclusão lógica de roles, catálogo de permissões, concessões por role, vínculos por identidade e acesso efetivo das roles ativas |

`/iam` redireciona para Identidade. O endereço anterior
`/authorization/role-management` redireciona para Autorização.

Cada área consulta sob demanda o OpenAPI do backend para mostrar seu contrato
de endpoints. A lista não executa requisições arbitrárias ou solicita payloads
de credenciais. As operações disponíveis têm formulários e controles próprios.

## Novos endpoints e permissões

| Operação | Permissão |
| --- | --- |
| GET `/api/v1/iam/identities?search=&page=0&size=25` | IDENTITY_USER_READ |
| GET `/api/v1/iam/identities/{id}` | IDENTITY_USER_READ |
| PATCH `/api/v1/iam/identities/{id}` | IDENTITY_USER_UPDATE |
| GET `/api/v1/iam/authentication/users/{userId}` | AUTHENTICATION_SESSION_READ |
| GET `/api/v1/iam/authentication/users/{userId}/sessions?page=0&size=25` | AUTHENTICATION_SESSION_READ |
| POST `/api/v1/iam/authentication/users/{userId}/sessions/{sessionId}/revoke` | AUTHENTICATION_SESSION_REVOKE |

São quatro novas definições, totalizando 30 no catálogo. A sincronização normal
as insere/atualiza em toda subida. O bootstrap administrativo DEV associa o
catálogo à role ADMIN quando configurado. Não foram atribuídas permissões
administrativas aos demais usuários para disponibilizar a interface.

Foi ativado `@EnableMethodSecurity` na configuração existente. Os
`@PreAuthorize` já declarados em Authorization e recuperação administrativa,
além dos novos endpoints IAM, passaram a ser efetivamente fiscalizados.
O menu é visível para usuários autenticados; a interface explica a permissão
necessária e o backend verifica cada operação independentemente da tela.
As rotas operacionais de outros módulos continuam com suas regras atuais.

## Separação da identidade

`IamModels.Identity` contém somente dados da pessoa e referência estável `id`.
CPF é retornado com apenas os dois últimos caracteres visíveis. As consultas
não selecionam hashes ou tokens. `AuthenticationState` e `Session` são
projeções distintas; permissões e roles usam seus contratos de Authorization.

O PATCH recebe somente `name`, `email`, `birthDate`, `phoneNumber` e
`expectedUpdatedAt`. Documento e credenciais não são alterados. A atualização
compara a revisão persistida e devolve 409 se outro processo tiver modificado
o registro. A revisão nula também é comparada. E-mail em conflito devolve 409.
As páginas têm tamanho limitado a 100 registros e ordenação determinística.

## Sessões e recuperação

Atividade de sessão depende de status ACTIVE e expiração futura. A interface
identifica registros ACTIVE já expirados como EXPIRED, sem alterar o histórico.

A revogação confere simultaneamente dono e ID da sessão, bloqueia a linha e
atualiza a sessão e os refresh tokens ativos na mesma transação. Repetir a
operação é permitido. Sessões de outras identidades não são afetadas; uma
combinação inexistente retorna 404. O filtro JWT verifica a validade da sessão
em cada chamada. Logs de mutações contêm IDs do ator/alvo, sem dados pessoais
ou segredos.

Recuperação usa os endpoints administrativos existentes para link de reset e
senha provisória. A geração exige confirmação e a permissão correspondente.
O resultado é oculto inicialmente e descartado em dois minutos, ao mudar a
identidade ou ao sair da área. Não é gravado em storage, logs ou arquivos.

Login, registro público, desafios de troca de senha e recuperação pública
continuam em suas telas/fluxos existentes. Eles aparecem no inventário da área
responsável. O registro legado ainda cria identidade e credencial em conjunto;
não foi criado um cadastro fictício de identidade sem suporte de persistência.

## Preparação para o microserviço

No backend, `iam/core` define projeções, casos de uso e o port `IamStore`.
`LegacyIamAdapter` contém exclusivamente o conhecimento das tabelas legadas.
Os controllers não dependem das entidades JPA de Authentication. Não foi
duplicado um banco de identidades nem alterado o vínculo canônico dos usuários.

No frontend, `features/iam` é isolada de features irmãs. O transport autenticado
é injetado por `views/iam`, que compõe a API pública de auth com a feature IAM.
Todas as URLs passam pelo gateway compartilhado. O futuro microserviço pode
substituir o adaptador e/ou o roteamento mantendo os contratos das telas.

Uma migração completa ainda precisará decidir identificador externo, provisionamento,
separação física de cadastro/credencial, OIDC/OAuth2, políticas de sessão e MFA,
auditoria persistente e governança de roles. Esses recursos não são simulados.

## Validação

- Suíte backend: 74 testes descobertos, sem falhas; três integrações opcionais
  sem credenciais foram puladas na execução geral.
- Teste adicional MySQL de `LegacyIamAdapter`: executado com fixture exclusiva
  dentro de transação marcada para rollback. Verificou concorrência cadastral,
  vínculo sessão/dono, revogação repetida e invalidação dos refresh tokens.
  A ausência da fixture foi confirmada após o rollback.
- Segurança: perfil sem permissão negado; leitor não edita cadastro nem revoga
  sessão; operação permitida executa com o ator correto; endpoints antigos de
  roles também exigem a permissão declarada.
- Frontend: lint, typecheck, testes de IAM e suítes existentes passaram; build
  executado pelo start do frontend no lifecycle DEV.
- Navegador isolado: consultas reais de diretório/conta/sessões/catálogo,
  desktop e celular, submenu com três links, redirect legado, perfil limitado
  e cancelamento de confirmação. Mutações de UI foram interceptadas, sem
  alterar cadastros, senhas ou vínculos reais durante o teste visual.

Evidências locais ignoradas pelo Git: `.runtime/iam-validation/`. O diretório
contém capturas e um resumo sem tokens ou credenciais. PROD não foi alterado.
