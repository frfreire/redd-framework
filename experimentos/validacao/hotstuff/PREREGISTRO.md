# PRÉ-REGISTRO: V-ABC-hotstuff, etapa 1 de 2 (V-A e V-C; V-B pré-registrado e bloqueado)

Data do registro: 2026-11-16. Tarefa: `V-ABC-hotstuff`. Autor: `jabs-experimento` (proponente do loop 3; **não aprova o próprio resultado**).
Impacto: **ALTO** (portão de validação): o loop 3 vai ao humano, sem arbiter.

**Nada foi simulado nesta etapa.** Não existe dado desta rodada. O que foi executado: leitura de dados antigos (K40, n=64, uma
conferência da fórmula), geração de manifesto, conferência de sementes, `--autoteste` do analisador sobre dado **sintético
derivado da fórmula** (não do simulador), e um build **de teste** em `/tmp/opencode/val-hs-fumaca` (só compilação). Este arquivo é
imutável depois que o orquestrador o commitar com `PREREGISTRO.sha256`; qualquer mudança exige novo pré-registro
(`hash-preregistro.py` recusa sobrescrever o hash).

## 0. Divulgação: o que eu já sabia antes de fixar a regra

Isto **não é cego** em dois pontos, e está dito aqui:

1. **Li o código do HotStuff e derivei uma fórmula analítica de tráfego por bloco** (`scripts/formula-analitica.py`), a partir de
   `HotStuffVote.computeSize`, `HotStuffQuorumCertificate.computeSize`, `HotStuffProposalMessage`, `HotStuffQcMessage` e do fluxo de
   `HotStuff.java`. A fórmula reproduz **exatamente** os dois números de um dado antigo (K40, n=64, L=100, ROTATING, INDIVIDUAL):
   `CbRede_avg_B` = 4 138 848 e `CbRedeConsenso_avg_B` = 913 248. Esse é o **único** ponto conferido; **nenhum dos n deste pré-registro
   (4, 7, 10, 16, 31) foi visto**, e o modo AGGREGATED (fixture de 96 B sem fonte) **nunca foi conferido com dado**.
2. **Por isso as previsões abaixo vêm do código, não do dado novo**, mas elas vão **decidir** a classificação (seção 3.2). Se a fórmula
   estiver errada em algum n ou modo, o resultado é "INCONSISTENTE com a fórmula", e o loop 3 deve separar "bug do simulador" de
   "erro da fórmula".

Também conheço, de rodadas anteriores: T candidatos do humano (16/11); custo medido por n (`custo-estimado.py`); que `Tb_avg` vale
0,129 s (n=4), 0,167 s (n=16), 0,375 s (n=64), 0,736 s (n=128) em L=100 ROTATING. Esses valores só dimensionam a duração.

## 1. O que se quer validar e a granularidade (declarada **antes** de rodar)

Pré-condição P4a (ADR-0009): "V-A em O(n)" para o HotStuff. **A ordem esperada depende da granularidade**, então declaro as duas:

| Granularidade | Medida (coluna) | Fonte | O que o ADR/skill esperam |
|---|---|---|---|
| **AGREGADO DE REDE por bloco** (principal) | `CbRede_avg_B` (total) e `CbRedeConsenso_avg_B` (sem a carga do bloco) de `mvp-results-cb.csv`: soma por altura sobre **todos os nós receptores**, sem corte local, nunca autoentrega; janela de regime: alturas 2 a hmax−2 | `jabs.metrics.CbRedeMedida` (ADR-0015 D1) | HotStuff **O(n)** |
| **POR NÓ** (secundária, só rótulo) | `RecvBytes` por nó de `*-nos.csv` dividido pelos blocos finalizados em W=[t0, fim] | `LinkLoadRecorder` (`--load-record`) | líder **O(n)**, réplica **O(1)** |

- **Não** uso o `Cb_avg` histórico (bytes do primeiro nó a finalizar; no HotStuff é sempre o líder): ele é artefato do ponto de
  medição (ADR-0015, registro 10/11) e não serve a V-A. O `CbPrimeiro_*` não entra.
- **O "O(n)" do ADR é de *autenticadores* com assinatura limiar** (artigo, tabela 1). O simulador, no modo **INDIVIDUAL** (o modo
  principal do ADR-0015), manda no QC uma **lista** de q=2f+1 assinaturas de 64 B, e o QC vai a n−1 nós: **em bytes, o tráfego de
  consenso é O(n²)** (fórmula, seção 3.2). Só o modo **AGGREGATED** (assinatura constante, 96 B, fixture sem fonte) dá O(n) em bytes.
  Essa distância entre "O(n) do ADR" e "O(n²) em bytes no INDIVIDUAL" **é uma previsão registrada agora**, e a decisão de qual modo
  responde por P4a é do humano (seção 9, item 2).

## 2. Instrumento: commit, build, JVM

- **Commit fixo do simulador: `a65bf83e105cdb0d0a7e0526dff06de934f053d5`** (HEAD no momento do registro; `git rev-parse HEAD`).
  `src/` tree `8c7d88e8faf6d173390ce58ddb77287cff882b29`, `pom.xml` blob `32f934e332097d6bfed46830ff03132c36763670`,
  observador blob `4024e15b53b8939458c22ae9d04f107b422e3cd0`. **O commit é fixo no script (`preparar-val.py` recusa outro)**, e **não** é
  "o HEAD da etapa 2": o commit deste pré-registro vai ser o HEAD, mas o código simulado é o de `a65bf83`.
- **Build a partir do git** (`git archive`), em `/tmp/opencode/val-hs`; a árvore de trabalho **não é usada**.
- **Mudanças não commitadas de outro agente (ficam DE FORA do build):** `M src/main/java/jabs/example/MVPComparison.java`,
  `?? src/test/java/jabs/example/MVPComparisonBeyondThresholdTest.java`, `?? scripts/jabs/nr_byz_fora_faixa.py` (tarefa
  `BYZ-fora-da-faixa-hotstuff`, em andamento). **V-A e V-C não dependem dela.** Se ela for commitada antes da etapa 2, `HEAD:src` muda, mas o
  build continua sendo o de `a65bf83` (a trava é o commit fixo, não o HEAD).
- **`-XX:hashCode=2` OBRIGATÓRIO em toda célula** (`-XX:+UnlockExperimentalVMOptions -XX:hashCode=2`), nas **duas** JVMs;
  `rodar_celula.py` **aborta** se o `cfg.json` não trouxer a flag. Justificativa: exigência do portão e de `--load-record` (bloco por
  nó). **Diverge da rodada K40, que rodou sem a flag** (custo). Custo com a flag em `a65bf83` **não foi medido** (seção 6).
  Com a flag, o ADR-0016 afirma saída idêntica ao HEAD anterior nos protocolos Ethereum e no HotStuff; **eu não reverifiquei isso aqui**.
- **Java 25 (alvo do pom: 17)**, Maven 3.8.7; serão gravados em `versao-simulador.json`.
- **Ressalva:** os T (timeout) foram calibrados no piloto sobre o código de `9f305dd` (`src/` tree `38820851…`), e este instrumento é
  `a65bf83` (tree `8c7d88e8…`, com a mudança do logger do ADR-0016). A conferência de que o T continua válido é a própria condição A1'
  desta rodada (zero timeouts).

## 3. Critérios (todos congelados antes de qualquer dado)

### 3.1 Regime e carga

ROTATING, L=100, `--hs-tx-size=512`, `--hs-max-future-view-horizon=1000`, BITMAP, carga **estacionária saturada** (cenário
`HotStuffLANScenario`: bloco de lote cheio por visão, sem gerador de carga; **mesmo regime da K40 e do piloto**, sem divergência),
sem ataque (f=0), LAN simulada do JABS (Pareto 20 ms, 100 Mbps), 1 trial por célula.
Divergências **declaradas**:
- **V-A por nó usa STABLE** (líder = nó 0, identificável). Em ROTATING todo nó lidera 1/n das visões e a média por nó **dilui** o
  gargalo do líder (ADR-0012 RM-04, B-24): o líder só aparece em STABLE. O ADR-0015 (Q8-B) já põe STABLE como caso base.
- Dois modos de assinatura: **INDIVIDUAL** (principal) e **AGGREGATED** (sensibilidade; 96 B é fixture sem fonte).

### 3.2 V-A, agregado de rede (decide)

**Níveis:** n ∈ {4, 7, 10, 16, 31} (ADR-0015); 5 sementes por (n, modo); 2 modos ⇒ **50 células**.
**Previsão pela fórmula** (bytes por bloco decidido, soma da rede; H=32, O=10, P=100·512=51 200; `formula-analitica.py`):

| n | INDIVIDUAL total | INDIVIDUAL consenso | AGGREGATED total | AGGREGATED consenso |
|---|---|---|---|---|
| 4 | 158 583 | 4 983 | 157 431 | 3 831 |
| 7 | 321 006 | 13 806 | 314 862 | 7 662 |
| 10 | 487 314 | 26 514 | 472 338 | 11 538 |
| 16 | 831 390 | 63 390 | 787 230 | 19 230 |
| 31 | 1 759 080 | 223 080 | 1 574 760 | 38 760 |

