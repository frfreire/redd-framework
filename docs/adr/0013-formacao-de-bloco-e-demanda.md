# ADR-0013: formação de bloco e demanda (gerador único de transações e fila de pendentes)

- **Estado:** Proposto
- **Data:** 2026-10-31
- **Decisores:** (humano) decisões (1) a (4) aceitas em 2026-10-30; os valores marcados `a definir com o humano` continuam abertos
- **Trava:** a tarefa de implementação em `src/` (`loop2-jabs-codigo`) do gerador único, da fila de pendentes e da regra de fechamento; a bateria principal; qualquer afirmação do artigo sobre ε_λ do tráfego (ver Consequências). Nada é aberto por este ADR.

Rótulos de evidência: **[L]** leitura de código, **[E]** executado, **[C]** calculado. Caminhos de `src/` são relativos à raiz do repositório.

## Contexto

O ADR-0000 (redesenho de escalas, Proposta) e o ADR-0010 (PI-00 a PI-10) definem a **demanda** como fórmula (λ̄ = N_op·κ/P, λ_pico = η·N_op·κ/W_janela, regimes Poisson e MMPP-2 de mesma média) e o artigo decompõe o tráfego por bloco como C_b ≈ M(n)·s_v + (n−1)·B·s_tx (eq. `eq:decomp`, `.experimento/artigo-redd-isys-v4/secoes/04-requisitos-escalas.tex:72-77`). Para a demanda alcançar a métrica, ela precisa **entrar no consenso**: transações chegam, esperam numa fila, e o bloco é formado com as que estão na fila. O simulador não faz isso de modo uniforme. Estado por protocolo, lido no código (rótulo **[L]**, salvo indicação):

| Protocolo | Gerador de tx | Como o bloco é formado | Tamanho do bloco | Evidência |
|---|---|---|---|---|
| **pBFT** (ARQ) | **nenhum**: `PBFTNode.generateNewTransaction()` é vazio ("nothing for now") | a rede propõe a cada **5,0 s** fixos (`scheduleBlockProposal`), sem olhar demanda; além disso, o primário do `PBFT` propõe o bloco seguinte após uma finalização (`PBFT.java:312-333`) e, no ataque de equivocação, gera dois blocos conflitantes (`PBFT.java:387-388`), ambos com 50 tx fixas | `sampleBitcoinBlockSize` (distribuição Bitcoin 2020, até ~2,4 MB), **independente** das tx do bloco; as tx (20 a 79 sorteadas, ou 50) não somam o tamanho | `PBFTNode.java:46-48`; `PBFTLocalLANNetwork.java:77-115` (5,0 s em :111); `BlockFactory.java:81-96` (tamanho em :94, `txCount` em :84-86); `PBFT.java:331-333,387-388`; `BlockFactory.java:52-54` |
| tamanho da tx no pBFT | sorteado | `samplePBFTTransaction` usa a distribuição de tamanho de tx do Bitcoin; hash fixo de 32 B | variável | `TransactionFactory.java:63-68` |
| **HotStuff** | nenhum | **lote cheio sem fila**: tamanho = `txCount × txSizeBytes` (lote do config; com o spec, 2048 × 512 B = 1 MiB); o lote parcial (`txCount` menor que o lote) existe na fábrica mas **não tem chamador em `src/main`** | `txCount × s_tx` (fixo, sem aleatoriedade) | `BlockFactory.java:98-149`; busca por chamadores de `sampleHotStuffBlock*` em `src/main`: só a definição |
| **Casper** (MCO2/TREE) e **Parlia** (AMBIFY) | **Poisson homogêneo**, nó sorteado a cada evento, intervalo médio `--tx-interval` (padrão 5,0 s) | o minerador monta o bloco do `memPool` até `MAXIMUM_BLOCK_GAS` = 12 500 000, **iterando um `HashSet`** | soma dos tamanhos das tx; tx de `sampleEthereumTransaction` (tamanho e gás de distribuições próprias) | `MVPComparison.java:53-54`; `TxGenerationProcessRandomNetworkNode.java:15-19`; `EthereumNode.java:72-77`; `EthereumMinerNode.java:27,51-59`; `TransactionFactory.java:42-55` |
| **Tendermint** | **não existe** no simulador (busca por "tendermint" em `src/main/java`: nenhuma ocorrência) | n/d | n/d | busca [L] |

