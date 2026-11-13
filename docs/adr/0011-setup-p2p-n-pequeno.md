# ADR-0011: O setup P2P compartilhado não termina com rede pequena (correção: opções A+D)

- **Estado:** Aceito (2026-10-30, decisão do humano)
- **Data:** 2026-10-29 (proposta); 2026-10-30 (aceite). A escolha da opção A+D consta do registro de rodadas em 2026-10-29T20:47:45Z (`.ia/rodadas/loop2-jabs-codigo.jsonl`, linha 47, papel `humano`).
- **Decisores:** humano. Nenhum agente aceitou este ADR; o `redd-dominio` só registrou a decisão.
- **Trava (histórico, liberada):** a tarefa `P2P-1-setup-n-pequeno` do `loop2-jabs-codigo` (aberta e **APROVADA** na rodada 2, ver "Decisão"); as 24 células de E1 com n = 4 em PoSA e PoS (ADR-0010, PI-19): deixam de travar **pelo P2P**, mas seguem sujeitas à conversão de n do `MVPComparison` (tarefa por abrir); a atualização do `AGENTS.md` (**ainda pendente**, ver "Consequências pendentes").
- **Origem:** pedido do humano em 2026-10-29 ("Crie o ADR para corrigir o setup P2P"), sobre o conflito de capacidade PI-19 do `docs/adr/0010-parametros-de-instrumento-do-estudo.md`. O diagnóstico veio do orquestrador, por leitura de código. Reconferi as linhas citadas abaixo. **Nada foi executado por mim**: tudo que não traz "medido por terceiros" é **por leitura, não executado**.

## Contexto

### 1. Mecanismo (por leitura; linhas conferidas em 2026-10-29)

- `src/main/java/jabs/network/p2p/AbstractBlockchainP2PConnections.java:27-41`, `connectToNetwork`: enquanto `outbound.size() < numOutboundConnections`, sorteia um nó de `network.getAllNodes()` com `network.getRandom().sampleFromList` (um `nextInt` por tentativa). Só conecta se `remoteNode != this`, não está em `outbound`, não está em `inbound` e `remoteNode.requestConnection(this)` aceita. **Não há contador, limite de tentativas nem teste de "não restou candidato"**: se os elegíveis acabam, o laço gira para sempre (laço infinito, não lentidão).
- `:43-51`, `requestConnection`: aceita enquanto `inbound.size() <= maxConnections - numOutboundConnections`. Aceita, portanto, `max - d + 1` entradas (8 no Ethereum, 118 no Bitcoin). Isso é um detalhe do código atual; **não deve ser "consertado" nesta tarefa**, porque mudaria a saída de quem termina.
- `inbound` é `private` (`:17`): um chamador de fora não enxerga a capacidade restante do vizinho.
- Quem chama `connectToNetwork`: `GlobalProofOfWorkNetwork.java:98` (Nakamoto, Casper, Parlia, DAGsper), `TangleGlobalNetwork.java:46` (TangleIOTA, que usa `BitcoinCoreP2P`, `:25`), `PBFTLocalLANNetwork.java:45` (malha completa, ver 3). A ordem de conexão é a ordem de `getAllNodes()`: mineradores, depois não mineradores (`GlobalProofOfWorkNetwork.java:88-95`).

### 2. Condições de término

Cada aresta é um par não ordenado distinto: `k -> r` exige `r` fora de `outbound(k)` e de `inbound(k)`, e o par inverso `r -> k` é barrado por `inbound`. Logo:

- **(a) Condição necessária global:** `n · d ≤ C(n,2)`, isto é, **`n ≥ 2d + 1`**. Para d = 3: n ≥ 7. Para d = 8: n ≥ 17. Abaixo disso o laço não termina **com certeza** (contagem de pares, não estimativa). *Correção à nota do orquestrador:* "n ≥ 9" para d = 8 vale para o grau de **um** nó, não para a rede toda; a rede exige 17.
- **(b) Condição local:** quando o nó `k` conecta, os candidatos são `n - 1 - |in(k)| - |out(k)|`, menos os que já estão sem capacidade de entrada. Ele termina se `n - 1 - |in(k)| ≥ d` (com capacidade). Os últimos nós da sequência já têm muitas entradas, então travam antes do limite de (a). Isso depende de `n`, de `d` e da semente, **não é um simples "n ≥ 10"**.
- **(c)** `n` entre `2d+1` e o "seguro" é **zona de fronteira**: para algumas sementes termina, para outras não. Por isso "os n em que hoje termina" é, na prática, um conjunto de trincas (protocolo, n, semente).

### 3. Graus-alvo e usos (por leitura)

| Classe P2P | Saídas d | Máx. conexões | Usada por |
|---|---|---|---|
| `EthereumGethP2P.java:3-13` | 10 / 3 = **3** | 10 | `EthereumNode.java:28,35` (Casper, Parlia, DAGsper) |
| `BitcoinCoreP2P.java:4-13` | **8** | 125 | `BitcoinNode.java:23,30` (Nakamoto), `TangleGlobalNetwork.java:25` (TangleIOTA) |
| `PBFTP2P.java:8-11` | malha completa (`peerNeighbors.addAll(getAllNodes())`) | n/a | PBFT/ARQ. **Não tem o problema.** |

`SnowP2P` tem `connectToNetwork` próprio (`SnowP2P.java:7`); não li o corpo e não entra aqui.

### 4. Evidência já registrada (medida por terceiros; **não reexecutada por mim**)

