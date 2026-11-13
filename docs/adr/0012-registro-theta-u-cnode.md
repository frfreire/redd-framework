# ADR-0012: registro de Θ, U e C_nó no simulador

- **Estado:** Proposto
- **Data:** 2026-10-30
- **Decisores:** (humano) decisão (a) aceita em 2026-10-30; os parâmetros marcados `a definir com o humano` continuam abertos
- **Trava:** a tarefa de instrumentação em `src/` (`loop2-jabs-codigo`) e a bateria principal (E1 e execuções de saturação). Nada é aberto por este ADR.

Rótulos de evidência: **[L]** leitura de código, **[E]** executado, **[C]** calculado. Caminhos de `src/` são relativos à raiz do repositório.

## Contexto

### O que o artigo exige

- **Definições** (`.experimento/artigo-redd-isys-v4/secoes/04-requisitos-escalas.tex:103-108`): Θ = "maior taxa de confirmação mantida sob saturação"; U = "fração do tempo em que o enlace de subida mais carregado permanece ocupado"; C_nó = tráfego máximo por nó, "em bytes por bloco". A eq. `util` (`U ≈ 8·C_nó·ν_b/K_nó`, :105) é "utilizada apenas como verificação".
- **Critérios** (`:110-116`): V2 usa L_tx e V3 usa U ≤ U_max. **Atenção: o V1 do `.tex` é `Q(kP) ≤ ε_Q`** (pendentes ao fim do k-ésimo período), e o texto de :116 diz que isso "é menos restritivo do que exigir Θ ≥ λ_pico". O **V1 do ADR-0000 é `Θ ≥ λ_pico`** (`.experimento/ADR-0000-redesenho-escalas.md:33`), e é assim que o ADR-0010 (PI-09a, PI-20) e o índice (6.6) o citam. As duas fontes **divergem**. Este ADR registra Θ (decisão do humano), mas **não decide qual V1 vale** nem cobre Q(kP) (pergunta aberta 1).
- **Hipótese do gargalo do líder** (`04:108`; `02-fundamentacao.tex:26`): "um protocolo pode ter custo total linear e, ainda assim, apresentar um nó cujo enlace se torna a restrição vinculante", "essencial para protocolos centrados no líder, como o HotStuff". É hipótese a testar, não resultado.
- Registros anteriores do problema: ADR-0010 PI-20 (`:175-177`), índice 6.6 e C-g. ADR-0013 (fila de pendentes) e este ADR são interdependentes (ver "Dependências" em Consequências).

### Estado do código (rótulo **[L]**, lido nesta sessão, árvore atual)