Observações sobre a tabela:

1. **Consequência para o artigo [C].** No pBFT o termo de disseminação (n−1)·B·s_tx de C_b **não depende da demanda**: o tamanho do bloco vem de uma distribuição e o intervalo é fixo. Logo, qualquer elasticidade ε_λ do **tráfego** em relação a λ sairia ≈ 0 **por construção do simulador**, não por propriedade do protocolo. O mesmo vale para o HotStuff (bloco cheio por definição). Em Casper e Parlia a demanda entra pelo `memPool`, mas por um gerador homogêneo (não MMPP-2) e com limite de gás, não de lote.
2. **O perfil de carga não alcança o consenso no pBFT.** É a lacuna **L14** da auditoria (`especificacoes/calibracao/auditoria-protocolo-experimental.md:220`: "PBFT/ARQ não tem gerador de tx; perfil de tx não alcançaria o consenso", dono: `humano` para a pergunta "qual grandeza o perfil modula no PBFT"). Este ADR a resolve (ver Decisão, item 5).
3. **Não existe gerador MMPP-2** em `src/main` (busca por "MMPP" e "Poisson": só o processo Poisson homogêneo `AbstractPoissonProcess`/`AbstractTxPoissonProcess`). O MMPP-2 é **proposta** do humano (ADR-0010, PI-10), registrada como direção, e a seção 6.3 do índice a mantém como proposta.
4. **O perfil sazonal saiu** (decisão B3 do humano, 2026-10-29; ADR-0010 PI-11 "NÃO USADO"). O fator de carga do E2 é o **regime Poisson × MMPP-2 de mesma média**. O spec `.experimento/bateria1.yaml` (`perfil_carga: [estacionario, sazonal]`) ainda cita o perfil sazonal e é herança a corrigir fora deste ADR.
5. **`tamanho_bloco_kb` é herdado.** `.experimento/bateria1.yaml` fixa `tamanho_bloco_kb: 1024` e `tamanho_tx_bytes: 512` como parâmetros fixos. Só o HotStuff os usa hoje (via `HotStuffConsensusConfig.batchSizeFor`); pBFT, Casper e Parlia não leem nenhum dos dois.

**Decisões do humano em 2026-10-30** (as recomendações do orquestrador, aceitas):

1. **Um gerador único de transações**, determinístico por semente, para os cinco protocolos: Poisson e depois MMPP-2 (ADR-0000; ADR-0010 PI-10).
2. **Fila de pendentes por nó** com **regra de fechamento comum**: lote máximo OU tempo máximo, o que ocorrer primeiro. Os **valores** são `a definir com o humano`; o ADR-0000 não os define.
3. **Mesmo s_tx para todos** os protocolos (valor = PI-08 do ADR-0010).
4. **Bloco dimensionado pelas transações realmente na fila**, nunca sorteado.

**Achado de 2026-10-31 (tarefa N1, conversão de n; relevante à prova de não regressão desta mudança).** A saída de Casper, Parlia e Nakamoto é sensível à **sequência de `System.identityHashCode` da thread principal**: o `memPool` do Ethereum é um `HashSet` de objetos sem `hashCode`/`equals` próprios, e a ordem de iteração depende do hash de identidade (é o débito 5 do ADR-0006, linha do T3 em `docs/adr/0006-pbft-trafego-medido.md:267`). Código novo executado antes da simulação (na N1: `CliArgs`, `NetworkSizing`, `Set.of`) desloca essa sequência e muda a saída **sem mudar a lógica do protocolo**. Evidência em `src/test/resources/nao-regressao/n1-conversao-n/diagnostico-hash/` (arquivos `resumo-*.txt`, `comandos.txt`), do que li:

