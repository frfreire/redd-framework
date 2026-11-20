# ADR-0006: PBFT mede tráfego em vez de estimar por fórmula (tarefa zero do loop 2)

- **Estado:** Aceito (2026-10-25, Fabricio)
- **Data:** 2026-10-25
- **Decisores:** Fabricio (humano). Nenhum agente aceita este ADR.
- **Trava:** loop 2 (JABS) inteiro e qualquer bateria de experimentos. O gate `loop2-jabs-codigo` só abre depois deste ADR aceito e da tarefa zero aprovada pelo critic.
- **Origem:** mesmo problema já decidido em `framework-redd` (ADR 0001, 0006, 0007). Esta raiz é base separada e ainda tem o defeito.

## Contexto (verificado em 2026-10-25)

1. PBFT reporta tráfego por fórmula: `PBFT.java:125-129`, `baseTraffic = numAllParticipants * 2 * 1024`, ruído gaussiano 15 %, piso 50 %, passado ao `BlockFinalizationEvent`. Custo linear em n, embora o protocolo faça broadcast todos-para-todos em PREPARE e COMMIT. Parlia, CasperFFG e Nakamoto acumulam `packet.getSize()` (`PeerBlockchainNode.java:45-56`).
2. PBFT não entra no caminho de medição: `processIncomingPacket` só chama `addBlockTraffic` para Parlia, CasperFFG e Nakamoto. Lint JB-020 acusa PBFT e DAGsper (tráfego sairia 0).
3. Aleatoriedade fora da semente: `Random(42)` no PBFT e no `TangleIOTA`; `Math.random()` em `Parlia.java:190`.
4. Quórum acima de 2f+1: `requiredVotes=(n/3)*2+1` com `currentVotes > requiredVotes` (`PBFT.java:67,108`). Para n=4 o código exige 4 votos, não 3.
5. 36 `catch (Exception)` vazios (JB-009).
6. Todo C_B do PBFT/ARQ já publicado desta base é estimativa: tratar como impacto CRÍTICO no loop 3 até refazer.

Hipótese herdada de `framework-redd`, NÃO reproduzida aqui: após medir, o C_B total do PBFT não cresce em O(n²) para n=25..200, porque o PRE_PREPARE leva o bloco (~1 MB) e PREPARE/COMMIT só o hash (~42 B). Daí a decomposição C_B_total / C_B_consenso e a convenção "tráfego recebido por nó".

## Opções

- **A (recomendada).** Medir no PBFT com dois acumuladores (bloco e consenso), tráfego por nó, remover fórmula e `Random(42)`, usar `RandomnessEngine`, quórum 2f+1 com teste. Prós: comparação legítima, determinismo. Contras: refazer simulações; escopo maior.
- **B.** Manter estimativa e declarar limitação. Comparação de C_B segue inválida; contraria "medido, não estimado". Não recomendada.
- **C.** Fórmula O(n²) para todos. Perde contenção e transiente; esvazia o simulador.
- **D.** Só PBFT agora; DAGsper, TangleIOTA e Parlia ficam como dívida rotulada em todo resultado.

## Decisão

Aceito pelo humano em 2026-10-25 ("Aceito o ADR006"), com a recomendação A, em tarefas de um gap cada, nesta ordem: (1) PBFT tráfego medido + remover `Random(42)`; (2) quórum 2f+1; (3) Parlia `Math.random()`; (4) DAGsper e TangleIOTA; (5) catch vazios.

- Opção escolhida: A
- Data do aceite: 2026-10-25
- Decisor: Fabricio (humano)

## Consequências

- Altera `PBFT.java`, `PeerBlockchainNode.java`, logger (campos C_B_total e C_B_consenso) e cria `src/test`.
- C_B anterior do PBFT/ARQ deixa de valer (CRÍTICO, humano decide).
- Todo resultado declara semente, versão, granularidade (por nó) e decomposição.
- `redd-ponte` não calibra o PBFT enquanto o tráfego for estimado; Fabric usa Raft, não bizantino.
- Até 15 rodadas (5 tarefas x 3); pode terminar em decisão humana.

## Nota de não regressão (critério 7)

A tarefa 1 muda **intencionalmente** a saída do PBFT/ARQ: C_B passa de fórmula para medição por nó (recebido), com C_B_total e C_B_consenso. Todo C_B anterior do PBFT/ARQ é inválido (CRÍTICO, decisão humana). Protocolos não tocados (Parlia, Casper, Nakamoto) devem manter a saída para semente fixa.

## Verificação

- `grep` sem `numAllParticipants * 2 * 1024` nem `Random(42)` em `PBFT.java`; PBFT no hook de `PeerBlockchainNode`.
- `lint-jabs.py`: JB-020/JB-003 do PBFT somem (hoje 44 bloqueantes).
- `checar-determinismo.py --protocolo PBFT --validadores 7`: mesma semente igual; sementes distintas, tráfego distinto.
- `validar-complexidade.py` sobre C_B por nó e sobre C_B_consenso, n=25,50,100,200, >=2 sementes.
- Teste de quórum: n=4 finaliza com 3 votos, não com 2.
- Critic aprova no loop 2 e `--verificar-gate loop2-jabs-codigo` sai com 0.

## Fechamento da tarefa 1 (2026-10-25)

- Loop 2, `T1-pbft-trafego-medido`: 3 rodadas de builder, 2 rejeições do critic (critério 7 nas duas), gap da rodada 2 classificado `GAP_NAO_BLOQUEANTE` pelo arbiter. Gate `loop2-jabs-codigo` liberado.
- Evidência de não regressão: `src/test/resources/nao-regressao/nao_regressao.py`, semente 1, baseline original reconstruída (cópia dos 4 arquivos originais). Casper/MCO2 (n=10, 50) e Nakamoto/OFFSET (n=40, 60) idênticos. A coluna nova `TrafficConsensusUntilFinalization` vale -1 nesses protocolos.
- **Débito técnico 1:** Parlia/AMBIFY não é determinístico por `Math.random()` em `Parlia.java:190`; a baseline diverge de si mesma. Com o valor fixado nos dois lados fica idêntico. Resolve na tarefa 3.
- **Débito técnico 2:** Nakamoto/OFFSET varia entre execuções da baseline em algumas sementes (2 e 3, n=40/80). Causa não diagnosticada; investigar antes de qualquer bateria que inclua OFFSET.
- **Débito técnico 3:** `checar-determinismo.py` não termina com n pequeno (n<10 Casper/Parlia, n<30 Nakamoto) e `--protocolo PBFT` não existe no `MVPComparison` (usar `ARQ`). O Verificação deste ADR deve ler `--protocolo ARQ`.
- Medição ARQ n=7, 60 s: C_B_total ~1,24 MB, C_B_consenso 514 a 556 B (sem as 5 tarefas, não vale como resultado publicável; só smoke test).

## Fechamento da tarefa 2 (2026-10-25)

- Loop 2, `T2-pbft-quorum-2f1`: quórum 2f+1 com `>=` em PREPARE e COMMIT; PREPARE só conta após PRE_PREPARE aceito, COMMIT só após prepared, votos adiantados retidos e reavaliados (`PBFTQuorumTest`, 17 testes). 3 rodadas; critic rejeitou 2x pelo critério 3 (pré-condições de fase; validação do primário). O gap do primário foi classificado `GAP_NAO_BLOQUEANTE` pelo arbiter (decisão final).
- Mudança intencional de saída do ARQ/PBFT (quórum menor), medida na rodada 1, n=4/7/10, semente 1, 1 trial, 60 s: Tb_avg base 0,926/0,922/1,191 contra atual 0,716/0,951/1,144; Cb_avg base 1,052/1,035/0,939 contra atual 0,966/0,969/1,086. Um trial só, sem valor estatístico; todo número de PBFT/ARQ anterior à T2 vale como quórum acima de 2f+1.
- **Débito técnico 4 (arbiter):** o PBFT aceita PRE_PREPARE de qualquer remetente; não valida o primário da view; a view é local a cada nó; `PBFTLocalLANNetwork.java:97` fixa o proponente em `min(10, n-1)` em vez de `view % n`; view change não existe (`PBFT.java:52`). Medido em cópia: só validar o remetente derruba o ARQ a 0 blocos; alinhando o agendador, o primário legítimo ainda é rejeitado por receptores com view defasada. Exige tarefa própria (validar remetente, alinhar agendador, sincronizar view) e ADR de mudança intencional de saída.
- **Consequência para resultados:** enquanto o débito 4 existir, o ARQ/PBFT não modela rotação de líder nem falha de primário; resultado que envolva líder bizantino ou view change não vale. Rotular em todo resultado.
- OFFSET n=60 diverge na própria baseline (débito 2, pré-existente).

