# Auditoria do protocolo experimental: o que ainda falta (A a D)

> Escopo: complementa `especificacoes/protocolo-experimental-index.md` (P1-P8 e inconsistências a-o). **Não repete** o que lá consta; só acrescenta o que ainda faltava verificar. Fonte do desenho: `.experimento/` (não editado). Verificado em 2026-10-29 lendo `src/main/java/jabs`; **nada foi executado** (sem build, sem simulação) e **não há medição da aplicação Fabric**. Onde falta dado: `NAO_MEDIDO` ou `a definir com o humano`.
>
> Nota de nomes: o índice usa os níveis do YAML (`pbft`, `casperffg`, `parlia`, `nakamoto`). No código, esses protocolos aparecem como classes `PBFT`, `CasperFFG`, `Parlia` e `NakamotoConsensus`, sem relação 1:1 com as chaves do `MVPComparison` (seção B.3).

---

## A. Protocolo x ataque

### A.1 Tabela (o que cada classe faz com o tipo de ataque configurado)

Legenda: **IMPL** = há ramo próprio para o tipo. **NO-OP** = o tipo é lido, mas cai no comportamento honesto sem erro. **OUTRO** = o tipo é tratado como outro tipo.

| Ataque | PBFT / ARQ | CasperFFG | Parlia | Nakamoto (Bitcoin/OFFSET) |
|---|---|---|---|---|
| SILENT | **IMPL**, em duas fases e com efeito duplo. Primário: não envia PRE_PREPARE (`PBFT.java:306-307`). COMMIT: não envia (`PBFT.java:343-344`). **PREPARE: sem ramo**, o byzantine ainda envia PREPARE (`PBFT.java:186-190`). No ARQ, SILENT derruba o nó na rede (`ArqReddVotingScenario.java:100-104`, `Node.crash`, `Node.java:96`), então também não envia nem recebe nada. | **IMPL**: não vota (`CasperFFG.java:271-272`). O nó continua recebendo e propagando blocos. | **IMPL**: o validador não vota (`Parlia.java:212-215`) e, se for proponente, não produz bloco (`Parlia.java:341-343`). | **NO-OP**: nenhum ramo para SILENT em `BitcoinNode.java:58-62`. O minerador byzantine com SILENT se comporta como honesto. |
| WITHHOLD | **IMPL**: primário não envia PRE_PREPARE (`PBFT.java:308-309`); não envia COMMIT (`PBFT.java:345-346`). PREPARE: sem ramo. Sem `crash`, o nó recebe e propaga como honesto. | **IMPL**: não vota (`CasperFFG.java:273-274`). | **IMPL**, idêntico ao SILENT (`Parlia.java:212-215`, `341-343`). | **IMPL** como retenção de bloco: não faz `broadcastBlockInvMessage` do próprio bloco (`BitcoinNode.java:60-61,69-71`). Semântica diferente das votantes (retém bloco minerado, não voto). |
| EQUIVOCATION | **IMPL**, metade dos vizinhos recebe um bloco, metade outro: PRE_PREPARE (`PBFT.java:310-322`) e COMMIT (`PBFT.java:347-358`). Dois blocos `a` e `b` com 50 tx cada, via `BlockFactory`. Erro engolido (`catch (Exception ignored)`, `PBFT.java:322,358`). PREPARE: sem ramo. | **IMPL**, metade vota num link, metade em outro (`CasperFFG.java:275-298`); o alternativo cai de volta no mesmo link se não houver bloco justificado alternativo (`CasperFFG.java:285-289`), degenerando em voto honesto. | **IMPL parcial**: vota no bloco e num alternativo (`Parlia.java:217-224`), mas o alternativo só existe se `originalBlock` e `validator` forem Ethereum (`Parlia.java:257-258`) e se houver pai (`Parlia.java:259-262`); senão `alt == null` e o byzantine vota só uma vez (como honesto), sem erro. Votos duplos não mudam o quórum (conjunto de votantes em `Parlia.java:108`), logo o efeito é nulo no quórum. | **OUTRO**: tratado como WITHHOLD (`BitcoinNode.java:61`). Já registrado em (l) do índice. |
| DOUBLE_SIGN | **IMPL só no COMMIT** (`PBFT.java:359-362`): envia o mesmo commit duas vezes. No primário e no PREPARE: sem ramo. O contador por voter distinto (`PBFT.java:263`, mapa voter -> voto) torna o efeito nulo no quórum. | **IMPL**: envia o mesmo voto duas vezes (`CasperFFG.java:299-304`); o mapa por voter (`CasperFFG.java:74`) neutraliza. | **IMPL**: mesmo ramo do EQUIVOCATION (`Parlia.java:217-230`): vota no bloco e, se `createEquivocationBlock` devolve um bloco, também no alternativo; se devolve `null`, repete o voto no mesmo bloco (`:225-228`). Conjunto de votantes (`:108`) neutraliza o voto repetido. | **NO-OP** (nenhum ramo). |

**Mesma semântica entre protocolos? Não.** Resumo do que difere:

1. EQUIVOCATION/DOUBLE_SIGN em votantes: sem efeito sobre o quórum em CasperFFG e Parlia (contagem por voter distinto), mas com possível efeito em PBFT (blocos `a` e `b` distintos viram votos em hashes diferentes, fragmentando o quórum). Os três "equivocam" por mecanismos distintos.
2. Em Nakamoto o ataque é sobre o bloco minerado, não sobre voto. Não existe voto a equivocar.
3. SILENT é crash completo só no ARQ (`ArqReddVotingScenario.java:101-104`). Em CasperFFG e Parlia, SILENT == WITHHOLD (o nó continua propagando). O `PBFTLANScenario` não injeta byzantine nenhum (`PBFTLANScenario.java:21-32`): o único caminho de ataque no PBFT é o `ArqReddVotingScenario`/`HybridNetworkScenario`.
4. Fração f: cada cenário calcula o conjunto byzantine de um jeito (A.3).