- `resumo-sonda.txt` **[E]**: o número de chamadas de `identityHashCode` até o fim difere entre base e atual (AMBIFY:10:60 posição 1296 contra 1411; MCO2:50:600, 2812 contra 2906; OFFSET:40:20000, 12371 contra 12223; ARQ:7:60, 23124 contra 23132).
- `resumo-flags.txt` e `resumo-flags2.txt` **[E]**: com a JVM em `-XX:+UnlockExperimentalVMOptions -XX:hashCode=2`, base e atual dão saída **IDENTICA** em AMBIFY:10:60 (inclusive base×base e atual×atual), MCO2:50:600 e OFFSET:40:20000 (semente 1). Com `-XX:hashCode=3` o AMBIFY ainda diverge entre base e atual (coluna `BlockSize`, 2826 contra 964), embora cada lado repita igual a si mesmo. Leitura: só o modo 2 neutralizou a sensibilidade nos casos testados. (O motivo, que o modo 3 seria um contador sensível ao número de chamadas, é conhecimento do HotSpot e **não foi verificado** aqui.)
- `resumo-todos.txt` **[E]**: sem fixar o hash, MCO2 n=50, AMBIFY n=10 e 12 e OFFSET n=40 divergem entre base e atual nas 3 sementes (1, 2, 3); ARQ n=7 e MCO2 n=10 ficam idênticos. A coluna "atual(direto) × base(wrapper k)" é IDENTICA em todas as linhas (k=11 em MCO2 e AMBIFY, k=7 em OFFSET): um deslocamento da sequência de hash faz a base reproduzir o atual, isto é, a divergência é deslocamento da sequência e não mudança de lógica nesses casos.
- `resumo-busca.txt` **[E]**: no sentido inverso (atual com wrapper k de 0 a 200 contra o alvo da base direta) **nenhum k** reproduziu o alvo no caso OFFSET:40:20000 s1. O deslocamento é, portanto, real mas não é um dado que se possa "devolver" ao código.
- A cobertura de Nakamoto é o caso `OFFSET` (rede Bitcoin do `MVPComparison`). Nakamoto saiu do estudo (emenda do ADR-0009, 2026-10-30; ADR-0010 PI-19), mas o código fica no repositório.

Consequência para este ADR: as mudanças aqui descritas mexem em código executado antes e durante a simulação (gerador, fila) e **voltarão a deslocar** a sequência de hash. A não regressão de Casper, Parlia e Nakamoto deixa de poder ser provada comparando saídas com a JVM em hash natural.

## Opções

Duas escolhas já foram tomadas pelo humano (gerador único, item 1; bloco dimensionado pela fila, item 4). A opção que sobra é a **regra de fechamento do bloco** a partir da fila de pendentes. Todas as quatro usam a mesma fila e o mesmo s_tx.

Notação: Q = transações na fila do proponente no instante de decisão; L = lote máximo (tx); T = tempo máximo (s); S = tamanho máximo do bloco (bytes); s_tx = tamanho da transação.

1. **Só lote (L).** Fecha quando Q ≥ L.
   - Prós: simples; o bloco é sempre de L tx; tráfego previsível.
   - Contras: com λ baixo o bloco **nunca fecha** (latência de inclusão ilimitada); é o comportamento de "bloco cheio" do HotStuff atual, que esconde a demanda (o tráfego não varia com λ até saturar).
2. **Só tamanho (S).** Fecha quando Σ tamanhos ≥ S.
   - Prós: reflete um limite de bytes do bloco, que é a forma comum de limitar bloco em redes reais.
   - Contras: com s_tx igual para todos (decisão 3) **equivale à opção 1** com L = ⌊S/s_tx⌋; só difere se o tamanho de tx variar. Mesmo defeito de latência ilimitada.
