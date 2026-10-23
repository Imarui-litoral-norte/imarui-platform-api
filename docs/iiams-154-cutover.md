# Corte integrado DEV — IIAMS-154

## Executar e recuperar

Build frontend com `LOGIMARUI_PROFILE=dev` e
`NEXT_PUBLIC_GATEWAY_URL=https://localhost:8443`, usando `npm run build -- --webpack`.
Build Core com Java 17: `./mvnw.cmd -DskipTests package`.
O build precisa terminar antes de iniciar os serviços.

```powershell
./scripts/start-iiam-integrated-dev.ps1
./scripts/verify-iiam-gateway.ps1
```

Entrada: `https://localhost:8443`. Core publica o Next interno 8191 e o IIAM
interno 4105, ambos gerenciados pelo supervisor. PostgreSQL 18 local roda como
serviço Windows, com banco/role exclusivos `iiam_dev`. Nenhum Docker foi criado.
Os demais serviços seguem o `.env` local: SAVI e Crítica habilitados;
Extração, Monitoring, Backup, n8n e Evolution desabilitados neste ambiente.

O certificado `.runtime/iiam/localhost.crt` precisa ser confiável no navegador.
Instalar manualmente em Usuário Atual / Autoridades de Certificação Raiz Confiáveis.
O truststore Java do Core contém o certificado local e as autoridades padrão,
sem desabilitar validação TLS. Configuração privada está fora dos repositórios,
em `../.runtime/iiam`, com ACL restrita. Não publicar esse diretório.

Recuperação local se o IIAM ficar indisponível:

```powershell
./scripts/start-iiam-integrated-dev.ps1 -Action restart
```

Com IIAM indisponível, APIs protegidas recusam acesso; a recuperação local não
depende de uma autorização HTTP do serviço que está parado. O launcher confere
o processo Core, os listeners e a data de criação dos processos contra o registro
do runtime antes de parar o DEV. PIDs reutilizados ou sem data verificável são recusados.
PostgreSQL continua ativo. No reconcile, o IIAM passa pelo runner Java, que
confere o executável e o artefato antes de encerrar um PID persistido.
No Windows, o runner consulta a linha de comando pelo CIM, porque o Java 17
não disponibiliza os argumentos de filhos em `ProcessHandle.Info`. Se o stop
for recusado, o runtime IIAM permanece registrado. O stop aguarda a saída do
ProcessHandle verificado, antes de permitir a nova instância.

Restart real pela API do supervisor, preservando a sessão atual e a porta:
`python scripts/verify-iiam-restart.py --restart` (somente DEV localhost:8443).

## Operadores

O bootstrap sintético SERVICE de governança é `logimarui-dev-operator`;
credencial no arquivo privado `operator.properties`. O consumidor sintético
do Core é `logimarui-dev-user`, em `core-operator.properties`. São contas distintas
para preservar a proteção contra autoelevação. O consumidor não recebe ROOT.
As oito pessoas do legado continuam sem Identity ou senha no IIAM.

## Contratos de cliente

| Legado | Contrato integrado |
| --- | --- |
| `/api/v1/authentication/login` | `/api/iam/authentication/login`: identityKind/identifier/password, CSRF |
| Refresh no JSON/storage | `/api/iam/authentication/refresh`: cookie HttpOnly e CSRF, rotação serializada por Web Locks |
| `/api/v1/authentication/me` | `/api/v1/gateway/me`: Identity própria e capacidades via PDP |
| Logout legado | `/api/iam/authentication/logout`, cookie e CSRF; limpeza do contexto de navegação |
| Cadastro com senha | `/api/iam/api/v1/identity-applications`: solicitação pendente |
| IAM/Role local | APIs V1 do IIAM; UUIDs de Identity/Tenant/Scope e IDs técnicos próprios para Role/Assignment/Deny |
| Recuperação/ativação local | recovery/request, recovery/complete e activation/complete do IIAM; frontend /first-access e /reset-password |

