# Auditoria de permissões — DEV, 16/10/2026

Este documento registra o snapshot anterior à implantação da área IAM.
O estado posterior tem 30 definições e validação de métodos habilitada;
consulte [IAM](iam.md) para os novos endpoints, permissões e testes.

## Catálogo, banco e API

Comparação executada entre `PermissionCode`, `PermissionDefinition`, tabela
`logimarui.authorization_permissions` do datasource de escrita DEV e resposta
autenticada de `GET /api/v1/authorization/permissions` na porta 81.

| Módulo | Código | Banco | API |
| --- | ---: | ---: | ---: |
| AUTHENTICATION | 7 | 7 | 7 |
| AUTHORIZATION | 12 | 12 | 12 |
| FINANCE | 1 | 1 | 1 |
| HEALTH | 2 | 2 | 2 |
| MEMBERSHIP | 4 | 4 | 4 |
| Total | 26 | 26 | 26 |

Nenhum código ausente ou excedente; descrições e módulos coincidem.
`RECEIVABLES_READ` está persistida em FINANCE e possui ID retornado pela API,
permitindo o vínculo a roles. O print fornecido também mostra essa permissão.
Não foi necessária inserção manual, alteração de roles ou SQL de migração.

O frontend não possui uma lista estática de permissões: `RolePermissionManager`
consulta o catálogo e os vínculos da role em paralelo pela `authorizationApi`.
A API utiliza agora o resolvedor compartilhado do gateway, corrigido anteriormente
para chamar a porta 81 a partir do frontend DEV 8191.

## Inicialização e integridade

`PermissionSynchronizationRunner` é um `ApplicationRunner` com `@Order(1)`, sem
restrição de perfil ou condição de bootstrap administrativo. Em toda inicialização
ele chama o serviço transacional `PermissionSynchronizationService`.

O serviço cria definições ausentes e atualiza descrição/módulo das existentes,
preservando IDs, datas de criação e vínculos. Uma segunda execução sem diferenças
não grava novamente. Uma falha é propagada ao startup; não é ignorada.

O bootstrap administrativo é outro runner, restrito a DEV, com `@Order(2)` e
condicionado a `security.bootstrap.admin.enabled`. A sincronização do catálogo
não depende dele. O bootstrap DEV, quando habilitado, associa todo o catálogo
à role administrativa configurada; roles comuns são administradas separadamente.

Foram adicionados logs de início, conclusão, criação e atualização para evidenciar
essa execução. O DevTools recarregou o backend após a compilação dos testes;
o log real do DEV registrou início e conclusão de sincronização de 26 definições
às 10:41:25 de 16/10/2026, horário de São Paulo.

Quatro testes passaram: correspondência exata entre enum e definições, criação e
idempotência entre subidas, atualização de metadados preservando identidade,
e presença/execução do runner em contextos dev/prod com bootstrap desabilitado.
Os contextos de teste usam mocks, sem conexão a ambiente PROD.

## Cobertura de autorização no restante do sistema

Todos os códigos referenciados por `@RequiredPermission` e `hasAuthority` estão
no catálogo. O OpenAPI vivo expõe 23 operações com `x-required-permissions`,
sem referências desconhecidas.

Isso não significa que toda funcionalidade já possui controle por permissões:

- Jornada e seu histórico, ocorrências, consultas de pedidos, etiquetas de
  clientes e reposição não declaram permissões próprias nos controllers.
- DTO e outras ferramentas expostas no frontend não ganham permissões somente
  por existir uma tela. É necessário definir a política e aplicá-la nos endpoints.
- Existem 11 códigos cadastrados sem referência nas anotações/expressões
  examinadas: os cinco `AUTHENTICATION_USER_*` básicos (READ, CREATE, UPDATE,
  DISABLE, ENABLE), dois de HEALTH e quatro de MEMBERSHIP.
- Na auditoria inicial não havia `@EnableMethodSecurity` no código de produção. Portanto os
  `@PreAuthorize` existentes, isoladamente, não garantiam bloqueio de acesso.
  Essa lacuna foi corrigida na implantação IAM descrita acima.
  `@RequiredPermission` é documentação do OpenAPI, não fiscalização de acesso.
- Boletos possui fiscalização explícita na `SecurityFilterChain`:
  GET em `/api/v1/receivables/**` exige `RECEIVABLES_READ`; demais verbos são negados.
  As demais rotas seguem as regras efetivas da mesma configuração, incluindo
  áreas públicas e a regra geral de autenticação.

Essas lacunas são de desenho/aplicação da autorização e não de sincronização
do banco. Este ajuste adiciona evidência e regressões da sincronização existente;
não inventa códigos ou altera a política de acesso global durante a auditoria.

O filtro JWT consulta as authorities do usuário no banco a cada requisição;
uma associação válida entre permissão, role ativa e usuário já é considerada
na próxima chamada autenticada do backend.
