# Launchers da raiz do workspace

Os BATs e o lifecycle ficam na pasta acima do repositório Core, que não é um
repositório Git. As fontes versionadas desses arquivos ficam aqui para que as
correções de inicialização também sejam publicadas e recuperáveis.

Depois de sincronizar o Core, instale explicitamente o perfil correspondente:

```powershell
.\scripts\install-workspace-launchers.ps1 -Environment prod
# No checkout DEV:
.\scripts\install-workspace-launchers.ps1 -Environment dev
```

O instalador preserva os arquivos anteriores com um manifesto SHA-256 em
`.runtime/launcher-install-*`. Ele copia somente os três BATs e o lifecycle;
as configurações privadas e os processos em execução não são alterados.

No PROD, `executar-core.bat` e `refresh.bat` geram um frontend atualizado com
Webpack e então reiniciam os serviços. Se o servidor estiver parado, eles o
iniciam depois do build. `Finish.bat` encerra os serviços supervisionados.
No DEV, `executar-core.bat` conserva o comando de inicialização do perfil DEV.
PROD solicita elevação do Windows quando necessário.
O launcher aguarda apenas a conclusão do lifecycle elevado, permitindo que o
Java continue em segundo plano depois de o BAT retornar.

Os templates `dev` e `prod` são distintos para preservar os perfis e portas de
cada ambiente. Nenhum arquivo de `.runtime/iiam` ou `.env` deve ser versionado.

Cada compilação do frontend é feita em `.runtime/iiam-releases` dentro do
frontend, com saída e configuração TypeScript próprias. O build servido continua
intacto durante a compilação. A versão ativa é registrada somente após Gateway,
IIAM e frontend responderem HTTP 200. O build ativo e o anterior são mantidos;
versões mais antigas criadas pelo launcher são limpas após uma subida bem-sucedida.