3. **Só tempo (T).** Fecha a cada T com o que houver na fila.
   - Prós: é o que o pBFT faz hoje (5,0 s) e é simples; latência de inclusão limitada por T.
   - Contras: sem teto de tamanho, o bloco cresce com λ sem limite, e sob saturação a fila não drena de forma controlada. Não reproduz o mecanismo de lote.
4. **Lote + tempo (L ou T, o que vier primeiro).** Fecha quando Q ≥ L **ou** quando passaram T s desde a abertura do bloco (ou da chegada da primeira tx pendente; ver pergunta aberta), desde que Q ≥ 1.
   - Prós: tem os dois comportamentos de interesse: **carga baixa** (fecha por T, bloco parcial, tráfego varia com λ) e **saturação** (fecha por L, a vazão Θ é limitada pelo lote e pelo ciclo de consenso, e a fila cresce, o que é o que o ADR-0012 precisa medir). É a mesma forma da regra de lote do ordenador do Fabric (tamanho máximo de lote mais tempo máximo), por conhecimento do produto, **não verificado neste repositório**, e vale lembrar que o Fabric usa Raft (ADR-0004).
   - Contras: dois parâmetros livres a justificar (L, T) em vez de um; o ADR-0000 não os define; com T pequeno e λ pequeno os blocos saem quase vazios (o custo fixo de consenso M(n)·s_v domina).

Sub-escolhas que ficam abertas em qualquer opção (ver "A definir com o humano"): política da fila (FIFO ou outra), capacidade da fila, o que fazer quando ela enche. Regra do projeto: **sem descarte silencioso**.

## Decisão

Aceitas pelo humano em 2026-10-30 (as quatro recomendações do orquestrador); este ADR as registra e **fica Proposto** porque falta a decisão de valores e a verificação de não regressão.

1. **Gerador único.** Um gerador de transações, determinístico por semente, compartilhado pelos cinco protocolos (pBFT, HotStuff, Tendermint, Casper, Parlia). Regime 1: Poisson homogêneo; regime 2: MMPP-2 de mesma média (ADR-0010, PI-10, ainda proposta no ADR-0010). O gerador deve usar um fluxo de aleatoriedade **próprio**, separado do de rede, mineração e bizantinos, para que variar o regime ou λ não desloque os sorteios dos demais (pareamento por semente, ADR-0010 PI-13). **Isto é recomendação deste ADR, não está entre as quatro decisões do humano**; confirmar. A taxa de chegada é a grandeza de demanda: λ̄ (Poisson) e λ(t) do MMPP-2, ancoradas em λ_B (PI-01).
2. **Fila de pendentes por nó + regra de fechamento comum: opção 4 (lote + tempo).** O proponente fecha o bloco quando a fila atinge o lote máximo L **ou** o tempo máximo T se esgota, o que ocorrer primeiro. **L e T são `a definir com o humano`** (o ADR-0000 não os define; não proponho valores).
3. **Mesmo s_tx** para todos os protocolos (valor = PI-08 do ADR-0010, `a definir com o humano`). Isso retira do pBFT a distribuição de tamanho de tx do Bitcoin e do Ethereum a sua distribuição (ver Consequências sobre o gás).
4. **Bloco dimensionado pela fila.** `tamanho_do_bloco = (nº de tx realmente tiradas da fila) × s_tx`. Nunca sorteado; a fábrica de bloco do pBFT deixa de chamar `sampleBitcoinBlockSize`. Não consome aleatoriedade (o HotStuff já é assim).
5. **Resolução da lacuna L14 da auditoria** (qual grandeza o perfil de carga modula no pBFT): **a taxa de chegada de transações**, λ, na entrada do gerador único. O perfil não modula tamanho de bloco, nem intervalo de proposta, nem número de nós. O tráfego e a latência passam a variar com λ por consequência da fila.
6. **Perfil sazonal fora (decisão B3, 2026-10-29; ADR-0010 PI-11 "NÃO USADO").** O regime de carga do estudo é **Poisson × MMPP-2 de mesma média**; nenhum valor de A ou T será fixado. A média estacionária do MMPP-2 deve igualar a do Poisson, e isso deve ser **conferido por contagem de transações geradas por semente** (condição P6 do índice).
7. **Mudança intencional de saída** e não regressão: ver Consequências.