Evidência de que o SILENT do ARQ é crash: `ArqReddVotingScenario.java:100-104`. Não há equivalente nos demais cenários (`CasperEthereumNetworkScenario.java:73-102`, `BitcoinGlobalNetworkScenario.java:125-158`, `ParliaBSCNetworkScenario.java:71-83`).

### A.2 String desconhecida (por exemplo `equivocacao`)

Dois caminhos de entrada com comportamentos diferentes:

| Caminho | O que acontece | Evidência |
|---|---|---|
| **CLI do `ByzantineSweep`** (`--attack=equivocacao`) | **Falha explícita** com `IllegalArgumentException` de `Enum.valueOf` (o `toUpperCase` é só de caixa; acentos e português não ajudam). Falha antes de qualquer célula rodar. | `ByzantineSweep.java:34-35` |
| **Construtor `String` do `ByzantineConfig`** | Aceita **qualquer** texto sem validar. | `ByzantineConfig.java:49-58`, `67-74` |
| **Dentro dos algoritmos** (se chegar string livre) | Cai no ramo honesto **sem erro e sem log**. PBFT: cadeia `if/else if` termina em "Normal broadcast" (`PBFT.java:323-334`, `363-369`). CasperFFG: `else` honesto (`CasperFFG.java:305-309`). Parlia: nenhum `if` casa, cai em "Honest behavior" (`Parlia.java:232-233`). Nakamoto: `shouldWithhold` fica `false` (`BitcoinNode.java:54-71`). | idem |
| **DAGsper (fora do escopo)** | Recusa: `UnsupportedOperationException` (`DAGsperNetworkScenario.java:104-107`, `DAGsper.java:341`). É o único com validação. | idem |

Consequência: o `bateria2.yaml` usa `ataque_bizantino: equivocacao` (linha 13). Se o gerador de configurações repassar esse texto ao construtor `String` (ou a um parâmetro que o construa), o resultado seria a Bateria 2 inteira com **todos os nós honestos e nenhum erro**. Se passar pelo `ByzantineSweep`, quebra no `valueOf`, mas só por acaso (o `ByzantineSweep` converte para o enum). O caminho `ByzantineConfig(String)` é usado em `Casper...:85`, `Bitcoin...:137-142` (via `AttackType.name()`, seguro) e `ByzantineSweep.java:157-161` (via enum, seguro); **os caminhos atuais passam por enum**, então o risco é de caminho futuro, não atual. O índice (f) já aponta o risco; aqui a verificação dos caminhos mostra que hoje só a CLI é validada, por acidente.

### A.3 Seleção dos nós byzantine (afeta o requisito "mesma semente entre protocolos" de `PROTOCOLO` §3)

| Cenário | Como escolhe os IDs | Evidência |
|---|---|---|
| ARQ/Hybrid | `selectByzantineValidators`: sorteia ids em `[0, n)` com `new Random(seed)` até atingir `ceil(n * pct/100)` | `ByzantineConfig.java:79-87` |
| CasperFFG | `java.util.Random(seed)` sorteia entre os **stakeholders**, ids deslocados por `numMiners`; `ceil(stake * pct/100)` | `CasperEthereumNetworkScenario.java:73-83` |
| Nakamoto | `java.util.Random(seed)` sorteia entre os **mineradores** (`numMiners`); `ceil(miners * pct/100)` | `BitcoinGlobalNetworkScenario.java:125-135` |
| Parlia | recebe um `ByzantineConfig` pronto; o sweep usa o mesmo construtor do ARQ (sorteio em `[0, n)`), mas só `numMiners = round(0,3 n)` são validadores (`ByzantineSweep.java:143,157-162`; `ParliaBSCNetworkScenario.java:86-94`) | idem |
| DAGsper | RNG próprio, subconjuntos aninhados | `DAGsperNetworkScenario.java:46-48` |

Problemas para a Bateria 2 (apenas apontados; sem decisão):

- Os conjuntos **não são os mesmos** entre protocolos para a mesma semente: universos de sorteio diferentes (todos os nós, stakeholders, mineradores) e intervalos de ids diferentes. Pareamento por semente (PROTOCOLO §3) não vale hoje.
- Entre valores de f, para um mesmo protocolo, o conjunto **deve** ser aninhado por construção (mesmo `Random(seed)`, mesmo laço `while` que só acrescenta ids; no sweep a semente não depende de f, `ByzantineSweep.java:74`). Isso é inferência da leitura: **só o DAGsper tem teste de aninhamento** (`DAGsperScenarioTest.java:190-199`); ARQ, Casper e Bitcoin não. Segundo ponto de atenção: `ceil(n * (pct/100.0))` em ponto flutuante (`ByzantineConfig.java:80`; `CasperEthereumNetworkScenario.java:77`; `BitcoinGlobalNetworkScenario.java:128`) pode dar +1 em alguns percentuais; **não verifiquei** para 10, 20, 33, 40 e 51.
- Em Parlia, apenas 30% dos nós são validadores; o byzantine sorteado em `[0, n)` pode cair em nó não validador, sem efeito. Em CasperFFG, os mineradores nunca são byzantine (só stakeholders). Isso significa que "f = 33%" tem significados diferentes por protocolo. (Mapeamento de `n` por protocolo em B.3.)
- **"Nós bizantinos coordenam entre si"** (`PROTOCOLO` §3): não há coordenação no código. Cada nó byzantine decide sozinho, com `half = size/2` dos vizinhos locais (`PBFT.java:314`, `CasperFFG.java:279`). Busca por `coordinat|collud` em `src/main/java`: nenhuma ocorrência.

### A.4 Lacunas desta seção

Ver tabela na seção E (lacunas L1 a L8).

---

## B. Parâmetros base dos specs versus o que MVPComparison e ByzantineSweep aceitam

### B.1 O que cada CLI aceita hoje

Ambos usam `parseArgs` próprio: só `--chave=valor` (`MVPComparison.java:548-559`, `ByzantineSweep.java:316-327`). **Chave desconhecida é descartada em silêncio** (ex.: `--largura-banda=...` é aceito pelo parser e ignorado). Não há leitura de YAML/JSON nos dois (`grep` por yaml/ObjectMapper em `src/main/java/jabs`: sem ocorrências).