- Builder da B1 (watchdog de pilha): `BitcoinCoreP2P` com n = 10 e n = 16 entra em laço infinito. A conta de pares confirma que isso é **certo**, não só observado: n = 10 pede 80 arestas e há 45 pares; n = 16 pede 128 e há 120.
- `scripts/jabs/nao_regressao.py:15-20`: Casper/MCO2 e Parlia/AMBIFY com n = 7 e Nakamoto/OFFSET com n ≤ 20 travam (rc = 124 em 40 s); OFFSET n = 30 termina em ~1 s (uma semente). n = 7 em d = 3 pede 21 arestas e há 21 pares (grafo completo exato): a conta explica o travamento.
- `AGENTS.md`, ADR-0006 (linha 89 e débito 3) e `DAGsperNetworkScenario.java:35-41,51-52,90-93`: n ≥ 10 como regra prática; o próprio DAGsper registra que n = 10 "não é garantia teórica de término". O DAGsper já **falha alto** com n < 10 (`:90-93`); Casper, Parlia e Nakamoto não.
- `especificacoes/calibracao/auditoria-protocolo-experimental.md`, seção D: mesmas medições; nota que o risco com n **alto** não está documentado.

### 5. Achado novo: o `n` da linha de comando não é o número de nós (por leitura, não executado)

`MVPComparison.java:321-322` (Casper/MCO2), `:347-348` (TreeCycle), `:375-376` (Parlia/Ambify) passam `(int)(n*0.3)` mineradores e `(int)(n*0.7)` "stakeholders", e a rede é criada com **a soma** (`CasperEthereumNetworkScenario.java:69`, `ParliaBSCNetworkScenario.java:66`, que chamam `populateNetwork(simulator, numMiners, numNonMiners, ...)`). O truncamento perde nós:

| n (ADR-0000) | mineradores | stakeholders | nós na rede |
|---|---|---|---|
| 4 | 1 | 2 | **3** |
| 16 | 4 | 11 | 15 |
| 64 | 19 | 44 | 63 |
| 128 | 38 | 89 | 127 |

No Parlia, os validadores adicionados ao consenso são só os mineradores (`ParliaGlobalBlockchainNetwork.java:54-55,72-79`): com n = 4 há **1 validador** e 3 nós. Para o Nakamoto/OFFSET, `numMiners = n` e `numNodes = 0` (`MVPComparison.java:396-401`), então nós = n. Isto liga a L10 da auditoria (o que "n" significa) e **muda a leitura do conflito**: no n = 4 do ADR-0000, PoSA/PoS têm **3 nós**, e d = 3 pede 3 vizinhos distintos quando só existem 2 outros. Aqui o travamento é **certo** (aritmética), não uma questão de sorte da semente.

### 6. Tabela: protocolo × grau-alvo × n mínimo

Marcas: **E** = estimado por leitura (contagem de pares e condição local); **M** = medido por terceiros (seção 4); **C** = a confirmar por teste de término (várias sementes, com timeout). Nenhum valor abaixo foi obtido por mim.

| Protocolo (classe P2P) | d / máx. | n mínimo **necessário** (2d+1) | Observado por terceiros | n mínimo **seguro** | Marca |
|---|---|---|---|---|---|
| Casper, Parlia (`EthereumGethP2P`) | 3 / 10 | 7 | n = 7 trava | **10** (regra prática; sem garantia teórica) | necessário: E certo; seguro: E, **C** |
| DAGsper (`EthereumGethP2P`) | 3 / 10 | 7 | trava abaixo de 10 (ADR-0006) | 10 (já falha alto, `:90-93`) | E, **C** |
| Nakamoto/OFFSET (`BitcoinCoreP2P`) | 8 / 125 | **17** | n = 10, 16 travam; n ≤ 20 travam (rc 124); n = 30 termina (1 semente) | ≥ 30 observado numa semente; entre 21 e 29 **não medido** | necessário: E certo; seguro: M parcial, **C** |
| TangleIOTA (`BitcoinCoreP2P`) | 8 / 125 | 17 | não medido | igual ao Nakamoto | E, **C**; fora do escopo (ADR-0009, decisão 5) |
| PBFT/ARQ (`PBFTP2P`) | malha | nenhum | n = 4, 7, 10 rodam (ADR-0006, smoke test) | n ≥ 4 | M |
| HotStuff, Tendermint | n/a | n/a | sem algoritmo nem cenário (ADR-0010, Contexto 4) | `HotStuffConsensusConfig.MIN_NODES` = 4 (índice, C-b) | n/a hoje |

### 7. Quais n do ADR-0000 travam hoje (E1: n ∈ {4, 16, 64, 128}; PoW excluído; por leitura, **C** em todos)

| Família | n = 4 | n = 16 | n = 64 | n = 128 |
|---|---|---|---|---|
| PoSA (Parlia), PoS (Casper) | **trava (certo)**: 3 nós, d = 3 | termina (E): 15 nós | termina (E): 63 nós | termina (E): 127 nós |
| pBFT | roda | roda | roda | roda |
| HotStuff, Tendermint | sem código | sem código | sem código | sem código |
| Nakamoto (fora do ADR-0000) | trava (certo: 4 nós) | **trava (certo)**: 128 > 120 | termina (E) | termina (E) |

### 8. Efeito nas duas leituras do desenho