## Tarefa 3 entregue (2026-10-26), em revisão

- `T3-parlia-aleatoriedade`: `Math.random()` de `Parlia.java:190` trocado pelo `RandomnessEngine` do cenário. **Mudança intencional de saída do Parlia/AMBIFY**: antes não reprodutível; nenhum número de AMBIFY anterior à T3 é comparável.
- **Débito técnico 5 (reportado pelo builder):** `EthereumMinerNode.memPool` é `HashSet<EthereumTx>` sem `hashCode`; na mesma JVM o conteúdo dos blocos de Casper e Parlia não é reproduzível (entre processos é). Trocar por `LinkedHashSet` mudou a saída do Casper MCO2 n=50, então não entrou. Exige tarefa própria e ADR de mudança de saída do Casper.
- **Débito técnico 6:** PBFT/ARQ com primário validado depende do ADR-0007 (aceito, opção 2).

## Tarefa 4 entregue (2026-10-26), em revisão: DAGsper e TangleIOTA

Nota de não regressão (critério 7), declarada pelo orquestrador a partir do relatório do builder:

- **Mudança intencional de saída: DAGsper.** Antes não emitia `BlockFinalizationEvent`. Agora emite um por bloco finalizado, com `C_B_total` (bloco mais votos recebidos) e `C_B_consenso` (só votos), por nó recebido, medidos em `PeerBlockchainNode`. Um voto é atribuído ao bloco que ele nomeia (`toBeJustified`).
- **Mudança intencional de saída: TangleIOTA.** Saem `Random(42)`, a estimativa fixa de 15 x 512 B e o ruído gaussiano; o tráfego passa a ser a soma de `packet.getSize()` recebidos por bloco (hook em `PeerDLTNode`, porque `IOTANode` herda de `PeerDLTNode` e não de `PeerBlockchainNode`). `C_B_consenso` fica em `NOT_MEASURED` (-1). O Tangle não consome mais aleatoriedade, então "mesma semente / sementes distintas" não se aplica a ele.
- **Nenhum número de DAGsper ou TangleIOTA anterior à T4 é comparável.**
- **Limite de evidência (importante):** nenhum dos dois roda ponta a ponta pelo `MVPComparison` (sem `case`). `IOTANode` dá NPE na construção já na baseline (genesis com `parents == null`), `confirmBlock` do Tangle não é chamado por ninguém, e `DAGsperGlobalBlockchainNetwork` não instancia o `DAGsper`. A medição foi provada só em teste unitário com rede montada à mão. **Não existe resultado de DAGsper nem de Tangle que valha como pesquisa até haver cenário executável (débito 7).**
- PBFT/ARQ, Parlia, Casper e Nakamoto mantêm a saída (harness `scripts/jabs/nao_regressao.py`, semente 1, base x atual idêntico em MCO2:10:60, MCO2:50:600, OFFSET:40:20000, ARQ:7:60, AMBIFY:10:60).
- **Débito técnico 7:** criar cenário executável para DAGsper e Tangle (`case` no `MVPComparison`, instanciar `DAGsper`, genesis válido para `IOTANode`) antes de qualquer bateria que os inclua.
- Rede P2P do DAGsper trava com n < 10 (laço infinito), como Casper/Parlia.

### Tarefa 4: rodada 3 e parada por orçamento (2026-10-26)

- **Convenção (T4, rodada 3): tráfego recebido por nó conta apenas pacotes que chegaram pela rede simulada** (fila de envio, latência e recepção). No DAGsper, o voto que o nó entrega a si mesmo (`DAGsper.java:287-288`) e o bloco que o minerador entrega a si mesmo (`EthereumMinerNode.java:72-75`) são chamadas diretas a `processIncomingPacket` com `from == this`. Não são tráfego e não somam bytes; o hook (`PeerBlockchainNode.isLocalSelfDelivery`) os ignora só para DAGsper. O broadcast para os outros nós continua contado com `packet.getSize()`. O PBFT conta o broadcast que inclui o remetente, porque ele passa pela rede (`PBFTP2P.java:9`).
- **Pendência de consistência (débito 8):** Casper e Nakamoto/Bitcoin ainda contam autoentrega local. Corrigir muda a saída deles; exige tarefa e ADR próprios.
- **Orçamento esgotado (3/3 rejeições)**, sem aprovação do critic. Rejeições: (1) crit. 7, nota no ADR (resolvida); (2) crit. 1, autoentrega contada como tráfego (corrigida na rodada 3); (3) crit. 3, o DAGsper compara com `>` contra `(n/3)*2+1` (`DAGsper.java:187,203`), exigindo mais que 2f+1 (n=7 pede 6 votos, o certo seria 5), o mesmo defeito que a T2 corrigiu no PBFT.
- **Decisão humana pendente** (ver mensagem da sessão): o gap 3 é quórum, não tráfego nem semente.

## Tarefa 4: rodada 4 (extra, autorizada pelo humano) e fechamento

### Correção do quórum do DAGsper

- **Defeito:** o DAGsper comparava `> (n/3)*2+1` na justificação e na finalização (`DAGsper.java:187,203`). Com n=3f+1 isso exige 2f+2 votos, um a mais que o quórum bizantino. É o mesmo defeito que a T2 corrigiu no PBFT. **É anterior à T4**: já está na cópia original em `framework-redd`; a T4 não o introduziu.
- **Correção:** quórum 2f+1, com `f = floor((n-1)/3)` e comparação `>=`. A checagem de altura não finalizável usa o mesmo valor.
- **Mudança intencional de saída (critério 7): DAGsper.** Votos necessários para finalizar caem de 4/6/8 para 3/5/7 (n=4/7/10). **Nenhum número de DAGsper anterior à correção é comparável**.

### Lacuna e limite de evidência

- **Lacuna (fonte):** a fórmula 2f+1 com `f = floor((n-1)/3)` não foi confrontada com o artigo do DAGsper, que não está no repositório. Vale como quórum bizantino clássico, não como confirmação do protocolo do artigo. **A conferir com o humano.**
- **Limite do teste:** o quórum foi testado por injeção direta de votos em `newIncomingVote`. Isso não prova finalização ponta a ponta com n<10, e a rede P2P do DAGsper trava com n<10 (ver "Tarefa 4 entregue"). A prova ponta a ponta depende do cenário executável (débito 7).

### Fechamento

- **T4 fechada como `GAP_NAO_BLOQUEANTE`, por decisão humana** (rodada extra, fora do orçamento de 3). O critério 6 (portão V-A/B/C) não é satisfeito para DAGsper e Tangle porque nenhum dos dois roda pelo `MVPComparison`. Isso é o **débito técnico 7**, pré-existente, e não foi causado pela T4.
- **Nova tarefa: `T5-dagsper-cenario-executavel`** (débito 7): `case` no `MVPComparison`, instanciar `DAGsper` em `DAGsperGlobalBlockchainNetwork` e genesis válido para `IOTANode`, conforme o débito 7. O travamento da rede P2P do DAGsper com n<10 continua registrado acima; se entra no escopo da T5, a definir com o humano.
- **Restrição até a T5 fechar:** DAGsper e TangleIOTA ficam **fora de qualquer bateria** de experimentos. Nenhum resultado deles vale como pesquisa.

## Notas de mudança intencional de saída e de não regressão (registro de 2026-10-30)

Notas que os builders entregaram como texto sugerido e que ninguém tinha gravado. Cada afirmação foi conferida nos arquivos de evidência e no código. O que não foi encontrado em arquivo está marcado **não confirmado**. Esta gravação não reexecutou simulação nem Maven.

Marcas: **[E]** executado por builder ou critic, com saída arquivada que li. **[C]** calculado agora a partir dos arquivos. **[L]** por leitura de código, de log ou de texto.

### Baseline de não regressão: o que `e703e3d` é e o que não é