| Chave | MVPComparison | ByzantineSweep |
|---|---|---|
| `validators` | sim, **lista** CSV (`:43`) | sim, **um** n (`:29`) |
| `protocols` | sim, lista (`:44`): EARTH, MCO2, TREE, AMBIFY, OFFSET, ARQ, DAGSPER (`:184-200`) | não; usa `--type` (`:33`): VOTING, POS, POW, POSA, DAGSPER (`:81-195`) |
| `trials` | sim, padrão 5 (`:45`) **[superado em 22/11/2026: obrigatório, sem padrão; ADR-0015 Q26-A]** | sim, padrão 30 (`:31`) **[superado: obrigatório, sem padrão]** |
| `duration` / `durations` | sim; global e por protocolo (`:46,54`) | só `duration`, padrão 600 (`:32`) |
| `seed` | sim, padrão 12345 (`:47`) **[superado em 22/11/2026: obrigatório, sem padrão]**; semente da execução = `seed + validators*1000 + trial` (`:84`) | sim, padrão 12345 (`:74`); `seed + n*1000 + t` (**mesma semente para todos os f**, só varia com `t`) |
| `output` / `csv-out` / `out` | sim | `--out` |
| `enable-tx`, `tx-interval` | sim; padrão `true`, 5,0 (`:50-51`); só EARTH, OFFSET, DAGSPER usam (`:186,194,198`) | sim; só POW e DAGSPER usam (`:130-131,185-186`) |
| `attack` | **não** (todos honestos: `AttackType.NONE`, `:262,298`) | sim, enum (`:34-35`) |
| `max-byz` / f | **não** | `--max-byz` (padrão `n/2`), passo `max(1, n/100)` (`:30,49-54`): f é **contagem absoluta**, convertida em % por `100*f/n` (`:83,101,121,142`) |
| `max-pdv-pct` | não | sim (`:198,257`) |
| `turn-length`, `epoch-length` | não | sim, só POSA (`:144-145`) |

### B.2 Parâmetros base dos YAML

| Parâmetro (valor no YAML) | MVPComparison | ByzantineSweep | Onde está fixo no código hoje | Falta |
|---|---|---|---|---|
| `largura_banda_mbps` (100) | **não** | **não** | PBFT/ARQ: constante 100 Mbps (`LAN100MNetworkStats.java:10`, `bits/s`; tempo de envio = `bytes*8/bandwidth`, `PacketSendingProcess.java:21`). Casper/Parlia/Ethereum: distribuição de 6 regiões (`GlobalNetworkStats6Region.java:134-140`). Nakamoto: 86 países, lognormal (`GlobalNetworkStats86Countries.java:144-152`). Ou seja, **o "100 Mbps igual para todos" só vale no PBFT/ARQ**; os outros protocolos usam larguras amostradas e heterogêneas. | parâmetro e `NetworkStats` homogêneo para os 4 protocolos |
| `topologia` (aleatoria) | **não** | **não** | PBFT: malha completa (`PBFTP2P.java:9`, todos os nós são vizinhos). Nakamoto: `BitcoinCoreP2P`, 8 saídas, máx. 125 (`BitcoinCoreP2P.java:5-6`). Casper/Parlia: `EthereumGethP2P`, máx. 10 pares, 3 saídas (`EthereumGethP2P.java:4-9`). Latências: Pareto (LAN) ou matriz de regiões. **Nenhuma é "aleatória" no mesmo sentido.** O PBFT é malha completa; os outros, grau limitado. | definir "topologia" como fator único e parâmetro; hoje é consequência do protocolo |
| `tamanho_bloco_kb` (1024) | **não** | **não** | PBFT: `sampleBitcoinBlockSize` (distribuição de 2020, ~1,0 a 1,4 MB, `BlockFactory.java:37-48,63,89`); Bitcoin com tx: até 1.800.000 B (`BitcoinMinerNode.java:24`); Ethereum: limite de gás 12.500.000 (`EthereumMinerNode.java:27`). Não há 1024 KB em lugar nenhum. | parâmetro único de tamanho/limite de bloco e implementação por protocolo |
| `tamanho_tx_bytes` (512) | **não** | **não** | amostrado de distribuições Bitcoin/Ethereum herdadas (`TransactionFactory.java:15-17,40-42,61-65`), não constante. PBFT: tx de distribuição Bitcoin com 32 B de hash (`TransactionFactory.java:61-65`). | idem; é também o ponto de entrada da calibração da ponte |
| `esquema_assinatura` (individual) | **não** | **não** | **não modelado**: nenhum tamanho ou custo de assinatura no código (`grep -i signature\|assinatura` em `src/main/java` só acha comentário em `AbstractConsensusAlgorithm.java:50,59`). Voto PBFT: `PBFT_VOTE_SIZE_OVERHEAD = 10` + hash 32 (`PBFTBlockVote.java:11`; `PBFTTrafficMeasurementTest.java:93`: 42 B). | decidir se "individual vs agregada" será modelado (muda o tamanho do voto); tamanho real depende da medição Fabric (`NAO_MEDIDO`) |
| `duracao_sim_s` (7200) | sim: `--duration` / `--durations` | sim: `--duration` | n/a | ok. Mas as unidades diferem: `BitcoinGlobalNetworkScenario` converte para `long` (`MVPComparison.java:398,426`; `ByzantineSweep.java:125`). |
| `warmup_descartar` (true) | **não** | **não** | **não existe** corte de transiente no simulador: sem ocorrência de `warmup`/`transient` em `src/main/java`. Os logs trazem `Time` por linha (`EnhancedBlockFinalizationLogger.java:433-448`), então o corte teria de ser pós-processamento; mas ver B.4 (agregação). | `a definir com o humano` (valor vem do Welch no piloto); script de corte fica fora de `src/main` |
| `sementes` (10) | `--trials` (+ `--seed` base) | `--trials` (+ `--seed` base) | A semente por trial é derivada (`seed + n*1000 + trial`): **réplicas = trials consecutivos**, não lista de sementes arbitrárias. | aceitar lista de sementes explícita (o protocolo pede "uma semente por processo") |
| `n` | `--validators` | `--validators` | mapeamento de n por protocolo é distinto (B.3) | |
| `perfil_carga` | **não** | **não** | C.1 | |
| `mix` | **não** (ARQ fixo 30%: `MVPComparison.java:254-262`) | só VOTING (`ByzantineSweep.java:84-90`, `privateTransactionPercentage` padrão 30) | índice P6/ressalva: tx do mix não entram na rede | |