- **Leitura ADR-0000 (Proposta; PoW excluído):** o único nível que trava é **n = 4 em PoSA e PoS: 24 das 240 células** (2 protocolos × 3 L × 2 R × 2 W_nó). O Nakamoto não entra no estudo; corrigir o setup **não é necessário para ele**, mas **é inevitável tocá-lo**, porque a classe é compartilhada (a não regressão tem que cobri-lo).
- **Leitura de 28/10 (PROTOCOLO + YAML + ADR-0009; PoW incluído):** `bateria1.yaml` lista `nakamoto` no fator A com n ∈ {25, 50, 100, 200, 400} (exclui só n = 400) e `topologia: aleatoria`. Casper e Parlia (n ≥ 25, 24 nós ou mais) **não são afetados**. O Nakamoto em **n = 25 cai na zona não medida (21 a 29)**: até 6 células (2 cargas × 3 mix) podem travar; n ≥ 50 termina por estimativa (**C**). O `piloto.yaml` não tem Nakamoto.
- Em qualquer leitura, **o código do Nakamoto continua no repositório**, e o fator A de `bateria1.yaml` (28/10) ainda o lista. Quem aceitar o ADR-0000 precisa reconciliar o ADR-0009 (índice, 6.1); este ADR não faz isso.

### 9. Estado do repositório

Segundo o orquestrador, o repositório inteiro está **untracked, sem HEAD** (não reconferi). Logo **não existe baseline** do código atual para a não regressão. `scripts/jabs/nao_regressao.py:23-28` descreve a baseline da T1 (4 arquivos originais em `src/test/resources/nao-regressao/baseline-original/`), que é o estado **anterior à T1**, não o estado de hoje.

## Opções

**A. Corrigir o laço compartilhado**, com alvo `min(numOutboundConnections, n-1)` e garantia de término: quando não restar candidato elegível (inclusive por capacidade de entrada), completar com o que existe e seguir. **Sem alterar o sorteio nem o consumo do `RandomnessEngine` nos casos em que hoje termina.**
- Prós: ataca a causa (falta de saída) para todas as famílias e todos os n, sem inventar limiar de n. Por construção, para `n ≤ d+1` o resultado é a **malha completa** (cada nó acaba conectado a todo candidato restante), que é o que `PBFTP2P` já faz. A verificação "existe candidato elegível?" pode ser feita **sem consumir RNG**, e só dispara onde o laço original nunca terminaria; a saída de quem termina fica idêntica por estrutura, e o teste de não regressão confirma.
- Contras: alguns nós terminam com menos de d saídas, e a rede **pode ficar desconexa** (nada na construção garante conexidade; hoje também não, mas com d = 3 e n grande é praticamente certa). Exige expor a capacidade de entrada do vizinho (`inbound` é `private`): mudança de API interna. O custo do teste de elegíveis é O(n) por tentativa (só no setup). A ordem em que se "completa" precisa ser determinística e **sem estrutura de ordem de hash** (ver CA-03).

**B. Caminho separado só para n pequeno** (ex.: malha completa quando `n-1 ≤ d`), laço original intacto.
- Prós: o laço original fica **byte a byte** como está; a não regressão é trivial por construção (para n acima do limiar o código nem muda). A topologia de n pequeno é explícita e declarável, igual à do pBFT, o que deixa neutra a variável topologia em n = 4.
- Contras: o limiar `n-1 ≤ d` cobre **só n ≤ 4** (Ethereum) e **n ≤ 9** (Bitcoin). Ficam travando n = 5 a 9 (d = 3; n = 5, 6 impossíveis, n = 7 grafo completo exato) e n = 10 a ~29 (d = 8), inclusive os n = 7 e 10 (Ethereum) e n = 16 (Nakamoto) do requisito CA-02. Cobrir isso exige um limiar maior, que é **parâmetro sem fonte** (`a definir com o humano`) e cria uma **descontinuidade de grau** (malha de grau n-1 contra grau médio ~2d logo acima), que confunde o efeito de n com mudança de topologia na fronteira. B sozinha não garante término.

**C. Manter só a limitação** (declarar n ≥ 10, usar n = 16 como menor n para PoSA/PoS; é a opção 2 do PI-19). **Rejeitada pelo humano em 2026-10-29.** Fica registrada para a trilha de decisão.

**D. Falhar alto na construção**, com mensagem clara (protocolo, n efetivo, d, "impossível: n·d > C(n,2)" ou "sem candidato elegível"), em vez de laço infinito.
- Prós: **melhoria independente, vale com A, B ou sozinha**. Hoje só o DAGsper falha alto, e o seu `MIN_NODES = 10` nem garante término. Não altera a saída de quem termina. Barata. Transforma um travamento silencioso (timeout de horas numa bateria) em erro imediato.
- Contras: **não torna n = 4 executável** (só avisa que não roda). A detecção exata ("impossível") é por contagem; a detecção de "preso" (zona de fronteira) usa o mesmo teste de elegíveis de A, ou um limite de tentativas sem progresso. Um contador de tentativas não pode ler nem avançar o RNG.

### Recomendação (RECOMENDAÇÃO, não decisão)