Isto é decisão do humano sobre **estrutura**, não sobre valor. Nenhum limiar numérico é fixado aqui.

## Consequências

### 1. Mudança intencional de saída (pBFT, Casper, Parlia)

A implementação **muda a saída** de três protocolos de propósito. Nenhum número anterior desses protocolos é comparável com o posterior:

- **pBFT:** tamanho de bloco deixa de ser sorteado de `sampleBitcoinBlockSize`; tx deixam de ter tamanho sorteado; a proposta deixa de ser fixa em 5,0 s e passa a ser regida pela fila. Muda tráfego (C_B), Tb e Tt.
- **Casper e Parlia:** o gerador deixa de ser o `TxGenerationProcessRandomNetworkNode` homogêneo com tx de distribuição própria e passa a ser o gerador único com s_tx comum; a montagem do bloco deixa de ser pelo gás (ver item 4). Muda tamanho de bloco, tráfego e finalização.
- **HotStuff:** passa de "lote cheio sem fila" a bloco pela fila (lote parcial, que a fábrica já suporta, `BlockFactory.java:124-141`). Como o HotStuff está em implementação (o spec fixa 2048 × 512 B), a saída dele **ainda não é dado de pesquisa arquivado**; mesmo assim a mudança é registrada.
- **Tendermint:** não existe; nasce já com o gerador e a fila.

Todo resultado produzido **antes** desta mudança fica marcado como não comparável e não entra em tabela do artigo junto com o posterior. A versão do simulador declarada no cabeçalho do CSV (regra 6) deve distinguir os dois.

### 2. Não regressão

A mudança é intencional apenas onde está descrita acima. **O resto não pode mudar.** Exigência:

- Baseline: commit **325f4e3**, worktree **`/tmp/opencode/base-325f4e3`** (existe). Executar base e atual com as mesmas sementes e comparar CSV normalizado (script `nao_regressao.py` citado no README da N1).
- **Protocolos sem mudança intencional** (os que a regra de fechamento não toca, p. ex. OFFSET/Nakamoto e DAGsper/Tangle, se ainda rodados): saída idêntica à base.
- **Casper, Parlia e Nakamoto são sensíveis à sequência de `System.identityHashCode`** (achado de 2026-10-31, N1; débito 5 do ADR-0006). Então a prova de identidade desses protocolos exige **uma** destas, a decidir:
  1. **Executar base e atual com a JVM em hash constante**: `-XX:+UnlockExperimentalVMOptions -XX:hashCode=2` nos dois lados. Prova só o que mudou na lógica, não elimina a sensibilidade. Modo 2 foi o único testado com sucesso (AMBIFY, MCO2, OFFSET, semente 1; ver Contexto).
  2. **Corrigir a causa:** trocar o `HashSet` do `memPool` por `LinkedHashSet` (ordem de inserção). Isso **muda a saída do Casper** (a ordem de iteração do `memPool` passa a ser outra) e, por isso, **exige ADR próprio** e nova linha de base; não é passo dentro desta tarefa.
- Enquanto uma das duas não for escolhida, o critério de não regressão desses três protocolos fica **PENDENTE**, não "idêntico". A implementação deste ADR não deve trocar o contêiner do `memPool`; isso é do ADR de correção.
- A estimativa de que a diferença observada na N1 é só deslocamento da sequência de hash é **[E] para os casos testados** (k=11 em MCO2/AMBIFY, k=7 em OFFSET, semente 1 a 3) e **não está provada em geral**.

### 3. Dependências e ordem de trabalho