### B.3 Mapeamento proposto (apenas descrição; **nada implementado**)

**Fator A (níveis do YAML) -> chaves do `MVPComparison` / `--type` do `ByzantineSweep`.** O índice já registrou que a correspondência é `a definir com o humano`. Abaixo, só o que o código permite sem inventar:

| Nível YAML | `MVPComparison --protocols=` | `ByzantineSweep --type=` | Classe de consenso efetiva | Observação verificada |
|---|---|---|---|---|
| `pbft` | `ARQ` | `VOTING` | `PBFT` sobre `PBFTLocalLANNetwork` | O ARQ roda no cenário `HybridNetworkScenario` (`MVPComparison.java:254-262`). O proponente é fixo, `min(10, n-1)` (`PBFTLocalLANNetwork.java:97`), e a view não é validada (ADR-0007). |
| `casperffg` | `MCO2` ou `TREE` (diferem só em `averageBlockInterval`: 15 s vs. 60 s, `MVPComparison.java:318,344`) | `POS` | `CasperFFG` sobre `CasperFFGGlobalBlockchainNetwork` | Escolher MCO2 ou TREE é decisão `a definir com o humano`. No `MVPComparison`, `n` = mineradores `0,3 n` + stakeholders `0,7 n` = **n nós** (`:321-322`). No `ByzantineSweep`, `numStakeholders = n` e `numMiners = 0,3 n` (`:99-100`) = **1,3 n nós**. **Os dois não equivalem.** |
| `parlia` | `AMBIFY` | `POSA` | `Parlia` sobre `ParliaGlobalBlockchainNetwork` | `MVPComparison`: mineradores `0,3 n` + `0,7 n` outros = n nós (`:375-376`); `ByzantineSweep`: mineradores `0,3 n` + `n` outros = 1,3 n nós (`:143,154-155`). Os validadores Parlia são só os mineradores (`ParliaBSCNetworkScenario.java:86-94`), ou seja `0,3 n` em ambos. O `n` do YAML tem pelo menos 3 leituras (total de nós, validadores, stakeholders). |
| `nakamoto` | `OFFSET` (profundidade de confirmação 14, intervalo 600 s, `MVPComparison.java:395-404`) ou `EARTH` (profundidade 6, intervalo 450 s, `:423-432`) | `POW` (profundidade 6, intervalo 600 s, `:120,126`) | `NakamotoConsensus` | Três configurações diferentes para "Nakamoto". EARTH está rotulado `ConsensusType.POS` no `MVPComparison` (`:421`) apesar de ser PoW. n = nº de mineradores (`numNodes = 0`). |
| `hotstuff`, `tendermint` | **inexistentes** | **inexistentes** | não implementados (P4a/P4b do índice) | |

Para os **mesmos** `n` do YAML, o total de nós simulados é diferente por protocolo (n, 1,0 n, 1,3 n). O fator B "validadores" **não tem o mesmo significado** entre os 6 níveis. Isso entra no ADR de desenho como lacuna.

**f em % -> parâmetros.** `ByzantineSweep` aceita f como **contagem** de nós (`--max-byz`, passo `max(1, n/100)`), gera todos os valores de 0 a `max-byz` e calcula `byzPct = 100*f/n` (`:49-54,83`). Para os níveis do YAML (0, 10, 20, 33, 40, 51 %) com n=100:

- Passo = `max(1, 100/100) = 1` -> os f inteiros 0..`max-byz` incluem 10, 20, 33, 40, 51, mas também todos os intermediários; hoje não há como pedir só os seis.
- `--max-byz=51` gera 52 valores de f; custo ~8,7× o necessário (52/6). `a definir` se o sweep ganha `--f-list=0,10,20,33,40,51` (gap de `jabs-builder`).
- `ByzantineConfig` converte % em contagem com `ceil(n * pct/100)` (`ByzantineConfig.java:80`; para n=100 e 33% dá 33; para 51% dá 51).
- Para n ≠ 100 a conta é diferente por protocolo (A.3); a Bateria 2 fixa n=100, mas "n=100" tem o significado de B.3.
- Em PoW, `byzPct` é % dos **mineradores**, não de poder de hash (`BitcoinGlobalNetworkScenario.java:127-128`; a documentação diz "hashpower", `:124,89`, mas a seleção é por contagem de mineradores com `hashPower` sorteado por nó, `GlobalProofOfWorkNetwork.java:75-79`). Para f=51% a conta por contagem e por hash divergem.
- Detalhe de bug latente: `ByzantineSweep.java:240` divide por `trialsExecuted`, que pode ser 0 se todos os trials lançarem exceção (`:233-237` só imprime e **não** pula: `// continue;` está comentado). Resultado: linha com NaN no CSV. O índice não cobria.

**Atenção à semântica de `secure`.** O sweep decide `secure` por `evaluateSecurityFromAverages` (`SimulationMetrics.java:421-490`) com limiares **embutidos**, e a função é inconsistente consigo mesma para POS: no ramo "BFT empírico > 0" usa `(n-1)/3 + 1` (1/3) para POS (`:445-447`), no ramo de fallback usa `ceil(n/2) + 1` (1/2) (`:479-482`). V-B (`PROTOCOLO` §6) espera "~1/2 para probabilísticos". Qual ramo vale depende de o BFT empírico ter sido medido > 0 naquela célula. Registrar como lacuna (L18). `ByzantineSweep` mapeia `POSA` para `POS` (`:271-273`).

### B.4 Granularidade da saída (afeta `raw/` "nunca agregar")