1. **D é a tarefa mínima em qualquer cenário** (inclusive se o humano mudar de ideia sobre C): nenhuma opção deve deixar um laço infinito sem diagnóstico.
2. Entre A e B, **a leitura do código sustenta A**: (i) B precisa de um limiar de n sem fonte e deixa a zona de travamento (n = 5 a 9 e 10 a ~29) sem cobertura, enquanto A cobre todos os n com o mesmo teste de elegíveis que D já exige; (ii) para o que o ADR-0000 realmente precisa (3 nós, `n ≤ d+1`), A produz a mesma malha completa que B; (iii) a identidade de saída nos casos que terminam é estrutural em A (verificação sem RNG, disparada só onde o laço original não termina), e o teste de não regressão a confirma.
3. **Ressalvas à recomendação:** se o humano quiser a topologia de n pequeno **declarada e congelada** no pré-registro (em vez de resultar de um algoritmo), B com limiar escolhido por ele é a opção mais auditável, ao custo da descontinuidade. A e B não são excludentes: B pode ser o caso particular `n ≤ d+1` dentro de A.
4. **Ressalva que vale para qualquer opção** (seção 5): corrigir o P2P é **necessário, não suficiente** para que o n = 4 do ADR-0000 signifique 4 nós. Hoje ele vira 3 nós e 1 validador em Casper e Parlia (por leitura). Isso é outra decisão (L10), fora deste ADR.

### Pontos de atenção (valem para qualquer opção que torne n pequeno executável)

- **Topologia aleatória × completa.** Para n pequeno a rede deixa de ser "aleatória de grau limitado" e passa a malha completa (ou quase). Isso toca o parâmetro `topologia: aleatoria` de `.experimento/bateria1.yaml` (que a auditoria, seção B, já diz não ser "aleatória no mesmo sentido" em nenhum protocolo) e deve ser **declarado no pré-registro**: em n = 4, **todos** os protocolos ficam em malha, e a topologia é neutra entre eles; de n = 16 em diante, pBFT é malha e PoS/PoSA têm grau médio ~2d. O "efeito de n" nos dois protocolos de grau limitado passa a misturar tamanho e densidade no nível n = 4.
- **Semântica de f e quórum em n = 4.** Em E1, φ = 0, então f não age; mas o limiar de finalização age. Com 3 nós (2 stakeholders) o quórum do Casper (`(numOfStakeholders/3)*2+1`, `CasperFFG.java:92`) e o do Parlia (`(stakeholders*2)/3`, `Parlia.java:125,191`) usam divisão inteira. **Não procurei testes** com esses valores e **não verifiquei** se a finalização é correta com 2 stakeholders. Em E2 ("n no centro da faixa", sem n declarado) o ponto não se aplica a n = 4.
- **Efeito sobre C_b e latência.** Mais vizinhos por nó significa mais tráfego de propagação e outra latência de difusão. Para os n que **antes travavam não existe saída anterior**, então a mudança é declarada como **mudança intencional de saída** (sem base para comparar). Para os n que terminavam, a saída deve ser **inalterada**; isso é o CA-01.

## Decisão

**Decisão do humano, aceita em 2026-10-30.** As seções "Contexto", "Opções" e "Recomendação" acima ficam como foram escritas em 2026-10-29 (histórico; a frase "nada foi executado por mim" vale para elas, não para esta seção). Em 2026-10-29 o humano já havia rejeitado a opção C (manter só a limitação).

### O que o humano decidiu

1. **Opção A + D.**
   - **A:** limitar o alvo de saídas e terminar quando não há candidato elegível, **sem alterar o sorteio nem o consumo do `RandomnessEngine` onde a rede hoje termina**.
   - **D:** falha alta com mensagem clara **onde A não resolve**. **Nunca laço infinito.**
   - A opção B (caminho separado com limiar de n) **não foi escolhida**; a pergunta aberta 5 (limiar de n) fica sem objeto.
2. **n = 4 em PoSA/PoS pode ser 3 nós com 1 validador.** A conversão de n do `MVPComparison` (`(int)(0,3n) + (int)(0,7n)`, seção 5 do Contexto) **fica como tarefa separada, ainda não aberta**.
3. **Nakamoto saiu do estudo.** O n = 16 em `BitcoinCoreP2P` é **só higiene** (a classe P2P é compartilhada), não requisito do estudo.
4. **Baseline = commit `e703e3d47da726f5fbcf94657f574db617a59023`** ("Experimento"). Isto resolve a pendência de baseline do ADR (o repositório já tem HEAD; a seção 9 do Contexto está superada). O worktree compilado usado nas comparações foi `/tmp/opencode/base-e703e3d` (fora da árvore e fora do git).

### Implementação (tarefa `P2P-1-setup-n-pequeno`, `loop2-jabs-codigo`)

Registro de rodadas (jsonl, linhas 47 a 51): builder rodada 1 em 2026-10-29T20:59:11Z; critic **rejeitou** no critério 7 às 21:01:30Z (CA-01 sem base real: identidade por reimplementação do laço e execução do critic limitada a uma semente, com `OFFSET:40` INCONCLUSIVO); builder rodada 2 às 21:51:10Z; critic **APROVOU** às 22:55:47Z. **180 testes** é o número do registro; **não rodei a suíte**. Orçamento usado: 2 de 3 rodadas (o código de saída 20 não foi atingido).

**O que o código faz de fato** (lido por mim em `src/main/java/jabs/network/p2p/AbstractBlockchainP2PConnections.java`, 159 linhas; não li o diff contra `e703e3d`, só o arquivo atual e a descrição do laço original no Contexto, seção 1):