> **Atualização de 2026-10-30 (corrigido em 2026-10-30).** Esta seção foi escrita quando `e703e3d` era o único commit. **Agora existe o commit `325f4e3`** (`JABS: HotStuff (dados, rede, replica), P2P n pequeno, quorum Casper, ataque unico`), de 2026-10-30 18:29:27 -03 (21:29:27Z), **criado a pedido do humano** (registro de rodadas, linha 84, tarefa `BASELINE-commit`, `DECISAO_HUMANA_ACEITA`). **[E]** `git log` lista os dois commits, `325f4e3` sobre `e703e3d`. Segundo o registro, `325f4e3` **inclui** B1, P2P-1, C1, D3a-1, D3a-2a-1, D3a-2a-2a, D3a-2a-2b, D3a-2a-2c-1 e D3a-2a-2c-2a. **[C]** Conferi pelo diff `e703e3d..325f4e3` (`git diff --numstat`): `HotStuff.java` (471 linhas), `HotStuffProposalMessage`, `HotStuffQcNetworkMessage`, `HotStuffP2P`, o pacote `consensus/algorithm/hotstuff/*` (ReplicaCore, ReplicaState, SafetyRule, VoteAggregator, ViewLeaders etc.), `CasperFFG` (54+/2-, C1) e `AbstractBlockchainP2PConnections` (106+/4-, P2P-1) estão no commit. A correspondência tarefa a tarefa vem do registro **[L]**; não reexecutei Maven nem simulação.
>
> **Papel de cada baseline daqui para a frente:**
> - **`325f4e3` é a baseline das próximas tarefas de não regressão.**
> - **`e703e3d` permanece como referência histórica**: é anterior a P2P-1 e C1 (e a D3a-2a-1 em diante) e posterior à aprovação do B1. Toda a evidência já arquivada (`p2p-1/`, `c1-casper/`, `b1/`) foi gerada contra `e703e3d` ou sem baseline registrada e **continua válida só para o que declara**; não foi regerada contra `325f4e3`.
> - O texto abaixo (e a "Regra para as próximas tarefas") fica como histórico. Onde ele diz "commit novo, a ser criado pelo humano", leia: **criado, é o `325f4e3`**.

- **[C]** O único commit do repositório é `e703e3d47da726f5fbcf94657f574db617a59023` ("Experimento", commit inicial), de 2026-10-29T20:36:56Z (`.git/logs/HEAD`, timestamp 1793306216 convertido).
- **É anterior a P2P-1, C1, D3a-2a-1 e D3a-2a-2a.** **[C]** No worktree `/tmp/opencode/base-e703e3d` (limpo, HEAD em `e703e3d`) não existem `hasEligibleCandidate` (P2P-1) nem `supermajorityThreshold` (C1), e `PeerBlockchainNode.java` não cita HotStuff. As entradas de builder desses trabalhos são de 2026-10-29T20:59Z em diante (`.ia/rodadas/loop2-jabs-codigo.jsonl`, linhas 48 e seguintes).
- **NÃO é anterior ao B1.** O critic aprovou o B1 às 2026-10-29T19:54:43Z (jsonl, linha 43), 42 minutos antes do commit. **[C]** O worktree da baseline já tem `ByzantineConfig.validateAttackName` (5 ocorrências de `validateAttackName`/`supportedAttacks` no arquivo), `supportedAttacks` em `AbstractConsensusAlgorithm`, `CasperFFG`, `PBFT` e `NakamotoConsensus`, `requireMeasuredTrials` em `ByzantineSweep` e `src/test/java/jabs/config/ByzantineConfigAttackTest.java`. Não tem `catch (Exception ignored)` ativo em `PBFT`, `CasperFFG` e `BitcoinNode`; em `Parlia` só em comentário. Os 14 `catch (Exception ignored)` ativos da baseline estão nos mesmos arquivos dos 14 de hoje.
- **Consequência:** não existe baseline "antes do B1". A frase "o único commit baseline é anterior a B1, P2P-1 e C1" é falsa para o B1 e vale para P2P-1 e C1. A não regressão de P2P-1 e C1 contra `e703e3d` é legítima. A do B1 não é contra código pré-B1 (ver a seção do B1).
- **D3a-1 [L/C]:** o worktree da baseline contém `ledgerdata/hotstuff/*` e `HotStuffConsensusConfig`, no estado do momento do commit. O critic aprovou a rodada 5 do D3a-1 às 20:47:44Z, depois do commit. Se a baseline é idêntica ao aprovado: **não confirmado**.
- **Regra para as próximas tarefas:** a baseline de não regressão deve ser um **commit novo, a ser criado pelo humano**. Aviso **[L]**: a árvore de trabalho de hoje contém código do D3a-2a-2b sem entrada de builder nem de critic no registro (`PeerBlockchainNode.java`: ramos de `HotStuffProposalMessage` e `HotStuffQcNetworkMessage`; `HotStuff.recordProposalTraffic`, `recordQcMessageTraffic`; `onProposal` e `onQcMessage` lançam `UnsupportedOperationException`). O humano decide se esse código entra no commit-baseline. Se entrar, ele vira "baseline" sem revisão.
  - **Corrigido em 2026-10-30.** (i) O commit foi criado: `325f4e3`, a nova baseline. (ii) O D3a-2a-2b **agora tem** entrada de builder (rodada 1, 2026-10-30T17:28:37Z, 256 testes) e de critic (`APROVADO`, 17:28:51Z) no registro (linhas 72 e 73); as duas entradas são posteriores ao código que eu tinha lido (o humano registrou a tarefa às 11:06:17Z, linha 71), isto é, o registro foi feito depois. (iii) A frase "`onProposal` e `onQcMessage` lançam `UnsupportedOperationException`" **envelheceu**: após a D3a-2a-2c-2a, `onProposal` delega a `replica.onLeaderMessage` e `onQcMessage` trata DECIDE e NEW_VIEW; só lança para outro tipo de QC (`HotStuff.java:281-313` **[L]**, "troca de visão em 2c-3"). (iv) "Se entrar, vira baseline sem revisão" **não se aplica ao 2b, 2c-1 e 2c-2a**: os três têm critic `APROVADO` ou, no caso da 2c-2a, `GAP_NAO_BLOQUEANTE` por decisão do humano (linha 83: `TreeBlockAccess.resolve` lê o pai de `Block.getParents` mutável antes da admissão; requisito para a 2c-2b: campo `parentRef` imutável no `HotStuffBlock`). **A 2c-2a entra na baseline com gap aberto, não aprovada limpa.**

### B1-ataque-desconhecido-falha-alto (APROVADA na rodada 4 pelo critic; 163 testes na época)

Estado do registro: 4 rodadas de builder; rodadas 1 a 3 rejeitadas no critério 4; a rodada 4 foi extra e autorizada pelo humano com escopo ampliado (jsonl, linhas 35 a 43). O critic aprovou às 2026-10-29T19:54:43Z. **[L]** O número 163 vem do registro; não rodei a suíte agora.

**O que mudou (por leitura de código; testes citados existem em `src/test`)**

- **[L]** `ByzantineConfig.validateAttackName` (`ByzantineConfig.java:87-99`) só aceita o nome exato de `AttackType` (`SILENT`, `WITHHOLD`, `EQUIVOCATION`, `DOUBLE_SIGN`, `NONE`). `null`, vazio, caixa diferente e tradução (`equivocacao`) lançam `IllegalArgumentException` com a lista dos válidos. Os três construtores passam por ela (`:38`, `:51`, `:69`). Testes: `ByzantineConfigAttackTest` (`recusaNomeEmPortuguesDoYamlComMensagemListandoOsValidos`, `naoNormalizaCaixaNemEspacos`, `recusaNuloEVazio`).
- **[L]** `AbstractConsensusAlgorithm.supportedAttacks()` tem padrão vazio (`:129-131`) e `setByzantineConfig` recusa com `UnsupportedOperationException` o ataque que o protocolo não declara (`:115-141`). `NONE` e config nula são sempre aceitos. Declarações atuais: PBFT, CasperFFG e Parlia aceitam `SILENT`, `WITHHOLD`, `EQUIVOCATION`, `DOUBLE_SIGN`; Nakamoto aceita `WITHHOLD` e `EQUIVOCATION`; DAGsper tem override próprio (conteúdo não lido por mim).
- **[L]** `ByzantineSweep`: erro de configuração (`IllegalArgumentException`, `UnsupportedOperationException`) aborta o sweep (`:278-283`). `requireMeasuredTrials` lança `IllegalStateException` com a marca `NAO_MEDIDO` quando nenhum trial rodou (`:69-76`, chamada em `:292`), em vez de gravar `NaN`. DAGSPER sem `--attack` explícito falha (`:43-50`). Testes: `ByzantineSweepConfigTest`.
- **Catches removidos.** Aprovação do critic e entrada do builder (rodada 4) dizem "13 catch ignored removidos em Casper, Parlia, BitcoinNode e 3 correlatos". **[C]** Hoje há 0 `catch (Exception ignored)` ativos em `PBFT`, `CasperFFG` e `BitcoinNode`; em `Parlia` só em Javadoc (`Parlia.java:272`). **Não confirmado:** o número 13 (a contagem exata por arquivo não está em arquivo de evidência; a baseline já vem sem eles, ver acima). **[C]** Restam 14 `catch (Exception ignored)` ativos (`EnhancedBlockFinalizationLogger` 9, `AbstractScenario` 2, `AbstractCSVLogger`, `ArqReddVotingScenario`, `CasperVoteDeliveryEvent` 1 cada), fora dos hooks bizantinos; a nota do critic cita "14 bloqueantes" do linter, que coincide em número.
- **[L] Mudança de comportamento:** Nakamoto com `SILENT` ou `DOUBLE_SIGN` rodava como nó honesto sem erro e agora falha na montagem (`UnsupportedOperationException`, `NakamotoConsensus.java:30-39`). Teste ponta a ponta: `cenarioNakamotoComSilentFalhaAltoEmVezDeRodarHonesto`.