- `MVPComparison` escreve **uma linha por (protocolo, n)**, média e desvio de `trials` (`MVPComparison.java:162-176`); o CSV por trial existe (`outputPath` do logger), mas o ARQ/PBFT só registra a **primeira finalização por altura** (`processedHeights`, `EnhancedBlockFinalizationLogger.java:121-124`).
- `ByzantineSweep` agrega por f (média e desvio, `:291-299`); os CSV por trial ficam em `tmp/<type>/...` (`:91,112,134,164,187`). Não há flag para guardar ou apagar.
- `SimulationMetrics` guarda no máximo 10.000 amostras por lista, descartando as **mais antigas** (`SimulationMetrics.java:28,111-113,144-146,185-187`). Para duração 7200 s e Tt, isso pode truncar justamente o início (o transiente), o que interage com o corte do Welch. Conta de volume por célula: `NAO_MEDIDO`.
- `TransactionSubmissionRegistry` limita a 50.000 submissões (`:26,53-57`), com remoção da mais antiga; tx mais antigas podem perder o tempo de submissão e cair no **fallback** de "latência estimada pelo tempo de criação do bloco" (`EnhancedBlockFinalizationLogger.java:176-183`). Isso mistura duas definições de Tt na mesma coluna. Não é "medido" no sentido da regra 6.
- Cabeçalho do CSV não registra semente, versão do simulador nem parâmetros (`EnhancedBlockFinalizationLogger.java:94-103`: só nome, nº de nós, tipo de rede e granularidade de C_B). A regra 6 do projeto exige semente e versão declaradas.

---

## C. Gerador de transações e perfil sazonal

### C.1 Como funciona hoje (por família de protocolo)

| Cenário | Gerador | Taxa | Alcança a rede? | Evidência |
|---|---|---|---|---|
| Nakamoto (`BitcoinGlobalNetworkScenario`) | `TxGenerationProcessRandomNetworkNode` sorteia um nó e chama `generateNewTransaction`, só se `enable-tx` | Poisson homogêneo, tempo médio entre chegadas = `--tx-interval` (padrão 5,0 s) | **sim**: `BitcoinNode.generateNewTransaction` registra a submissão e faz `broadcastTxInvMessage` | `BitcoinGlobalNetworkScenario.java:187-194`; `AbstractPoissonProcess.java:22-29` (`sampleExponentialDistribution(média)`); `BitcoinNode.java:105-121` |
| CasperFFG (`CasperEthereumNetworkScenario`) | mesmo processo | **fixo 0,5 s**, **sem parâmetro** (o `--tx-interval` não chega aqui) | sim, via `EthereumNode.generateNewTransaction` (`broadcastTransaction`) | `CasperEthereumNetworkScenario.java:109-114`; `EthereumNode.java:71-77` |
| Parlia (`ParliaBSCNetworkScenario`) | mesmo processo | **fixo 0,1 s**, sem parâmetro | sim | `ParliaBSCNetworkScenario.java:110-115` |
| PBFT/ARQ (`HybridNetworkScenario`) | **não é um processo Poisson**: um evento a cada 1 s sorteia 5 a 20 tx (`5 + nextInt(16)`) | ~12,5 tx/s em média, discreto e periódico, **não** Poisson | **não**: as tx são amostradas, contadas em `metrics.recordPublicTransaction/recordPrivateTransaction` e registradas no `AccessControlManager`; sem broadcast | `HybridNetworkScenario.java:264-297` (já no índice, P6) |
| PBFT/ARQ (blocos) | duas fontes de bloco, ambas sem fila de tx: (1) o agendador da rede propõe a cada **5,0 s fixos**, pelo nó de índice `min(10, n-1)`, com `txCount = -1` -> **20 a 79 tx** sorteadas na hora e pai = bloco gênesis (`PBFTLocalLANNetwork.java:97-111`; `BlockFactory.java:76-81`); (2) quando um nó finaliza, o primário `currentViewNumber % n` propõe o próximo com **50 tx** (`PBFT.java:300,325-331`) | determinado pelo intervalo de bloco e pela finalização, não pela chegada de tx | as tx fazem parte do bloco, mas **não vêm de uma fila**; `PBFTNode.generateNewTransaction` é vazio (`PBFTNode.java:46-48`) | `PBFTLocalLANNetwork.java:97-111`; `PBFT.java:300,325-331`; `BlockFactory.java:76-91` |

Consequências para o perfil:

1. **O processo é homogêneo**: `timeToNextGeneration` usa uma média constante (`AbstractPoissonProcess.java:27-29`). Não há taxa dependente do tempo em lugar nenhum do código.
2. **No PBFT/ARQ a taxa de tx não influencia o consenso** (blocos de 50 tx a cada 5 s, independentemente de qualquer chegada). Ou seja, um perfil sazonal aplicado às tx **não mudaria nada** no PBFT/ARQ sem mudar também o proponente de blocos (que não tem fila de tx).
3. **A taxa nominal difere por protocolo** (tx/s, conta de `1/média` ou `tx por evento / intervalo`): Nakamoto 0,2 (padrão do `--tx-interval`), Casper 2, Parlia 10, ARQ/Hybrid ~12,5 (5 a 20 por segundo, mas sem rede). No PBFT/ARQ a carga que o consenso vê é a de bloco (20 a 79 tx por proposta do agendador a cada 5 s, ou 50 tx por proposta do primário), não a do Hybrid. Com `tamanho_tx_bytes` constante e "carga igual", os protocolos receberiam cargas diferentes hoje. O fator "carga" do desenho não é comparável entre protocolos.
4. **O tempo de submissão é registrado** em `TransactionSubmissionTracker` (thread-local) só em `BitcoinNode` e `EthereumNode` (`BitcoinNode.java:109`; `EthereumNode.java:75`). PBFT/ARQ: nada registrado, logo Tt cai no fallback (`EnhancedBlockFinalizationLogger.java:176-183`).
5. **Determinismo**: o `RandomnessEngine` (Mersenne Twister) é criado por cenário com a semente (`AbstractScenario.java:75`; `RandomnessEngine.java:12-14`) e é compartilhado por tx, latência e blocos. Mudar a ordem de consumo do RNG (por exemplo, adicionar um sorteio por tx) altera **toda** a trajetória, inclusive o fluxo de blocos. No ARQ/Hybrid, o `privacyRandom` é um `java.util.Random` separado (`HybridNetworkScenario.java:104,133`), fora do RNG do simulador.