1. **Nada disto existe.** `EnhancedBlockFinalizationLogger` emite 14 colunas (`:432-449`): `Time` a `Pdv_...` e `TrafficConsensusUntilFinalization`. Busca por ocupação/utilização/vazão em `src/main/java`: sem resultado. O tráfego existente (C_B) é **do lado que recebe**: bytes recebidos pelo nó que finalizou primeiro cada altura (cabeçalho `:95-105`; hooks em `PeerBlockchainNode.processIncomingPacket`, `:43-240`). **Não há contador do lado que envia.**
2. **Linha só na primeira finalização por altura** (`:121-124`, `processedHeights`). Quando a linha sai, outros nós ainda podem estar enviando COMMIT: qualquer total "por bloco" gravado na linha é um instantâneo desse instante.
3. **Um único ponto de passagem do envio.** Há 23 chamadas a `addToUpLinkQueue` (Node 3 em `:129,139,148`; PeerBlockchainNode 5; PeerDLTNode 5; EthereumNode 4; BitcoinNode 2; IOTANode 1; PBFT `:395`; CasperFFG `:357`; HotStuff `:389`) e **todas** convergem em `NetworkInterface.addToUpLinkQueue` (`:109-113`) → `addToLinkQueue` (`:120-125`) → `AbstractPacketProcessor.addToQueue`/`execute` (`:36-54`). A instrumentação cabe em `NetworkInterface`/`AbstractPacketProcessor`, não nas 23 chamadas.
4. **Entregas diretas não passam pela fila.** `processIncomingPacket` é chamado direto em `CasperFFG:360-369`, `DAGsper:353`, `BitcoinMinerNode:68`, `BitcoinMinerNodeWithoutTx:43` e `EthereumMinerNode:70`. Não são enviadas nem recebidas pela rede simulada (convenção do ADR-0006) e **não entram** em C_nó nem em U.
5. **Modelo do enlace de subida.** `PacketSendingProcess.processingTime = 8·size/uploadBandwidth` (`:20-22`). Em `AbstractPacketProcessor.execute` (`:46-54`) o evento seguinte é agendado com o tempo de transmissão do pacote **que acabou de sair**, não do próximo: o k-ésimo pacote de uma rajada sai em `t0 + T1 + Σ_{j<k} T_j`. Logo o tempo ocupado de uma rajada de n pacotes é `T1 + Σ_{j<n} T_j`, e **difere de `Σ T_j` por `T1 − Tn`**. É comportamento herdado do JABS; **não deve ser "corrigido" aqui** (mudaria a saída de todos os protocolos). Consequência: a eq. `util` e a medição só concordam a menos dessa diferença (RM-09).
6. **Perda de pacotes: não há perda aleatória.** `Network.getLatency` só devolve latência (`Network.java:36-38`). Há **dois** caminhos em que bytes não chegam, e a igualdade enviado = recebido precisa tratá-los: (i) interface caída: `addToUpLinkQueue` e `addToDownLinkQueue` descartam sem registrar quando `networkInterfaceDown` (`NetworkInterface.java:99-103,109-113`; `Node.crash`, `Node.java:96-98`; ataque SILENT em `ArqReddVotingScenario.java:100-102`); (ii) resíduo no fim: o laço para em `simulationStopCondition` com filas e pacotes em trânsito (`AbstractScenario.java:121`).
7. **Banda.** PBFT, HotStuff e Snow usam `LAN100MNetworkStats`: 100 Mbps constantes (`:10,33-44`). A banda por nó uniforme entre protocolos é tarefa à parte (ADR-0010 PI-07); até lá, **U só é comparável entre protocolos que partilham a rede LAN**.
8. **Confirmação de transações.** O logger conta cada tx na primeira finalização da altura (`:158-184`); a extração de txs devolve conjunto vazio **tanto** para bloco sem lista **quanto** para exceção engolida (`:332-384`, catch silencioso em `:379-381`): 0 e "não medido" se confundem. As listas de latência de `SimulationMetrics` são cortadas nos últimos 10 000 valores (`:28,186-188`); só o contador `confirmationCount` é completo.
9. **A taxa de confirmação hoje não é vazão.** O número de tx por bloco no pBFT é sorteado (20 a 79, ou 50) e o intervalo é fixo (5,0 s): a "taxa" seria artefato do gerador (ADR-0013, tabela do Contexto).
10. **Fila de pendentes: não existe.** Em `src/main/java` "pending/pendente" só aparece em votos retidos do PBFT (`PBFT.java:35-36`), propostas retidas do HotStuff (`ReplicaCore`), `memPool` dos mineradores e num comentário de `BlockFactory.java:126`. `docs/adr/0013-formacao-de-bloco-e-demanda.md` **está no disco** (175 linhas, Estado Proposto, decisões 1 a 4 do humano de 2026-10-30), mas é só especificação: **nada implementado**.
11. **Líder.** `PBFTP2P` põe o próprio nó entre os vizinhos (`:9`), então o broadcast do PBFT inclui um pacote para si que **passa** pela fila, pela latência e pela recepção; `HotStuffP2P` não (`:32-37`), e ali a autoentrega é local. Tamanhos: PRE_PREPARE = bloco + 10 B (`PBFTPrePrepareVote:7`); PREPARE e COMMIT = 32 + 10 = 42 B (`PBFTPrepareVote:8`, `PBFTBlockVote:11`) **[C]**. O primário do PBFT é `viewNumber % n` e a visão avança a cada bloco finalizado (`PBFT.java:283,468-470`): **rotativo**, ao contrário do "líder estável" de PI-14 (tensão já registrada no ADR-0009, "Consequências"). Além disso `PBFTLocalLANNetwork:101,111` faz o nó 10 propor a cada 5,0 s (ADR-0013 trata disso).
12. **Baseline.** Worktree `/tmp/opencode/base-325f4e3` existe. `NetworkInterface` e `EnhancedBlockFinalizationLogger` têm as mesmas linhas de interesse nas duas árvores (`:101,111,120,122` e `:98,101,447,489`). **Não consegui rodar `git log` nem `diff -r` (bloqueados nesta sessão)**: o hash `325f4e3` vem dos ADR-0006/0009, não confirmei no git.

### Achado de 2026-10-31 (N1) que condiciona a prova de não regressão

A saída de **Casper, Parlia e Nakamoto** é sensível à sequência de `System.identityHashCode` da thread principal (débito 5 do ADR-0006; `memPool` é `HashSet` de objetos sem `hashCode`). Evidência **[E]** arquivada em `src/test/resources/nao-regressao/n1-conversao-n/diagnostico-hash/`: `resumo-sonda.txt` (posição na sequência difere base×atual: AMBIFY:10:60 1296 contra 1411; MCO2:50:600 2812 contra 2906; OFFSET:40:20000 12371 contra 12223; ARQ:7:60 23124 contra 23132); `resumo-flags.txt`/`resumo-flags2.txt` (com `-XX:+UnlockExperimentalVMOptions -XX:hashCode=2`, base e atual dão saída IDENTICA em AMBIFY, MCO2 e OFFSET, semente 1; com `hashCode=3` o AMBIFY diverge); `resumo-base-k.txt` (**ARQ:7:60 idêntico para k = 0, 1, 2, 5, 10, 50 e 200, mais a repetição de k = 0, tanto na base quanto no atual [E]**: o ARQ/PBFT não é afetado pela sequência; AMBIFY, MCO2 e OFFSET divergem para k ≥ 1). **Limite do que sei:** a sonda mostra *contagens* diferentes de chamadas a `identityHashCode`, mas o que no código da N1 as produziu **não está isolado** em nada que li. Por isso este ADR não afirma que "criar objetos" desloca a sequência; exige que a instrumentação **não chame** `hashCode`/`identityHashCode` nem use coleções hash chaveadas por `Node`, `Packet` ou `Block` (RM-08) e que a sonda comprove.