**Não regressão: o que foi e o que não foi comparado**

- **[E]** Matriz própria "antes/depois": `/tmp/opencode/sig-antes.txt` (e réplica `sig-antes-b.txt`) contra `/tmp/opencode/sig-depois-final.txt`, 80 linhas cada, geradas por `src/test/java/jabs/scenario/SignatureMatrixMain.java`. Células: MCO2 e AMBIFY com n=10 e 16; OFFSET e EARTH com n=24 e 40; sementes 1 e 42; 5 modos (`NONE`, `SILENT`, `WITHHOLD`, `EQUIVOCATION`, `DOUBLE_SIGN`) = 80. **[C]** Comparei os dois arquivos agora: **idênticos nas 80 linhas** (0 divergências). `antes` e `antes-b` também idênticos. Matriz de PBFT (`pbft-sig-antes.txt` contra `pbft-sig-depois-final.txt`, 40 linhas, n=4, 7, 10, 13): idêntica.
- **Limite 1 (importante): os arquivos ficam em `/tmp/opencode`, fora do repositório.** Se `/tmp` for limpo, a evidência some. Não há cópia em `src/test/resources/nao-regressao/`. **Pendente:** cópia para `src/test/resources/nao-regressao/b1/` (escopo do `jabs-builder`; não editei `src/`).
  - **Corrigido em 2026-10-30: FEITA.** A cópia existe em `src/test/resources/nao-regressao/b1/` e está no commit `325f4e3` **[E]** (`git show --name-only 325f4e3` lista `b1/README.txt`, `sig-*`, `pbft-sig-*`, `lint-*`, `nr-b1/*-a.csv` e `base-arq/`). Resumo dos limites do `README.txt` de lá, cada um conferido por mim nos arquivos (ver a subseção "Evidência do B1 versionada" abaixo). O "Limite 1" (evidência só em `/tmp`) **deixou de valer**; os Limites 2 e 3 continuam.
- **Limite 2 (importante): a coluna "antes" da matriz não é pré-B1.** Cronologia **[C]** (horários dos arquivos, -03 convertido para UTC): `sig-antes.txt` 19:07Z, `sig-depois-final.txt` 19:52Z; a rodada 4 foi autorizada às 17:16Z e a entrada do builder da rodada 4 é de 19:53Z. Ou seja, o "antes" foi gerado **depois** das rodadas 1 a 3 e **dentro da janela da rodada 4**. **Não confirmado:** se o "antes" é o código exato do fim da rodada 3 (nenhum arquivo registra a versão). Evidência interna de que não é pré-B1: `EARTH` e `OFFSET` com `SILENT` e `DOUBLE_SIGN` já aparecem como `THREW UnsupportedOperationException` (16 das 80 linhas, nos dois arquivos), isto é, a recusa do Nakamoto já existia no "antes". **Leitura segura:** a matriz mostra que a mudança feita entre os dois instantâneos (a remoção dos `catch` dos hooks) não alterou a saída das 64 células que rodam. **Não prova** nada sobre a recusa de nome e de ataque não suportado (rodadas 1 a 3) **nem mede o comportamento antigo "Nakamoto com SILENT/DOUBLE_SIGN rodava como honesto"**; essa afirmação vem do Javadoc de `NakamotoConsensus.java:30-33` e do jsonl: **[L], não medida por mim.** Isto corrige o texto sugerido ("0 divergências, rodada antes de qualquer commit baseline"), que sugeria cobertura do B1 inteiro.
- **Limite 3:** a matriz foi gerada **antes** do commit `e703e3d` (arquivos de 16:07 a 16:52 -03; commit às 17:36 -03), como o texto sugerido dizia, mas o commit já contém o B1 inteiro (ver acima). Não existe comparação do B1 contra o commit.
- **[E]** Outra comparação, `/tmp/opencode/nr-b1/` (`nao_regressao.py`, MCO2:10:60, AMBIFY:10:60, ARQ:10:60, OFFSET:40:20000, 4 lados por caso). **[C]** Normalizei os CSVs (sem linhas `#`) agora: base e atual iguais nos 8 CSVs, e `a` igual a `b` nos dois lados. Arquivos de 13:48 -03 (16:48Z), um minuto antes da entrada da rodada 1 no registro (16:49Z): provavelmente é a não regressão da rodada 1. **Não confirmado:** qual era o diretório "base" (o commit `e703e3d` só existe a partir de 20:36Z; a origem dessa base não está em arquivo que eu tenha lido). Fora do repositório; não uso como prova.
- **Escopo:** a não regressão cobre apenas as 80 + 40 células acima, semente 1 e 42 (PBFT, 1 e 42), 1 trial. Não cobre `ByzantineSweep` ponta a ponta, DAGsper nem outros n.

**Evidência do B1 versionada (acrescentado em 2026-10-30).** `src/test/resources/nao-regressao/b1/README.txt` (124 linhas, escrito pelo `jabs-builder` em 2026-10-30; li inteiro). Conferi agora o que cabia conferir sem executar nada:

- **[C]** `sig-antes.txt` tem 80 linhas, 16 delas `THREW` e 8 com `fin=0`; `diff -q sig-antes.txt sig-depois-final.txt` não acusa diferença; o mesmo para `sig-antes-b.txt` (grep de contagem e `diff -q` na cópia do repositório). Isso bate com o README e com o que eu tinha calculado em `/tmp`.
- **Limites que o README declara (resumo; valem para qualquer citação desta pasta):**
  1. **A matriz de 80 células compara só o "antes" e o "depois" da remoção dos `catch (Exception ignored)` da rodada 4 do B1**, e nada mais: nas 64 células que rodam, essa remoção não mudou a saída. Não cobre `ByzantineSweep` ponta a ponta, DAGsper, outros n nem outras sementes.
  2. **Não há baseline pré-B1** para as rodadas 1 a 3 (recusa de nome de ataque, recusa de ataque não suportado, `ByzantineSweep`): `e703e3d` já contém o B1 inteiro, e a coluna "antes" foi gerada (19:07Z) depois dessas rodadas; nenhum arquivo registra a versão exata do código "antes".
  3. **16 linhas `THREW` idênticas** antes e depois (`UnsupportedOperationException: NakamotoConsensus does not implement attack {SILENT|DOUBLE_SIGN}`, em OFFSET e EARTH): prova interna de que a recusa do Nakamoto já existia no "antes". O comportamento antigo "Nakamoto com `SILENT`/`DOUBLE_SIGN` rodava como honesto" **não foi medido** (vem do Javadoc e do registro).
  4. **8 das 64 células que rodam têm `fin=0 hash=7`** (AMBIFY n=10 `SILENT`/`WITHHOLD`; OFFSET n=40 `EQUIVOCATION`/`WITHHOLD`; sementes 1 e 42): iguais, mas sem bloco finalizado, o que discrimina pouco.
  5. **As assinaturas são hash de eventos de finalização**: dizem "igual" ou "diferente", não quanto; 1 trial por célula; sem intervalo de confiança.
  6. **Os CSV de `nr-b1/` e `base-arq/` perderam o CR**: a origem tinha fim de linha CRLF e a cópia tem LF (diferença de bytes = número de linhas; cada linha da cópia está contida na da origem, conferência por substring, mais fraca que igualdade). Só as execuções "a" foram copiadas; logs, `mvp-results.csv` e duplicatas "b" ficaram em `/tmp`.
  7. **A base de `nr-b1/` e de `base-arq/` não está registrada**: não usar como prova do B1. Os comandos do README são **reconstituídos dos fontes**, não os efetivamente usados; `pbft-sig-depois.txt` está vazio (0 B) na origem; `sig-depois.txt` (execução que falhou, `SEM_SAIDA exit=1`) não foi copiado.
  8. `lint-antes.txt` e `lint-depois.txt`: bloqueantes 26 → 14 (−12, todos JB-009) e avisos 43 → 41. O README **não** confirma o número "13 catch removidos" e não registra em que árvore cada lint rodou; o "13" segue **não confirmado**.
- **Efeito sobre a tabela "Ledger de mudanças de saída":** a linha do B1 continua "parcial"; a cópia para o repositório resolve a **perda da evidência**, não os limites 1 a 4.

**Lacunas conhecidas (registradas pelo código, não corrigidas)**