**Critérios, por (modo, medida):**
- **I (integridade, por célula):** rc=0 nas duas JVMs; MD5 do CSV por bloco (sem `BlockHashCode`) igual entre `MVPComparison` e
  observador; **zero timeouts disparados** (A1': `timeouts_disparados_total`=0, `STALE_VIEW_TIMEOUT` não conta); ≥ **100 alturas** de
  regime; esperadas = obtidas. Célula que falha em I **não é excluída**: fica listada e o veredito vira "INCONCLUSIVO (instrumento)".
- **F (fidelidade à fórmula):** `|medido − fórmula| / fórmula` ≤ **0,001** em **toda** célula íntegra (agregado). Tolerância
  escolhida porque a fórmula reproduziu o dado K40 com erro 0 (soma da rede é inteira e constante por altura em regime).
- **E (classe, decide):** `b` = inclinação OLS de ln(bytes) em ln(n) sobre as **médias por n** (5 pontos). **Regra do ponto médio:**
  b < 0,5 ⇒ O(1); 0,5 ≤ b < 1,5 ⇒ O(n); b ≥ 1,5 ⇒ O(n²). Previsão pela fórmula: INDIVIDUAL total b=1,170 (O(n)), INDIVIDUAL consenso
  b=1,856 (O(n²)), AGGREGATED total b=1,120 e consenso b=1,126 (O(n)).
- **S (`validar-complexidade.py`, REPORTADO, não decide):** rodo `--esperado linear` e `--esperado quadratico` para cada (modo, medida) e
  imprimo as duas saídas. **Critério de aceite do ajuste, declarado:** "linear" é aceito se o script devolve rc 0 **ou** o termo
  quadratico do ajuste vale **< 10 %** do valor ajustado em n máximo; "quadrático" é aceito se rc 0 **e** o termo vale ≥ 10 %. **Por que
  não decide:** o dado é determinístico e sem ruído, o critério ΔAIC > 4 do script enxerga **qualquer** curvatura, inclusive a do termo
  constante (autoteste sobre a fórmula: o script diz "quadrático" até para o AGGREGATED total, cujo termo quadrático é 0,04 % do
  valor). Com 5 valores de n, o script avisa que a distinção é frágil (`< 6`).
- **Veredito mecânico pré-registrado** (`analisar-val.py`, função `veredito_va`):
  - **INCONCLUSIVO (instrumento):** célula faltante, não íntegra, com timeout, ou n sem célula íntegra.
  - **INCONSISTENTE:** algum ponto infiel à fórmula (F), **ou** classe medida ≠ classe prevista pela fórmula (E), **ou** (modo AGGREGATED)
    classe medida ≠ O(n) do ADR (total e consenso).
  - **CONSISTENTE:** nenhum dos anteriores. **Rótulo não decisório:** INDIVIDUAL com classe ≠ O(n) do ADR é **rotulado**, não reprovado
    (é previsto pela fórmula, seção 1).
  - **Este veredito mecânico não é aprovação.** O loop 3 (humano) decide.
- **Réplica:** o tráfego de consenso do caminho feliz é **determinístico em bytes** (a latência Pareto muda o tempo, não o tamanho).
  Por isso **não reporto IC** de bytes (seria precisão fingida); reporto **mínimo, máximo e média entre sementes** e o erro relativo à
  fórmula. As 5 sementes servem para detectar dependência de semente (timeouts, regime), não para estreitar IC.

### 3.3 V-A, por nó (rótulo; não decide sozinha)

n ∈ {4, 7, 10, 16, 31}, 2 modos, 3 sementes ⇒ **30 células**, STABLE, `--load-record=true --load-record-t0=20.0`.
Medida: `RecvBytes_i / B`, com B = nº de blocos com `Time` ∈ [t0, fim da janela] (CSV por bloco). Líder = nó 0; réplica = média dos nós ≠ 0.
Previsão: INDIVIDUAL líder O(n²) (b=1,674) e réplica O(1) (b=0,041); AGGREGATED líder O(n) (b=1,123) e réplica O(1) (b=0,000).
**Fidelidade por nó ≤ 0,02** (o recorte do primeiro e do último bloco da janela dá ~1/B de erro). **A fórmula por nó nunca foi
conferida com dado**, então infidelidade por nó **só rotula** ("pode ser erro da fórmula"), e o veredito do bloco usa só E (classe).

### 3.4 V-C (condicional; confronto com publicado)

- **Critério numérico pedido:** mesma ordem de grandeza e mesmo ordenamento do artigo (Yin et al. 2019). **Não há número VERIFICADO**
  (`FONTES-V-C.md`): os valores estão em figuras que não consegui ler. **Declaro o V-C numérico INVIÁVEL por falta de dado comparável
  verificado**, a menos que o humano forneça os números (seção 9, item 6). **Não inventei nenhum.**
- **O que a bateria ainda mede (só fatos, 20 células):** n ∈ {4, 16}, ROTATING, INDIVIDUAL, L ∈ {100, 400}, 5 sementes. L=400 é o lote
  da varredura de n no artigo; L=100 é o caso central do estudo. Medidas: `Tb_avg` (média de `BlockFinalizationTime` do CSV por bloco,
  **descartando blocos com `Time` < 20 s**, transiente) e vazão (blocos por s × L, janela 20 s ao fim).
- **Teste ordinal pré-registrado (fraco, declarado):** em cada L, `Tb(n=16) > Tb(n=4)` **e** `vazão(n=16) < vazão(n=4)`. O artigo dá
  como "degradação graciosa ao crescer n" (texto lido). Passar **não** valida a magnitude e **não** substitui o confronto numérico.
- **Regimes diferentes (limite de comparabilidade, fato do texto lido):** artigo em EC2 (latência < 1 ms, 1,2 GB/s sem limite),
  HotStuff **encadeado**, latência **do cliente**; JABS: LAN de 100 Mbps com latência Pareto de 20 ms, HotStuff **básico**, `Tb` do primeiro
  nó. Uma diferença de vazão de ordem do número de fases é **esperada por construção** e **não medida**.

### 3.5 V-B (PRÉ-REGISTRADO; **NÃO EXECUTAR**: aguarda `BYZ-fora-da-faixa-hotstuff`)

- **Níveis:** n ∈ {4, 7, 10, 13}; f inteiro de 0 a f_max+1, f_max = ⌊(n−1)/3⌋ (decisão 10/11, Q34: `f` inteiro; fração só por conversão
  inteira). ROTATING, INDIVIDUAL, L=100, ataque **EQUIVOCATION**, 10 sementes, 300 s ⇒ **180 células** (18 pares (n, f) × 10), listadas
  em `celulas-esperadas.json` (bloco `vb`). Sementes 420 346 a 433 355 (disjuntas). O ponto f=f_max+1 (f=2, 3, 4, 5) é `fora_da_faixa`
  (decisão 3, Q34) e exige a flag da tarefa de código: `flag_fora_da_faixa` fica **"A_DEFINIR"**.
- **`gerar-grid.py` descarta em silêncio todo f com 3f ≥ n** (`byz*3 >= n and byz != 0`), exatamente o ponto f_max+1. Por isso o bloco
  V-B **não** é gerado por ele: `gerar-celulas-val.py` recusa o bloco `vb` e o `executar-lote.py` também.
- **O que "ruptura em 1/3, tolerância de 1 p.p." significa com n pequeno (a resolução é 1/n, e eu não finjo precisão que n=13 não tem):**
  f/n só assume valores de 1/n. Entre os níveis de f consecutivos o intervalo contém 1/3:

  | n | f_max | f_max/n | (f_max+1)/n | largura do intervalo (p.p.) |
  |---|---|---|---|---|
  | 4 | 1 | 25,0 % | 50,0 % | 25,0 |
  | 7 | 2 | 28,6 % | 42,9 % | 14,3 |
  | 10 | 3 | 30,0 % | 40,0 % | 10,0 |
  | 13 | 4 | 30,8 % | 38,5 % | 7,7 |

  **Com n ≤ 13 o melhor que V-B localiza é a ruptura num intervalo de 7,7 p.p. (n=13), nunca "1 p.p.".** A tolerância de 1 p.p. do
  PROTOCOLO §6 **não é testável** com estes n. O que V-B testa é **igualdade inteira**: `f*` (menor f sem vivacidade) = f_max+1, ou
  seja, ruptura em ((f_max)/n, (f_max+1)/n], que **contém** 1/3 em todos os quatro n. Isso falsifica "ruptura ≠ f_max+1"; **não** confirma
  1/3 com precisão alguma. Para 1 p.p. seria preciso n ≥ 100 (≥ 1/n = 1 p.p.), fora dos níveis do ADR-0015.
- **Medida:** vivacidade por célula = ≥ 1 altura finalizada com `Fmin_s` ≥ 20 s (observador); `L(f)` = fração das 10 sementes vivas.
  **Só vivacidade é medida; a segurança (dois commits conflitantes) NÃO é verificada pelo JABS nesta bateria.**
- **Critério pré-registrado:** **CONSISTENTE** se, nos quatro n, `L(f)=1` para todo f ≤ f_max **e** `L(f_max+1)=0`; **INCONSISTENTE**
  se algum f ≤ f_max tem `L<1` (perda de vivacidade dentro da tolerância) ou se `L(f_max+1)=1` (sem ruptura); **MISTO** se `0 < L(f_max+1) < 1`
  (reporta-se; decide o humano).
- **Previsão honesta de risco (não sei, leitura do código):** a equivocação do voto da réplica bizantina manda o voto real a metade dos
  vizinhos; se o líder cair nessa metade, o voto **conta**. Logo, com f=f_max+1, algumas visões ainda podem formar QC e a ruptura pode ser
  **parcial (MISTO)** ou ausente. Isso **depende do modelo de ataque provisório (B-34, B-35, B-36)**, não de erro. Os conjuntos
  bizantinos **não são aninhados entre valores de f** no HotStuff hoje (sorteio por `RandomnessEngine` dedicado; "mesmo f, não mesmo
  conjunto").
- **T em V-B:** o mesmo da seção 4 (parâmetro de instrumento). Com falha, o critério "zero timeouts" **não se aplica**; o T só fixa o
  tempo de recuperação por visão.

## 4. Parâmetros de instrumento do V-ABC (**"a definir com o humano"**, justificados só por "zero timeouts sem falha")

| n | T usado (s) | de onde vem |
|---|---|---|
| 4 | **0,918** | T candidato do humano (16/11/2026, m=3, L=100) |
| 16 | **1,090** | idem |
| 7, 10, 13 | **1,090** | **não existe T decidido; uso o T do n mais próximo ACIMA (n=16)**. Mais folga que a interpolação linear entre 4 e 16 (≈ 0,96 a 1,06). |
| 31 | **2,065** | **não existe T decidido; uso o T do n mais próximo ACIMA (n=64)**. Interpolação linear entre 16 e 64 daria ≈ 1,48. |
| L=400, n=4 / 16 (só V-C) | **1,023 / 1,829** | T do L=500 do piloto (m=3, sensibilidade), L mais próximo acima |

**Por que "n mais próximo acima" e não interpolação:** o critério do piloto é A1' (zero timeouts espúrios) e ele é **unilateral**: T maior
nunca cria timeout. Um T maior que o necessário não invalida V-A (que mede bytes de caminho feliz) nem V-C. **Efeito colateral:** T grande
**atrasa a recuperação** sob falha (V-B). **Esses valores NÃO são o T final do estudo**; são do instrumento do V-ABC. Se algum n tiver
timeout, a célula **não é excluída** e o veredito vira INCONCLUSIVO (seção 3.2).

## 5. Sementes, replicações, transiente, regime

- **Fórmula:** `semente = semente_base + n·1000 + replicação`; `MVPComparison --seed = semente − n·1000`; observador `--run-seed = semente`.
  A **mesma semente** vale nos dois modos (pareamento) e, em V-B, para todo f.
- **Bases (acima de toda semente já usada, 16 346 a 180 385):** V-A 220 345; V-A por nó 320 345; V-B 420 345; V-C 520 345.
  **Faixas:** 224 346 a 251 350; 324 346 a 351 348; 424 346 a 433 355; 524 346 a 536 350.
- **Colisão:** `checar-sementes-val.py` leu **849 `cfg.json`** sob `experimentos/` (284 sementes distintas já usadas), **0 colisões** com
  as 90 sementes novas, **0 duplicadas entre blocos** (`checagem-sementes-preregistro.json`; a repetição entre modos/f é intencional).
- **Réplicas:** 5 (V-A, V-C), 3 (V-A por nó), 10 (V-B). Justificativa na seção 3.2 (determinismo em bytes; sem IC fingido).
- **Duração:** 120 s (V-A, V-A por nó, V-C), 300 s (V-B). Com `Tb` de 0,13 a ~0,25 s, dá ≥ 400 alturas por célula (n=31 estimado por
  interpolação; **não medido**). Mínimo exigido na integridade: 100 alturas.
- **Transiente:** V-A agregado: a janela de regime do código (alturas 2 a hmax−2) **já exclui** o arranque e as 2 últimas; sem corte extra.
  V-A por nó e V-C: **t0 = 20 s** simulados (escolha, **a definir com o humano**; não derivada de Welch; o ADR-0015 B-29 prevê
  corte único vindo do Welch do piloto, ainda não feito). O agregado **não depende** de t0.
- **Determinismo (verificação extra, declarada):** 4 células são **executadas duas vezes** com a **mesma semente** (`va_individual_n31_s01`,
  `va_aggregated_n7_s01`, `vano_individual_n16_s01`, `vano_aggregated_n31_s01`) em `dados-brutos/determinismo-rep/`, e
  `comparar-determinismo.py` exige saídas idênticas (CSV por bloco, `mvp-results*.csv`, `*-nos.csv`, `*-corrida.csv`, observador). Divergência
  ⇒ INCONCLUSIVO (instrumento). **Não entram nas estatísticas** (mesma semente).

## 6. Custo e paralelismo

Fonte: tempos de parede **medidos** em 290 células antigas (L=100 ROTATING, duração ≥ 100 s; `custo-estimado.py`). Custo por segundo simulado
(2 JVMs): 0,0175 (n=4), 0,0320 (n=16), 0,0493 (n=64), 0,0545 (n=128); **n=7, 10, 13, 31 são interpolação linear** (não medidos).
Eficiência de paralelismo medida: 7,43× com 8 processos (3 092 s de CPU em 416 s de parede; `maxCelulasDeSimulacaoParalelas`=8).

| Bloco | Células | CPU sem a flag | Parede (8 par.) sem a flag | Parede com a flag (pior caso 5,5×) |
|---|---|---|---|---|
| V-A | 50 | 159 s | 21 s | 118 s |
| V-A por nó | 30 | 95 s | 13 s | 71 s |
| V-C | 20 | 59 s | 8 s | 44 s |
| **Total V-A + V-A por nó + V-C** | **100** | **313 s** | **0,7 min** | **3,9 min** |
| (V-B, não executar agora) | 180 | 1 291 s | 174 s | 956 s (≈ 16 min) |

Mais ~2 min de build, 4 repetições de determinismo (≈ 1 min) e análise (< 1 min): **≈ 5 a 8 min no total, muito abaixo de 1 h.**
**O fator 5,5× foi medido no piloto (HEAD anterior) e é de 4 a 5,5× em L=100; com o commit `a65bf83` (ADR-0016) o custo com a flag NÃO foi medido.**
Memória: RSS máximo medido 751 MB (n=128), 8 paralelos de n ≤ 31 cabem com folga; disco ≈ 0,4 MB por célula.
O custo de V-B é **menos certo** (muitos timeouts por visão com líder bizantino; células sem bloco dão rc≠0, como no ataque anterior).

## 7. Etapa 2: comandos exatos (só depois do commit do pré-registro)

Raiz do repositório = diretório de trabalho. `V=experimentos/validacao/hotstuff`.
```
0.  python3 $V/scripts/hash-preregistro.py conferir --artefatos     # exit 0 obrigatório
    git status --short -- $V                                         # sem alteração não commitada
1.  python3 $V/scripts/checar-sementes-val.py                         # exit 0
2.  python3 $V/scripts/preparar-val.py                                # build em /tmp/opencode/val-hs a partir de a65bf83; grava $V/versao-simulador.json
3.  for b in va vano vc: python3 $V/scripts/gerar-celulas-val.py gerar $b --saida $V/celulas/$b --simulador-commit a65bf83e105cdb0d0a7e0526dff06de934f053d5
    python3 $V/scripts/gerar-celulas-val.py conferir-manifesto $b $V/celulas/$b/manifesto.json   # exit 0
4.  python3 $V/scripts/executar-lote.py $V/celulas/$b --saida $V/dados-brutos/$b --paralelo 8     # b = va, vano, vc; sem espiar resultado parcial
5.  rodar as 4 células de determinismo de novo (rodar_celula.py <cfg> $V/dados-brutos/determinismo-rep) e comparar-determinismo.py
6.  python3 $V/scripts/custo-estimado.py                              # só estimativa; o custo real sai de resultado.json
7.  python3 $V/scripts/analisar-val.py $V/dados-brutos/va --vano $V/dados-brutos/vano --vc $V/dados-brutos/vc --saida $V/analise-val.json
```
Depois: `PARECER-TECNICO.json` (campos da skill: `afirmacao`, `impacto`=ALTO, `requisito`, `fontesPrimarias`, `n_celulas_esperadas`=100,
`n_celulas_obtidas`) e entrega ao loop 3. **O parecer não inclui o "porquê está certo"** e não escreve a justificativa para o critic.

## 8. O que é pós-hoc (mistura proibida)

Resultado primário desta rodada = **só as células novas** dos blocos `va`, `vano`, `vc`. **Nada** das rodadas anteriores entra no
veredito (o dado K40 serviu **só** para conferir a fórmula). **Pós-hoc (rotulado e fora do veredito):** outro limiar de materialidade,
outro T, tolerância F/E diferente da declarada, exclusão de célula, regressão com outra transformação, qualquer conclusão sobre n=64 ou
n=128 (não simulados aqui), comparação com Tendermint/PBFT, qualquer número publicado não VERIFICADO.

## 9. Decisões que dependem do humano

1. **Commit do pré-registro** (precedente da K40, commit `06c67b5`). Eu **não commito**. Precisa de commit, **antes de qualquer dado**, de:
   `experimentos/validacao/hotstuff/` (PREREGISTRO.md, PREREGISTRO.sha256, FONTES-V-C.md, config/, scripts/, celulas-esperadas.json,
   checagem-sementes-preregistro.json). Sem isso eu **não** rodo V-A nem V-C.
2. **Qual modo responde por P4a ("V-A em O(n)")?** Previsão pela fórmula: INDIVIDUAL (modo principal do ADR-0015) dá O(n²) em bytes no
   tráfego de consenso (e líder O(n²)); AGGREGATED (fixture de 96 B sem fonte) dá O(n). O veredito mecânico trata **AGGREGATED como o
   que deve bater com o ADR** e **só rotula** a divergência do INDIVIDUAL. Se o humano quiser o INDIVIDUAL como critério de P4a, o ADR
   precisa dizer "O(n) em autenticadores, O(n²) em bytes" (ou o INDIVIDUAL reprova P4a).
3. **T para n=7, 10, 13, 31 (e L=400)**: "n mais próximo acima" (tabela da seção 4). Alternativa: interpolação linear. Marcado "a definir".
4. **Tolerância de V-B**: 1 p.p. **não é testável** com n ≤ 13 (resolução 7,7 a 25 p.p.). Proposta: critério inteiro `f*=f_max+1`
   (seção 3.5). Se o humano exigir 1 p.p., é preciso n ≥ 100 e novo desenho.
5. **Regime do V-A por nó em STABLE** (não ROTATING), e **t0 = 20 s** (não derivado de Welch).
6. **Fontes do V-C**: nenhum número publicado VERIFICADO. Peço os valores de vazão e latência das figuras 4 e 6 de Yin et al., n=4 e
   n=16, **marcados como lidos de figura**, ou autorização para declarar o V-C numérico inviável.
7. **V-B**: só depois de `BYZ-fora-da-faixa-hotstuff` (aprovada) e de novo pré-registro **só do que mudar** (flag do modo). Sugestão: reaproveitar este
   pré-registro e emendar apenas `flag_fora_da_faixa` e o commit do simulador.

## 10. O que NÃO foi verificado (limites)

- Nenhuma célula desta rodada foi simulada; as previsões da seção 3.2 vêm de leitura de código e de **um** ponto conferido (n=64, INDIVIDUAL).
- A fórmula por nó e a de AGGREGATED não foram conferidas com dado.
- Custo de `-XX:hashCode=2` em `a65bf83` (não medido); custo em n=7, 10, 13, 31 (interpolado); duração de n=31 (≥ 400 alturas é estimativa).
- Os T de n=7, 10, 13, 31 não foram verificados com zero timeouts (é o que A1' mede).
- Segurança (commits conflitantes) **não** é medida por V-B. A distância Raft (Fabric) × BFT (JABS) segue valendo (`AGENTS.md`): V-ABC valida
  o **instrumento**, não afirma nada sobre o Fabric.
- Nenhum número publicado de HotStuff foi lido (figuras). Os T de timeout e o m=3 continuam **circulares** com o piloto.
- Independência entre sementes consecutivas não foi verificada (MersenneTwister por semente, como nas rodadas anteriores).
- Java 25 em vez de 17 (alvo do pom). LAN simulada, não rede real.

## 11. Artefatos pré-registrados e SHA-256 (conferidos por `hash-preregistro.py conferir --artefatos`)

Caminhos relativos à raiz do repositório. O hash do próprio `PREREGISTRO.md` fica em `PREREGISTRO.sha256` (não pode estar dentro dele).
Os arquivos de `experimentos/piloto-timeout-hotstuff/` e `scripts/jabs/` abaixo **não aparecem em `git status`** (nem como modificados nem como não rastreados);
não consegui rodar `git ls-files` para confirmar que estão rastreados (o comando foi recusado pela permissão), então **o orquestrador deve conferir isso antes do commit**.

| arquivo | SHA-256 |
|---|---|
| `experimentos/validacao/hotstuff/config/fatores-va.json` | `00a60969117ca89f33573e69b3aadc8fb207614fd8def24e2d08822b5f6beef9` |
| `experimentos/validacao/hotstuff/config/fatores-va-no.json` | `da88488afea17dc39899c4257d4229676bf79572a2ad58be2e783d0263aeef14` |
| `experimentos/validacao/hotstuff/config/fatores-vb.json` | `545387b84eeed689f5f684887fc2930930155cd64c33cebc58ce9f03dfd7b4b1` |
| `experimentos/validacao/hotstuff/config/fatores-vc.json` | `73e2a9057fee4eb8e8ecaf25f9e0c0371bbf91e33a2ff89a13f59210116e9d41` |
| `experimentos/validacao/hotstuff/celulas-esperadas.json` | `9c3e95c43ce7cfc2b000800f2b1857335ebd656f7ea7f831933ca8e3dc9397a8` |
| `experimentos/validacao/hotstuff/checagem-sementes-preregistro.json` | `da549e637169f968c04f7383b446448427be6d75fca9bb10ce80d77ee45fcb91` |
| `experimentos/validacao/hotstuff/FONTES-V-C.md` | `9d4e55203e57229949f9423b32be61861b1100baa631bc65bf1e3ee2dfd775d7` |
| `experimentos/validacao/hotstuff/scripts/formula-analitica.py` | `0656a72acb3365cd591fc1293758727992c701fdc31fb75bb33c1d1d68aa59c8` |
| `experimentos/validacao/hotstuff/scripts/gerar-celulas-val.py` | `df1669983b73e391490cc0d172d90ac2a26c2f24526cce12700a029f8adccbc3` |
| `experimentos/validacao/hotstuff/scripts/checar-sementes-val.py` | `98c3e7e580f7ecd6194f2b8512764e12f711fa21a7ce261fab23d82e830c5c2b` |
| `experimentos/validacao/hotstuff/scripts/preparar-val.py` | `ea9e9272cc356c9e520c0ecae4972a310d02758cd4f64a6e503f548c5d7cbd16` |
| `experimentos/validacao/hotstuff/scripts/rodar_celula.py` | `62d52bb270e393407470c453588743391c7288ab71d4849dff41eb9133b5cac8` |
| `experimentos/validacao/hotstuff/scripts/executar-lote.py` | `6bc30029e9f001b3eba3189bb17b60a3f5e49c026989e0aecb9c089c3b26ef31` |
| `experimentos/validacao/hotstuff/scripts/analisar-val.py` | `131ae8a5a7454c792cad02bafd6c453cb4ac391cd76da6210cc9c8af13c947d8` |
| `experimentos/validacao/hotstuff/scripts/comparar-determinismo.py` | `b8fce6c8b1dd70c6c874a449e4772e3561e66bea2f86589eb996bcdf454fb8b8` |
| `experimentos/validacao/hotstuff/scripts/custo-estimado.py` | `4ca0afaafe109cee243420a5fba4d18a185155eb0df329dcf1ab002ded986a15` |
| `experimentos/validacao/hotstuff/scripts/hash-preregistro.py` | `f8889ada52f05b5da32130a1f56675d4ad756b95342b52a1d84aa150ede00f96` |
| `experimentos/validacao/hotstuff/scripts/dry-run-comandos.py` | `a56f2080a70eca65da71d96b75b3b7fc8ef44066557138adcf9c6ca8f0279958` |
| `experimentos/validacao/hotstuff/scripts/sondagem-leitura.py` | `a5c32187056d9a1b989c30ae6cc783f2128b83970ec9872d6307f2324ba50218` |
| `experimentos/piloto-timeout-hotstuff/scripts/executar-lote.py` | `885c101be4ccd2672430a5957fb36e986f790e50ac789e5896942f1d1b78d593` |
| `experimentos/piloto-timeout-hotstuff/scripts/rodar_celula.py` | `98fdb32c3511f75b349f3f24cb98ba23b1c876f12e689b4bb95cbb43fd2465f2` |
| `experimentos/piloto-timeout-hotstuff/harness/ObservadorHotStuff.java` | `6abfcd570435773642293a2aa4128e319ea8565fae1ae87c565e2e197c3270b4` |
| `scripts/jabs/gerar-grid.py` | `7fb2a83eefb5f642f1934281238579c0e762cad5302620512237e646616241fd` |
| `scripts/jabs/validar-complexidade.py` | `e229f38d1ea25d614ca976da0f6f6620c6914be0c485bb4646d436b4f8f684a8` |

---

## Emendas antes dos dados, 16/11/2026

**Etapa 1b. Nenhuma célula desta rodada foi simulada; nenhum dado desta rodada foi visto.** As mudanças abaixo são legítimas por isso
e valem o registro datado. **Nada acima foi apagado**: o texto da v1 (SHA-256 `17a30899d3fbfc67e4bf64a85e129b7c2976a3370a24f2ccc89daa3bfde85617`)
está arquivado byte a byte em `PREREGISTRO.v1-original-2026-11-16.md` (hash em `PREREGISTRO.v1-original.sha256`). **Precedência:** onde uma
emenda contradiz o texto acima, **vale a emenda**; o texto acima fica como histórico. A tabela de artefatos da seção 11 é **histórica**;
a vigente é a do fim desta seção.

O que foi executado depois da v1, e que não gera dado desta rodada: leitura de código; cálculo da fórmula (`emenda-1b-prever.py`);
`javac` do observador novo contra o build de fumaca (só compilação); sorteio dos ids bizantinos do V-B pelo próprio simulador
(`--listar-byz-faixa`: só sorteia e imprime, não constrói cenário); geração de células em `/tmp` e dry-run dos comandos; autoteste do
analisador sobre dado **sintético derivado da fórmula**; conferência de sementes. **Nenhuma JVM de simulação foi executada.**
Decisões do humano em 16/11/2026, aplicadas literalmente; o que é meu está marcado como tal.

### E1. Modo que responde por P4a: INDIVIDUAL (decisão 1)

**Decisão do humano:** o modo INDIVIDUAL responde por P4a. O resultado esperado e o critério de V-A para o HotStuff em INDIVIDUAL é
**"O(n) em autenticadores e O(n²) em bytes"** (previsão que eu fiz pela fórmula do QC com 2f+1 assinaturas, seção 1).
AGGREGATED fica **só como sensibilidade rotulada** (tamanho agregado BLS de 96 B é fixture sem fonte). **AGGREGATED fica DENTRO desta
rodada** (50 + 30 células já pré-registradas, das quais 25 + 15 em AGGREGATED), rotulado, **sem decidir nada**: não gera INCONSISTENTE
nem INCONCLUSIVO. Se o humano quiser tirá-lo da rodada, saem 40 das 90 células executáveis.

**Ordem esperada declarada antes dos dados, por métrica** (granularidade: **agregado de rede por bloco decidido**, janela de regime
`CbRedeMedida`: alturas 2 a hmax−2; soma sobre todos os nós receptores; nunca autoentrega; n ∈ {4, 7, 10, 16, 31}; 5 sementes):

| Métrica | Coluna / fonte | Fórmula (INDIVIDUAL) | n=4, 7, 10, 16, 31 | b previsto | Classe esperada | Papel |
|---|---|---|---|---|---|---|
| **Autenticadores** (contagem de mensagens) | `rede_msgs` = média por altura de `NetworkHeightLedger.Cell.getPackets()` somada sobre os nós (`obs/pacotes-por-altura.csv.gz`) | 8(n−1) = 4(n−1) proposta/QC + 3(n−1) votos + (n−1) NEW_VIEW com QC | 24, 48, 72, 120, 240 | 1,120 | **O(n)** | **DECIDE** (A1) |
| **Bytes agregados de consenso** (sem a carga do bloco) | `CbRedeConsenso_avg_B` | 3(n−1)V + 4(n−1)(H+O+Q) + (n−1)Q, com Q ∝ q=2f+1 | 4 983; 13 806; 26 514; 63 390; 223 080 | 1,856 | **O(n²)** | **DECIDE** (A2) |
| Bytes agregados totais (com a carga do bloco) | `CbRede_avg_B` | consenso + (n−1)·51 200 | 158 583; 321 006; 487 314; 831 390; 1 759 080 | 1,170 | O(n) até n=31 | **só rótulo** |

- **Por que as duas ordens diferem (fórmula, não dado):** cada QC leva q=2f+1 assinaturas de 64 B, e o QC vai a n−1 nós em 5 mensagens por
  bloco: o número de **assinaturas individuais** por bloco é 3(n−1) + 5(n−1)q = 54; 168; 342; 870; 3 240 (b=1,998, O(n²)); o número de
  **autenticadores no sentido do artigo** (QC conta como um) é 8(n−1), O(n). Essa coluna de assinaturas é **só fórmula**, **não é medida
  pelo simulador** e não decide.
- **Leitura que eu fiz de "O(n²) em bytes" (marcada, ver pontos abertos):** são os **bytes de consenso** (sem a carga do bloco). Os
  bytes **totais** têm a carga (n−1)·51 200 B, que é linear em n e domina até n=31 (o termo quadrático vale 11,7 % em n=31): a fórmula
  prevê **O(n)** neles. Eles são reportados e rotulados, e **não entram no critério**. Se o humano quis dizer bytes totais, o critério
  falha **por construção** no n deste desenho.
- **Critério mecânico de aceite (V-A, bloco `va`, INDIVIDUAL):** `b` = inclinação OLS de ln(métrica) em ln(n) sobre as médias por n
  (5 pontos). Classes: b < 0,5 O(1); 0,5 ≤ b < 1,5 O(n); **1,5 ≤ b < 2,5 O(n²)**; b ≥ 2,5 "> O(n²)".
  - **A1:** `rede_msgs` com 0,5 ≤ b < 1,5 **e** `|medido − fórmula| / fórmula ≤ 0,001` em **toda** célula íntegra (a contagem é inteira, então é igualdade).
  - **A2:** `rede_consenso` com 1,5 ≤ b < 2,5 **e** fidelidade ≤ 0,001 em toda célula íntegra.
  - **CONSISTENTE** = integridade (seção 3.2, I) + A1 + A2. **INCONSISTENTE** = I ok e (A1 ou A2 falha). **INCONCLUSIVO (instrumento)** = célula
    INDIVIDUAL faltante, extra, não íntegra, com timeout, ou n sem célula íntegra. Este veredito **não é aprovação**: o loop 3 (humano) decide.
  - **Integridade acrescentada (só para `rede_msgs`):** `pacotes-por-altura.csv.gz` com exatamente `regime_heights` alturas e a média por altura
    dos bytes totais do observador igual (±0,01) ao `CbRede_avg_B` do `MVPComparison`. Divergência ⇒ a célula não é íntegra.
- **Ressalva de previsão:** a fórmula em **bytes** foi conferida com dado em **um** ponto (K40, n=64, INDIVIDUAL, erro 0). A fórmula de
  **pacotes** (8(n−1)) **não foi conferida com dado algum**: se falhar, o resultado é "INCONSISTENTE com a fórmula" e o loop 3 separa
  "erro da fórmula" de "bug do simulador" (seção 0, item 2). Margem do ponto médio: msgs 0,38 do limite 1,5; consenso 0,36 de 1,5 e 0,64 de 2,5.
- **AGGREGATED (sensibilidade, rotulado):** previsto `rede_msgs` b=1,120 O(n); `rede_consenso` b=1,126 O(n); `rede_total` b=1,120 O(n); número de
  assinaturas 8(n−1), O(n). Reporta-se b, classe e fidelidade; **nada disso decide**.
- **V-A por nó (bloco `vano`, secundário, STABLE):** INDIVIDUAL decide só a classe prevista pelo código (líder O(n²), réplica O(1));
  infidelidade por nó só rotula (como na seção 3.3). AGGREGATED rotulado.
- **Muda no critério de V-A em relação à v1:** ver o quadro "Diff de decisões de V-A" abaixo.
### E2. T aceitos (decisão 2)

Os T da seção 4 ficam **como estão** e passam de "a definir com o humano" para **"aceito pelo humano em 16/11/2026"**: n=4 → 0,918 s;
n=7, 10, 13, 16 → 1,090 s; n=31 → 2,065 s; L=400 → T do L=500 do piloto (n=4 1,023 s; n=16 1,829 s). O L=400 deixa de ser executado
(E4), então o T de L=400 fica aceito mas **sem uso nesta rodada**. **A condição de zero timeouts sem falha continua como aceite do
instrumento** (A1′): qualquer célula íntegra com `timeouts_disparados_total` > 0 ⇒ INCONCLUSIVO (instrumento) em INDIVIDUAL. Esses T
**não** são o T final do estudo. O mesmo vale para o corte de transiente t0 = 20 s (V-A por nó e V-C): continua como escolha do pré-registro,
**não** foi tema da decisão do humano, e **fica como ponto aberto** (seção 9, item 5, não respondida).

### E3. Tolerância de V-B (decisão 3)

**Aceito o critério inteiro "a ruptura ocorre em f\* = ⌊(n−1)/3⌋ + 1"** (menor f sem vivacidade). **Registro:** a resolução de 1 p.p. **não é
testável** com n ≤ 13 (a menor largura do intervalo que contém 1/3 é 7,7 p.p., em n=13; seção 3.5); V-B falsifica "ruptura ≠ f_max+1", não confirma
1/3 com precisão. O critério CONSISTENTE / INCONSISTENTE / MISTO da seção 3.5 fica como está.

### E4. V-C numérico declarado INVIÁVEL (decisão 4)

**O humano declarou o V-C numérico INVIÁVEL**: nenhum número VERIFICADO de Yin et al. (2019); figuras ilegíveis. Fica **só o teste
ordinal fraco**: em L=100, `Tb(n=16) > Tb(n=4)` **e** `vazão(n=16) < vazão(n=4)`, como **registro qualitativo, rotulado como NÃO sendo
confronto numérico** (a saída do analisador traz esse rótulo). Passar ou falhar **não valida magnitude** e não entra no veredito de V-A.
- **Removido (não executado):** o L=400 (era o lote da varredura de n do artigo, só útil ao confronto numérico). **V-C passa de 20 para 10
  células** (n ∈ {4, 16}, L=100, INDIVIDUAL, 5 sementes). `fatores-vc.json` e `celulas-esperadas.json` já refletem isso; o T do L=400 e o
  que seriam as 10 células ficam em `L400_nao_executado`.
- **`FONTES-V-C.md` fica intacto** como registro da busca (hash inalterado).
- O pedido da seção 9, item 6 (números das figuras 4 e 6) **fica encerrado** pela decisão do humano.

### E5. Simulador pinado: 76932b9 (decisão 5)

- **Commit fixo: `76932b925719a2b83d6165b5a84d30f5501896ee`** (HEAD no momento da emenda; inclui `--allow-beyond-bft-threshold`, aprovada pelo
  critic; **dentro da faixa a saída é byte a byte idêntica à de `a65bf83`**, segundo o humano; **eu não reverifiquei isso aqui**, pois não simulei). Substitui `a65bf83` em
  **todos** os lugares: `preparar-val.py` (commit e tree), `custo-estimado.py`, `config/*.json` (campo `simulador_commit` vem do argumento), comandos da seção 7, e `versao-simulador.json` (gerado na etapa 2).
- **Hashes recomputados (por `git rev-parse`):** `src/` tree `a1b464ee983b78a2a6b6de7166a6e6460839b8af` (antes `8c7d88e8…`);
  `pom.xml` blob `32f934e332097d6bfed46830ff03132c36763670` (igual); `ObservadorHotStuff.java` do piloto blob `4024e15b53b8939458c22ae9d04f107b422e3cd0` (igual).
  Só `MVPComparison.java` (blob `ac9f6cb5…`, antes `f6ba4369…`) mudou em `src/main`, além do teste novo e de `scripts/jabs/nr_byz_fora_faixa.py` (fora de `src/`).
- **`git status --short src pom.xml` = vazio** na data da emenda: **não há mudança não commitada em `src/` nem em `pom.xml`**. A declaração da v1 (seção 2)
  sobre mudanças não commitadas de outro agente **deixou de valer**: aquelas mudanças são o próprio `76932b9`.
- **Observador compilado muda (declarado):** para medir `rede_msgs` (E1) o analisador precisa de pacotes por altura, que o observador do piloto não
  grava. `harness/ObservadorVal.java` é o `ObservadorHotStuff.java` do piloto (SHA-256 conferido `6abfcd57…`) **mais** uma função que lê o
  `NetworkHeightLedger` (API pública; **não altera a simulação**) e grava `obs/pacotes-por-altura.csv.gz`; renomeia a classe. Gerado por
  `gerar-observador-val.py`, compilado em teste (`javac rc=0`), pinado por SHA-256 em `preparar-val.py`. **O harness não está em `src/`** e
  **não foi simulado**; que ele não perturba a saída é **verificado na etapa 2** pela igualdade do MD5 do CSV por bloco entre as duas JVMs (critério I) e pela
  conferência dos bytes do observador contra `CbRede_avg_B`.
- **Custo do `-XX:hashCode=2` em 76932b9:** continua **não medido** (herdado de `a65bf83`; seção 10).

### E6. V-B: continua pré-registrado e BLOQUEADO; pergunta aberta (decisão 6)

**Isto ainda NÃO é decisão do humano.** V-B segue pré-registrado (180 células, seção 3.5) e **bloqueado: não executar**. **Nota exata do que bloqueia:**
a regra do ataque EQUIVOCATION (`HotStuff.requireEquivocationSplit`) exige ≥ 2 vizinhos e ≥ 1 destinatário honesto em **cada** metade dos vizinhos
(`half = max(1, (n−1)/2)`, vizinhos = os outros ids em ordem crescente); se um nó bizantino não cumpre, lança `IllegalStateException` e **a célula
fora da faixa pode não rodar** por regra do ataque (não por falha do protocolo), e isso **depende da semente**. A flag `--allow-beyond-bft-threshold`
já existe, mas **não resolve isso**. **Não proponho mudar o ataque.**

**Células afetadas (previsão por script, sem simular; `vb-celulas-bloqueadas.json`, `listar-vb-bloqueadas.py`):** o sorteio dos ids bizantinos vem do
próprio simulador (`ByzantineConfig.withExactCount`, mesmas sementes do pré-registro) e a regra é aplicada por aritmética sobre o código lido. Das 140 células com f ≥ 1:
**4 abortam, todas em n=4, f=2 (fora da faixa), sementes de replicação 3, 4, 7 e 9** (ids `vb_individual_n4_f02_s03`, `_s04`, `_s07`, `_s09`; sementes 424 348, 424 349, 424 352 e 424 354; bizantinos {0,2}, {0,3}, {0,2}, {0,1});
n=4 f=2 roda nas outras 6. **Nenhuma** célula de n=7, 10 ou 13 aborta pela regra nas sementes pré-registradas, **inclusive n=7 f=3**.
**Limite:** é previsão por leitura de código. O humano relatou que n=7 f=3 depende da semente; **este sorteio não a reproduz** (0/10 nas minhas sementes), o que só diz que, **nestas** sementes, a regra
não dispara; **não** confirma nada sobre a rede inteira, e outros modos de falha (nenhum bloco finalizado ⇒ rc ≠ 0) só aparecem simulando.
**Pergunta aberta ao humano:** manter V-B bloqueado até decidir o que fazer com as células que abortam (excluir como "não executável", trocar semente, mudar a regra do ataque). **Nenhuma** dessas opções está decidida.
A flag de V-B em `fatores-vb.json` passa de `A_DEFINIR` para a existente, mas V-B continua **proibido de rodar**.

### Diff de decisões de V-A causado pelo item 1

| Aspecto | v1 (13/11 a 16/11, antes da emenda) | Emenda 1b |
|---|---|---|
| Modo que responde por P4a | indefinido (pergunta ao humano, seção 9.2) | **INDIVIDUAL** |
| O que se espera do HotStuff em INDIVIDUAL | só "rótulo não decisório" se ≠ O(n) do ADR | **"O(n) em autenticadores e O(n²) em bytes"**, **decide** |
| Métrica de autenticadores | não existia | **`rede_msgs`** (pacotes por bloco; b prev. 1,120; O(n)) |
| Bytes que decidem | `rede_total` e `rede_consenso`, classe contra a **previsão da fórmula** | só **`rede_consenso`** O(n²); `rede_total` vira **rótulo** |
| AGGREGATED | **decidia** (tinha de bater O(n) do ADR; INCONSISTENTE se não) | **só sensibilidade rotulada**; nunca INCONSISTENTE nem INCONCLUSIVO |
| Classe | b ≥ 1,5 era sempre O(n²) | 1,5 ≤ b < 2,5 O(n²); b ≥ 2,5 "> O(n²)" |
| Integridade | rc, MD5, zero timeouts, ≥ 100 alturas | **+** pacotes-por-altura com `regime_heights` alturas e bytes do observador = `CbRede_avg_B` ± 0,01 |
| INCONCLUSIVO por célula | qualquer modo | **só INDIVIDUAL** (AGGREGATED só rotula) |
| Células de V-A (`va` + `vano` + `vc`) | 50 + 30 + 20 = **100** | 50 + 30 + **10** = **90** |
| Instrumento | `a65bf83`; observador do piloto | **`76932b9`**; `ObservadorVal` (observador do piloto + pacotes por altura) |
| T | "a definir com o humano" | **aceito pelo humano em 16/11/2026** |

### Custo atualizado (E4 e E5)

Estimado por `custo-estimado.py` (tempos de parede medidos em 290 células antigas; n=7, 10, 13, 31 interpolados; 5,5× do `hashCode=2` medido no HEAD anterior, **não** em 76932b9):

| Bloco | Células | CPU sem a flag | Parede (8 paralelos) sem a flag | Parede com a flag (pior caso 5,5×) |
|---|---|---|---|---|
| V-A | 50 | 159 s | 21 s | 118 s |
| V-A por nó | 30 | 95 s | 13 s | 71 s |
| V-C (só L=100) | **10** (antes 20) | **30 s** (antes 59 s) | **4 s** (antes 8 s) | **22 s** (antes 44 s) |
| **Total** | **90** (antes 100) | **284 s** (antes 313 s) | **0,6 min** (antes 0,7) | **3,5 min** (antes 3,9) |
| (V-B, não executar) | 180 | 1 291 s | 174 s | 956 s |

Acrescentam-se ~2 min de build, 4 repetições de determinismo (≈ 1 min) e análise: **≈ 5 a 8 min no total**, abaixo de 1 h. O observador novo tem um custo extra de
escrita por altura que **não foi medido**. Removeram-se **10 células** de V-C numérico (100 → 90).

### Sementes (conferido por script)

`checar-sementes-val.py` leu **849 `cfg.json`** sob `experimentos/` (284 sementes distintas já usadas, **incluindo as 80 `cfg.json` da K40**, faixa 116 346 a 180 385):
**0 colisões** com as 90 sementes novas (faixa 224 346 a 536 350), **0 duplicadas entre blocos**. Saída em `checagem-sementes-emenda-1b.json`. As sementes não mudaram com a emenda
(só o L=400 saiu; reaproveitava as do L=100).

### Comandos da etapa 2 que mudam (substituem os da seção 7)

```
V=experimentos/validacao/hotstuff
2.  python3 $V/scripts/preparar-val.py          # git archive de 76932b92...; confere tree a1b464ee..., pom 32f934e3..., ObservadorVal (sha256); grava versao-simulador.json
3.  for b in va vano vc: python3 $V/scripts/gerar-celulas-val.py gerar $b --saida $V/celulas/$b --simulador-commit 76932b925719a2b83d6165b5a84d30f5501896ee
    python3 $V/scripts/gerar-celulas-val.py conferir-manifesto $b $V/celulas/$b/manifesto.json
```
Os demais passos (0, 1, 4 a 7) ficam como na seção 7; `vc` agora tem 10 células e o `PARECER-TECNICO.json` usa `n_celulas_esperadas` = **90**.

### Pontos ainda abertos (não decididos)

1. **"O(n²) em bytes" = bytes de consenso (sem a carga) ou bytes totais?** Eu apliquei **consenso** (E1). Em bytes totais a fórmula prevê O(n) até n=31, e o critério falharia por construção.
2. **V-B: o que fazer com as 4 células de n=4 f=2 que abortam** pela regra do ataque, e o que fazer com a dependência de semente relatada para n=7 f=3 (não reproduzida nas sementes pré-registradas).
3. **t0 = 20 s** (V-A por nó e V-C) continua sem decisão (não derivado de Welch).
4. **AGGREGATED dentro desta rodada ou fora:** deixei dentro, rotulado (E1); retirar economiza 40 células.
5. **Commit do pré-registro emendado** (precedente `06c67b5`) **antes** de qualquer dado: o orquestrador commita; este agente não commita. `experimentos/validacao/` ainda está **não rastreado** no git.
6. **Custo de `hashCode=2` em 76932b9** não medido; **fórmula de pacotes 8(n−1)** nunca conferida com dado.

### Artefatos vigentes (SHA-256) apos a Emenda 1b

A tabela da seção 11 é histórica (v1). A que `hash-preregistro.py conferir --artefatos` confere é **esta**.

| arquivo | SHA-256 |
|---|---|
| `experimentos/validacao/hotstuff/PREREGISTRO.v1-original-2026-11-16.md` | `17a30899d3fbfc67e4bf64a85e129b7c2976a3370a24f2ccc89daa3bfde85617` |
| `experimentos/validacao/hotstuff/config/fatores-va.json` | `eab3e6770ef227adc3e146c65a94c8f9856aebb27558227abc57de16e15e89ef` |
| `experimentos/validacao/hotstuff/config/fatores-va-no.json` | `be2eb98dac6ef0f36813fc3431edfad7377fe1d0d3865a801e91fa8e13cb16ad` |
| `experimentos/validacao/hotstuff/config/fatores-vb.json` | `daba17377f5e2cc28a5a04b6841a4393427ccfb12ea4902d7c996b87ee0a0ad2` |
| `experimentos/validacao/hotstuff/config/fatores-vc.json` | `b02eb6f8ba0afecb0ce17b6a2aa13f5141195557ee74e07c2ea44822c8895183` |
| `experimentos/validacao/hotstuff/celulas-esperadas.json` | `896c7193ee1b67ff309792cfb40de83741cdb0ba52bd6e59d5a3e53d1909fbc2` |
| `experimentos/validacao/hotstuff/checagem-sementes-emenda-1b.json` | `da549e637169f968c04f7383b446448427be6d75fca9bb10ce80d77ee45fcb91` |
| `experimentos/validacao/hotstuff/vb-celulas-bloqueadas.json` | `4790f09849a403842293ab192b768abe5f9356b9bcff36b26878fb8afab787cd` |
| `experimentos/validacao/hotstuff/emenda-1b-previsoes.json` | `54847cab9e865973b1723a24ffe2a7c000a16f30c514c5020aa06867f4acc124` |
| `experimentos/validacao/hotstuff/custo-estimado.json` | `4f1f07be49aff23ed8ed735714b24a6e0c9bff98d89bdd5c313c48eb9f45098f` |
| `experimentos/validacao/hotstuff/FONTES-V-C.md` | `9d4e55203e57229949f9423b32be61861b1100baa631bc65bf1e3ee2dfd775d7` |
| `experimentos/validacao/hotstuff/harness/ObservadorVal.java` | `21aa3f9e7669765ba32ff91f4cadd8e7b28c364d8989a27d6a850c7e9d61e089` |
| `experimentos/validacao/hotstuff/scripts/formula-analitica.py` | `0cbf3675fc8014bb8171b9d3d047b7b9feed38221a36ae0bcb5b86d82b5abc9d` |
| `experimentos/validacao/hotstuff/scripts/gerar-celulas-val.py` | `df1669983b73e391490cc0d172d90ac2a26c2f24526cce12700a029f8adccbc3` |
| `experimentos/validacao/hotstuff/scripts/checar-sementes-val.py` | `98c3e7e580f7ecd6194f2b8512764e12f711fa21a7ce261fab23d82e830c5c2b` |
| `experimentos/validacao/hotstuff/scripts/preparar-val.py` | `eba9f7b09f9c211835033eca464685e6ccd99d2dfb13422b7463290577537a0d` |
| `experimentos/validacao/hotstuff/scripts/rodar_celula.py` | `4b5f8ef1c8ebd8f3d7c035db05e3ce4b3ba6aeb5edc7583c607f2bfc3d4b9a98` |
| `experimentos/validacao/hotstuff/scripts/executar-lote.py` | `6bc30029e9f001b3eba3189bb17b60a3f5e49c026989e0aecb9c089c3b26ef31` |
| `experimentos/validacao/hotstuff/scripts/analisar-val.py` | `0f02df1b668c52ef47579bdfb3f8c7d0b146d1386f9857120d0c9f5b8216f804` |
| `experimentos/validacao/hotstuff/scripts/comparar-determinismo.py` | `463ca1dafaecfccfa4bea7941a77bddb4ade43aad4b2e517284f519cae0b58f2` |
| `experimentos/validacao/hotstuff/scripts/custo-estimado.py` | `57c9ecc39d32dde724e5f33c7cebb38b71b2e62e99ce3897804daac56fc63a20` |
| `experimentos/validacao/hotstuff/scripts/hash-preregistro.py` | `96bbb7f493d47b41772c9a1c96b15845eafe5d74011d4bbce802cfbb6190f9d1` |
| `experimentos/validacao/hotstuff/scripts/dry-run-comandos.py` | `a56f2080a70eca65da71d96b75b3b7fc8ef44066557138adcf9c6ca8f0279958` |
| `experimentos/validacao/hotstuff/scripts/sondagem-leitura.py` | `a5c32187056d9a1b989c30ae6cc783f2128b83970ec9872d6307f2324ba50218` |
| `experimentos/validacao/hotstuff/scripts/emenda-1b-prever.py` | `78b6b609cbaeb072cb6247c7b47ae795c9bf24443c9d3728dc0db9c6672da674` |
| `experimentos/validacao/hotstuff/scripts/arquivar-original.py` | `ba3f6b26ae7114de54a33bce21dd2f2e97d6d5195ba7e6a226f2defe50450313` |
| `experimentos/validacao/hotstuff/scripts/gerar-observador-val.py` | `6997c3ebc0c8bebe9d42189458b1d4955d6e96ac26fb26a84b437dc9e6dfef37` |
| `experimentos/validacao/hotstuff/scripts/listar-vb-bloqueadas.py` | `ffe8b7c354dfc94d70e3a562a8105d29afdc50d3e3c75e370f65a4ba89b74197` |
| `experimentos/validacao/hotstuff/scripts/montar-tabela-emenda.py` | `45870e773cbfb3db9e5874bf0307fdd98c8a5ca62a8628f5dce5d61b8dcd4cd5` |
| `experimentos/piloto-timeout-hotstuff/scripts/executar-lote.py` | `885c101be4ccd2672430a5957fb36e986f790e50ac789e5896942f1d1b78d593` |
| `experimentos/piloto-timeout-hotstuff/scripts/rodar_celula.py` | `98fdb32c3511f75b349f3f24cb98ba23b1c876f12e689b4bb95cbb43fd2465f2` |
| `experimentos/piloto-timeout-hotstuff/harness/ObservadorHotStuff.java` | `6abfcd570435773642293a2aa4128e319ea8565fae1ae87c565e2e197c3270b4` |
| `scripts/jabs/gerar-grid.py` | `7fb2a83eefb5f642f1934281238579c0e762cad5302620512237e646616241fd` |
| `scripts/jabs/validar-complexidade.py` | `e229f38d1ea25d614ca976da0f6f6620c6914be0c485bb4646d436b4f8f684a8` |




---

## E7. V-B liberado (decisão do humano 16/11/2026)

**Etapa 3a. Nenhuma célula de V-B foi simulada e nenhum dado de V-B existe ou foi visto.** O texto da v2 (v1 + Emenda 1b, SHA-256
`bf1d85f78a1f70e5f74b368e836a80b32cd3e065e3c0c834d37d0cfc44ed4ec2`) está arquivado byte a byte em
`PREREGISTRO.v2-emenda-1b-2026-11-16.md` (hash em `PREREGISTRO.v2-emenda-1b.sha256`); esta seção **só anexa** (`hash-emenda-e7.py` confere que o
arquivo novo começa pelos mesmos bytes da v2). **Precedência:** onde a E7 contradiz a seção 3.5, a E6 ou os comentários de `fatores-vb.json`
("BLOQUEADO", "A_DEFINIR", "pergunta aberta"; o arquivo não foi editado porque seu hash está pinado), **vale a E7**. A E6 deixa de estar em aberto.
**A E7 não altera nenhum critério, limiar, semente, T nem script de V-A ou V-C**: dados e parecer da etapa 2 estão commitados (`26c98f6`) e nenhum
arquivo deles foi tocado (`hash-preregistro.py conferir --artefatos` segue conferindo os hashes da Emenda 1b, todos inalterados, mais a tabela E7 abaixo).

**O que foi executado nesta etapa, e que não gera dado de V-B:** leitura de código (`HotStuff.java`, `MVPComparison.java`, `ObservadorVal.java` em
`76932b9`); sorteio dos ids bizantinos pelo próprio simulador pinado (`ObservadorVal --listar-byz-faixa`: só sorteia e imprime, não constrói cenário);
enumeração exaustiva de conjuntos bizantinos por aritmética; geração das 180 configurações em `/tmp` (não em `celulas/vb`) e conferência contra
`celulas-esperadas.json`; autoteste de `analisar-vb.py` sobre dado **sintético fabricado pelo próprio script** (não é saída do simulador); conferência de
que o build de V-A/V-C é o commit pinado; leitura de `resultado.json` de V-A/V-C (já commitados) para estimar custo. **Nenhuma JVM de simulação foi executada.**

**Decisões do humano em 16/11/2026, aplicadas literalmente:** P4a fechado (O(n) em pacotes/mensagens por bloco e O(n²) em bytes de consenso, INDIVIDUAL;
contagem de assinaturas nos QC não medida, só fórmula); bytes de consenso decidem; 5 valores de n bastam; t0 = 20 s aceito; V-B: **manter a regra de
ataque `requireEquivocationSplit`** (não mudar o ataque); tolerância de V-B: critério inteiro "a ruptura ocorre em f\* = ⌊(n−1)/3⌋ + 1".
Interpretação minha, a confirmar: "5 valores de n bastam" refere-se a V-A (n ∈ {4, 7, 10, 16, 31}); **V-B fica com os 4 valores de n já pré-registrados (4, 7, 10, 13)**.

### E7 (a) Células que abortam pela regra do ataque: classe própria, definida antes dos dados

**Previsão (script, sem simular) e sua independência da semente.** `listar-vb-e7.py` refaz o sorteio com o build pinado e **confere igual** à Emenda 1b
(0 diferenças nas 140 células com f ≥ 1). Além disso enumera **todos** os conjuntos bizantinos possíveis e aplica a regra lida em
`HotStuff.requireEquivocationSplit` (vizinhos = outros ids em ordem crescente; `half = max(1, (n−1)/2)`; ≥ 1 honesto em cada metade): **só n=4, f=2 tem
conjunto que aborta: 3 dos 6 pares possíveis, exatamente os que contêm o nó 0** ({0,1}, {0,2}, {0,3}). Para n=7 (f de 1 a 3), n=10 (1 a 4) e n=13 (1 a 5),
**nenhum** conjunto aborta, **para qualquer semente**. A previsão de 4 abortos em n=4, f=2 **continua válida** no simulador pinado `76932b9`:

| id | semente | bizantinos |
|---|---|---|
| `vb_individual_n4_f02_s03` | 424 348 | {0, 2} |
| `vb_individual_n4_f02_s04` | 424 349 | {0, 3} |
| `vb_individual_n4_f02_s07` | 424 352 | {0, 2} |
| `vb_individual_n4_f02_s09` | 424 354 | {0, 1} |

As outras 6 células de n=4 f=2 são previstas **executáveis**: sementes 424 346 ({2,3}), 424 347 ({1,3}), 424 350 ({2,3}), 424 351 ({1,2}), 424 353 ({1,3}),
424 355 ({1,3}). **Limite que isto impõe:** essas 6 cobrem só **3 conjuntos bizantinos distintos** ({1,2} 1×, {1,3} 3×, {2,3} 2×), todos com o nó 0 honesto.
A seleção por regra do ataque **não é aleatória**: o veredito de n=4, f=2 vale **condicionado a "nó 0 honesto"**, e isso vai dito no relatório. O relato do
humano de que n=7, f=3 depende da semente **não é reproduzido** pela regra lida (0 de 35 conjuntos); fica nos pontos abertos.

**Classe própria `NAO_EXECUTAVEL_POR_REGRA_DO_ATAQUE`:**

> Célula cujo `MVPComparison` e cujo observador abortam com a `IllegalStateException` de `HotStuff.requireEquivocationSplit` (critério mecânico em E7 (b)).
> **Não conta como ruptura nem como sucesso. Não é substituída por outra semente. Não é reexecutada.** Fica listada por `id` e `semente` (com fase e nó que
> violou) em `analise-vb.json`, campo `nao_executaveis_por_regra_do_ataque`. A previsão acima é comparada com o observado (`previsao_E7_x_observado`:
> previstos que rodaram; abortos não previstos), **sem reclassificar nada com base nela: vale o que o log disser**. Se uma célula **fora** das 4 previstas
> abortar pela mesma regra, ela entra na mesma classe pelo mesmo critério mecânico, e isso é relatado como **previsão falsificada**, não ajustado.

**Mínimo de células executáveis por ponto (n, f) para o veredito valer, escolhido AGORA:**

> **`MIN_EXEC = 6`.** Célula executável = classe `VIVA` ou `SEM_VIVACIDADE` (a soma das duas é `k_exec`). Para cada ponto (n, f) o veredito de **confirmação**
> (L(f) = 1 dentro da faixa; L(f\*) = 0, ou 0 < L(f\*) < 1, em f\*) **só vale com `k_exec ≥ 6` e nenhuma célula `OUTRA_FALHA` no ponto**; fora disso o ponto é
> **INCONCLUSIVO** e o veredito de n **não** é CONSISTENTE. **Refutação não precisa de mínimo:** dentro da faixa (f ≤ f_max), **uma** célula `SEM_VIVACIDADE` basta
> para INCONSISTENTE (contraexemplo), com qualquer `k_exec`. O critério de V-B **por n** usa **só as células executáveis** e **declara o `k_exec` de cada ponto**
> (`pontos[n][f].k_exec`, de 10, mais `nexec`). **Veredito de n=4, f=2:** com as 6 executáveis previstas (`k_exec = 6 ≥ 6`) o ponto é decidido pelo critério inteiro
> (L(2) = 0 ⇒ CONSISTENTE; 0 < L(2) < 1 ⇒ MISTO; L(2) = 1 ⇒ INCONSISTENTE). **Se restarem menos de 6 células executáveis em n=4, f=2 (por aborto não previsto ou
> `OUTRA_FALHA`), o veredito de n=4 f=2, e portanto de n=4, é INCONCLUSIVO.** O veredito **global** de V-B só é CONSISTENTE se **os quatro n** forem CONSISTENTES;
> com algum n INCONCLUSIVO e nenhum INCONSISTENTE, o relatório diz "INCONCLUSIVO (n decididos: …)" e **não é aprovação**: vai ao humano (impacto ALTO, sem arbiter).

**Por que 6, e o que a justificativa não é:** (i) é **maioria estrita das 10 réplicas pré-registradas** (> 50 %); com 5 ou menos a amostra executável é metade ou menos do
desenho. (ii) Com L observado = 0 em `k` células, o limite superior unilateral exato de 95 % para a probabilidade de vivacidade é 1 − 0,05^(1/k): **39,3 % (k = 6)**, 45,1 % (k = 5),
25,9 % (k = 10); o analisador grava esse número como rótulo (não decide). "Ruptura" em V-B quer dizer "0 de `k` sementes vivas", **não** probabilidade zero; o critério é determinístico
sobre as sementes pré-registradas, como já era na v1. (iii) Um mínimo de 7 ou mais tornaria n=4, f=2 inconclusivo por construção. **Dito com franqueza:** escolhi 6 **sabendo** da
previsão de 6/10 executáveis em n=4 f=2; essa previsão vem de código e de sorteio, **não de dado de V-B**, mas o limite poderia ter sido outro: é a escolha mais exigente que ainda
deixa o ponto decidível, e aceitá-la é decisão do humano (ponto aberto 2).

### E7 (b) Como distinguir os casos (critério mecânico, decidido pelos arquivos da célula)

Todos os campos existem em `dados-brutos/vb/<id>/` e são lidos por `analisar-vb.py` (ordem de decisão):

| Classe | Critério mecânico (todos obrigatórios) | Campos que decidem |
|---|---|---|
| **`NAO_EXECUTAVEL_POR_REGRA_DO_ATAQUE`** (abortou pela regra do ataque) | (1) `stdout-mvp.log` (linha `✗ failed: <mensagem>`) **ou** `stderr-mvp.log` (pilha) contém `EQUIVOCATION em <FASE> (no <id>) exige ao menos (2 vizinhos \| 1 destinatario honesto)` **e** `Ataque nao aplicado: simulacao abortada em vez de degradar` (texto de `HotStuff.java` linhas 840 a 866, `IllegalStateException`, relançada por `rethrowIfHotStuff` como `trial HOTSTUFF falhou (n=…, seed=…): …`); (2) `resultado.json: rc_mvp != 0`; (3) `resultado.json: rc_observador != 0`; (4) `obs/observador-resumo.json` **ausente** (o observador só o escreve depois de `scenario.run()`, que nem termina) | regex nos dois logs; `rc_mvp`; `rc_observador`; existência de `observador-resumo.json` |
| **`SEM_VIVACIDADE`, sub-rótulo `SEM_BLOCO`** (liveness perdida: o dado que V-B procura) | `observador-resumo.json` existe e `finalizacoes_locais_total == 0`; `rc_mvp != 0`; `stdout-mvp.log` tem `failed: HOTSTUFF finalizou 0 blocos`; `observador-por-altura.csv.gz` sem linha de dados; `rc_observador == 0`; **nenhuma** mensagem de regra de ataque | `finalizacoes_locais_total`; `rc_mvp`; a linha `finalizou 0 blocos` |
| **`SEM_VIVACIDADE`, sub-rótulo `PARADA_ANTES_DE_T0`** | rodou até o fim (`rc_mvp == rc_observador == 0`, MD5 do CSV por bloco igual entre as duas JVMs, `blocos_mvp == blocos_obs`) e **nenhuma altura** com `Fmin_s ≥ 20 s` em `obs/observador-por-altura.csv.gz` | `observador-por-altura.csv.gz`, coluna `Fmin_s` |
| **`VIVA`** | idem, e **ao menos uma** altura com `Fmin_s ≥ 20 s` | idem |
| **`OUTRA_FALHA`** (relatada, **não** classificada como ruptura nem como sucesso) | qualquer outro caso: sem `resultado.json`; observador sem resultado e **sem** a mensagem da regra; mensagem da regra mas `rc_mvp == 0` ou observador terminou (incoerente); outra `IllegalStateException` de `EQUIVOCATION` (ex.: "bloco real sem pai", "só fabrica blocos em HotStuffNode"); MD5 do CSV por bloco ou nº de blocos diferentes entre as JVMs; observador com blocos mas `rc_mvp != 0`; observador sem finalização mas o `MVPComparison` **não** falhou com `finalizou 0 blocos` | lista `outras_falhas` em `analise-vb.json`, com `motivo` |

Regras que seguem: **`OUTRA_FALHA` em um ponto ⇒ o ponto é INCONCLUSIVO** (a célula não é excluída em silêncio nem reexecutada; fica listada). A distinção entre "aborto pela regra" e
"liveness perdida" é o par **`rc_observador` / `observador-resumo.json`**: na regra, o observador **morre** (mesma exceção, mesma semente, mesma construção do cenário); na liveness perdida,
o observador **termina** e relata zero finalizações. `executar-lote-vb.py` usa `--rc-mvp-registrado`; o **código de saída 3 do lote é esperado** quando há `rc_observador != 0` (previstas: 4)
e **não** é erro de execução; quem classifica é `analisar-vb.py`. **Verificado só em teste sintético** (texto copiado do código-fonte; o formato `failed: …` do `MVPComparison` já aparece em logs
antigos do piloto para o caso "0 blocos"): **nunca vi o log real de um aborto pela regra** em `76932b9` (as 4 células nunca rodaram). Por isso a etapa 3b roda **primeiro só essas 4** e confere o regex
contra o log real **antes** do resto; se o formato real diferir, ajusta-se o regex por errata datada **antes de qualquer outra célula** (as 4 não carregam dado de ruptura, então o ajuste é cego a ela).

### E7 (c) Definição mecânica de ruptura

**Já estava na v1 (seção 3.5) e vale como está:** vivacidade por célula = ≥ 1 altura finalizada com `Fmin_s ≥ 20 s` (observador; tempo simulado; `Fmin_s` = instante em que o primeiro nó finaliza a
altura); `L(f)` = fração das células com vivacidade; `f*` = menor f sem vivacidade; critério CONSISTENTE / INCONSISTENTE / MISTO. **Faltava e define-se agora, antes dos dados:**

1. **Denominador de `L(f)`:** só as células **executáveis** (`VIVA` + `SEM_VIVACIDADE`); `NAO_EXECUTAVEL_POR_REGRA_DO_ATAQUE` e `OUTRA_FALHA` **não** entram.
2. **Ruptura em f** ⇔ `L(f) = 0` com ponto DECIDIDO (`k_exec ≥ 6`, sem `OUTRA_FALHA`). **"A ruptura ocorre em f\* = ⌊(n−1)/3⌋ + 1" (critério inteiro do humano)** ⇔ `L(f) = 1` para todo f de 0 a
   f_max **e** `L(f_max + 1) = 0`, em pontos DECIDIDOS: **CONSISTENTE**. **INCONSISTENTE** ⇔ algum f ≤ f_max tem **ao menos uma** célula `SEM_VIVACIDADE` (perda de vivacidade dentro da tolerância;
   refutação não exige mínimo), **ou** `L(f_max + 1) = 1` num ponto DECIDIDO (sem ruptura). **MISTO** ⇔ `0 < L(f_max + 1) < 1` num ponto DECIDIDO (reporta-se; decide o humano). **INCONCLUSIVO** ⇔ algum
   ponto não é DECIDIDO e não há refutação.
3. **Fração de réplicas honestas que finalizam: NÃO decide.** Reporto por célula, só como **rótulo**, `alturas_com_Fmin_ge_20s`, `ultimo_Fmin_s` e `max_replicas_que_finalizaram_apos_20s` (coluna `n_finalizaram`,
   **que não separa bizantinos de honestos**: os bizantinos rodam o núcleo honesto e também finalizam). Não mudei o critério da v1 para "fração de honestos" porque o observador não grava o conjunto de
   finalizadores por altura; trocar o critério agora seria desenhar métrica sem dado.
4. **Limites declarados:** (i) o critério é **binário por célula**: uma única altura finalizada após 20 s basta para `VIVA`, mesmo com vazão muito degradada (f\* poderia ter `L = 1` com vazão quase nula);
   `ultimo_Fmin_s` e `alturas_com_Fmin_ge_20s` mostram isso, mas **não decidem**. (ii) **Só vivacidade é medida; a segurança (dois commits conflitantes) NÃO é verificada** (v1, 3.5); "ruptura" aqui é perda
   de vivacidade. (iii) 1 p.p. continua **não testável** com n ≤ 13 (E3).

### E7 (d) Flag fora da faixa

**Mantida** `--allow-beyond-bft-threshold=true` **nos pontos f = f\* = f_max + 1** (n=4 f=2, n=7 f=3, n=10 f=4, n=13 f=5; 40 das 180 células) e **só neles**: `gerar-celulas-vb.py` grava
`flags_extras = ["--allow-beyond-bft-threshold=true"]` nesses cfg e `[]` nos demais (dentro da faixa a saída com a flag seria idêntica, mas ela não é passada). Conferido por
`gerar-celulas-vb.py conferir-manifesto` sobre as 180 configurações geradas em `/tmp`: **manifesto == especificação** (sementes, n, f, duração, T, modo, ataque, flags, commit, `-XX:hashCode=2`).
f = 0 (40 células) roda **sem ataque** (`attack: null`), como controle honesto (`MVPComparison` recusa `--byzantine-count=0` com ataque ativo). Ataque **inalterado**: EQUIVOCATION com a regra provisória (B-34 a B-36).

### E7 (e) Listagem por script das células afetadas (sem simular)

`python3 experimentos/validacao/hotstuff/scripts/listar-vb-e7.py` (grava `vb-celulas-bloqueadas-e7.json`; não toca em `listar-vb-bloqueadas.py` nem em `vb-celulas-bloqueadas.json`, ambos hash-conferidos):
**4 células abortam, todas em n=4 f=2 (fora da faixa), sementes 424 348, 424 349, 424 352 e 424 354**; 0 em n=7, 10, 13; **0 diferenças** em relação à Emenda 1b; exaustivo: só n=4 f=2 (3 de 6 conjuntos).
**Limite:** previsão por leitura de código e sorteio; cobre **só** esta regra, **não** cobre `SEM_BLOCO` nem `OUTRA_FALHA` (só a execução mostra).

### E7 (f) Instrumento, sementes, custo

- **Simulador:** commit `76932b925719a2b83d6165b5a84d30f5501896ee` (o mesmo de V-A/V-C). `conferir-build-vb.py` (grava `conferencia-build-e7.json`): os **1 076** arquivos de `src/` da cópia
  `/tmp/opencode/val-hs` têm o mesmo blob git que os do commit (0 faltando, 0 sobrando, 0 diferentes); `pom.xml` e `ObservadorVal.java` com os hashes pinados; tree de `src/` do HEAD de trabalho = `a1b464ee…`
  (sem mudança de código desde `76932b9`). **Limite:** que os `.class` vêm dessa fonte só se sabe por "foi construído por `preparar-val.py` e a fonte é idêntica" (`.class` não tem blob de origem). Se
  `/tmp/opencode/val-hs` sumir, rodar `preparar-val.py` (ele já recusa outro commit) antes de V-B.
- **`-XX:hashCode=2` obrigatório** em toda célula (`rodar_celula.py` aborta sem ela; presente no cfg gerado). `rodar_celula.py` e `ObservadorVal` **são os de V-A/V-C, sem alteração**.
- **Sementes:** V-B usa 424 346 a 433 355 (base 420 345). `checar-sementes-val.py` → `checagem-sementes-e7.json`: **0 colisões** com as 284 sementes já usadas (849 `cfg.json`) e **0 duplicadas entre blocos**;
  a repetição da mesma semente entre valores de f é intencional (pareamento).
- **Custo estimado de V-B (180 células, 300 s simulados, 8 paralelos):** `custo-vb-e7.py` → `custo-vb-e7.json`, a partir de tempos **medidos** em V-A/V-C (INDIVIDUAL, com a flag, em `76932b9`; n = 4, 7, 10, 16, 31;
  n=13 = máximo entre n=10 e n=16, **não medido**): **CPU ≈ 3 376 s (média) a 3 892 s (teto por n); parede com 8 paralelos (eficiência 7,43 medida) ≈ 7,6 min (média), 8,7 min (teto), 26 min (teto × 3)**; pior célula
  estimada ≈ 32 s. **Isto substitui os 956 s do `custo-estimado.json`**, que usava o fator pessimista 5,5× (medido no piloto em outro HEAD; V-A/V-C mostraram custo real menor). **Limites:** custo de célula **com ataque** em
  `76932b9` não medido; duração 300 s vs 120 s (escala linear assumida); n=13 não medido; sem tempo de build (reaproveitado). Memória: RSS máximo medido em V-A/V-C 501 MB; 8 paralelos cabem; disco ≈ 0,4 a 1 MB por célula.
- **Replicações e duração:** 10 sementes, 300 s, 180 células, como na seção 3.5. T por n como na seção 4 (aceitos pelo humano em 16/11).

### E7: comandos da etapa 3b (só depois do commit desta emenda; **não executar agora**)

```
V=experimentos/validacao/hotstuff
0.  python3 $V/scripts/hash-preregistro.py conferir --artefatos         # exit 0 obrigatório
    python3 $V/scripts/hash-emenda-e7.py conferir                         # exit 0
    git status --short -- $V                                              # sem alteração não commitada
1.  python3 $V/scripts/conferir-build-vb.py                               # exit 0 (se falhar: preparar-val.py com destino vazio)
    python3 $V/scripts/checar-sementes-val.py                             # exit 0
2.  python3 $V/scripts/gerar-celulas-vb.py gerar --saida $V/celulas/vb --simulador-commit 76932b925719a2b83d6165b5a84d30f5501896ee
    python3 $V/scripts/gerar-celulas-vb.py conferir-manifesto $V/celulas/vb/manifesto.json   # exit 0
3.  PILOTO (só as 4 previstas a abortar; não carregam dado de ruptura): rodar_celula.py célula a célula em $V/dados-brutos/vb; conferir o log real contra o regex de E7 (b).
    Se o log real não casar: errata datada do regex ANTES de qualquer outra célula; se casar: seguir.
4.  python3 $V/scripts/executar-lote-vb.py $V/celulas/vb --saida $V/dados-brutos/vb --paralelo 8 --pular-existentes   # sem espiar resultado parcial; exit 3 esperado
5.  python3 $V/scripts/analisar-vb.py $V/dados-brutos/vb --saida $V/analise-vb.json
```
Depois: `PARECER-TECNICO-vb.json` (`n_celulas_esperadas` = 180; `n_celulas_obtidas` = células com `resultado.json`; **mais** a contagem por classe e os `k_exec` por ponto) e loop 3 (humano, impacto ALTO).
**Nenhum limiar é ajustado depois de ver o resultado**; tudo o que for escolha posterior é **pós-hoc rotulado**.

### E7: pontos ainda abertos que dependem do humano

1. **n=7, f=3 "depende da semente":** a regra lida **não** reproduz isso (0 de 35 conjuntos abortam). Ou o relato vem de outro modo, versão ou protocolo, ou a minha leitura da regra está incompleta. Não afeta o desenho
   (a classe trata qualquer aborto por regra do mesmo jeito), mas **a previsão de 4 abortos pode ser falsificada** no piloto da etapa 3b.
2. **`MIN_EXEC = 6`** (E7 (a)): escolha minha; se o humano exigir mínimo maior, n=4 f=2 vira INCONCLUSIVO por construção.
3. **n=4 f=2 só cobre 3 conjuntos bizantinos, todos com o nó 0 honesto** (E7 (a)): o veredito de n=4 é condicional; aceitar essa leitura ou declarar n=4 f=2 fora do critério global?
4. **Critério binário de vivacidade** (≥ 1 altura após 20 s): mantido como na v1; se o humano quiser um limiar de vazão, é critério novo, não pré-registrado.
5. **"5 valores de n bastam" aplicado só a V-A** (decisões, acima): V-B segue com 4 valores de n. Confirmar.
6. **Commit desta emenda** antes da etapa 3b (precedente `06c67b5`); este agente não commita.
7. Custo de célula com ataque (e n=13) é **estimado, não medido**.

### Artefatos acrescentados pela E7 (SHA-256; conferidos por `hash-preregistro.py conferir --artefatos`, que lê do marcador da Emenda 1b até o fim)

A tabela "Artefatos vigentes (SHA-256) apos a Emenda 1b" **continua valendo, sem mudança**; abaixo só o que a E7 acrescenta.

| arquivo | SHA-256 |
|---|---|
| `experimentos/validacao/hotstuff/PREREGISTRO.v2-emenda-1b-2026-11-16.md` | `bf1d85f78a1f70e5f74b368e836a80b32cd3e065e3c0c834d37d0cfc44ed4ec2` |
| `experimentos/validacao/hotstuff/vb-celulas-bloqueadas-e7.json` | `ed2f74a8be2232538169b9aad36f15db4452539fb4264e6aa02598b6f72f7a2c` |
| `experimentos/validacao/hotstuff/checagem-sementes-e7.json` | `da549e637169f968c04f7383b446448427be6d75fca9bb10ce80d77ee45fcb91` |
| `experimentos/validacao/hotstuff/conferencia-build-e7.json` | `8474fcb66ad34aceed58986e48ef10620ebb3e329c2f70e4a670f95637595fb3` |
| `experimentos/validacao/hotstuff/custo-vb-e7.json` | `25d25847aa3c94750abbffa453c97318655a421a87825cc959fe29296e244f78` |
| `experimentos/validacao/hotstuff/scripts/hash-emenda-e7.py` | `586a43b9f24410a271df535133a07355c9dc4791e4c25f0342766f0b68cbf4e2` |
| `experimentos/validacao/hotstuff/scripts/gerar-celulas-vb.py` | `44b50bedc91f6b0775f6f85f0323116cc04677373dee3fcf4f98ad20f6da04d4` |
| `experimentos/validacao/hotstuff/scripts/executar-lote-vb.py` | `f1dca382f519626e1f137ed0fda56281b923739ce9542d35dbb006c9f2bd546b` |
| `experimentos/validacao/hotstuff/scripts/analisar-vb.py` | `99d819f42563cc7bf76421b8fb0b57908271c89e2ed40bd70fd9c2ce942e6e90` |
| `experimentos/validacao/hotstuff/scripts/conferir-build-vb.py` | `1dba73f36b0f036ddb310649cfe977ea1f3a956515274e06f35e267e38d801f0` |
| `experimentos/validacao/hotstuff/scripts/listar-vb-e7.py` | `8dd4ca7109cbe97e0c94ae1c882656d830f1a749a3ff5cc473a4150b1a466c24` |
| `experimentos/validacao/hotstuff/scripts/custo-vb-e7.py` | `406f38199866ec20b9cff1f3d01ae46d40ce0711d8449d7228603733aaf68c1f` |