### C.2 O que seria preciso para um perfil sazonal com taxa média igual (descrição, sem forma nem amplitude)

Requisitos que o código impõe (não escolhas):

1. **Taxa dependente do tempo no gerador.** Substituir o intervalo exponencial homogêneo por um processo cujo intervalo dependa de `simulator.getSimulationTime()`. O ponto de extensão natural é `AbstractPoissonProcess.timeToNextGeneration` (`:27-29`), que só é usado por tx e blocos (`BlockMiningProcess` herda dele); por isso o perfil deve ser aplicado **só** nas subclasses de tx (por exemplo uma nova subclasse de `AbstractTxPoissonProcess`), não na classe base, para não mudar a mineração.
2. **Mesma média no horizonte.** A média de longo prazo do perfil sazonal tem de coincidir com `1/tx-interval` do estacionário. O código precisa receber ou calcular uma **constante de normalização** (taxa base × função de forma, com a integral da função de forma no período = período); isso exige conhecer a duração e o período, que são parâmetros de entrada. Verificação objetiva: contar tx geradas por semente nos dois perfis e comparar com a esperada (`NAO_MEDIDO` hoje).
3. **Determinismo por semente.** Gerar o tempo seguinte com o `RandomnessEngine` do cenário, por exemplo sorteando de uma taxa máxima e aceitando ou rejeitando (técnica de *thinning*), ou invertendo a taxa acumulada. Em qualquer caso, **o número de sorteios consumidos do RNG precisa ser o mesmo entre execuções com a mesma semente**; e, para que estacionário e sazonal sejam comparáveis, é desejável (não obrigatório) um RNG **dedicado** da carga (como o DAGsper fez para os byzantine: `DAGsperNetworkScenario.java:46-48`) para a carga não deslocar o resto da trajetória. Isso é decisão de desenho: `a definir com o humano`.
4. **Aplicar nos 4 cenários.** Casper (0,5 s) e Parlia (0,1 s) têm intervalo fixo no código (`CasperEthereumNetworkScenario.java:110`, `ParliaBSCNetworkScenario.java:111`), sem parâmetro; é preciso parametrizar. PBFT/ARQ **não tem gerador de tx**: é preciso decidir se o perfil modula (a) o número de tx por bloco (hoje 20 a 79 ou 50 por bloco), (b) o intervalo entre blocos (5 s fixo) ou (c) uma fila de tx real. Cada escolha muda o que o consenso mede. `a definir com o humano`.
5. **Registrar a submissão** em `TransactionSubmissionTracker` para todos os protocolos, senão Tt cai no fallback (C.1 item 4).
6. **Tabela de taxa nominal por protocolo** (C.1 item 3): o fator "carga" precisa ter **uma taxa de tx/s por nível**, igual entre protocolos, e uma tradução para os parâmetros de cada cenário.
7. **Saída**: registrar o índice de dispersão do número de tx por janela, calculado dos instantes de geração (a ponte exige isso). Hoje o simulador não registra o instante de cada tx fora do `TransactionSubmissionRegistry` (limite de 50.000, B.4); seria preciso um log próprio de chegadas.

Pontos **sem decisão aqui** (todos `a definir com o humano`): forma da sazonalidade, amplitude, período, nome do perfil (`sazonal` vs `sazonal-rajada`, índice (f)), janela do índice de dispersão. A origem dos valores é a medição da aplicação (ponte), que **não foi feita**; nenhum número foi inventado.

---

## D. Limites de n (até 400) e custo: o que já está medido ou documentado

**Medido ou documentado no repositório:**

| Fato | Evidência |
|---|---|
| `Parlia`/`Casper` exigem `n >= 10`; abaixo disso o setup da rede **não termina** (laço `while` sem vizinhos suficientes). Casper/MCO2 e Parlia/AMBIFY com n=7 e Nakamoto/OFFSET com n<=20 travam (rc=124 em 40 s). Nakamoto/OFFSET n=30 termina em ~1 s. | `scripts/jabs/nao_regressao.py:15-20` (cabeçalho); mecanismo: `AbstractBlockchainP2PConnections.java:27-39` |
| O limite vale também para o DAGsper (`MIN_NODES = 10`), com a ressalva de que n=10 não garante término | `DAGsperNetworkScenario.java:35-41,52,90-93` |
| PBFT: n=25, 50, 100, 200 estão **previstos** na verificação do ADR-0006 (`validar-complexidade.py`, ≥2 sementes), mas **nenhum resultado está arquivado**: `experimentos/validacao/` e `experimentos/calibracao/` vazios | `docs/adr/0006-pbft-trafego-medido.md:52`; `ls experimentos/*` |
| Medido em n=4/7/10 (1 trial, 60 s) e n=7 (60 s, ARQ), apenas como *smoke test* | `0006-...md:63,68` |
| O C_B total do PBFT inclui o bloco (~1 MB) em cada PRE_PREPARE; "hipótese herdada, não reproduzida aqui" de que C_B_total não cresce em O(n²) para n=25..200 | `0006-...md:18` |
| Limites de memória embutidos no código: `alreadySeenBlocks` e `alreadySeenVotes` zerados em 50.000 entradas (`PeerBlockchainNode.java:68,180`); registro de submissões 50.000 (`TransactionSubmissionRegistry.java:26`); listas de métricas 10.000 (`SimulationMetrics.java:28`); poda da árvore local do PBFT em 1000 alturas (`PBFT.java:424`) | idem |

**Não medido (`NAO_MEDIDO`):**