- `connectToNetwork` (`:58-79`): enquanto `outbound.size() < numOutboundConnections`, obtém `getAllNodes()` e, **antes de sortear**, chama `hasEligibleCandidate(nodes)`. Se não há elegível, `break` (**o nó fecha com menos de d saídas, inclusive zero**). Se há, faz o mesmo `sampleFromList` do laço original; se o sorteado é elegível, `requestConnection` e `addOutbound`.
- `isEligible` (`:119-127`): não é o próprio nó, não está em `outbound` nem em `inbound`, e o vizinho **é** `AbstractBlockchainP2PConnections` com `hasInboundCapacity()`. Vizinho de outra classe P2P (`PBFTP2P`, `SnowP2P`) nunca é elegível (recusa em `requestConnection`). Sem RNG, sem efeito colateral.
- `hasInboundCapacity` (`:110-112`) é a mesma conta de `requestConnection` (`inbound.size() <= max - d`): o "+1" histórico (aceita `max - d + 1` entradas) **foi mantido de propósito**.
- `hasEligibleCandidate` varre `getAllNodes()` em ordem de lista e sai no primeiro elegível: **sem `HashSet`, sem `hashCode`, sem RNG**. Custo O(n) por tentativa, só no setup.
- Por leitura, a sequência de chamadas a `sampleFromList` é a do laço original enquanto houver candidato; a única diferença é que **deixa de haver chamada** onde o original nunca terminaria. A evidência arquivada (CA-01 abaixo) é o que sustenta isso na prática; a leitura sozinha não prova.
- Novos acessores públicos: `getOutboundCount()` e `getInboundCount()`.
- **Opção D, e a diferença em relação ao texto do ADR.** O texto da opção D falava em falhar alto para "impossível: n·d > C(n,2)" ou "sem candidato elegível". **No código isso não acontece**: como A termina antes (sem candidato, `break`), o caso "impossível por contagem" **completa com o que existe e segue, sem erro**. `IllegalStateException` (`:74-75`, mensagem de `describe`: `n`, `d`, `saidas`, `entradas`, `elegiveis`, `no`) só ocorre em **estado inconsistente**: um vizinho que `isEligible` apontou e que mesmo assim recusa em `requestConnection`. É coerente com a decisão do humano ("D onde A não resolve"), mas **o aviso imediato de n inviável, que o texto original de D prometia, não existe**: quem roda n = 4 em Casper recebe 3 nós em malha completa, não um erro. Isto já tinha sido registrado como divergência para o humano no ADR-0006 (seção da P2P-1).
- **Topologia resultante em n pequeno** (Javadoc da classe e por construção): se n ≤ d+1 e há capacidade de entrada, o laço só para quando o nó está ligado a todos, então o resultado é **malha completa**, como no `PBFTP2P`. Acima disso, a rede não é "aleatória de grau d" nos últimos nós processados, que ficam com menos saídas. **Nada garante conexidade** (a baseline também não garantia).

**O que a evidência mostrou** (todos os caminhos relativos a `src/test/resources/nao-regressao/p2p-1/`; li `README.txt`, `resumo.txt` e parte de `hashes.txt`, **não reexecutei nada**):

- **Termina na baseline: 31 execuções, base × atual IDENTICO em 31/31** (também base × base e atual × atual), sementes 1, 2 e 3, 4 JVMs por vez, contra `e703e3d`. Casper `MCO2:10:60`, `:20:300`, `:50:600`; Parlia `AMBIFY:10:60`, `:12:60`, `:30:300`; Nakamoto `OFFSET:30`, `:35`, `:40` (`:20000`); controle `ARQ:7:60` (não usa a classe alterada); mais `OFFSET:20:20000` semente 3, que terminou na baseline por acaso da semente.
- **Trava na baseline: 17 execuções**, `TIMEOUT` de 30 s em `base-a` e `base-b`, e o atual termina com `rc=0` em 0 a 2 s: `MCO2:4` e `:7`, `AMBIFY:4` e `:7`, `OFFSET:10` (sementes 1, 2, 3) e `OFFSET:20` (sementes 1 e 2). O script devolve `exit=2` (INCONCLUSIVO) **de propósito**: sem baseline não há comparação. Isto é **mudança intencional de saída**, sem valor anterior.
- **Fronteira que depende da semente:** só `OFFSET:20:20000` (travou nas sementes 1 e 2, terminou na 3). Os casos rotulados "fronteira" (`MCO2:10`, `AMBIFY:10`, `OFFSET:30`) terminaram nas 3 sementes testadas.
- **Termina não é "tem dado útil".** Na semente 1: `MCO2:4` gerou CSV por bloco com **0 linhas** (só `mvp-results.csv`, 1 linha: sem finalização em 60 s; o README do C1 cita ~210 s para o checkpoint do Casper), `AMBIFY:4` gerou 20 linhas, `OFFSET:10` gerou 13. Não interpretei o conteúdo.
- **Conexidade** (`target/p2p-conexidade.txt`, arquivo **não versionado**, lido por mim; mtime 2026-10-30 17:10): 25 sementes × n ∈ {4, 7, 10, 16} × {`EthereumGethP2P` d=3, `BitcoinCoreP2P` d=8} = 200 redes, `desconexas=0`, `componentes_max=1` em todas. Saídas por nó (mín., máx.): Geth n=4 [0,3], n=7 [0,3], n=10 [2,3], n=16 [3,3]; Bitcoin n=4 [0,3], n=7 [0,6], n=10 [0,8], n=16 [1,8]. **É medição, não garantia.**
- **Limite declarado no README:** "IDENTICO" vale com **4 JVMs simultâneas**; sob carga o Nakamoto (e uma vez o Parlia) diverge **até base × base**, causa não localizada. Uma bateria com muitas JVMs pode dar DIVERGENTE falso.