- **Gerador MMPP-2: não existe.** Esta decisão depende dele para o regime 2. O regime 1 (Poisson) existe como `AbstractTxPoissonProcess`, mas é homogêneo por nó sorteado, não gerador único com fila. Ordem lógica: (a) fila + regra de fechamento + gerador Poisson único; (b) MMPP-2 sobre o mesmo gerador. Os valores do MMPP-2 (PI-10) seguem `a definir`.
- **Tarefa N1 (conversão de n, `--n-semantics`):** feita e testada (ver `src/test/resources/nao-regressao/n1-conversao-n/README.txt`), mas com a **não regressão contra 325f4e3 PENDENTE** pelo achado de hash. A implementação deste ADR **não deve começar sobre uma N1 sem veredito**, ou a mudança intencional se mistura com o deslocamento de hash; ordem recomendada: fechar o veredito da N1 (opção 1 ou 2 acima) e só então abrir esta tarefa. A implementação toca o mesmo caminho comum do `MVPComparison`.
- **ADR-0012 (registro de Θ, U e C_nó): em elaboração** (esqueleto; seções "a preencher" na data deste ADR). Θ sob saturação **exige a fila de pendentes que este ADR cria**: sem fila não há vazão limitada por demanda, e Θ ficaria igual à taxa de proposta. A instrumentação do 0012 e a bateria de saturação só fazem sentido depois deste.

### 4. Parâmetros herdados que deixam de existir ou mudam de papel

- **`tamanho_bloco_kb`** (`.experimento/bateria1.yaml`, 1024) **deixa de ser parâmetro de tamanho de bloco** e passa a ser, no máximo, a fonte do **lote máximo**: L = tamanho_bloco_kb · 1024 / s_tx. Com s_tx = 512 B (valor atual do spec, que **não é o PI-08 decidido**) daria 2048 tx, o mesmo lote do HotStuff. **Conferir antes de adotar**; o L final é `a definir com o humano`. Se L for definido diretamente em tx, `tamanho_bloco_kb` sai do spec.
- **Proposta a cada 5,0 s no pBFT** (`PBFTLocalLANNetwork.java:111`): deixa de existir como cadência fixa. Pode virar o tempo máximo T ou ser descartada. **`a definir com o humano`**. Observação: 5,0 s também é o padrão de `--tx-interval` (`MVPComparison.java:54`), coincidência de valor, não relação.
- **Casper e Parlia, limite de gás (`MAXIMUM_BLOCK_GAS` = 12 500 000, `EthereumMinerNode.java:27`) contra lote:** com s_tx comum, o gás de cada tx deixa de vir da distribuição de gás do Ethereum. Opções: (i) o gás vira atributo constante da tx e o lote L substitui o limite; (ii) o gás permanece como segundo limite de fechamento (lote OU gás OU tempo), o que afasta a regra "comum"; (iii) remover o gás. **`a definir com o humano`**. Fato: hoje o minerador fecha por gás, não por lote nem por tempo.
- **Fase do tempo T:** no PoW/PoS o tempo de bloco é regido pelo protocolo (mineração, slot), não pela regra de fechamento. Para Casper e Parlia a regra de fechamento se aplica **ao conteúdo** do bloco no instante em que o protocolo o produz, não à cadência. **Isto é minha leitura**, a confirmar: se o humano quer T como cadência também para eles, o desenho muda.

### 5. Consequência para o artigo (enquanto não implementado)

Até a implementação, **ε_λ do tráfego não é informativa** para pBFT e HotStuff (e, por consequência de desenho, não é comparável entre os cinco). O artigo precisa declarar isso e apoiar a comparação das escalas em **latência e tempo de finalização**. Redação sugerida, **para o arquivo de correções** (não editado aqui):