- Tempo de parede por célula, em qualquer n. `.experimento/PROTOCOLO-EXPERIMENTAL.md:285-288` já diz que só existe depois de medir; confirmado: nenhum número em `docs/`, `.ia/rodadas/`, `scripts/` ou `ADR`.
- Memória por célula e necessidade de heap. Não há `-Xmx`, `argLine` nem `forkCount` em `pom.xml`, scripts ou `*.json` (busca feita). Ambiente: 28 CPUs lógicas e ~31 GiB de RAM (`/proc/cpuinfo`, `/proc/meminfo`); `maxCelulasDeSimulacaoParalelas = 8` (`enxame.config.json:38`). Se 8 células com n=400 cabem em memória: `NAO_MEDIDO`.
- Crescimento do custo em n: em PBFT/ARQ, cada nó mantém a malha completa (`PBFTP2P.java:9`), então cada broadcast gera n pacotes e uma fase de votos gera ~n² pacotes por altura por fase (`PBFT.java:186-190`, `Node.java:127-133`); o número de eventos por altura cresce com n². Para n=400 isso é da ordem de 160.000 pacotes por fase, antes da latência e das filas. **Estimativa estrutural pelo código, não medição.**
- O limite superior de n com término: o setup P2P do Nakamoto exige 8 saídas distintas por nó (`BitcoinCoreP2P.java:5-6`) e aceita até `maxConnections - numOutbound` entradas (`AbstractBlockchainP2PConnections.java:42-45`). Com n grande isso é folgado; o risco é com n pequeno. O risco de não terminar em **n alto** não está documentado.
- `n=400` com Nakamoto: o protocolo o exclui "por custo" (`PROTOCOLO` §2, §9) sem número.
- Teste/execução do PBFT com n=400: **nunca registrado**. O maior n em evidência arquivada é 200 (previsto, não executado nos registros) ou 25/50/100/200 no plano de ADR-0006.
- Poda da árvore local do PBFT em 1000 alturas (`PBFT.java:424`) contra `duracao_sim_s = 7200` e propostas do agendador a cada 5 s (até 1.440 propostas em 7200 s): se o número de alturas finalizadas passa de 1000, há poda dentro de uma execução. Quantas alturas o PBFT/ARQ realmente finaliza em 7200 s: `NAO_MEDIDO` (o agendador usa o gênesis como pai, `PBFTLocalLANNetwork.java:104-105`, então não dá para presumir 1 altura por proposta). Efeito sobre o resultado: `NAO_MEDIDO`.

**Número de células e execuções (cálculo, não medição):** 174 + 36 = 210 células da rodada principal; com r=10 provisório, 2.100 execuções (`PROTOCOLO` §8), mais 180 do piloto. Com 8 em paralelo, são ~263 "ondas" de 8 células; o tempo total depende do custo por célula, que é desconhecido.

---

## E. Lacunas

Coluna "Pré-condição" usa as P1-P8 do índice (P3 instrumento de tráfego, P4 HotStuff/Tendermint, P5 portões V-A/B/C, P6 perfis de carga, P7 D0 limiares, P8 pré-registro). `—` = não afeta diretamente uma Pn, mas afeta a validade da bateria.