**Efeito sobre o estudo.** As 24 células de E1 com n = 4 em PoSA/PoS **passam a executar** (3 nós em malha completa; 1 validador no Parlia), mas: (i) a semântica de finalização com 2 stakeholders **não foi verificada** (Contexto, "Pontos de atenção"); (ii) a evidência é anterior à correção do limiar do Casper (C1, ADR-0006), então saídas de Casper com finalização de antes e depois não são comparáveis; (iii) a topologia de n = 4 é malha completa para **todos** os protocolos e deve ser declarada no pré-registro (Contexto, "Pontos de atenção"); (iv) o significado de n (L10) segue aberto até a tarefa de conversão.

## Consequências

- **Células:** as 24 das 240 de E1 (n = 4 × PoSA/PoS) deixam de ser bloqueadas **pelo P2P** (em `MCO2:4` e `AMBIFY:4`, 3 sementes cada, o atual termina com `rc=0`). Continuam sujeitas à seção 5 (3 nós, 1 validador) e ao significado de n.
- **Muda o simulador:** `AbstractBlockchainP2PConnections` (compartilhado por Nakamoto, Casper, Parlia, DAGsper e TangleIOTA). Não muda: chaincode, `PBFTP2P`, PBFT, HotStuff.
- **Ligações:** **ADR-0010, PI-19** (este ADR é a opção 1 do PI-19, com a opção 2 rejeitada); **ADR-0000 de `.experimento/`** (n ∈ {4,16,64,128}, PoW excluído; Proposta); **ADR-0009** (decisão 1: seis níveis incluem o Nakamoto, que o humano agora tirou do estudo em 2026-10-29: reconciliar o ADR-0009 segue com o humano; decisão 5: DAGsper e TangleIOTA fora do escopo); **ADR-0006** (nota da P2P-1); auditoria seção D e L10.
- **Baseline:** resolvida pelo commit `e703e3d` (ver "Decisão"). A pendência original do humano está encerrada.

### Consequências pendentes (nada disto foi feito por mim)

1. **`AGENTS.md`, seção "Ambiente", linha 96 (não edito; para o orquestrador/humano).** Texto atual: "Parlia/Casper do JABS exigem `n >= 10` (senão o setup da rede não termina)." Está **desatualizado**. Texto sugerido:

   > Parlia/Casper do JABS terminam o setup P2P em redes pequenas desde a tarefa `P2P-1-setup-n-pequeno` (ADR-0011, opções A+D): o nó fecha com menos de d saídas quando não há candidato elegível, e n ≤ d+1 vira malha completa. Antes disso, `n < 10` não terminava. Atenção: (i) o `n` da linha de comando do `MVPComparison` não é o número de nós (`(int)(0,3n) + (int)(0,7n)`: n = 4 dá 3 nós e, no Parlia, 1 validador); (ii) a rede **pode ficar desconexa** (medido: 0 de 200 redes no nível da classe P2P; não é garantia); (iii) `DAGsperNetworkScenario` continua exigindo n ≥ 10.

   Ressalva: (i) vem de leitura do `MVPComparison` (ADR-0006, `[C]`), não de execução com n efetivo impresso. O mesmo texto "exigem n >= 10" aparece, e **também está desatualizado**, em: `.agents/skills/jabs-protocolo-builder/SKILL.md:76`, `scripts/jabs/nao_regressao.py:20` e `src/test/resources/nao-regressao/nao_regressao.py:20` (comentário). Fora do meu escopo; avisar quem os mantém.
2. **`DAGsperNetworkScenario`: javadoc e `MIN_NODES = 10` permanecem** (CA-06; leitura de `:35-41, :52, :90-93`). O javadoc ainda afirma que `connectToNetwork` "NAO [foi] alterado" e que n < 10 não termina, o que **deixou de ser verdade** para a classe P2P. Relaxar ou manter o `MIN_NODES` é decisão do humano (pergunta 7), e o DAGsper está fora do escopo do estudo. Tarefa em `src/`, não aberta.
3. **Conversão de n do `MVPComparison`: tarefa por abrir.** Sem ela, n = 4 em Casper/Parlia/TREE continua sendo 3 nós. Nenhum agente deve abrir sozinho: depende de decisão sobre o que "n" significa (L10).
4. **ADR-0006:** a seção da P2P-1 ainda diz que o ADR-0011 "continua Proposto" e que a decisão A+D só existe no jsonl (linhas 163 e 254 na leitura de hoje). Com este aceite, o orquestrador deve atualizar o texto. Não editei o ADR-0006.
5. **Pendências de evidência (ver "Não confirmado" abaixo):** nenhuma tarefa aberta.
6. **Linhas "n ≥ 10" do índice (C-b, linhas 154 e 169) e de ADR-0010 (PI-18):** mantidas, porque descrevem o estado anterior e a pergunta de significado de n; recebem apenas os acréscimos desta data (PI-19 e seção 6.5). Reescrevê-las depois da tarefa de conversão de n.

## Verificação

Critérios de aceite da tarefa `P2P-1-setup-n-pequeno`. **CA-xx não são INV-xx**: INV-xx é invariante de domínio em `especificacoes/dominio-redd.md`; estes são critérios deste ADR.