```latex
\paragraph{Limitação do simulador quanto à demanda.}
Nas versões do simulador usadas neste trabalho, o tamanho do bloco do pBFT
é amostrado de uma distribuição independente das transações e as propostas
ocorrem em intervalo fixo, e o bloco do HotStuff é sempre de lote cheio.
Nesses protocolos o volume de bytes por bloco, e portanto o termo
$(n-1)\,B\,s_{\mathrm{tx}}$ de \eqref{eq:decomp}, não depende da taxa de
chegada de transações $\lambda$. A elasticidade do tráfego em relação a
$\lambda$, $\varepsilon_\lambda$, resultaria próxima de zero por construção do
simulador, e não por propriedade do protocolo. Por isso $\varepsilon_\lambda$
do tráfego \emph{não} é reportada como informativa para esses protocolos, e a
comparação entre a escala de governança e a de mercado apoia-se na latência
de commit e no tempo de finalização, que respondem à fila de pendentes.
A formação de bloco a partir de uma fila de pendentes, com regra de
fechamento comum, está especificada e não implementada.
```

### 6. Efeito sobre a calibração e a ponte

A ponte (`redd-ponte`) calibra o simulador com tamanhos de tx, bloco e latência de commit medidos no Fabric. Com o s_tx único, a calibração passa a ter um único ponto de entrada (s_tx), e o lote/tempo do orderer do Fabric (se medido) podem informar L e T. Isso é possibilidade, **`NAO_MEDIDO`** hoje. O Fabric usa Raft; resultado BFT continua sendo "alternativa de ordenação, não o Fabric atual" (ADR-0004, REQ-10).

### 7. A definir com o humano

Nenhum destes é fixado aqui:

| Item | Observação |
|---|---|
| Lote máximo L | ADR-0000 não define; ver relação com `tamanho_bloco_kb` acima |
| Tempo máximo T | idem; relação com a proposta de 5,0 s do pBFT |
| s_tx | PI-08 do ADR-0010; o 512 B do spec não é valor decidido |
| λ_B e demais PI do ADR-0010 | PI-01 a PI-06, PI-09, PI-10 |
| Tratamento do limite de gás do Ethereum | itens (i), (ii), (iii) acima |
| Política da fila | FIFO ou outra |
| Capacidade da fila | e o que acontece quando enche: **sem descarte silencioso** (se houver descarte, é contado, registrado e declarado) |
| Instante de início do relógio de T | desde a abertura do bloco anterior, ou desde a chegada da primeira tx pendente |
| Fila por nó: quais nós têm fila | todos os nós, ou só os proponentes/validadores; como uma tx gerada num nó chega ao proponente (difusão ou não) |

## Verificação

Critérios para o critic do loop 2 e para o loop 3. Nenhum foi executado: este ADR não tem código.

1. **Gerador único**: a mesma semente e o mesmo λ geram a mesma sequência de instantes e de nós de origem **em todos os protocolos**; um teste conta as transações geradas por semente.
2. **MMPP-2 (regime 2)**: a média de transações por janela é igual à do Poisson de mesma média (contagem por semente, tolerância `a definir`); o índice de dispersão do regime 2 é maior que o do regime 1. Aplicável só depois que o MMPP-2 existir.
3. **Bloco pela fila**: para qualquer bloco, `tamanho = nº de tx do bloco × s_tx`, e o nº de tx ≤ L. Nenhuma chamada a `sampleBitcoinBlockSize` no caminho do pBFT.
4. **Regra de fechamento**: com λ muito menor que L/T o bloco fecha por T (bloco parcial); com λ muito maior que L/T fecha por L e a fila cresce. Um teste por regime.
5. **Conservação**: todas as tx geradas = incluídas em bloco + ainda na fila + descartadas contadas (sem descarte silencioso). Verificável no fim da simulação.
6. **Tráfego varia com a demanda**: no pBFT, C_B (parcela de disseminação) cresce com λ até saturar; é o teste que derruba o efeito "ε_λ ≈ 0 por construção".
7. **Não regressão**: base 325f4e3 contra atual, com as sementes do README da N1; protocolos sem mudança intencional idênticos; Casper, Parlia e Nakamoto com `-XX:hashCode=2` nos dois lados (ou após ADR do `LinkedHashSet`). O relatório diz qual das duas formas foi usada.
8. **Mudança intencional declarada**: cabeçalho do CSV traz a versão do simulador; teste que impede misturar saída anterior e posterior.
9. **Medido, não estimado** (regra 6): Θ, tráfego e latência só valem de pacotes que passaram pela rede simulada, com semente, versão do simulador e granularidade declaradas.

