# STATUS

Atualizado pelo orquestrador a cada fechamento de tarefa. Fonte operacional; o histórico fino está em `.ia/rodadas/*.jsonl` (e `python3 scripts/enxame/status.py`).

Última atualização: 2026-11-18. Base: commit `8b8ec24` ("Versão 2", 2026-11-17), árvore limpa.

## Incidente de 2026-11-18

A árvore de trabalho estava divergente do HEAD: 2.747 arquivos (parte com data de 25/10, `pom.xml` e `scripts/jabs/checar-determinismo.py` encolhidos, 25 scripts de evidência de `scripts/jabs/` apagados, testes e DTOs da `api-gateway` removidos). Origem fora do git (reflog sem checkout nem reset). **Restaurada para o HEAD por decisão do humano.** O conteúdo divergente está guardado em `git stash` (`stash@{1}` = "backup-pre-restore-head-2026-11-18"; `stash@{0}` = um ADR-0014 antigo, "faixa-lote", que não existe no HEAD). Nada foi descartado de forma irreversível. Não rodar `git stash drop` sem decisão humana.

## Gates

| Gate | Estado | Motivo |
|---|---|---|
| loop1-chaincode | **liberado** | 6 de 6 tarefas fechadas |
| loop2-jabs-codigo | **fechado** | 4 tarefas em `PENDENTE_REVISAO_HUMANA` (backlog real, ver abaixo) |

## Loop 1: chaincode

L1-T1 a T6 fechadas (T5 `GAP_NAO_BLOQUEANTE`, auditabilidade do ator em ADR-0008). Faltam, segundo o domínio: INV-05/06/14/15 (transferir e aposentar), INV-13, INV-07/08/09/10 (revisão). Instalar o chaincode na rede depende de Docker Desktop ativo.

## Loop 2: JABS (resumo do que importa)

Aprovados ou aceitos, entre outros: PBFT tráfego medido e quórum 2f+1, Parlia sem `Math.random`, Casper quórum (C1), N1 (`--n-semantics`), M1/M2 (registro de θ̃, U, C_n e formação de bloco, aceitos pelo humano), HotStuff completo (núcleo, réplica em rede, líder, troca de visão, MVPComparison, EQUIVOCATION, CB fase 2), Tendermint (núcleo, adaptador, equivocação), PBFT prepare-equivocation, Logger (corrida de flush e chave sequencial, ADR-0016), BYZ-UNICA partes 1-3 (contagem inteira exata, gerador único aninhado, universo só de validadores, fora da faixa no `ByzantineSweep`).

DAGsper e Tangle estão fora do escopo do estudo (ADR-0009).

### Backlog que mantém o gate fechado