- **CA-01 (não regressão, requisito 1).** Para toda trinca (protocolo, n, semente) que **termina na baseline dentro do timeout**, a saída do código novo é idêntica (`scripts/jabs/nao_regressao.py`, `--base` contra `--atual`, semente fixa, ao menos 2 execuções de cada lado). Cobrir: cada família (Casper, Parlia, Nakamoto), ao menos um n de **fronteira** e um folgado, e ≥ 3 sementes (sugestão, sem fonte; o builder propõe os valores). Trincas que travam na baseline **não têm base**: saem do CA-01 e entram no CA-02 como "mudança intencional de saída". Pré-condição: baseline criada pelo humano (Consequências).
- **CA-02 (término e conexidade, requisito 2).** Para n de nós `∈ {4, 7, 10}` (famílias de `EthereumGethP2P` e de `BitcoinCoreP2P`) e n = 16 (`BitcoinCoreP2P`), o setup termina e a rede é **conexa** (busca em largura sobre `getNeighbors()`, tratando o grafo como não direcionado). Testar no nível da classe P2P com `getAllNodes().size()` declarado, e também pelo `MVPComparison` com o n efetivo da seção 5 impresso. Em muitas sementes (sugestão ≥ 20). Se algum grafo sair desconexo, a política é pergunta aberta 4.
- **CA-03 (determinismo, requisito 3).** Mesma semente produz a mesma lista de vizinhos (ids, em ordem) em duas execuções na mesma JVM **e em JVM nova com `-XX:hashCode` variando** (pode exigir `-XX:+UnlockExperimentalVMOptions`; não verificado). Alvo: código novo que itere `HashSet<Node>` ou use `hashCode` de identidade para decidir ordem.
- **CA-04 (término com timeout, requisito 4).** Teste de término para cada par (família, n) do CA-02 **e** para n impossíveis (n·d > C(n,2)): cada um ou termina, ou lança o erro claro de D, **nunca trava**. O laço não verifica interrupção: usar thread daemon ou processo filho, senão a JVM de teste não encerra e o timeout não prova nada.
- **CA-05 (declaração, requisito 5).** Nota de não regressão no ADR-0006 com três classes de saída: (i) inalterada (terminava), (ii) mudança intencional (travava, sem saída anterior), (iii) fronteira (depende da semente). Feita pelo orquestrador a partir do relatório do builder.
- **CA-06 (V-A/B/C, requisito 6).** `PBFTP2P`, `PBFT` e o ARQ não mudam; ARQ com a mesma saída (`nao_regressao.py`, ex.: ARQ:7:60). Observação: V-A/B/C ainda **não estão documentados** (índice, P5), então "não afetados" significa "nenhum caminho que eles usam foi alterado", não comparação com resultado arquivado. `DAGsperNetworkScenario.MIN_NODES = 10` **fica como está** até decisão do humano (DAGsper está fora do escopo do estudo).
- **CA-07 (processo, requisito 7).** Uma tarefa, `P2P-1-setup-n-pequeno`, no `loop2-jabs-codigo`: `jabs-builder` e `gauntlet-critic-jabs`, orçamento de 3 rodadas, contador por `scripts/enxame/registrar-rodada.py` (código 20 = parar e pedir decisão humana). Escopo de escrita: `src/` e `pom.xml`. Critérios mais exigidos da bar: determinismo e não regressão (critério 7).
- `python3 scripts/enxame/validar-config.py` sem falha.

### Estado dos CA em 2026-10-30

Legenda: **atendido** = a evidência que li cobre o enunciado; **atendido parcialmente** = cobre parte e o resto está nomeado; **não verificado** = não há evidência arquivada que eu tenha lido. Não reexecutei testes, `nao_regressao.py` nem o `MVPComparison`: tudo vem de arquivos e do registro de rodadas. O número 180 (testes) é do registro.

