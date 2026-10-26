# Acesso temporario por login no PROD

O launcher integrado de PROD define `CORE_AUTHORIZATION_PERMISSIONS_REQUIRED=false`.
Nesse modo, servicos do gateway, supervisor e modulos de negocio do Core aceitam
qualquer usuario com token IIAM valido e sessao ativa, sem exigir grants de permissions.
O contexto `/api/v1/gateway/me` publica as mesmas capacidades aceitas pelos endpoints.

Cada requisicao protegida continua validando o JWT e a sessao no IIAM. Sessao revogada,
token invalido e ausencia de login continuam retornando 401; indisponibilidade da
validacao de sessao retorna 503. HTTPS e protecao de origem dos cookies continuam ativos.
Rotas de autenticacao e administracao do proprio IIAM mantem as regras do upstream.
Contratos que nao suportam escrita, como Receivables, continuam sem aceitar escrita.

A propriedade `app.security.permissions-required` tem padrao `true`. Para voltar a exigir
permissions do Core, alterar o valor no launcher integrado para `true` e reiniciar pelo
lifecycle. Esse modo usa o PDP do IIAM tanto nas requisicoes quanto nas capacidades e
cookies publicados para os servicos. O modo temporario nao cria roles nem grants no banco.