## Opções

Decisão do humano em 2026-10-30: **(a)**. As outras duas ficam registradas com o motivo da rejeição.

| Opção | O que é | Prós | Contras | Estado |
|---|---|---|---|---|
| **(a)** | Instrumentar o simulador (envio por nó e ocupação da fila de envio) **antes** da bateria principal | U vem de simulação, como o artigo promete (`04:107`); C_nó passa a ser medido, não derivado; V3 decidível por célula | Mexe em `src/` (loop 2, critic obrigatório) e na saída; precisa da prova de não regressão (ver Verificação); atrasa a bateria | **ACEITA** (humano, 2026-10-30) |
| (b) | Não medir U: calcular a posteriori por `8·C_nó·ν_b/K_nó` | Sem tarefa em `src/` | A própria fórmula é "apenas verificação" no artigo (`04:107`); usar a verificação como única fonte tira a medição; pressupõe ν_b constante e C_nó medido (que também não existe); herda a diferença T1 − Tn do item 5 do Contexto sem poder medi-la | **REJEITADA** |
| (c) | Restringir as fronteiras de viabilidade ao critério V2 (latência) | Nada a instrumentar | A hipótese do gargalo do líder (`04:108`) fica sem teste; V3 deixa de ser avaliado; a "restrição vinculante" do artigo (`04:116`) perde um dos critérios | **REJEITADA** |

### Sub-escolhas dentro da opção (a)

Decididas pelo humano (2026-10-30):

1. **C_nó = bytes ENVIADOS** (soma de `packet.getSize()` na fila de envio), por bloco, máximo entre os nós, **inclui** o envio do bloco pelo líder. Não é bytes recebidos (o C_B de hoje, ADR-0006).
2. **U = fração do tempo** em que a fila de envio do nó fica ocupada, **medida** no simulador. A aproximação do artigo vira só verificação.
3. **Θ = taxa de confirmação sustentada SOB SATURAÇÃO**, em execuções **separadas** das de E1. Em E1 a taxa de confirmação segue a demanda e só dá `min(λ, Θ)`; por isso V1 (Θ ≥ λ_pico) não é decidível por E1.

Ainda abertas (listadas em "A definir com o humano"): U_max, L_max, critério de saturação, duração mínima da janela de U, nó mais carregado por bloco ou por média, granularidade de saída.

### Alternativas de implementação consideradas (sem escolha do humano; o builder segue a recomendada, o critic confere)

| Ponto | Recomendada | Alternativa | Motivo |
|---|---|---|---|
| Onde contar | `NetworkInterface`/`AbstractPacketProcessor` (ponto único, Contexto item 3) | Nas 23 chamadas | Esquecer uma chamada subconta C_nó em silêncio; ponto único é verificável por teste |
| Estrutura dos contadores | Arrays/`ArrayList` indexados por `getNodeID()`, `LinkedHashMap` se mapa | `HashMap<Node,...>` | Nó sem `hashCode` próprio: chavear por identidade chama `identityHashCode` (RM-08) |
| Quando contar o byte enviado | Quando o pacote **sai** da fila de envio (`execute`), isto é, quando termina a transmissão | Quando entra na fila (`addToUpLinkQueue`) | Entrar na fila conta bytes que ainda não ocuparam o enlace; sob saturação inflaria C_nó. Ver RM-03 para a consequência no fim da janela |

## Decisão

Adota-se a opção **(a)** com as sub-escolhas 1 a 3 do humano (2026-10-30). Cada requisito de medição tem identificador estável **RM-xx**, para a tarefa de `src/`, o critic, os testes e as métricas o citarem. Tudo o que não veio do humano está marcado **recomendado** ou `a definir com o humano`.

### Definições operacionais

**RM-01. Bytes enviados por um nó.** `S_i` = soma de `packet.getSize()` dos pacotes cuja transmissão no **enlace de subida** do nó *i* terminou, isto é, que saem da fila em `AbstractPacketProcessor.execute` (Contexto, itens 3 e 5). Inclui o envio do bloco pelo líder. Não inclui entrega direta (Contexto, item 4). Pacote com destino ao próprio nó **que passa pela fila** (o `PBFTP2P` inclui o nó entre os vizinhos) **conta** como enviado: ocupa o enlace no modelo e mantém a igualdade enviado = recebido. Que contar ou não esse pacote é pergunta aberta 4.