- **[L]** Casper (`CasperFFG.java:328-349`) e Parlia (`Parlia.java:233-244, 268-283`): sem link alternativo justificável (Casper) ou sem bloco alternativo (Parlia), a equivocação vira voto honesto, sem erro. Está comentado no código como "LACUNA registrada (semântica mantida)".
- **[L]** Nakamoto trata `EQUIVOCATION` como `WITHHOLD` (`BitcoinNode.java:56-59`; `NakamotoConsensus.java:30-33`: "mantido sem alteração", decisão do humano).
- **Consequência para resultados:** nenhum resultado de ataque `EQUIVOCATION` em Casper, Parlia e Nakamoto descreve equivocação plena. Rotular em todo resultado que a use. Medição de `EQUIVOCATION`/`DOUBLE_SIGN` do Casper: `DOUBLE_SIGN` é "neutralizado pela contagem por votante" (Javadoc de `CasperFFG.java:102-105`).

### P2P-1-setup-n-pequeno (ADR-0011, opções A+D; APROVADA na rodada 2; 180 testes)

Estado do registro: decisão do humano de implementar A+D em 2026-10-29T20:47:45Z (jsonl, linha 47); builder rodada 1 às 20:59Z; critic rejeitou no critério 7 (CA-01 sem base real) às 21:01Z; builder rodada 2 às 21:51Z; critic aprovou às 22:55Z (linhas 48 a 51). **[L]** O número 180 vem do registro; não rodei a suíte agora.

**Fonte do texto.** `docs/adr/0011-setup-p2p-n-pequeno.md` continua com **Estado: Proposto** e não traz relatório de builder nem a decisão A+D; a decisão só existe no jsonl. Por isso esta nota se apoia em `src/test/resources/nao-regressao/p2p-1/` (README, `resumo.txt`, `hashes.txt`, `comandos.txt`) e no código. **Pendência do humano:** aceitar o ADR-0011 e registrar a opção A+D nele.

> **Corrigido em 2026-10-30.** O parágrafo acima descreve o estado de quando esta seção foi escrita e fica como histórico. **[L]** Hoje `docs/adr/0011-setup-p2p-n-pequeno.md` está **`Estado: Aceito (2026-10-30, decisão do humano)`**, com a opção **A+D** registrada (linha 4 cita a decisão no jsonl, linha 47, 2026-10-29T20:47:45Z) e um mapa de critérios CA-01 a CA-07. Portanto: (i) "continua Proposto" é falso; (ii) "a decisão A+D só existe no jsonl" é falso; (iii) a pendência "aceitar o ADR-0011" está **encerrada**. A divergência entre o texto de "D" do ADR-0011 (falhar alto) e o código (completa com o que existe; falha alto só em estado inconsistente) segue registrada abaixo e no ADR-0011. O código do P2P-1 está no commit `325f4e3`.

**O que mudou no código [L]** (`AbstractBlockchainP2PConnections.java:58-79, 106-147`): o laço de `connectToNetwork` encerra quando não há candidato elegível (`hasEligibleCandidate`, varredura em ordem de `getAllNodes()`, sem RNG). Enquanto houver candidato, a sequência de `sampleFromList` é a do laço original. Um nó pode terminar com **menos de d saídas, inclusive zero** (ver medição abaixo). `IllegalStateException` (opção D) só ocorre em estado inconsistente (vizinho elegível que recusa); o caso "impossível por contagem" **não** falha alto: completa com o que existe e segue. Mudança em relação à letra do ADR-0011 (D descrito como "falhar alto com mensagem clara"): **registrar para o humano**.

**Três classes de saída**

- **(i) Inalterada onde a baseline termina. [E] 31 execuções, base × atual IDENTICO em 31/31** (`resumo.txt` linhas 51-52, e leitura de cada linha `termina`): contra o commit `e703e3d`, sementes 1, 2 e 3, 4 JVMs por vez (`base-a`, `base-b`, `atual-a`, `atual-b`; também `base×base` e `atual×atual` IDENTICO). Casos: Casper `MCO2:10:60`, `:20:300`, `:50:600`; Parlia `AMBIFY:10:60`, `:12:60`, `:30:300`; Nakamoto `OFFSET:30`, `:35`, `:40` (todos `:20000`); ARQ `ARQ:7:60` (controle, não usa a classe alterada); mais `OFFSET:20:20000` semente 3 (terminou na baseline por acaso da semente). 3 sementes × 10 casos + 1 = 31 **[C]**.
- **(ii) Mudança intencional onde a baseline travava (sem saída anterior). [E]** Baseline em `TIMEOUT` de 30 s nos dois lados (`base-a`, `base-b`) e o atual termina com `rc=0` em 0 a 2 s: `MCO2:4` e `MCO2:7` (sementes 1, 2, 3), `AMBIFY:4` e `AMBIFY:7` (sementes 1, 2, 3), `OFFSET:10` (sementes 1, 2, 3), `OFFSET:20` (sementes 1 e 2). São 17 execuções. O script devolve `exit=2` (INCONCLUSIVO) nesses casos **de propósito**. Em `MCO2:4` e `MCO2:7` o CSV novo tem **0 linhas** de bloco finalizado (só o `mvp-results.csv` tem 1 linha): termina, mas sem finalização em 60 s (`hashes.txt`); o README do C1 explica que o checkpoint do Casper leva ~210 s. Não há "saída de Casper em rede pequena" para usar.
  - **Não confirmado em arquivo:** `OFFSET` com **n = 4 e n = 16**. O texto sugerido os lista como mudança de saída; a evidência cobre só `OFFSET:10` e `OFFSET:20`. Para n = 4 e 16 há apenas a conta de pares do ADR-0011 (seção 4: n = 16 pede 128 arestas, há 120) **[L/C]**, não execução. Tratar como previsto, não medido.
  - **Não medido:** Nakamoto com n entre 21 e 29 (ADR-0011, seção 6).
- **(iii) Fronteira que depende da semente. [E]** `OFFSET:20:20000`: travou nas sementes 1 e 2 e terminou na 3 (`exit=0`, IDENTICO). É o único caso com os dois comportamentos na evidência. Os casos rotulados "fronteira" no README (`MCO2:10`, `AMBIFY:10`, `OFFSET:30`) terminaram nas 3 sementes testadas. Para as demais trincas (protocolo, n, semente) a classe **não está determinada**; só vale para as trincas executadas.

**n efetivo do `MVPComparison` [C, sobre `MVPComparison.java:321-322, 347-348, 375-376`]:** nós = `(int)(0,3n) + (int)(0,7n)` para MCO2, TREE e AMBIFY. n = 4 → 3; 7 → 6; 12 → 11; 16 → 15; 10 → 10; 20 → 20; 50 → 50. Em `OFFSET`, `numMiners = n` e `numNodes = 0` (ADR-0011, seção 5; linhas 396-401 não relidas agora **[L]**), então nós = n. Em Parlia só os mineradores viram validadores (ADR-0011, seção 5, **[L]**): n = 4 dá 3 nós e 1 validador. Essa conversão **ficou fora da tarefa** por decisão do humano.

**Conexidade [E, por teste; saída lida em `target/p2p-conexidade.txt`, gerada 2026-10-30 08:09 -03]:** 25 sementes × n ∈ {4, 7, 10, 16} × 2 famílias (`EthereumGethP2P` d=3, `BitcoinCoreP2P` d=8) = **200 redes, 0 desconexas**, `componentes_max=1` em todas **[C]**. Menor e maior número de saídas por nó: Geth n=4 [0,3], n=7 [0,3], n=10 [2,3], n=16 [3,3]; Bitcoin n=4 [0,3], n=7 [0,6], n=10 [0,8], n=16 [1,8]. **É medição, não garantia**: a construção não impõe conexidade (Javadoc da classe). Limites: o arquivo está em `target/` (não versionado); a medição é no nível da classe P2P com rede de teste (`RedeP2PDeTeste`), **não** pelo `MVPComparison` com n efetivo impresso (parte do CA-02): **não confirmado**.

**Determinismo (CA-03).** Existe teste com JVM nova e `-XX:hashCode` variando (`P2PSetupTest`, `P2PVizinhosMain`). **Não confirmado:** que a flag foi aceita e quantos dos 6 modos foram verificados (a saída do teste não está arquivada).

**Limite declarado [L, README]:** IDENTICO vale com **4 JVMs simultâneas**. Sob carga o Nakamoto não é determinístico **nem na baseline** (base × base divergiu com 12 e com 40 JVMs; o Parlia divergiu uma vez, `saida/termina-parlia-s3.txt`). A causa não foi localizada (candidatas não confirmadas no README). A primeira bateria (`saida/`) fica como evidência da fragilidade, não como prova.