Não existe compatibilidade com JWT HS256 ou sessão legada. Access fica em memória
no cliente. Transporte suplementar Secure/HttpOnly por prefixo serve somente às
aplicações publicadas; não autentica as APIs Core nem o supervisor.
SMTP não foi homologado; convites podem ser gerados manualmente no console IAM.

## Solicitações legadas

Após o gateway integrado passar nos cenários reais, o importador leu apenas
id/nome/CPF/e-mail/nascimento/telefone/status do MySQL, em transação READ ONLY.
Não consultou password_hash ou tabelas de grants. Oito contas ACTIVE eram elegíveis;
oito solicitações PERSON foram criadas, todas PENDING, sem Tenant, Affiliation,
Identity resolvida ou ator de revisão. Reexecução: zero novas, oito correspondentes.

Apelidos técnicos determinísticos `legacy-core-<id>` permitem retomada. O mapeamento
de IDs antigos para UUIDs de solicitações, a projeção de origem e os relatórios ficam
apenas no runtime privado. Aprovação, classificação e convites ficam com o usuário.

```powershell
python scripts/import-legacy-identity-applications.py          # inventário
python scripts/import-legacy-identity-applications.py --apply  # retoma solicitações
```

O importador exige evidência anterior do gateway funcionando e não contém chamada
de aprovação, classificação, criação de senha ou grants. Dados obrigatórios ausentes
são reportados, nunca inventados.

## Evidências e limites

| Cenário | Resultado |
| --- | --- |
| Core `mvn test` com IIAM real | 110 testes; zero falhas/erros; 3 skips READ_DB |
| Launcher: ownership de PIDs | 5 verificações de metadados válidos/reutilizados/ausentes, sem encerrar processos no teste |
| Frontend npm test / typecheck / lint focado / build webpack | passaram |
| HTTPS real pelo gateway | 22 cenários passaram |
| Receivables, SAVI e Crítica via gateway | responderam com sucesso |
| ALLOW / DENY / revogação / catálogo idempotente | testes reais IIAM passaram |
| IIAM interrompido | supervisor respondeu 503 sem fallback |
| Restart IIAM pela API do supervisor | processo substituído, mesma porta, sessão autenticada preservada |
| Rotação JWKS e tokens inválidos | testes de contrato passaram |
| JWKS indisponível com cache vazio | 503 sem aceitar token; assinatura/kid inválidos com JWKS disponível continuam 401 |
| Fonte MySQL preservada / reexecução do importador | 8 contas na fonte; 8 solicitações pendentes, sem duplicação |
| Operador administrativo / contexto próprio / revisão | acesso autorizado pela API às 8 solicitações PENDING; sem aprovação |
| Formulários públicos no frontend interno | login e solicitação renderizaram; sem envio de formulário |
| Login HTTPS / refresh real em várias abas | pendente da confiança manual do certificado |
| Serviços desabilitados e fluxos SMTP | sem homologação operacional nesta máquina |

Fonte IIAM fixada em `4a299044b767d11a0c7be0db33298043b570b9dd`, checkout
concorrente sem alterações no momento da conferência. Artefato isolado no snapshot
privado `build-v1`; o checkout do outro agente não foi modificado.
SHA256 do artefato IIAM: `8BFC6F2F8525DE0B011D3909CE48C533FFFCD715086C009403CBBC9FD8B4CFB3`.

## Rollback

Parar somente o DEV integrado com `-Action stop`. Preservar banco PostgreSQL,
migrations publicadas, chaves e solicitações. Não reativar emissão de tokens legados.
O Core MySQL mantém as tabelas antigas e os módulos de negócio; Hibernate valida
o schema inclusive na EntityManagerFactory customizada, sem `update` implícito.
Os fontes removidos estão no arquivo local com manifesto SHA256. A volta a um
snapshot anterior exige considerar que alterações posteriores do IIAM não existem
no legado. Não houve merge, deploy, VPS ou alteração do checkout PROD nesta rodada.