**RM-02. C_nó por bloco.** `S_i(b)` = parte de `S_i` em pacotes cuja mensagem **nomeia** o bloco *b* (bloco, proposta, voto, QC, anúncio por hash), como nos hooks de recepção do ADR-0006. `C_nó(b) = max_i S_i(b)`; registra-se também o `NodeID` do argmax (empate: menor `NodeID`, determinístico). Mensagem que não nomeia bloco (gossip de tx, `NEW_VIEW` sem QC) vai para um contador **não atribuído** por nó, fora de qualquer `C_nó(b)` e **dentro** de `S_i` e de U. **Corte:** o mesmo instante da linha do CSV (primeira finalização da altura), para ser comparável com C_B (ADR-0006). **Recomendado**; o corte alternativo (total do bloco até o fim da corrida) é pergunta aberta 5. Protocolo sem atribuição implementada: `-1`.

**RM-03. U de um nó.** Seja a janela `W = [t0, t1]`. `busy_i(W)` = medida do tempo em `W` em que a fila de envio de *i* é **não vazia**, integrada **por evento** (sem amostragem): o intervalo abre quando um pacote entra na fila vazia (`addToLinkQueue`, `NetworkInterface.java:120-125`) e fecha quando a fila esvazia em `execute`; intervalo aberto no fim é cortado em `t1`. `U_i(W) = busy_i(W)/(t1 − t0)`, com `0 ≤ U_i ≤ 1` por construção. Pelo item 5 do Contexto, `busy` de uma rajada vale `Σ T_j + (T_1 − T_n)`: **mede-se o que o simulador faz**, e a eq. `util` é só verificação (RM-09).

**RM-04. Qual U vai ao V3.** Duas leituras, e **elas dão números diferentes**: **U_corrida** = `max_i U_i(W)` (nó de maior média na janela) e **U_bloco** = `max_i` de `busy_i(I_b)/|I_b|` por intervalo de bloco `I_b`, depois resumido. Com líder **rotativo** (PBFT hoje, Contexto item 11; HotStuff `ROTATING`) cada nó lidera 1/n do tempo, então U_corrida tende à média entre nós e **dilui o pico do líder**, que é a hipótese a testar; U_bloco pega o pico, mas exige definir `I_b`. Com líder estável (`STABLE`, ablação) as duas coincidem no líder. **Registrar as duas; qual alimenta o V3 é `a definir com o humano`** (pergunta aberta 3).

**RM-05. Θ.** `ConfRate(W) = N_conf(W)/(t1 − t0)`, com `N_conf` = nº de transações **distintas** cuja primeira finalização cai em `W`, contadas por contador próprio (não pelas listas truncadas de `SimulationMetrics`, Contexto item 8; se a extração de txs falhar, `-1`, não 0). `Θ := ConfRate(W)` **somente** em corrida **saturada** (RM-07); caso contrário `Θ = -1` e `ThetaStatus` diz por quê. Em E1 `ConfRate ≈ min(λ, Θ)`, então **E1 não decide V1**; Θ vem de corridas separadas de saturação.

### Janela e transiente

**RM-06. Janela de medição.** O registro só liga com `t0` explícito (parâmetro de linha de comando, nome a fixar pelo builder); ausente, **falha alto** (nada de janela implícita nem transiente embutido). `t1` = fim da simulação. O simulador toma um instantâneo dos contadores em `t0` e subtrai no fim: o que ocorre antes de `t0` não entra em `S_i`, `busy_i` nem `N_conf`, e o transiente é descartado por construção. O CSV grava `WindowStart_s` e `WindowEnd_s`. Duração mínima da janela e critério de fim do transiente: `a definir com o humano` (pergunta aberta 6); a verificação `|W| ≥ W_min` é do pré-registro e da análise, não do simulador. A tarefa de `src/` não deve embutir valor nenhum.

### Saturação

**RM-07. Protocolo de saturação.** Para cada célula (protocolo, n, W_nó, R=Poisson), uma **escada** de demandas `λ_1 < λ_2 < …` (valores, passo e teto: `a definir com o humano`), uma corrida por degrau, mesma semente-base e o gerador único do ADR-0013. Um degrau é **saturado** quando a fila de pendentes cresce sem limite. **A fila de pendentes não existe hoje**: é criada pela tarefa de implementação do ADR-0013, que está **Proposto e sem código** (conferido no disco: `docs/adr/0013-formacao-de-bloco-e-demanda.md`, 175 linhas). Sem ela não há vazão limitada por demanda e `ConfRate` seria a taxa de proposta (Contexto, item 9). Critério operacional de "cresce sem limite": `a definir com o humano`, com três candidatos sem valor proposto: (i) inclinação positiva de `Q(t)` na segunda metade da janela, com teste estatístico; (ii) `Q(t1)` acima de um múltiplo do lote L; (iii) platô de `ConfRate` entre degraus (`ConfRate(λ_k) − ConfRate(λ_{k−1}) < ε`). O (iii) não olha para `Q`, mas só existe platô se o bloco for dimensionado pela fila (ADR-0013, decisão 4). Se nenhum degrau até o teto satura: `ThetaStatus = NOT_SATURATED` e Θ não é reportado como número (é censurado: Θ > teto).