| # | Lacuna | Arquivo:linha | Pré-condição afetada | Quem resolve |
|---|---|---|---|---|
| L1 | Tipo de ataque desconhecido cai no comportamento honesto sem erro (PBFT, CasperFFG, Parlia, Nakamoto); o construtor `ByzantineConfig(String)` não valida | `ByzantineConfig.java:49-58,67-74`; `PBFT.java:323-334`; `CasperFFG.java:305`; `Parlia.java:232-233`; `BitcoinNode.java:54-71` | P5 (V-B), Bateria 2 | `jabs-builder` (validar no construtor; tabela de tradução `equivocacao` -> `EQUIVOCATION` é decisão do humano) |
| L2 | SILENT/WITHHOLD/EQUIVOCATION/DOUBLE_SIGN sem a mesma semântica entre protocolos; Nakamoto sem ramo para SILENT e DOUBLE_SIGN; EQUIVOCATION == WITHHOLD no PoW | `BitcoinNode.java:58-62`; `Parlia.java:257-262` | P5 (V-B), P4 (paridade de hooks) | `humano` (definir o modelo de ataque único em termos de efeito observável) e depois `jabs-builder` |
| L3 | PREPARE sem ramo byzantine no PBFT; DOUBLE_SIGN só no COMMIT; EQUIVOCATION do primário sem efeito se o primário fixo não for byzantine | `PBFT.java:186-190,310-322,359-362`; `PBFTLocalLANNetwork.java:97` | P3 (ressalva ii), Bateria 2 | `jabs-builder` (T6/ADR-0007), depois do modelo de ataque do humano |
| L4 | O conjunto de nós byzantine não é o mesmo entre protocolos para a mesma semente e f (universos distintos: todos os nós, stakeholders, mineradores); aninhamento entre f só testado no DAGsper; arredondamento `ceil` em ponto flutuante não verificado | `ByzantineConfig.java:79-87`; `CasperEthereumNetworkScenario.java:73-83`; `BitcoinGlobalNetworkScenario.java:125-135`; `ByzantineSweep.java:143,157-162`; `DAGsperScenarioTest.java:190-199` | P5 (V-B), pareamento de `PROTOCOLO` §3 | `jabs-builder` (RNG dedicado e seleção comum), decisão do humano sobre o universo (nós/validadores) |
| L5 | "Nós bizantinos coordenam entre si" não existe no código | `PBFT.java:314`; `CasperFFG.java:279` (decisão local por nó) | Bateria 2 | `humano` (manter ou remover a exigência) |
| L6 | `ByzantineSweep`: f é contagem; faltam lista explícita de f, lista de sementes e entrada de `n` por nível; divide por zero se todos os trials falham | `ByzantineSweep.java:30,49-54,74,233-240` | P8 (insumo), Bateria 2 | `jabs-builder` |
| L7 | `MVPComparison` não aceita ataque, f, mix, perfil, banda, topologia, bloco, tx, assinatura nem warmup; chave desconhecida é descartada em silêncio | `MVPComparison.java:42-51,548-559` | P6, P8 | `jabs-builder` (inclusive falhar em chave desconhecida) |
| L8 | Nenhum parâmetro base do YAML é aplicado de modo uniforme: banda (100 Mbps só no PBFT/ARQ), topologia (malha completa vs grau 8 vs grau ≤10), bloco (1 MB amostrado vs 1,8 MB vs gás) e tx (distribuições herdadas, não 512 B) | `LAN100MNetworkStats.java:10`; `PBFTP2P.java:9`; `BitcoinCoreP2P.java:5-6`; `EthereumGethP2P.java:4-9`; `BlockFactory.java:37-48`; `BitcoinMinerNode.java:24`; `TransactionFactory.java:15-65` | P6, P8 (os fixos do pré-registro são hoje fictícios) | `jabs-builder`; valores do bloco e da tx: `NAO_MEDIDO`, vêm da ponte quando houver medição Fabric |
| L9 | `esquema_assinatura` não é modelado (nenhum tamanho de assinatura) | `PBFTBlockVote.java:11`; `PBFTTrafficMeasurementTest.java:93` | P5 (V-A usa tráfego) | `humano` (decidir se entra) e `redd-dominio`/ponte (tamanho real: `NAO_MEDIDO`) |
| L10 | "n" significa nº de nós totais diferentes por protocolo (1,0 n, 1,3 n; Parlia e Casper só validam 0,3 n ou n) e difere entre `MVPComparison` e `ByzantineSweep` | `MVPComparison.java:321-322,375-376`; `ByzantineSweep.java:99-100,143,154-155`; `ParliaBSCNetworkScenario.java:86-94` | P8; fator B | `humano` (definir n) + `jabs-builder` |
| L11 | Nível do fator A -> chave: `casperffg` (MCO2 ou TREE), `nakamoto` (OFFSET, EARTH ou POW do sweep), `pbft` = ARQ (híbrido) em vez do PBFT puro; EARTH rotulado POS | `MVPComparison.java:318,344,395-404,421-432`; `ByzantineSweep.java:120-126` | P2, P8 | `humano` |
| L12 | Sem perfil sazonal e sem taxa variável no tempo; processo de tx homogêneo | `AbstractPoissonProcess.java:27-29` | P6 | `jabs-builder` (implementação), `humano` (forma, amplitude, período, nome) |
| L13 | Taxa de tx não comparável entre protocolos (0,2; 2; 10; ~12,5 tx/s); Casper e Parlia com intervalo fixo e sem parâmetro | `BitcoinGlobalNetworkScenario.java:187-194`; `CasperEthereumNetworkScenario.java:110`; `ParliaBSCNetworkScenario.java:111`; `HybridNetworkScenario.java:273` | P6 | `jabs-builder`; tabela de taxa por nível: `humano` |
| L14 | PBFT/ARQ não tem gerador de tx; perfil de tx não alcançaria o consenso (blocos de 20 a 79 tx ou 50 tx, sem fila) | `PBFTNode.java:46-48`; `PBFTLocalLANNetwork.java:97-111`; `PBFT.java:300,325-331` | P6 | `humano` (qual grandeza o perfil modula no PBFT) + `jabs-builder` |
| L15 | Tx do mix público/privado não entram na rede (já no índice); o tempo de submissão só é registrado em Bitcoin e Ethereum, o PBFT cai no fallback "latência estimada pelo tempo do bloco" | `HybridNetworkScenario.java:275-288`; `BitcoinNode.java:109`; `EthereumNode.java:75`; `EnhancedBlockFinalizationLogger.java:176-183` | P6, regra 6 | `jabs-builder` |
| L16 | Sem corte de transiente (`warmup`); `SimulationMetrics` descarta as amostras **mais antigas** acima de 10.000; registro de submissões descarta acima de 50.000 | `SimulationMetrics.java:28,111-113,185-187`; `TransactionSubmissionRegistry.java:26,53-57` | P5, P8 (Welch) | `jabs-builder` (log bruto completo e sem descarte) + `jabs-experimento` (corte pós-processado) |
| L17 | Saída agregada por (protocolo, n) ou por f; CSV sem semente, versão do simulador nem parâmetros no cabeçalho | `MVPComparison.java:162-176`; `ByzantineSweep.java:291-299`; `EnhancedBlockFinalizationLogger.java:94-103` | P8; regra 6 (medido, com semente e versão declaradas) | `jabs-builder` |
| L18 | Limiar de segurança embutido e inconsistente entre ramos: para POS, 1/3 se o BFT empírico for > 0 e 1/2 no fallback; PoW usa 1/2 + 1; o protocolo espera ~1/2 para probabilísticos | `SimulationMetrics.java:445-447,479-482` | P5 (V-B), P7 | `humano` (qual limiar vale) + `redd-dominio` (rastreio a norma, se houver) |
| L19 | Custo por célula e uso de memória em n=200/400: nenhum dado arquivado; `experimentos/validacao/` e `experimentos/calibracao/` vazios | `docs/adr/0006-pbft-trafego-medido.md:52`; `.experimento/PROTOCOLO-EXPERIMENTAL.md:285-288` | P5, P8, `PROTOCOLO` §8 | `jabs-experimento` (medir uma célula por protocolo e n) |
| L20 | Limites de memória do próprio simulador (50.000 / 10.000 / 1000 alturas) podem alterar resultados em 7200 s; efeito `NAO_MEDIDO` | `PeerBlockchainNode.java:68,180`; `PBFT.java:424` | P5 | `jabs-builder` (documentar ou parametrizar) |
| L21 | Sem coordenação de entradas: o parser de CLI duplicado em dois `main` | `MVPComparison.java:548-559`; `ByzantineSweep.java:316-327` | P8 | `jabs-builder` (baixa prioridade) |

---

## F. O que **não** foi feito aqui

- Nenhuma execução (sem `mvn`, sem simulação). Todas as afirmações são de leitura de código, com arquivo:linha.
- Nenhuma medição da aplicação Fabric. Valores de bloco, tx, assinatura e taxa: `NAO_MEDIDO`.
- Nenhuma edição em `.experimento/`, `src/` ou `docs/`.
- Pendências para o humano, em ordem de impacto: (1) modelo de ataque único (L2, L5); (2) significado de n e mapeamento de níveis (L10, L11); (3) qual grandeza o perfil de carga modula no PBFT (L14) e nome/forma do perfil (L12); (4) limiar de segurança por tipo de consenso (L18); (5) o que entra como parâmetro fixo, de fato (L8, L9).