**Interação com o C1 (aviso).** A evidência do P2P-1 é anterior ao C1 e foi gerada com o limiar antigo do Casper. **[E, C1 `pedido-Casper-MCO2_*`]** Os três casos de Casper da classe (i) (`MCO2:10:60`, `:20:300`, `:50:600`) continuam IDENTICO contra `e703e3d` depois do C1, porque o C1 não os exercita (ver a seção do C1). Para Casper com n que finalize bloco e S mod 3 ≠ 2, as saídas do P2P-1 e as de hoje **não** são comparáveis. Em `MCO2:4` e `MCO2:7` (classe ii) o CSV novo tem 0 linhas de finalização em 60 s, então o C1 não aparece aí; **não confirmado** para durações maiores.

**Mudança de documentação pendente (ADR-0011, Consequências):** `AGENTS.md` ainda diz "Parlia/Casper exigem `n >= 10`"; `DAGsperNetworkScenario.MIN_NODES = 10` ficou como estava (CA-06). Não editei `AGENTS.md` (fora do meu escopo).

### C1-casper-quorum-correcao (APROVADA na rodada 2; 210 testes)

Estado do registro: triagem `C1-casper-quorum-triagem` com veredicto `DEFEITO_ENCONTRADO` do critic às 2026-10-30T01:02:30Z; humano autorizou a correção às 01:19:33Z; builder rodada 1 às 01:35Z (208 testes); critic rejeitou no critério 3 por Javadoc (severidade sugerida não bloqueante) às 01:37Z; builder rodada 2 às 01:38Z (210 testes); critic aprovou às 01:40:12Z; humano encerrou a triagem às 01:40:25Z (jsonl, linhas 53 a 63). **[L]** O número 210 vem do registro.

**Mudança intencional de saída do Casper.** `CasperFFG`, justificação e finalização usam o mesmo limiar, de `votos > ((S/3)*2)+1` para `votos >= ceil(2S/3)`, em inteiro `(2S+2)/3` (`CasperFFG.supermajorityThreshold`, `CasperFFG.java:79-84`; uso em `:46` e `:144`). **[L]** Peso unitário por validador; stake desigual não modelado (a definir com o humano). Cenários afetados: MCO2 e TREE (`CasperEthereumNetworkScenario`) e `ByzantineSweep` POS; `Main` Casper. **Nenhum número de Casper anterior a esta correção é comparável com os posteriores.**

**Limiar (votos distintos exigidos), antigo → novo [C]** (antigo = `(S/3)*2 + 2`, menor inteiro que satisfaz `>`):

| S | 4 | 7 | 10 | 13 | 16 | 64 | 128 |
|---|---|---|---|---|---|---|---|
| antigo | 4 | 6 | 8 | 10 | 12 | 44 | 86 |
| novo | 3 | 5 | 7 | 9 | 11 | 43 | 86 |

Por resto: S = 3k, antigo 2k+2 contra novo 2k (dois votos a mais); S = 3k+1, antigo 2k+2 contra 2k+1 (um a mais); S = 3k+2, igual. **[C]** Conferi a tabela e o resto com a fórmula; confere com o Javadoc em `CasperFFG.java:55-73`. Atenção: S é `numOfStakeholders`, não o n da linha de comando.

**Não regressão contra `e703e3d`** (README do C1; `scripts/jabs/nao_regressao.py`, um caso e uma semente por vez, 4 JVMs; 36 comandos = 12 + 9 + 15 **[C]**, `comandos.txt` tem 36 linhas `python3`):

- **[E]** Protocolos que não usam `CasperFFG` — `ARQ:7:60`, `AMBIFY:10:60`, `AMBIFY:12:60`, `OFFSET:40:20000`, sementes 1, 2, 3: **IDENTICO em 12/12** (`exit=0`). Confirmação por leitura: `CasperFFG` só é instanciado por `EthereumCasperNode`/`EthereumCasperMiner`, usados só por `CasperFFGGlobalBlockchainNetwork` **[L, do README]**.
- **[E]** Casos MCO2 padrão (`10:60`, `20:300`, `50:600`), sementes 1, 2, 3: **IDENTICO em 9/9**, **porque não exercitam a correção**, não porque ela não tenha efeito. `MCO2:10:60` e `:20:300` não finalizam nenhum bloco (checkpoint de 14 blocos a 15 s, ~210 s). `MCO2:50:600` tem S = `(int)(0,7·50)` = 35, S mod 3 = 2, e o limiar coincide (24 e 24) **[C]**.
- **[E] Casos suplementares** (600 s, escolhidos por S mod 3 ∈ {0,1}): `n=10, 30, 40, 100` × sementes 1, 2, 3 = 12 execuções, **todas DIVERGENTE** base × atual (`exit=1`), e **IDENTICO** atual × atual e base × base. Controle `n=20` (S = 14, mod 3 = 2): IDENTICO nas 3 sementes. Limiar antigo → novo nesses casos: n=10 (S=7) 6→5; n=30 (S=21) 16→14; n=40 (S=28) 20→19; n=100 (S=70) 48→47; n=20 (S=14) 10→10 **[C]**.
- **[C] Tb nos 12 casos que divergem:** `Tb atual ≤ Tb base` nas 12; a diferença vai de **0,0005 s a 0,13 s** (mínimo 0,000485 em n=100 s1; máximo 0,1327 em n=10 s1). **Correção ao texto sugerido e ao README**, que dizem "0,0008 s a 0,13 s": o mínimo real é 0,000485. `Cb` muda em 5 das 12 linhas, nos dois sentidos (0,125728→0,083819 desce; 0,033712→0,035882 sobe), porque é medido no **primeiro nó** a finalizar (muda o nó e o instante); não é custo por voto. Blocos finalizados iguais base × atual (14 ou 28).
- **Hipótese, não verificada por instrumentação:** o novo limiar exige menos votos, então o quórum chega antes e `Tb` cai. O README diz o mesmo. O efeito de 1 a 2 votos a menos é pequeno em redes de 10 a 100 nós.
- **Não rodado:** `ByzantineSweep` POS; stake desigual; falhas bizantinas. Granularidade: 1 trial por JVM, sementes 1 a 3, agregado de rede.

**Débitos abertos (nenhum corrigido; registrados na decisão do humano às 01:40:25Z)**

1. **Denominador.** S = `numOfStakeholders` exclui os mineradores, que também votam. **[E, diagnóstico `CasperPopulacaoMain`, relatado no README]** Todos os `(int)(0,3n)+(int)(0,7n)` nós são `EthereumCasperMiner`/`EthereumCasperNode` e votam; medido por reflexão no mapa de votos: n=10/600 s → 10 votantes distintos (3 mineradores + 7 stakeholders); n=30/600 s → 30 (9 + 21). Efeito: limiar efetivo de 5/10 = **50%** da população votante em n=10 e 24/50 = **48%** em n=50 **[C]** (2/3 dos votantes reais seriam 7 e 34 segundo o README). Antigo: 60% (6/10) em n=10 e 48% (24/50) em n=50. Consequência para tolerância a falha: **o Casper simulado aceita finalizar com menos de 2/3 dos que votam**, então "BFT empírico" do Casper não é comparável com o dos protocolos de quórum 2f+1 sobre n. Decisão do humano.
2. **Link.** `CasperFFG.newIncomingVote` (`:126-166`) não valida fonte justificada nem consecutividade do link (só `areBlocksConnected`). Anotado pelo critic na triagem (jsonl, linha 54).
3. **Stake.** Desigual não modelado.

**Fora desta correção, não alterado [L, README]:** outros pontos com `/3` (Parlia 2S/3 estrito, DAGsper, PBFT, `SimulationMetrics`). Observação: o DAGsper foi corrigido na T4; o Parlia (`(stakeholders*2)/3`) **não foi revisado** nesta tarefa nem em outra; **não confirmado** se tem o mesmo defeito.

**Triagem `C1-casper-quorum-triagem`:** `DEFEITO_ENCONTRADO`, encerrada (`DECISAO_HUMANA_ACEITA`, jsonl, linha 63). O critic afirmou viés de "mais difícil finalizar" (Tb e L_tx maiores, menor tolerância) no limiar antigo; o suplementar acima é coerente com isso nos 12 casos (Tb menor ou igual), sem provar a causa.

### D3a-1, D3a-2a-1 e D3a-2a-2a (HotStuff: dados, configuração, infraestrutura e mensagens de rede)