### Saída

**RM-08. Não altera a saída existente.** Registro **desligado por padrão**. Desligado, a saída é **idêntica byte a byte** à anterior, cabeçalho incluído. Ligado, só **acrescenta**: as 14 colunas e o comentário inicial de `EnhancedBlockFinalizationLogger` (`:99-105,432-449`) não mudam de posição nem de valor; as novas vêm depois, e o registro por corrida/nó vai em arquivos **novos**. A instrumentação **não pode** chamar `hashCode`/`identityHashCode`, nem usar `HashMap`/`HashSet` chaveados por `Node`, `Packet`, `Block` ou `Message` (Contexto, achado N1): contadores em arrays indexados por `getNodeID()`; nenhum `RandomnessEngine` consumido; nenhum evento novo no `Simulator` (a ordem `number` de `ScheduledEvent`, `Simulator.java:70`, desempata eventos de mesmo instante e mudaria a saída).

**Colunas novas em `<prefix>-t0.csv`** (ao final, após `TrafficConsensusUntilFinalization`; `-1` = não medido):

| # | Coluna | Tipo | Significado |
|---|---|---|---|
| 15 | `CnodeSentMax_B` | long | `C_nó(b)` em bytes (RM-02) |
| 16 | `CnodeSentMaxNodeID` | int | `NodeID` do argmax de RM-02 |

**Arquivo novo por corrida** `<prefix>-corrida.csv` (1 linha): `Seed`, `SimulatorVersion`, `Protocol`, `n`, `WindowStart_s`, `WindowEnd_s`, `ConfRate_tx_per_s`, `Theta_tx_per_s`, `ThetaStatus` (`SATURATED`, `NOT_SATURATED` ou `NOT_MEASURED`), `U_run_max`, `U_run_argmaxNodeID`, `U_block_max`, `SentTotal_B`, `RecvTotal_B`, `DroppedAtDest_B`, `DroppedSenderDown_B`, `ResidualBytes_B`, `UnattributedSent_B`. **Arquivo novo por nó** `<prefix>-nos.csv`: `NodeID`, `SentBytes`, `RecvBytes`, `BusyTime_s`, `U_i`. Versão do simulador, semente e granularidade vão no comentário inicial dos dois, como já faz o logger (`:95-105`).

**RM-09. Sentinela.** `-1` significa **não medido** e **é diferente de 0** (0 é medido e nulo: nó que não enviou nada tem `SentBytes = 0` e `U_i = 0,0`). Regras: registro desligado → nenhuma coluna nova; ligado em protocolo sem atribuição de bloco → `CnodeSentMax_B = -1`; corrida que não é de saturação → `Theta_tx_per_s = -1` e `ThetaStatus = NOT_MEASURED`; extração de tx falhou → `ConfRate = -1`. Nenhum consumidor pode tratar `-1` como número (nem somar, nem tirar média, nem ajustar). O mesmo vale para a verificação da eq. `util`, que usa C_nó medido e `ν_b` medido e compara com U medido (tolerância e uso: `a definir`).

**RM-10. Granularidade.** Declarada no cabeçalho: C_nó **por nó que envia, por bloco, máximo entre os nós** (≠ C_B, que é por nó que recebe); U **por nó, sobre a janela**; Θ **agregado da rede, sobre a janela**. Formato definitivo da saída (colunas por bloco vs arquivos por corrida/nó): `a definir com o humano`; o desenho acima é **recomendado**.

### Colisão de nomes V1 a V3

Nomes iguais para coisas diferentes: **V1 a V3** do ADR-0000 e do artigo (critérios de viabilidade) e **V-A, V-B, V-C** do repositório (portões de qualidade do protocolo: `gauntlet-loop.config.json:71`; `enxame.config.json:128,170`), mais o fato de o V1 do ADR-0000 (Θ ≥ λ_pico) e o V1 do `.tex` (`Q(kP) ≤ ε_Q`) não serem o mesmo critério (Contexto). **Sugestão, a confirmar pelo humano:** renomear os de viabilidade para **CV1, CV2, CV3** no pré-registro (índice 6.6; ADR-0010 PI-20), mantendo V-A/B/C. Não renomeei nada em fonte alguma. Este ADR usa V1/V3 só quando cita o artigo e dispensa o nome nas regras RM.

## Consequências

### Efeito sobre o artigo e sobre a hipótese do líder