| CA | Estado | Evidência | Não coberto |
|---|---|---|---|
| CA-01 | **atendido** | 31/31 IDENTICO contra `e703e3d` (`resumo.txt`; `hashes.txt`; `comandos.txt`), 3 famílias, 3 sementes, 2 JVMs por lado (`base-a/b`, `atual-a/b`), fronteira (`MCO2:10`, `AMBIFY:10`, `OFFSET:30`, `OFFSET:20` s3) e folgado (`MCO2:50`, `AMBIFY:30`, `OFFSET:40`). Trincas que travavam saíram do CA-01, como o CA pedia. Critic aprovou às 22:55:47Z | Vale com 4 JVMs simultâneas; sob carga o Nakamoto diverge até base × base (README). Só as trincas executadas; n e sementes fora da lista não estão cobertos. Casper não foi exercitado com finalização de bloco depois do C1 (ver ADR-0006) |
| CA-02 | **atendido parcialmente** | Nível da classe P2P: 200 redes (25 sementes × n ∈ {4,7,10,16} × Geth d=3 e Bitcoin d=8), 0 desconexas (`target/p2p-conexidade.txt`, lido). Por execução: `MCO2:4`/`:7`, `AMBIFY:4`/`:7`, `OFFSET:10` terminam com `rc=0` em 3 sementes (`resumo.txt`) | **Não confirmado:** (a) o CA pedia também o teste **pelo `MVPComparison` com o n efetivo impresso**: não há registro; (b) o arquivo de conexidade está em `target/`, **não versionado**; (c) `OFFSET` com n = 4 e n = 16 **não foi executado** (só `OFFSET:10` e `:20`); n = 16 em `BitcoinCoreP2P` aparece só no teste de classe, e é higiene (Nakamoto saiu do estudo) |
| CA-03 | **atendido parcialmente** | Por leitura: o código novo não usa `HashSet`, `hashCode` nem RNG na varredura (`AbstractBlockchainP2PConnections.java:129-137`). Existe teste de mesma JVM e de JVM nova com `-XX:hashCode` 0 a 5 (`P2PSetupTest.java:305-339`), que passou no registro de 180 testes | **Não confirmado: `-XX:hashCode`.** O teste **ignora silenciosamente** o modo cuja JVM filha devolve `ERRO:` (só imprime "CA-03 NAO VERIFICADO", `:329-333`), logo passar não prova que algum modo foi verificado, e a saída não está arquivada. Não sei quantos dos 6 modos rodaram nem se a flag é aceita na JVM usada (Java 25 no ambiente) |
| CA-04 | **atendido** (por leitura do teste e registro) | `P2PSetupTest`: thread daemon com timeout (`:38-52`), n = 1 a 2d para as duas famílias com 5 sementes (`:144-166`), n = 1 (`:168-177`), recusa após elegível lança erro claro (`:265-276`); todos no registro de 180 testes aprovado | Eu não executei. O teste aceita "termina" **ou** `IllegalStateException`; no código atual o caminho que lança só ocorre em estado inconsistente (ver "Decisão") |
| CA-05 | **atendido parcialmente** | A nota existe em `docs/adr/0006-pbft-trafego-medido.md`, seção "P2P-1-setup-n-pequeno", com as três classes (i) inalterada, (ii) mudança intencional, (iii) fronteira | A classe (ii) lista `OFFSET` n = 4 e n = 16 **sem execução** (só conta de pares); o texto ainda diz "ADR-0011 continua Proposto" (desatualizado após este aceite); a divergência de D foi registrada ali, não resolvida |
| CA-06 | **atendido** | `ARQ:7:60` IDENTICO nas 3 sementes (controle). `git status --short` não lista `PBFT*`, `PBFTP2P` nem `DAGsperNetworkScenario` como alterados em relação a `e703e3d`; `MIN_NODES = 10` lido em `DAGsperNetworkScenario.java:52` | V-A/B/C não estão documentados (índice, P5): "não afetados" = nenhum caminho usado foi alterado, não comparação com resultado arquivado. Não li o diff arquivo a arquivo contra `e703e3d` |
| CA-07 | **atendido** (pelo registro) | `.ia/rodadas/loop2-jabs-codigo.jsonl` linhas 47 a 51: builder, critic REJEITADO (critério 7), builder, critic APROVADO; 2 de 3 rodadas | **Não verifiquei** que o contador foi o `registrar-rodada.py` nem o escopo de escrita dos agentes (o `git status` mostra outras mudanças em `src/` de outras tarefas) |
| `validar-config.py` | **atendido** | rodado por mim em 2026-10-30 ao fim das edições: "OK: configuracao coerente (0 aviso(s))" | Não valida o conteúdo deste ADR, só a coerência da configuração |

### Não confirmado (consolidado, sem inventar)

- **CA-03 com `-XX:hashCode`:** modos verificados e aceitação da flag; saída não arquivada.
- **CA-02 via `MVPComparison` com o n efetivo impresso:** sem registro. O n efetivo (3 nós em n = 4) continua **[C/L] por leitura**.
- **`OFFSET` n = 4 e n = 16 como mudança de saída:** previsto pela conta de pares (n = 4: 4 nós, d = 8; n = 16: 128 arestas pedidas, 120 pares), **nunca executado**.
- **Nakamoto com n entre 21 e 29:** não medido (seção 6); fora do estudo, só higiene.
- **Finalização correta com 2 stakeholders (n = 4):** não verificada (Pontos de atenção).
- **Causa da divergência base × base sob carga:** não localizada (README).
- **Suíte de 180 testes:** número do registro, não reexecutado por mim.

## Perguntas abertas ao humano

Respondidas em 2026-10-29/30 (decisão do humano, ver "Decisão"): 1 (A+D; D como mínima), 2 (baseline = commit `e703e3d`), 3 (n = 4 pode ser 3 nós e 1 validador; conversão de n em outra tarefa, por abrir), 6 (Nakamoto sai do estudo; n = 16 é higiene), 5 (sem objeto: B não foi escolhida). **Sem resposta registrada:** 4 e 7. Na 4, o código aceita e a medição não achou grafo desconexo (200 redes), mas **não há decisão do humano** sobre aceitar desconexão; na 7, `MIN_NODES = 10` ficou como está (CA-06), sem decisão sobre relaxar. Texto original das perguntas:

1. Qual opção implementar: A, B ou A+D? D entra como tarefa mínima? (Recomendação acima; decisão é sua.)
2. Como criar a baseline do código de hoje (cópia compilada fora da árvore ou commit) e quando, antes de abrir a tarefa?
3. O n = 4 de PoSA/PoS pode ser 3 nós e 1 validador (seção 5), ou a conversão de n em `MVPComparison` entra em outra tarefa? (L10.)
4. Se um grafo sair desconexo: aceitar e registrar, ou exigir regra de reconexão (que mudaria a topologia)?
5. Se B: qual limiar de n para a malha completa (parâmetro sem fonte)?
6. Nakamoto fica no estudo (leitura 28/10, n = 25 na zona não medida) ou sai (ADR-0000)? Define se o CA-02 inclui n = 16 em `BitcoinCoreP2P` como requisito do estudo ou só como higiene.
7. `DAGsperNetworkScenario.MIN_NODES` fica ou relaxa depois da correção?