| Tarefa | Situação | Pré-condição |
|---|---|---|
| `V-ABC-tendermint` | pré-registro com 215 células, decisões do humano de 17/11 (MIN_EXEC=6, `--tm-*` CometBFT, V-C numérico inviável, P4b exige locking e votos nil) | pré-registro ainda não congelado (`COMMIT_PINADO` a definir); conferência do PBFT depois de D2 e paridade |
| `PARIDADE-teto-retencao-pbft-tendermint` | teto de retenção equivalente a `maxFutureViewHorizon` (1000) para PBFT e Tendermint | **fechada em 20/11/2026** (critic APROVADO na rodada 2; PR #2) |
| `BYZ-gerador-unico-conjuntos` | partes 1-3 commitadas em BYZ-UNICA; restam as partes 4 (D2) e 5 (paridade) | D2 e paridade |
| `AUTOENTREGA-uniforme` | = D2: autoentrega sai do Cb principal, coluna própria; ARQ honesto muda só em Cb; verificar Casper/Nakamoto/Parlia | tarefa pronta para começar |

Dívida aceita: `ByzantineSweep.java:111-126` monta `fValues` antes de limitar `maxByz` ao universo (só com `--max-byz` absurdo digitado à mão); entra na D2 sem rodada própria.

### Próximas tarefas (ordem aceita em 17/11/2026, uma por vez)

1. **D2** autoentrega uniforme (inclui a dívida do laço de `maxByz`).
2. **Paridade** do teto de retenção PBFT/Tendermint.
3. **`--n-semantics=nodes` obrigatório.**
4. **Ataque uniforme no MVPComparison** (inclui Tendermint e PBFT).

Decisões já tomadas que condicionam as próximas: Q13.1-13.4 opção A (gerador MMPP-2 depois da Bateria 2; MMPP-2 começa em estado sorteado da distribuição estacionária); Q35 gás constante por tx com trava g·L ≤ limite, **valor de g a definir com o humano** e repetir por script o achado dos 22% antes de aceitar; Parlia/universo opção C (f como percentual do universo de validadores, rotulado, `#` fora_da_faixa; tabela de f efetivo por n e protocolo no pré-registro).

## Loop 3: resultados e decisões

Fechados com `DECISAO_HUMANA_ACEITA`: ADR-0015 (sessão de decisão, `maxFutureViewHorizon`, parâmetros de literatura L/T/timeout), piloto de timeout do HotStuff (por n, confirmação, v2, K40), V-ABC do HotStuff (pré-registro, V-A e V-C, V-B com emenda E7). Resultado do V-B: `INCONSISTENTE`, sem ruptura de vivacidade em f* sob EQUIVOCATION com partição por índice; fechado com ressalva no ADR, reabrir com ataque mais forte (silêncio ou voto dirigido ao líder) e novo pré-registro. V-ABC do Tendermint: pré-registro em rascunho.

Resultado publicado que depende do C_B do PBFT continua com impacto CRÍTICO até a medição corrigida ser revalidada.

## ADRs

0001-0005 domínio (aceitos 2026-10-25); 0006/0007 PBFT (aceitos); 0008 auditabilidade do ator; 0009 escopo de protocolos; 0010 parâmetros de instrumento; 0011 setup P2P n pequeno; 0012 θ̃, U, C_n; 0013 formação de bloco e demanda; 0014 tamanho máximo de página; 0015 sessão de decisão de parâmetros; 0016 chave de transação do Logger e saída sem flag. Anexos reprodutíveis do 0015 em `docs/adr/anexos/`.

## Aplicação (Fabric)

- Rede: validação estática `scripts/rede/validar-rede.sh` (0 falhas, 5 avisos em 01/11); **rede não levantada**. Docker Desktop está ativo desde 18/11, mas nenhum deploy foi feito depois da restauração.
- API (`api-gateway/`): código e testes voltaram ao estado do HEAD; a fase 2 só começa com o chaincode instalado.

## Ambiente (verificado em 2026-11-18)

- Windows, Java 21 (JABS mira 17), Maven 3.9.11, Node 24, Python 3.14 (sem pandas).
- `bash` do PATH é o do WSL (`C:\Windows\System32\bash.exe`), que não enxerga o `npx` do Windows de forma confiável: **a memória Ruflo deve ser usada por `python3 scripts/enxame/memoria.py`** (equivalente a `memoria.sh`, com as mesmas recusas de segredo). Git Bash está em `C:\Program Files\Git\bin\bash.exe`.
- Parlia/Casper do JABS exigem `n >= 10`. `npx.ps1` é bloqueado; usar `npx.cmd`. Docker Hub direto falha; usar `mirror.gcr.io/`.
- Memória Ruflo: `.enxame/memoria-redd-jabs.db`, namespace `redd-jabs`, busca semântica funcional via `npx.cmd`.

## Estrutura de IA

Estas pastas e arquivos são ignorados pelo git e **não foram afetados pela restauração**: `.opencode/`, `.agents/`, `.claude/`, `.ia/`, `.enxame/`, `scripts/enxame/`, `especificacoes/`, `AGENTS.md`, `opencode.json`, `enxame.config.json`, `gauntlet-loop.config.json`, `docs/STATUS.md`. Como estão fora do git, **não há backup nem histórico**: vale copiar `.ia/` e `.enxame/` periodicamente.