- **O que passa a ser testável:** V3 (U ≤ U_max) por célula, e a hipótese de `04:108` ("U captura o nó gargalo") com `CnodeSentMax_B` e `U_*` medidos. **O que não passa a ser provado:** que o líder é o gargalo. Isso é resultado, não premissa (regra 6 do `AGENTS.md`).
- **PBFT e HotStuff, previsão a conferir, não resultado [C, de leitura de código]:** no PBFT o primário envia o PRE_PREPARE (bloco + 10 B) a n destinatários (inclui ele mesmo, `PBFTP2P`), e **todo** nó envia 1 PREPARE e 1 COMMIT de 42 B a n destinatários; logo o envio por nó é `O(n)` para um nó comum (≈ 2·n·42 B) e o primário soma a isso `n·(B·s_tx + 10)`. No HotStuff o líder envia a proposta (com a carga do bloco) a n−1 e os QCs, e os demais enviam um voto ao líder; o tamanho do QC cresce com n pelo bitmap (`ceil(n/8)`, PI-15). Portanto o termo de carga do bloco é **linear em n no líder nos dois protocolos**; a diferença está no termo de votos (≈ `2·n·42` B em **todo** nó no PBFT; ≈ um voto por nó e QCs de tamanho crescente no líder no HotStuff). Se o líder for o gargalo dependerá de `B·s_tx` contra o termo de votos: a conta acima não decide, e o número virá da medição.
- **Líder rotativo dilui U_corrida** (RM-04). Se a tese do artigo for "o líder satura", a comparação precisa de U_bloco ou do HotStuff/PBFT com líder estável (ablação, ADR-0009/PI-14). A tensão rotativo × estável do pBFT (ADR-0009, Consequências) fica **aberta** e afeta a leitura de U.
- **Θ não se compara entre protocolos sem banda uniforme** (Contexto, item 7; PI-07): hoje só o LAN100M é constante.
- **Redação para o artigo, para o arquivo de correções (não editado aqui):** "Θ é a taxa de confirmação sustentada sob saturação, medida em execuções separadas de E1; U é a fração do tempo em que a fila de envio do nó fica ocupada, medida no simulador; a aproximação `8·C_nó·ν_b/K_nó` é só verificação." O V1 do `.tex` (`Q(kP) ≤ ε_Q`) **não** é coberto por este ADR e precisa de decisão (pergunta 1).

### Dependências e ordem de trabalho

1. **ADR-0013 (fila de pendentes), Proposto, sem código.** A escada de saturação (RM-07) e o Θ **dependem** da fila e da regra de fechamento. A instrumentação de **C_nó e U** (RM-01 a RM-04, RM-06, RM-08) **não** depende da fila e pode ser feita antes; o Θ não.
2. **N1 (conversão de n):** sem veredito de não regressão (ADR-0013, Dependências). Misturar a instrumentação com o deslocamento de hash da N1 invalida a prova; fechar o veredito da N1 primeiro.
3. **Ordem sugerida:** (i) veredito da N1; (ii) tarefa A: contadores de envio/ocupação + arquivos novos + testes (RM-01 a RM-04, RM-06, RM-08, RM-09), sem Θ; (iii) ADR-0013 implementado; (iv) tarefa B: Θ e protocolo de saturação (RM-05, RM-07). São tarefas do `loop2-jabs-codigo`, **nenhuma aberta por este ADR**.

### Riscos

- **Custo de desempenho:** contadores por pacote no caminho quente. Esperado pequeno, **não medido** (`NAO_MEDIDO`); a tarefa mede o tempo de parede de ARQ:7:60 e MCO2:50:600 ligado e desligado.
- **Truncamento silencioso:** as listas de latência cortam em 10 000 (`SimulationMetrics:28`). Se algum número novo reaproveitar essas listas, nasce truncado; RM-05 exige contador próprio.
- **Fim de corrida:** o laço para por tempo com pacotes em trânsito. Bytes contados como enviados que não chegam aparecem em `ResidualBytes_B` e na diferença `SentTotal_B − RecvTotal_B`; nenhuma regra os descarta.
- **Frequência do corte de C_nó:** a linha do CSV sai na primeira finalização da altura (Contexto, item 2); C_nó do bloco é um instantâneo, e o COMMIT tardio de outros nós fica de fora. Mesma limitação do C_B (ADR-0006); declarada, não corrigida.

### Parâmetros que continuam `a definir com o humano`

U_max (sugestão do artigo, 0,7, é **exemplo** do `.tex:116`, sem fonte); L_max; critério de saturação (RM-07); duração mínima da janela e fim do transiente (RM-06); U de corrida ou de bloco (RM-04); corte de C_nó (RM-02); granularidade/formato de saída (RM-10); degraus, passo e teto da escada de λ (RM-07); tolerância da verificação da eq. `util` (RM-09).

## Verificação

Critérios para o critic do `loop2-jabs-codigo` e para o loop 3. **Nenhum foi executado**: este ADR não tem código. Cada teste cita o RM que cobre. Desligado = registro desligado (padrão).

### Testes exigidos