Estado deste ADR: **Proposto**. Passa a Aceito quando o humano confirmar (a) o conteúdo acima e (b) pelo menos L, T e a política da fila; a implementação, quando aberta, segue o loop 2 com o critic.


---
## Nota 16/11/2026 — L, T e fila deixaram de ser `a definir`; implementação não iniciada

Anexo datado; o corpo acima **não foi reescrito** (o estado deste ADR continua **Proposto**). Fonte das decisões: `docs/adr/0015-sessao-de-decisao-parametros.md`, registro de 12/11/2026 (L e T) e registro de 16/11/2026, item (d) (Q29), com a trilha em `.ia/rodadas/loop2-jabs-codigo.jsonl` (tarefa `Q32-Q29-Q15-Q14-Q33-decisoes`, 2026-11-16T21:27:55Z).

1. **L e T não são mais `a definir com o humano`** (seção 7 e Decisão, item 2, acima). **L = 100**, com varredura **{10, 100, 500}**; **T = 2 s**, com varredura **{1, 2}**. Origem: registro de 12/11/2026 do ADR-0015 (revisão de literatura; ordem de grandeza, não valores transplantados) e a decisão de 16/11 que resolveu Q7 e Q24 por esses valores. L = 2048 (`tamanho_bloco_kb·1024/s_tx` da seção 4) foi **descartado como caso base** (registro de 12/11). A cadência fixa de 5,0 s do pBFT é descartada (B-55). Isto fixa **valores de projeto (ESCOLHIDO)**, não medição; o texto da seção 4 sobre `tamanho_bloco_kb` e sobre os 5,0 s permanece como histórico.
2. **Q29 decidida (opção A)**, o que responde os itens da seção 7 sobre a fila: **FIFO**; capacidade **sem limite, com alarme medido** (a regra "sem descarte silencioso" continua valendo); **relógio de T desde a chegada da primeira tx pendente**; **fila nos proponentes**, com a **difusão da tx contada no tráfego**.
3. **Continuam abertos neste ADR:** s_tx como valor final (512 B é caso base do registro de 12/11, sem medição própria; sensibilidade no envelope medido); tratamento do limite de gás do Ethereum em Casper e Parlia (Q35, não decidida); λ_B e demais PI do ADR-0010 (Q14 foi decidida como faixa de sensibilidade sem ponto central, sem número); regime MMPP-2 e RNG próprio (Q13); a forma da prova de não regressão (Q36, ver Consequências, item 2) e o veredito da N1. Para Q13, Q35 e Q36 **não encontrei decisão registrada** nas fontes lidas nesta data.
4. **A implementação do gerador único, da fila de pendentes e da regra de fechamento continua NÃO INICIADA.** Em `src/main` não achei fila de pendentes, regra L/T nem gerador MMPP-2 nesta data (busca por padrões de fila, mempool e MMPP: só o `memPool` do Ethereum/Bitcoin, `ReplicaCore.pending` e `HotStuff.leaderQueue`, que não são a fila deste ADR); `PBFTLocalLANNetwork.java:111` ainda propõe a cada 5,0 s. A entrada `M2-unificacao-formacao-bloco` de 12/11 em `.ia/rodadas/loop2-jabs-codigo.jsonl` foi marcada pelo humano como fechada ("implementada nos commits 2c-x/2d-x/HS-CB"); isso **não** é a implementação de gerador e fila descrita aqui, e a ordem de trabalho da seção 3 (fechar o veredito da N1 antes de abrir a tarefa) segue valendo. A tarefa de gerador de carga e fila é a de número 8 da ordem de execução do registro de 10/11/2026 do ADR-0015, **não aberta**.
5. O ADR-0013 **não passa a Aceito** por esta nota: o critério do fim do documento (confirmação do conteúdo e de L, T e política da fila) é ato do humano, que não o declarou nas fontes lidas.