Estado do registro (jsonl): D3a-1 aprovada na rodada 5 (20:47:44Z de 2026-10-29, 173 testes); D3a-2a-1 aprovada na rodada 1 (2026-10-30T01:15:46Z, 195 testes); D3a-2a-2a com 3 rejeições do critic no critério 3 e `GAP_NAO_BLOQUEANTE` por decisão do humano às 11:06:17Z (240 testes na rodada 3 do builder). **[L]** Os números de testes vêm do registro; não rodei a suíte.

**Afirmação: não alteram a saída de nenhum protocolo existente (só código novo e ramos de tráfego próprios do HotStuff).** O que consegui verificar:

- **[C, diff contra o worktree `e703e3d`]** `BlockFactory.java`: 68 linhas adicionadas, 0 removidas. `TransactionFactory.java`: 16 adicionadas, 0 removidas. `PeerBlockchainNode.java`: 52 adicionadas e 1 removida; a linha "removida" é `}else if (message instanceof QueryMessage) {` que reaparece como `} else if (message instanceof QueryMessage) {` depois dos dois ramos novos de mensagem HotStuff (só reformatação de posição). Os ramos de tráfego novos são guardados por `consensusAlgorithm instanceof HotStuff`, e o de mensagens HotStuff só é alcançado por mensagens que só o HotStuff cria. O protocolo `HotStuff` ainda não tem algoritmo: `onProposal` e `onQcMessage` lançam `UnsupportedOperationException` (D3a-2a-2c), e `HotStuffLocalLANNetwork` falha alto sem algoritmo (D3a-2a-1).
- **[E, parcial]** As execuções do C1 contra `e703e3d` (ARQ, AMBIFY, OFFSET: IDENTICO, ver seção do C1) foram feitas depois do aceite da D3a-2a-1 (01:15Z) e antes do builder do C1 (01:35Z), portanto com o código da D3a-2a-1 na árvore. Isto é uma inferência por cronologia **[C]**, não uma execução planejada para a D3a; **não confirmado** que a árvore daquela rodada continha exatamente a D3a-2a-1.
- **Não confirmado:** execução de não regressão para a D3a-2a-2a (nenhum arquivo em `src/test/resources/nao-regressao/` a cobre). A conclusão "não altera saída" para a D3a-2a-2a se apoia em **leitura/diff**, não em execução. As classes `HotStuff*` ficaram `final` (rodada 3) e `HotStuffBlock` é `sealed`; isso afeta só o HotStuff.
- **Ressalva:** o worktree da baseline já tem `ledgerdata/hotstuff/*` (D3a-1 em estado do commit); a D3a-1 foi aprovada depois do commit. Se o D3a-1 aprovado é idêntico ao do commit: **não confirmado** (ver nota da baseline).

**Dívida da base (decisão do humano, `GAP_NAO_BLOQUEANTE` da D3a-2a-2a, jsonl linha 70).** `Block.getParents()` devolve a lista interna mutável (`Block.java:34-36`) e `SingleParentBlock` a cria como `new ArrayList<>()` (`SingleParentBlock.java:9`) e a preenche por `getParents().add(parent)` (`:10`) **[L]**. Correção ao texto sugerido: o `ArrayList` está na linha 9 e a linha 10 é o `add`. A lista é compartilhada por todos os protocolos que herdam de `SingleParentBlock` e **já é assim no commit `e703e3d`** **[C]** (mesmo `getParents` na linha 34 e mesmo construtor no worktree da baseline). Risco: qualquer código que receba um bloco pode alterar a lista de pais; a validação de identidade do bloco no HotStuff (`HotStuffProposalMessage`) só verifica a referência. Tarefa própria, **sem prazo**; exige não regressão em **todos** os protocolos. Requisito já fixado pelo humano para a 2b: o HotStuff captura o hash do bloco na criação da mensagem em vez de depender da lista de pais. **[L]** Não verifiquei se a captura do hash foi implementada.

### Ledger de mudanças de saída

Quais tarefas mudaram a saída do simulador, em que protocolo, e se o resultado novo se compara com o anterior. "Comparável" responde: dá para pôr o número novo ao lado do número antigo da mesma célula? As linhas de T1 a T4 vêm das seções acima deste ADR (fechamentos e notas da T1 a T4); as de B1, P2P-1 e C1 vêm das seções desta gravação. A D3a (HotStuff) ganhou linha em 2026-10-30 (ver abaixo); a tarefa de conversão de n do `MVPComparison` está listada como **em aberto**.

| Tarefa | Protocolo afetado | O que mudou | Comparável com resultado anterior? | Evidência |
|---|---|---|---|---|
| T1 `T1-pbft-trafego-medido` | PBFT/ARQ | `C_B` passa de fórmula (`numAllParticipants*2*1024` com ruído) para tráfego medido por nó (recebido); novas colunas `C_B_total` e `C_B_consenso` (`TrafficConsensusUntilFinalization`, vale -1 nos demais protocolos) | não (todo `C_B` anterior do PBFT/ARQ é inválido; CRÍTICO, decisão humana). Casper, Parlia e Nakamoto: sim, saída mantida | "Fechamento da tarefa 1" (Casper/MCO2 n=10, 50 e Nakamoto/OFFSET n=40, 60 idênticos, semente 1; Parlia/AMBIFY idêntico só com o valor aleatório fixado nos dois lados, débito 1); `nao_regressao.py`, `baseline-original/` |
| T2 `T2-pbft-quorum-2f1` | PBFT/ARQ | quórum de `>(n/3)*2+1` para `>=2f+1`; PREPARE só conta após PRE_PREPARE aceito, COMMIT só após prepared | não (todo número de PBFT/ARQ anterior vale como quórum acima de 2f+1; medido na rodada 1: `Tb_avg` base 0,926/0,922/1,191 contra 0,716/0,951/1,144 em n=4/7/10, 1 trial) | "Fechamento da tarefa 2"; `PBFTQuorumTest` (17 testes). Débito 4 aberto (primário e view) |
| T3 `T3-parlia-aleatoriedade` | Parlia/AMBIFY | `Math.random()` em `Parlia.java:190` trocado por `RandomnessEngine` | não (AMBIFY anterior não era reprodutível) | "Tarefa 3 entregue"; critic APROVADO (jsonl, linha 13); débito 5 aberto (`memPool` `HashSet`) |
| T4 `T4-dagsper-tangle-trafego-semente` | DAGsper; TangleIOTA | DAGsper passa a emitir `BlockFinalizationEvent` com `C_B_total` e `C_B_consenso` e quórum 2f+1 com `>=` (votos 4/6/8 → 3/5/7 em n=4/7/10); Tangle sem `Random(42)`, tráfego medido | não (nada anterior à T4 é comparável). Além disso, **nenhum dos dois produz resultado que valha como pesquisa**; a T5 foi cancelada (jsonl, linha 25) e ficam fora das baterias | "Tarefa 4 entregue", "rodada 3", "rodada 4 e fechamento"; `GAP_NAO_BLOQUEANTE` por decisão humana; débito 7 aberto. PBFT/ARQ, Parlia, Casper e Nakamoto sem mudança, semente 1 (cinco casos) |
| B1 `B1-ataque-desconhecido-falha-alto` | todos que recebem `ByzantineConfig`; Nakamoto (com `SILENT`/`DOUBLE_SIGN`) | nome de ataque exato ou erro; ataque não suportado pelo protocolo recusado; `ByzantineSweep` aborta em erro de configuração e em 0 trials (`NAO_MEDIDO`); `catch` removidos dos hooks | parcial: nas 64 células que rodam a saída entre os dois instantâneos da matriz é idêntica (80 linhas; semente 1 e 42; MCO2/AMBIFY n=10, 16; OFFSET/EARTH n=24, 40). Nakamoto com `SILENT`/`DOUBLE_SIGN` passa a falhar na montagem (**sem saída para comparar**). Não há comparação contra código pré-B1 | seção B1; `/tmp/opencode/sig-antes.txt` e `sig-depois-final.txt` (fora do repositório); testes `ByzantineConfigAttackTest`, `ByzantineSweepConfigTest`. Lacunas: Casper/Parlia equivocação→voto honesto sem alternativa; Nakamoto `EQUIVOCATION`=`WITHHOLD` |
| P2P-1 `P2P-1-setup-n-pequeno` | Casper, Parlia, Nakamoto (classe P2P compartilhada); TangleIOTA e DAGsper pela mesma classe, sem execução | `connectToNetwork` encerra sem candidato elegível em vez de laço infinito | parcial: sim onde a baseline termina (31/31 IDENTICO contra `e703e3d`, 3 sementes); não existe comparação onde a baseline travava (17 execuções: MCO2/AMBIFY n=4 e 7, OFFSET n=10 e 20); fronteira depende da semente (OFFSET n=20) | `src/test/resources/nao-regressao/p2p-1/` (README, `resumo.txt`, `hashes.txt`, `comandos.txt`); `target/p2p-conexidade.txt` (200 redes, 0 desconexas). Vale com 4 JVMs simultâneas |
| C1 `C1-casper-quorum-correcao` | Casper (MCO2, TREE, `ByzantineSweep` POS) | limiar de supermajority link de `>((S/3)*2)+1` para `>=ceil(2S/3)` | não para Casper que finaliza com S mod 3 ∈ {0,1} (12 de 12 casos suplementares divergem, `Tb` menor ou igual em 0,0005 a 0,13 s). Sim, por não exercitar a correção, em MCO2 padrão (9/9) e nos outros protocolos (12/12) | `src/test/resources/nao-regressao/c1-casper/` (README, `comandos.txt`, `hashes.txt`, `saida-final/`); `CasperFFGQuorumTest`. Débitos: denominador S, validação do link, stake desigual |
| D3a `D3a-1`, `D3a-2a-1`, `D3a-2a-2a`, `D3a-2a-2b`, `D3a-2a-2c-1`, `D3a-2a-2c-2a` (acrescentada em 2026-10-30) | HotStuff (código novo). Nenhum protocolo existente muda a saída | só código novo e ramos próprios do HotStuff: `BlockFactory` (+68/−0), `TransactionFactory` (+16/−0), `PeerBlockchainNode` (+52/−1; a linha removida é o `else if (message instanceof QueryMessage)`, reposicionada). Os ramos de tráfego são guardados por `consensusAlgorithm instanceof HotStuff` e os de mensagem só são alcançados por `HotStuffProposalMessage`/`HotStuffQcNetworkMessage` | sim para PBFT, Casper, Parlia e Nakamoto **por leitura/diff**, não por execução específica da D3a. HotStuff não tem número anterior para comparar (não existia) e **ainda não roda ponta a ponta**: sem `case HOTSTUFF` no `MVPComparison` (D3a-2a-2c-4 pendente); a troca de visão por timeout (2c-3) não existe | **[C]** `git diff --numstat e703e3d 325f4e3` (acima); **[E parcial]** execuções do C1 contra `e703e3d` (IDENTICO em ARQ, AMBIFY, OFFSET) feitas com a D3a-2a-1 na árvore, por cronologia (inferência). Registro: D3a-1 aprovada na rodada 5; 2a-1 e 2b aprovadas; 2c-1 aprovada; **2a-2a e 2c-2a fechadas como `GAP_NAO_BLOQUEANTE` por decisão do humano** (dívida de `Block.getParents()` mutável). Não existe pasta de não regressão da D3a |
| PARIDADE `PARIDADE-teto-retencao-pbft-tendermint` (2026-11-20) | PBFT/ARQ e Tendermint | teto de retenção obrigatório `maxFutureHorizon` (estudo: 1000), equivalente ao `maxFutureViewHorizon` do HotStuff: votos/mensagens além do teto são descartados e contados (PBFT: bytes entram nos agregados e nos ledgers `getAccounted*`; Tendermint: `FUTURE_BEYOND_HORIZON`). Flags `--tm-max-future-horizon` e `--pbft-max-future-horizon` obrigatórias, sem default | sim, sem ataque e sem atraso: saída idêntica byte a byte ao HEAD `45425dab` (ARQ n=4/7/10, TENDERMINT n=4/7, HOTSTUFF n=4, seed 1). **Não comparável** sob atacante de retenção (votos futuros acima do teto deixam de ser retidos; C_B muda) | PR frfreire/redd-framework#2; comparação `par2.py` |
| Conversão de n do `MVPComparison` (**em aberto**, tarefa ainda não aberta) | a definir: MCO2, TREE e AMBIFY (hoje `(int)(0,3n)+(int)(0,7n)` nós) e, conforme a decisão, OFFSET | **em aberto.** Não há tarefa, ADR nem decisão sobre o que é "n" (L10) | **em aberto.** Se a conversão mudar o número de nós de MCO2/TREE/AMBIFY, a saída muda para toda célula afetada e os números anteriores deixam de ser comparáveis; qual é a nova regra, `a definir com o humano`. Nada medido | registro de rodadas, linhas 47 e 71 (o humano decidiu que a conversão ficaria fora do P2P-1 e viria depois da 2b); ADR-0011, seção 5 e "Perguntas"; ADR-0010, PI-19. Exigirá nota de não regressão (critério 7) e ADR de mudança de saída |