| ID | Teste | Cobre |
|---|---|---|
| VT-01 | **U ≤ 1** e **U ≥ 0** para todo nó, em toda corrida, inclusive saturada e com nó caído (`crash`). Teste com enlace estrangulado (banda baixa) até a fila nunca esvaziar: U tem de chegar a 1,0 (±arredondamento) e **não passar** | RM-03 |
| VT-02 | **Enviado = recebido**, por contagem de bytes. `RecvTotal_B` conta no ponto em que `PacketReceivingProcess` entrega ao nó (`processIncomingPacket` vindo da fila de recepção), nunca nas entregas diretas. Identidade a provar: `SentTotal_B = RecvTotal_B + DroppedAtDest_B + ResidualBytes_B`, em que `DroppedAtDest_B` são pacotes enviados e descartados porque a interface do destino estava caída (`NetworkInterface.java:99-103`), e `ResidualBytes_B` são os que no fim da corrida estão em trânsito (evento de latência já agendado) ou na fila de recepção. `DroppedSenderDown_B` (descartado no remetente caído, `:109-113`) **nunca entra** em `SentTotal_B` e é só informativo. **Caso sem `crash` e rodado até esvaziar** (nenhum evento pendente): `SentTotal_B == RecvTotal_B` exato. A rede simulada não tem perda aleatória (Contexto, item 6); as duas exceções reais têm coluna própria e não são perda silenciosa | RM-01, RM-08 |
| VT-03 | **Bloco único em rede de 4 nós** (PBFT): `S_i` por nó bate com a conta à mão (PRE_PREPARE = bloco + 10 B a n destinatários incluindo o próprio; PREPARE e COMMIT de 42 B a n). Valores esperados escritos no teste, não derivados do código testado | RM-01, RM-02 |
| VT-04 | **Autoentrega direta não conta**: `CasperFFG:360-369` e `DAGsper:353` não mudam `S_i` | RM-01 |
| VT-05 | **Teste de ocupação com valor fechado**: nó que envia k pacotes de tamanho s em rajada com banda K tem `busy = (s·8/K)·k` quando todos têm o mesmo tamanho (T1 = Tn); com tamanhos diferentes, `busy = Σ T_j + (T_1 − T_n)` (Contexto, item 5). O teste documenta a diferença, não a esconde | RM-03 |
| VT-06 | **Janela**: dois registros de mesma corrida com `t0` diferentes dão `S_i`, `busy_i` e `N_conf` iguais à diferença dos instantâneos; sem `t0`, **falha alto** | RM-06 |
| VT-07 | **Sentinela**: registro ligado em protocolo sem atribuição de bloco dá `CnodeSentMax_B = -1`; um nó que não enviou nada dá `0`. Nenhuma coluna mistura `-1` e `0`. Corrida não saturada dá `Theta = -1` e `ThetaStatus = NOT_MEASURED` | RM-09 |
| VT-08 | **Θ sob saturação (depende do ADR-0013)**: com λ muito abaixo de L/T, `ConfRate ≈ λ` e `ThetaStatus ≠ SATURATED`; com λ muito acima, `ConfRate` estabiliza num valor independente de λ e `Q(t)` cresce; duplicar λ não muda Θ além da tolerância | RM-05, RM-07 |
| VT-09 | **Fila de pendentes cresce** na corrida saturada e não cresce na não saturada (critério de RM-07, quando fixado) | RM-07 |
| VT-10 | **Argmax determinístico**: empate de `S_i(b)` resolve para o menor `NodeID`; mesma semente, duas corridas, mesmas colunas | RM-02 |
| VT-11 | **Igualdade com a verificação do artigo**: com blocos de tamanho igual e ν_b constante medido, `U_i` medido concorda com `8·C_nó·ν_b/K_nó` dentro de tolerância `a definir`, e a diferença residual é explicada por T1 − Tn (VT-05) e por mensagens não atribuídas | RM-09 |

### Não regressão (exigência central)

**Regra:** a instrumentação não pode alterar a saída existente (RM-08). Baseline = commit **`325f4e3`**, worktree **`/tmp/opencode/base-325f4e3`** (existe e tem `target/classes`). Comparação com `scripts/jabs/nao_regressao.py`, que já ignora colunas com "hash" e linhas `#`.

1. **Registro desligado: saída idêntica byte a byte** (cabeçalho e comentário incluídos) à baseline e à árvore sem a tarefa, em ARQ, HotStuff (quando rodar ponta a ponta), AMBIFY, MCO2, OFFSET. Ligado: as 14 colunas atuais idênticas; as novas ao final; o resto do arquivo igual. **Atenção:** `nao_regressao.py` aceita hoje uma única coluna extra (`TrafficConsensusUntilFinalization` com `-1`, `:80,97-103`). Para a coluna 15 e 16 o script **não tem tratamento**, e ele fica em `scripts/` (fora do meu escopo): a tarefa de `src/` precisa pedir a extensão ao dono do script ou comparar as 14 primeiras colunas por outro meio. Pergunta aberta 8.
2. **ARQ/PBFT: não afetado pela sequência de hash.** `resumo-base-k.txt` mostra ARQ:7:60 idêntico para k = 0 a 200 [E]. A comparação direta base × atual vale **sem fixar o hash**, mas **só para ARQ:7:60 semente 1**; outros n e sementes **não** estão cobertos por essa evidência. Cobrir ao menos ARQ n ∈ {4, 7, 16}, 3 sementes, 2 JVMs por lado (padrão do CA-01 do ADR-0011).
3. **Casper (MCO2/TREE), Parlia (AMBIFY) e Nakamoto (OFFSET): provar com a JVM em hash constante nos dois lados**, `-XX:+UnlockExperimentalVMOptions -XX:hashCode=2` (única forma testada com sucesso: `resumo-flags.txt`, `resumo-flags2.txt`; com `hashCode=3` o AMBIFY ainda diverge). **Alternativa:** corrigir a causa (`memPool` em `LinkedHashSet`), que **muda a saída do Casper** e exige ADR próprio e nova baseline (ADR-0013, Não regressão); não é passo desta tarefa. Enquanto nenhuma das duas for executada, o critério de não regressão desses três é **PENDENTE**, não "idêntico". O relatório diz qual forma foi usada.
4. **Sonda de hash.** A tarefa roda `SondaHash` (`src/test/java/jabs/example/SondaHash.java`) base e atual em ARQ:7:60 e AMBIFY:10:60. Se a posição da sequência diferir, a instrumentação consome `identityHashCode` e viola RM-08, mesmo que a saída fixada coincida. **Observação:** em ARQ:7:60 a base já difere do atual em 8 chamadas (23124 contra 23132, `resumo-sonda.txt`) **sem** mudar a saída; essa diferença vem da N1, não deste ADR. A comparação válida é "atual sem a tarefa" contra "atual com a tarefa".
5. **Não misturar:** rodar a não regressão só depois do veredito da N1, para que a diferença de hash da N1 não se confunda com a desta tarefa (ADR-0013, Dependências).
6. **Mudança intencional declarada:** o comentário inicial do CSV traz a versão do simulador; teste impede misturar saída anterior e posterior (mesmo item 8 da Verificação do ADR-0013).

### Gates de processo

- Tarefas no `loop2-jabs-codigo` (builder `jabs-builder`, critic `gauntlet-critic-jabs`), 3 rodadas, contador por `scripts/enxame/registrar-rodada.py` (código 20 = parar). Escopo de escrita: `src/` e `pom.xml`. A tarefa deve listar na entrega os arquivos tocados em `src/` e a saída da sonda.
- `python3 scripts/lint/lint-jabs.py src/main/java` sem achados novos. O linter não prova corretude.
- Número de Θ, U e C_nó só entra em resultado com semente, versão do simulador e granularidade declaradas (regra 6) e passa pelo loop 3 (impacto ALTO ou CRITICO: humano).

### Estado deste ADR

**Proposto.** Passa a Aceito quando o humano confirmar (a) este conteúdo e (b) pelo menos U_max, o critério de saturação e a janela mínima. A decisão (a) de 2026-10-30 e as sub-escolhas 1 a 3 já estão registradas e não precisam ser confirmadas de novo.

### Perguntas abertas ao humano

1. **V1 do artigo × V1 do ADR-0000.** O `.tex` define V1 como `Q(kP) ≤ ε_Q` (pendentes ao fim do período) e diz que é "menos restritivo" que `Θ ≥ λ_pico`; o ADR-0000 define V1 como `Θ ≥ λ_pico`. Qual vale no pré-registro? Se for Q(kP), este ADR não o cobre (precisa medir a fila a cada kP, que só existe depois do ADR-0013). Se forem os dois, o nome muda (CV1a/CV1b?).
2. **U_max e L_max.** Valores e fonte. O 0,7 do `.tex:116` é só "por exemplo".
3. **U de corrida ou de bloco (RM-04), e líder rotativo.** Qual alimenta o V3? E o PBFT segue rotativo (ADR-0007, opção 2) ou líder estável (PI-14)? Mudar a política muda U.
4. **Pacote para si pela fila (PBFTP2P).** Conta em `S_i` e em U? Recomendado: sim (ocupa o enlace no modelo), com coluna própria para poder tirar.
5. **Corte de C_nó (RM-02).** Instantâneo da primeira finalização da altura (comparável ao C_B) ou total do bloco até o fim da corrida?
6. **Janela (RM-06).** Duração mínima, critério de fim do transiente, e se `t0` é igual para todos os protocolos.
7. **Critério de saturação (RM-07).** (i), (ii) ou (iii)? Degraus, passo e teto da escada de λ?
8. **Dono da extensão de `nao_regressao.py`** para as colunas novas (o script está em `scripts/`, fora do escopo do `redd-dominio`).
9. **Nomes CV1 a CV3.** Confirma a sugestão?
10. **Formato de saída (RM-10).** Colunas na linha de bloco, arquivos por corrida/nó, ou os dois?
11. **Banda por nó uniforme (PI-07).** É pré-requisito de comparar U e Θ entre protocolos; entra antes da bateria?
12. **Confirmar o hash `325f4e3`** (não consegui rodar `git log` aqui).