**Leitura da coluna "comparável":** "não" vale para a célula afetada; protocolos e células que a mesma linha cita como idênticos continuam comparáveis entre si. Nenhuma linha acima autoriza juntar números de antes e depois da mudança na mesma tabela de resultado.

### Pendências e itens não confirmados desta gravação

- **Baseline.** *(Corrigido em 2026-10-30: a baseline das próximas tarefas é `325f4e3`; ver a nota no início da seção de baseline. O texto a seguir é histórico.)* `e703e3d` contém o B1 inteiro, nenhum P2P-1, C1, D3a-2a-1 e D3a-2a-2a (ver a nota no início). A baseline de não regressão das próximas tarefas deve ser um **commit novo a ser criado pelo humano**. A árvore de trabalho tem código da D3a-2a-2b sem registro de builder nem de critic; o humano decide se entra nesse commit.
- **Evidência do B1 fora do repositório** *(corrigido em 2026-10-30: FEITO, a cópia está em `src/test/resources/nao-regressao/b1/` e no commit `325f4e3`; permanece o não confirmado sobre a origem da base de `nr-b1`, agora registrado no README como "base não registrada")* (`/tmp/opencode/sig-*.txt`, `pbft-sig-*.txt`, `nr-b1/`): copiar para `src/test/resources/nao-regressao/b1/` é tarefa do `jabs-builder`. Não confirmado: origem da base da pasta `nr-b1`.
- **ADR-0011 continua `Proposto`** *(corrigido em 2026-10-30: o ADR-0011 está ACEITO com A+D; o aceite do humano foi dado. O que resta é a divergência do texto de "D" com o código, já registrada no ADR-0011)*: a decisão A+D e o relatório do builder só estão no jsonl; o aceite do humano falta. O texto de "D" do ADR-0011 (falhar alto) difere do código (completa com o que existe; falha alto só em estado inconsistente).
- **Não confirmado em arquivo:** 13 `catch` removidos (B1); `OFFSET` n = 4 e n = 16 como mudança de saída do P2P-1; CA-03 (`-XX:hashCode`) e CA-02 pelo `MVPComparison` com n efetivo; se a D3a-1 do commit é a aprovada; execução de não regressão específica da D3a-2a-2a; se o Parlia tem o defeito de quórum que o Casper tinha; captura do hash do bloco na 2b.
- **Correções ao texto sugerido:** C1, intervalo de `Tb` é 0,0005 s a 0,13 s (não 0,0008); `SingleParentBlock.java:9` é o `ArrayList` e `:10` o `add`; B1, a matriz cobre a rodada 4, não o B1 inteiro, e a baseline não é anterior ao B1; "OFFSET n = 4 e 16" não tem evidência de execução.
- **`docs/` está no `.gitignore` (linha 163), portanto os ADRs NÃO estão versionados no commit `325f4e3` (acrescentado em 2026-10-30).** **[E]** `.gitignore:163` é `docs/`; `git check-ignore -v docs/adr/0006-pbft-trafego-medido.md` aponta essa linha; `git ls-files docs` não devolve nada; o commit `325f4e3` não contém nenhum arquivo de `docs/` (a lista de arquivos do commit tem `especificacoes/`, `.ia/`, `.experimento/` e `src/`). O registro de rodadas já diz o mesmo (linha 84: "docs/ esta no .gitignore: ADRs nao versionados"). Consequência: **este ADR, o 0007, 0009, 0010 e 0011 só existem na árvore de trabalho**; `325f4e3` carrega o código e a evidência, não as decisões que as justificam, e um `git clean -fdx` ou clone novo as perde. `especificacoes/` **está** versionada (o índice entrou no commit). **Decisão do humano pendente:** tirar `docs/` do `.gitignore` e versionar (em novo commit), versionar só `docs/adr/` com exceção explícita, ou manter fora. Não alterei `.gitignore` (fora do meu escopo).
- **Não confirmado, acrescentado em 2026-10-30:** execução de não regressão contra `325f4e3` (nenhuma tarefa a fez ainda; a evidência arquivada foi gerada contra `e703e3d`); se o `325f4e3` compila e passa a suíte inteira no estado commitado (o registro cita 332 testes na 2c-2a, mas não rodei Maven); a árvore de trabalho tem apenas `.ia/rodadas/loop2-jabs-codigo.jsonl` modificado em relação a `325f4e3` (`git status --short`), isto é, **o código de `src/` da árvore coincide com o commit**.
