# PRÉ-REGISTRO (RASCUNHO, etapa 1): V-ABC-tendermint (V-A, V-B e V-C)

Data do rascunho: 2026-11-16. Tarefa: `V-ABC-tendermint`. Autor: `jabs-experimento` (proponente do loop 3; **não aprova o próprio resultado**).
Impacto: **ALTO** (portão de validação): o loop 3 vai ao humano, sem arbiter.

**ESTADO: RASCUNHO. NÃO é o pré-registro congelado.** Faltam o pino do simulador (seção 2) e o commit do arquivo. Enquanto
`COMMIT_PINADO = a definir (após BYZ-UNICA, D2 e PARIDADE estarem commitadas)`, este arquivo **não tem hash** (`hash-preregistro-tm.py gravar`
recusa) e **nenhuma célula de portão pode rodar**. O hash final só vale depois do pino.

**Nada foi simulado nesta etapa.** O que foi executado e **não gera dado**: leitura de código; cálculo da fórmula (`formula-analitica-tm.py`);
um build de **dry-run** do HEAD `bc07355` em `/tmp/opencode/tm-dry` (só `mvn compile`, `javac` do observador e `--listar-byz-faixa`, que sorteia ids
sem construir cenário); geração de células em `/tmp` e conferência de manifesto; `--autoteste` dos dois analisadores sobre dado **sintético**
(derivado da fórmula ou fabricado pelo script, **não** do simulador); `dry-run-comandos-tm.py` (monta comandos e confere flags; **não executa JVM**); leitura de
duas fontes publicadas (`aux-ler-pdf.py`). **Nenhuma JVM de simulação foi executada.**

## 0. Divulgação: o que eu já sabia antes de fixar a regra

1. **Li o código do Tendermint e derivei uma fórmula analítica** (`formula-analitica-tm.py`) a partir de `Tendermint.java`, `TendermintProposalMessage`,
   `TendermintVoteMessage`, `TendermintP2P`, `PeerBlockchainNode` (hook de tráfego) e `NetworkHeightLedger`. **É o mesmo código que gera o dado**: a fórmula
   confere que o simulador faz o que o código diz, **não** que o modelo reproduz o Tendermint do artigo, nem que 64 B / 10 B são os tamanhos de uma implementação real
   (mesma ressalva do V-ABC do HotStuff, ADR-0015 16/11, item 3). **A fórmula NÃO foi conferida com nenhum dado**: não existe célula de Tendermint em `validacao/`. No HotStuff
   ela foi conferida num ponto antigo (K40); aqui **nem isso**. Se falhar, o resultado é "INCONSISTENTE com a fórmula" e o loop 3 separa "erro da fórmula" de "bug do simulador".
2. Conheço o resultado do V-ABC do HotStuff (`26c98f6`, `bc07355`): P4a fechado como "O(n) em pacotes e O(n²) em bytes de consenso (INDIVIDUAL)", V-B sem ruptura em f\*
   com vazão em f\* de 5,7 % a 8,0 % da de f = 0. **Isso orienta a minha expectativa para o Tendermint e eu a declaro como risco (seção 3.3), não como critério.**
3. Li o teste `TendermintEquivocationTest` (Javadoc): **n = 4, f = 1 não progride quando o bizantino é proponente** (limitação do broadcast direto sem gossip; "não corrigida").
   Isso contradiz o critério "L(f) = 1 dentro da faixa" **para n = 4**, e eu o declaro **antes dos dados** na seção 3.3 (previsão de risco). O ataque é o do código, não o do pré-registro.
4. Não vi dado antigo de Tendermint em `/tmp/opencode` (busca por arquivos de Tendermint: nenhum), então **nenhum valor desta rodada foi visto**.

## 1. O que se quer validar e a granularidade (declarada **antes** de rodar)

Pré-condição **P4b** (ADR-0009, D3b; `PROTOCOLO-EXPERIMENTAL.md`): "passa no V-A com ordem O(n²); *locking* e votos nil verificados". **A ordem esperada depende da granularidade**, então declaro as duas:

| Granularidade | Medida | Fonte | Esperado |
|---|---|---|---|
| **AGREGADO DE REDE por altura decidida** (principal; decide) | `rede_msgs` (pacotes), `rede_consenso` (`CbRedeConsenso_avg_B`), `rede_total` (`CbRede_avg_B`) | `NetworkHeightLedger` (ADR-0015 D1; soma sobre todos os nós receptores, sem corte local, nunca autoentrega; janela de regime: alturas 2 a hmax−2) | `rede_msgs` e `rede_consenso`: **O(n²)**; `rede_total`: O(n) até n = 31 (rótulo) |
| **POR NÓ** (secundária; **só rótulo**) | pacotes e bytes recebidos por nó e por altura, separados em **proponente** e **validador** do round 0 | `obs/por-no-por-altura.csv.gz` (mesmo ledger, por nó) | proponente **O(n)** (recebe 2(n−1) votos); validador **O(n)** em pacotes e bytes de consenso e **O(1)** em bytes totais (a carga do bloco domina) |

- **Diferença de granularidade em relação ao HotStuff, e por quê:** no HotStuff o líder concentra o tráfego (líder O(n²) em INDIVIDUAL). No Tendermint **todos os nós
  difundem** prevote e precommit: não há nó que concentre. A distinção que importa é **proponente × validador**, e **ambos são O(n)** por nó: o termo O(n²) é **só do agregado**
  (n nós × O(n) cada). Logo o "O(n²)" do ADR-0009 é **uma propriedade do agregado de rede**; se alguém medir por nó verá O(n) e **não** é contradição (seção 3.2).
- O `Cb_avg` histórico (bytes do primeiro nó a finalizar) **não entra** (artefato do ponto de medição; ADR-0015 D1). `CbPrimeiro_*` não decide.
- **Não há "O(n²) em assinaturas" a medir**: o voto leva **uma** assinatura (64 B), o QC não existe no Tendermint do JABS (a decisão exige 2f+1 votos individuais **recebidos**, não carregados). Logo
  pacotes e bytes de consenso são **ambos O(n²) por construção do broadcast todos-para-todos** (n·(n−1) votos por fase), sem a distinção "autenticadores × bytes" que o HotStuff tem. Isso é **previsão da fórmula**, registrada agora.

## 2. Instrumento: commit, build, JVM

- **`COMMIT_PINADO = a definir (após BYZ-UNICA, D2 e PARIDADE estarem commitadas)`.** Hoje o HEAD é `bc073557870aaac0e9b5f3fc62bdf240a4303260` (`src/` tree
  `a1b464ee983b78a2a6b6de7166a6e6460839b8af`, `pom.xml` blob `32f934e332097d6bfed46830ff03132c36763670`), **mas há mudanças não commitadas de outro agente em `src/`** (BYZ-UNICA: contagem
  inteira e gerador único de conjuntos; depois autoentrega D2 e paridade de teto PBFT/Tendermint), listadas pelo `git status` (`ByzantineConfig`, `ByzantineSweep`, `MVPComparison` e cenários
  de ARQ, Bitcoin, Casper, DAGsper, Hybrid). **O build do dry-run exclui essas mudanças** (`git archive` do HEAD) e `preparar-tm.py` **recusa** rodar sem o pino.
- **O que muda no pino e invalida números deste rascunho (ver seção 8):** (a) a **PARIDADE de teto de retenção** dá ao Tendermint um teto para `futureMessages`/alturas e rounds futuros; **pode mudar a saída do Tendermint**
  (a especificação diz que sem ataque e sem atraso deve ficar idêntica; **eu não verifiquei**); (b) a **autoentrega (D2)** muda a contagem de tráfego dos protocolos que a contam (PBFT e outros); no Tendermint o código lido **já não
  autoentrega** (`TendermintP2P` não liga o nó a si mesmo; `isLocalSelfDelivery`), mas o tratamento pode mudar; (c) o **gerador único de conjuntos** (BYZ-UNICA) **pode mudar o sorteio dos ids bizantinos** de `withExactCount`
  e, portanto, **a lista de células que abortam** (seção 5); e (d) `ByzantineConfig.beyondBftThreshold/bftLimit` **só existem na árvore suja**: o `ObservadorTm` replica a regra com aritmética inteira local por isso.
- **Quando o pino existir:** preencher em `preparar-tm.py` (`COMMIT_PINADO`, `TREE_SRC`, `BLOB_POM`, `SHA_OBS_TM`), regerar `vb-celulas-abortam-previsto.json` e `custo-estimado-tm.json`, **reconferir o observador contra a API pinada**
  (re-hashear se mudar), rodar `hash-preregistro-tm.py gravar`, e só então commitar. **O hash final do pré-registro só vale depois disso.** Se a PARIDADE alterar a saída do Tendermint, **este V-ABC repete** (decisão do humano, 13/11, item 5, aplicada por analogia).
- **Build a partir do git** (`git archive`) em `/tmp/opencode/val-tm`; a árvore de trabalho **não é usada**. Java 25 (alvo do pom: 17), Maven 3.8.7; serão gravados em `versao-simulador.json`.
- **`-XX:hashCode=2` OBRIGATÓRIO** em toda célula, nas **duas** JVMs (`rodar_celula.py` aborta sem ele). Custo com a flag em `Tendermint` **não foi medido** (seção 7).
- **Observador: `harness/ObservadorTm.java` (declarado e hasheado).** Adaptação de `ObservadorVal.java` do HotStuff (SHA-256 `21aa3f9e7669765ba32ff91f4cadd8e7b28c364d8989a27d6a850c7e9d61e089`, **não alterado**). **Muda:**
  (i) conta `TendermintTimeoutEvent` e subtrai os `STALE_TIMEOUT` do núcleo (`TendermintCore.discardCount`) para obter timeouts **disparados** (o Tendermint não expõe contador como o `getTimeoutAdvances` do HotStuff);
  (ii) grava `pacotes-por-altura.csv.gz` **e** `por-no-por-altura.csv.gz` (proponente do round 0 = (altura−1) mod n, de `TendermintCore.roundRobin`); (iii) grava round final, descartes `INVALID_VALUE`,
  equivocações, voto nil sem altura, pico de mensagens de altura futura e `max_lead` (maior diferença de altura entre nós); (iv) **em V-B é o único instrumento** (ver seção 3.3). Compila **contra o HEAD `bc07355`**
  (verificado: `javac` rc = 0, SHA-256 `74cf13e40a99c978ab4017986505756c692872351176e417d205886fa41b5717` na versão deste rascunho). **O SHA do pino será outro se ele for editado.**
  Que o observador **não perturba** a simulação é verificado na etapa 2 pela igualdade do MD5 do CSV por bloco entre as duas JVMs (**só em V-A e V-C**).

## 3. Critérios (todos congelados antes de qualquer dado, **após o pino**)

### 3.1 Regime e carga
`L = 100`, `s_tx = 512` (lote cheio por altura; **sem gerador de carga**, como o HotStuff; `BlockFactory.sampleTendermintBlockWithTx`), LAN simulada do JABS (Pareto 20 ms, 100 Mbps), `TendermintP2P` = **malha completa, difusão direta, sem gossip**,
proponente round-robin `(h + r) mod n`, **sem `timeout_commit`** (Q6, a vazão é **limite superior**), **sem POL em bytes** (Q9). 1 trial por célula. **Distâncias para o Tendermint real, declaradas:** sem gossip; sem `timeoutCommit`; POL não modelada;
`TendermintCore` registra a mensagem do próprio nó sem pacote.

### 3.2 V-A, agregado de rede (decide)
**Níveis:** n ∈ {4, 7, 10, 16, 31} (ADR-0015); 5 sementes por n ⇒ **25 células** (`va`; **sem AGGREGATED**: o Tendermint não tem modo de assinatura agregada). Sem ataque.
**Previsão pela fórmula** (por altura decidida; H=32, O=10, sv=sp=64, P=51 200; `formula-analitica-tm.py`; **fixtures sem fonte**):

| n | pacotes (`rede_msgs`) | bytes de consenso | bytes totais |
|---|---|---|---|
| 4 | 27 | 2 862 | 156 462 |
| 7 | 90 | 9 540 | 316 740 |
| 10 | 189 | 20 034 | 480 834 |
| 16 | 495 | 52 470 | 820 470 |
| 31 | 1 890 | 200 340 | 1 736 340 |

Fórmulas: pacotes = (n−1)(2n+1); consenso = (n−1)(H+O+sp) + 2n(n−1)(H+O+sv); total = consenso + (n−1)·P.
**Previsão de b (OLS de ln(métrica) em ln(n), 5 pontos):** `rede_msgs` **2,0715** (O(n²)); `rede_consenso` **2,0715** (O(n²)); `rede_total` **1,1705** (O(n); termo quadrático ≈ 11,7 % em n = 31, por aritmética: 2·(H+O+sv)·n² = 203 732 de 1 736 340). **Margem do ponto médio:** 2,0715 está a 0,57 de 1,5 e a 0,43 de 2,5.
**Por nó (rótulo):** proponente `no_proponente_msgs` b=1,120 (O(n)), `no_proponente_bytes_consenso` b=1,120 (O(n)); validador `no_validador_msgs` b=1,055 (O(n)), `no_validador_bytes_consenso` b=1,055 (O(n)), `no_validador_bytes_total` b=0,051 (O(1)).

**Critérios, por medida (DECIDEM só as duas do agregado):**
- **I (integridade, por célula):** rc = 0 nas **duas** JVMs; **`blocos_mvp > 0`** (o `MVPComparison` **engole** falha de trial do Tendermint — imprime `failed:` e sai com rc 0, **sem** o `rethrowIfHotStuff` do HotStuff —, então rc = 0 **não basta**); MD5 do CSV por bloco (sem `BlockHashCode`) igual entre as JVMs;
  **zero timeouts disparados** (A1'); `round_final_maximo = 0`, `INVALID_VALUE = 0`, equivocações = 0 e **pacotes sem altura (voto nil) = 0** (caminho feliz); ≥ **100 alturas** de regime; `pacotes-por-altura` com exatamente `regime_heights` alturas e média por altura dos bytes do observador = `CbRede_avg_B` (± 0,01); esperadas = obtidas.
  Célula que falha em I **não é excluída**: o veredito vira "INCONCLUSIVO (instrumento)".
- **F (fidelidade à fórmula):** `|medido − fórmula| / fórmula ≤ 0,001` em **toda** célula íntegra (a soma da rede é inteira e constante por altura em regime).
- **E (classe):** `b` pela OLS sobre as médias por n. b < 0,5 O(1); 0,5 ≤ b < 1,5 O(n); 1,5 ≤ b < 2,5 O(n²); b ≥ 2,5 "> O(n²)".
- **A1 (decide):** `rede_msgs` com 1,5 ≤ b < 2,5 **e** F. **A2 (decide):** `rede_consenso` com 1,5 ≤ b < 2,5 **e** F. `rede_total` e todas as medidas por nó: **só rótulo**.
- **S (`validar-complexidade.py`, REPORTADO, não decide):** mesma regra do HotStuff (dado determinístico sem ruído: o AIC enxerga qualquer curvatura). Roda-se `--esperado quadratico` para `rede_msgs` e `rede_consenso`.
- **Veredito mecânico** (`analisar-va-tm.py`, `veredito_va`): **INCONCLUSIVO (instrumento)** = célula faltante, extra, não íntegra, com timeout, ou n sem célula íntegra. **INCONSISTENTE** = A1 ou A2 falha (classe ≠ O(n²) ou ponto infiel). **CONSISTENTE** = nenhum dos anteriores.
  **Não é aprovação**: o loop 3 (humano) decide. **P4b exige também "locking e votos nil verificados":** **este V-A não os verifica** (caminho feliz, sem nil e sem polka em round > 0). Fica **fora desta rodada** e dito: a verificação de *locking* (PoLC) e de votos nil depende de teste de unidade
  (`TendermintSafetyTest`) e de V-B (equivocação, rounds > 0); **eu não os conferi nesta etapa**. **Pergunta ao humano (seção 9, item 4): P4b se fecha só com V-A, ou exige a evidência de *locking* e nil?**
- **Réplica:** o tráfego do caminho feliz é **determinístico em bytes**; **sem IC de bytes** (precisão fingida); reporta-se mínimo, máximo, média e erro à fórmula. As 5 sementes detectam dependência de semente (timeouts, regime).
- **Previsão de `b` — o que eu NÃO sei (risco declarado):** a fórmula supõe que **cada** voto chega a **cada** nó dentro da altura. Votos que chegam **depois** de o destino finalizar a altura **entram** (ledger "sem corte local"); só os de alturas não decididas por todos na janela ficam de fora, e a janela já descarta as 2 últimas. Se a decisão (2f+1) ocorrer antes de todos os votos chegarem, **a contagem por altura ainda é n(n−1)·2 + (n−1)** (cada pacote entregue conta uma vez): **a previsão não depende disso**. Já a **duplicata** (voto repetido) **conta** (`DUPLICATE` é contado no hook antes do despacho); **sem falha não deve ocorrer**.

### 3.3 V-B (limiar bizantino): **vivacidade sob EQUIVOCATION com partição por índice provisória (Q28), não segurança**
- **Níveis:** n ∈ {4, 7, 10, 13}; f inteiro de 0 a ⌊(n−1)/3⌋ + 1; **EQUIVOCATION** do Tendermint (`D3b-tendermint-equivocation`); L = 100; 10 sementes; 300 s ⇒ **180 células** (`vb`), 40 delas em f = f\* com `--allow-beyond-bft-threshold=true`. Sementes 824 346 a 833 355 (base 820 345).
- **Critério decidido pelo humano e **proposto** aqui para o Tendermint (aguarda confirmação):** o critério inteiro "ruptura em f\* = ⌊(n−1)/3⌋ + 1" (V-B só testa a igualdade inteira; **1 p.p. não é testável com n ≤ 13**, mínimo 7,7 p.p. em n = 13). `MIN_EXEC = 6`. Classe `NAO_EXECUTAVEL_POR_REGRA_DO_ATAQUE` (sem substituir semente, sem reexecutar). Vivacidade **binária**: ≥ 1 altura finalizada com `Fmin_s ≥ 20 s` (t0 = 20 s). Regra do ataque mantida (`requireEquivocationSplit`).
- **Atualização de 20/11/2026 (ataque uniforme, PR #5):** o `MVPComparison` passou a ler `--attack=EQUIVOCATION` e `--byzantine-count` também para TENDERMINT e ARQ. **A decisão deste bloco não muda:** o instrumento de V-B segue sendo só o `ObservadorTm` (já pré-registrado, com contagem de timeouts, descartes e rounds que o `MVPComparison` não expõe); o texto a seguir descreve o `MVPComparison` de `bc07355`.
- **Instrumento de V-B: só o `ObservadorTm`.** O `MVPComparison` **não lê** `--attack` para TENDERMINT (`attackForMain`: "ignorada (só o HOTSTUFF a lê)") e o `ByzantineSweep` não tem Tendermint. **Consequências declaradas:** (i) **sem JVM do MVPComparison em V-B**: não há conferência cruzada de CSV por bloco entre duas JVMs; a integridade de V-B é só `rc_observador`, resumo e por-altura; (ii) a regra de f > ⌊(n−1)/3⌋ **é replicada** no `ObservadorTm` (aritmética inteira local), **não herdada** do `MVPComparison`; (iii) não existe `failed: ... finalizou 0 blocos` do `MVPComparison`: zero blocos vira `SEM_VIVACIDADE`/`SEM_BLOCO` pelo resumo (`finalizacoes_locais_total = 0`, rc = 0); qualquer rc ≠ 0 sem a mensagem da regra é `OUTRA_FALHA`.
- **Ruptura mecânica** (`analisar-vb-tm.py`): `VIVA` ⇔ resumo presente e ≥ 1 altura com `Fmin_s ≥ 20 s`; `SEM_VIVACIDADE` ⇔ resumo presente e nenhuma; `NAO_EXECUTAVEL_POR_REGRA_DO_ATAQUE` ⇔ mensagem da regra no log (`EQUIVOCATION em <PROPOSAL|PREVOTE|PRECOMMIT> (no <id>) exige ...Ataque nao aplicado...`) **e** rc_observador ≠ 0 **e** resumo ausente; `OUTRA_FALHA` = o resto. Veredito por ponto e por n como no HotStuff (E7 (c)): CONSISTENTE = L(f) = 1 para f ≤ f_max e L(f\*) = 0 em pontos DECIDIDOS (k_exec ≥ 6, sem `OUTRA_FALHA`); INCONSISTENTE = uma célula `SEM_VIVACIDADE` com f ≤ f_max **ou** L(f\*) = 1; MISTO = 0 < L(f\*) < 1; INCONCLUSIVO = ponto não decidido.
- **PREVISÃO DE RISCO, declarada antes dos dados (leitura do código e do teste, não dado):** (a) **n = 4, f = 1** pode **não** dar vivacidade quando o bizantino é proponente (Javadoc de `TendermintEquivocationTest`: "NÃO progride" nesse caso; causa provável: o voto B chega só a uma metade e, sem repasse, os honestos da outra nunca veem a polka de B). Se acontecer, **n = 4 fica INCONSISTENTE por refutação** (uma célula `SEM_VIVACIDADE` com f ≤ f_max basta) **embora o protocolo "devesse" tolerar f = 1**: **é limitação do broadcast direto sem gossip (modelo), não ruptura em 1/3.** Isto **separa o resultado do Tendermint do do HotStuff** (que não perdeu vivacidade) e **o loop 3 deve dizê-lo**; (b) **n = 7 f = 3**, **n = 10 f = 4** e **n = 13 f = 5** podem ter ruptura **parcial** (MISTO) ou **nenhuma**, como no HotStuff (o voto bizantino real conta). **Não sei qual.**

### 3.4 V-C (condicional; confronto com publicado): **proposta de declarar INVIÁVEL (aguarda confirmação do humano)**
Ver `FONTES-V-C.md` (6 fontes, cada uma classificada). **Nenhum número VERIFICADO e comparável** de vazão/latência do Tendermint para n = 4 e 16 em LAN (tese de Buchman 2016, cap. 9: números só em figuras e tabelas ilegíveis por extração; regime de longa distância, tx de 250 B, timeouts a 1 ms; o artigo do algoritmo e o whitepaper de Kwon não têm avaliação). **Proposta:** declarar o V-C numérico **inviável** e manter só o teste ordinal fraco:
n ∈ {4, 16}, L = 100, 5 sementes ⇒ **10 células** (`vc`; sementes 924 346 a 936 350): `Tb(n=16) > Tb(n=4)` **e** `vazão(n=16) < vazão(n=4)`, descartando blocos com `Time` < 20 s. **Rótulo na saída: "teste ordinal fraco, não é confronto numérico, não valida magnitude"; fora do veredito de V-A.**
**Previsão de risco do teste ordinal:** sem `timeout_commit`, com difusão direta e LAN de 100 Mbps, a **proposta de 51 200 B por n−1 destinatários** pela fila de upload do proponente domina a latência em n = 16 (serialização ≈ 15 × 51 200 B × 8 / 100 Mbps ≈ 61 ms); a direção "Tb sobe com n" é **provável**, mas **não sei**; passar ou falhar não valida nada.

## 4. Parâmetros `--tm-*` (12 flags obrigatórias, sem padrão no código) e o que está decidido

| Parâmetro | Valor provisório | Estado |
|---|---|---|
| `--tm-batch-size` (L) | 100 | **decidido** (registro de 12/11: caso central L = 100) |
| `--tm-tx-size` (s_tx) | 512 B | **decidido como caso base** (12/11; 512 B sem medição própria) |
| `--tm-signature-size` (voto) | 64 B | **a definir com o humano** (Q1/A-01; fixture sem fonte; ECDSA 64 B é a sugestão da revisão de 12/11) |
| `--tm-proposer-signature-size` | 64 B | **a definir com o humano** (A-02; "igual ao voto" é **escolha provisória minha**, a decisão explícita pede o humano) |
| `--tm-message-overhead` | 10 B | **a definir com o humano** (Q3/A-03; fixture sem fonte; igual a PBFT e HotStuff) |
| `--tm-max-future-horizon` | 1000 | **decidido** (ADR-0015: teto de retenção equivalente ao `maxFutureViewHorizon` do HotStuff; PARIDADE fechada em 20/11/2026; o mesmo valor em toda célula, vindo de `config/fatores-*.json`, nunca fixo no observador) |
| `--tm-propose-timeout` / `-delta` | **3,0 s / 0,5 s** | **a definir com o humano** (A-16/A-17; Q5 sem decisão) |
| `--tm-prevote-timeout` / `-delta` | **1,0 s / 0,5 s** | **a definir com o humano** (A-18/A-19) |
| `--tm-precommit-timeout` / `-delta` | **1,0 s / 0,5 s** | **a definir com o humano** (A-20/A-21) |

- **Justificativa única dos timeouts provisórios (a condição "zero timeouts disparados sem falha"):** os valores são os **padrões do CometBFT** (3000/1000/1000 ms, delta 500 ms; ADR-0015 A.3, fonte lida em 09/11) **usados como convenção** (Q5, opção B) e **iguais para todo n**.
  Eles **não** são "um múltiplo declarado da latência medida" como no HotStuff (Q12-A): **não existe piloto de timeout do Tendermint** (o piloto do ADR-0015 foi só do HotStuff). Por que devem dar **zero timeouts sem falha**, **por análise do código e da rede, não por dado**: o caminho feliz do round 0 tem 3 passos; a pior
  latência de entrega da proposta (51 200 B × (n−1) destinatários pela fila de upload a 100 Mbps; n = 31: ≈ 30 × 4,1 ms ≈ 123 ms, mais Pareto de média 20 ms) fica **uma ordem de grandeza abaixo de 3 s**; prevote e precommit (106 B) são desprezíveis; o `timeoutPrevote` (1 s) e o `timeoutPrecommit` (1 s) só são armados **depois** de 2f+1 votos e disparam
  o próximo passo, **não** a decisão. **Isto é uma estimativa minha, NÃO medida.**
- **Regra pré-registrada, sem reparametrizar depois de ver dado:** se **qualquer** célula íntegra de um n disparar timeout (`timeouts_disparados_total > 0`) sem falha, **aquele n é INCONCLUSIVO (instrumento)**, a célula **não é excluída** e **não se muda o timeout nesta rodada**. Um timeout diferente é decisão do humano e **nova rodada**.
- **Limite de comparabilidade com o HotStuff:** no HotStuff o `--hs-view-timeout` vem do piloto sem falha por n (0,918 a 2,065 s, aceitos em 16/11). **Aqui os timeouts não têm piloto e não são comparáveis em número**; o V-ABC compara **classes de complexidade**, não latências.
- **Timeouts sob falha (V-B):** crescem `base + r·delta`; com f bizantinos o tempo de recuperação por round falho é ≈ 3 s + ~1 s por round. O critério "zero timeouts" **não se aplica** em V-B.

## 5. V-B: células que abortam pela regra do ataque (previsão por script, SEM simular)

`listar-vb-tm.py` (regra lida em `Tendermint.requireEquivocationSplit`, linhas 382-414: vizinhos = outros ids em ordem crescente; `half = max(1, size/2)`; ≥ 1 honesto em **cada** metade; roda a cada envio equivocado de nó bizantino) + sorteio do build de **dry-run** (`ObservadorTm --listar-byz-faixa`; só sorteia e imprime):

- **Exaustivo (independe de semente e de gerador): só n = 4, f = 2 tem conjunto que aborta: 3 dos 6 pares, exatamente os que contêm o nó 0** ({0,1}, {0,2}, {0,3}) — **a mesma estrutura do HotStuff** (a regra é a mesma aritmética). **Nenhum** conjunto de n = 7 (f = 1 a 3), n = 10 (f = 1 a 4) ou n = 13 (f = 1 a 5) aborta, para qualquer semente.
- **Células previstas a abortar (sorteio do HEAD `bc07355`): 6, todas em n = 4, f = 2:** `tvb_n4_f02_s01` (824 346; {0,3}), `_s02` (824 347; {0,1}), `_s03` (824 348; {0,2}), `_s05` (824 350; {0,2}), `_s06` (824 351; {0,2}), `_s09` (824 354; {0,2}). **Executáveis previstas: 4** (`_s04`, `_s07`, `_s08`, `_s10`).
- **CONSEQUÊNCIA PARA `MIN_EXEC = 6`, declarada antes dos dados:** com `k_exec = 4 < 6`, **o ponto n = 4, f = 2 é INCONCLUSIVO por construção** (e o veredito de n = 4 **não** pode ser CONSISTENTE; só INCONSISTENTE, por refutação em f ≤ f_max, ou INCONCLUSIVO). **No HotStuff eram 4 abortos e k_exec = 6 (decidido).** Aqui são 6 e k_exec = 4. **Eu não escolhi sementes para evitar isso**: as sementes são as da fórmula `base + n·1000 + r`, fixadas antes de ver o sorteio.
  **O humano pode:** (a) aceitar o INCONCLUSIVO de n = 4, f = 2 (o veredito global fica "INCONCLUSIVO (n decididos: …)" e vai ao humano); (b) fixar outro `MIN_EXEC` (≤ 4) **agora, antes dos dados**; (c) pedir mais sementes em n = 4, f = 2 (**custa células; não reescrever o pré-registro depois do dado**). **Nenhuma está decidida.**
- **Os dados da `vb-celulas-abortam-previsto.json` dependem do gerador de conjuntos:** BYZ-UNICA (gerador único) **pode mudar o sorteio**; **regerar no pino**. A enumeração exaustiva não muda.
- **Limite:** previsão por leitura de código; só a execução confirma; **não cobre** `SEM_BLOCO` nem `OUTRA_FALHA`; **a regra é aplicada a cada nó bizantino que emite voto não-nil**: um bizantino que nunca emitisse voto não-nil não dispararia a checagem (em round 0 com proposta, todo nó emite prevote não-nil).
- **Critério mecânico de distinção** (`analisar-vb-tm.py`): ver seção 3.3. A distinção "aborto pela regra" × "liveness perdida" é o par `rc_observador` + `observador-resumo.json`: na regra o observador **morre** (a exceção sobe de `scenario.run()`); na liveness perdida o observador **termina** e o resumo existe.
  **Verificado só em teste sintético** (texto copiado do código-fonte): **nunca vi o log real de um aborto** do Tendermint; a etapa 3b roda **primeiro só as 6 previstas** e confere o regex contra o log real **antes** do resto; se não casar, errata datada do regex **antes de qualquer outra célula**.

## 6. Sementes, replicações, transiente, regime
- **Fórmula:** `semente = semente_base + n·1000 + replicação`; `MVPComparison --seed = semente − n·1000`; observador `--run-seed = semente`. Mesma semente para todo f em V-B (pareamento). **Bases:** va **620 345**; vb **820 345**; vc **920 345** (faixas 624 346–651 350; 824 346–833 355; 924 346–936 350).
- **Disjunção de TODAS as sementes já usadas:** `checar-sementes-tm.py` leu **1 393 `cfg.json`** sob `experimentos/` (374 sementes distintas, 16 346 a 536 350, **incluindo K40, V-ABC-hotstuff e piloto**) **e** as listas esperadas do HotStuff: **0 colisões**, **0 duplicadas entre blocos** (`checagem-sementes-rascunho.json`; a repetição entre valores de f em V-B é intencional).
  **O V-ABC do Tendermint reaproveita a K40/V-ABC? Não**: as sementes são todas novas.
- **Réplicas:** 5 (V-A, V-C), 10 (V-B). **Duração:** 120 s (V-A, V-C); 300 s (V-B). Mínimo exigido: 100 alturas. **Estimativa de alturas por célula: não medida** (no HotStuff, Tb de 0,13 a ~0,25 s; o Tendermint sem `timeout_commit` deve ser comparável, mas **não sei**; se uma célula tiver < 100 alturas ela é não íntegra).
- **Transiente:** V-A agregado: a janela de regime do código (alturas 2 a hmax−2) já exclui o arranque; V-C e V-B: t0 = **20 s** simulados (aceito pelo humano para o HotStuff em 16/11; **proposta** manter para o Tendermint).
- **Determinismo (verificação extra, declarada):** 4 células executadas **duas vezes** com a **mesma semente** em `dados-brutos/determinismo-rep/` (`tva_n31_s01`, `tva_n7_s01`, `tvc_n16_s01`, `tvb_n13_f05_s01`); exige-se saída idêntica (CSV por bloco, `mvp-results*.csv`, `observador-por-altura`, `pacotes-por-altura`, `por-no-por-altura`). **Não entram nas estatísticas.**
  `comparar-determinismo.py` **do HotStuff** procura nomes `hotstuff-*`; **precisa de uma versão `-tm`** (a escrever no pino; **não está neste rascunho**).

## 7. Custo e paralelismo
**NENHUMA célula de Tendermint foi medida.** `custo-estimado-tm.py` usa como proxy o **custo MEDIDO do HotStuff** (`custo-vb-e7.json`, 76932b9, com a flag) × `(2n+1)/8` (razão de pacotes por altura Tendermint/HotStuff), supondo a **mesma taxa de alturas por segundo** (limite declarado):

| Bloco | Células | CPU (média; teto) | Parede com 8 paralelos (média; teto) |
|---|---|---|---|
| V-A | 25 | 818 s; 949 s | 1,8 min; 2,1 min |
| V-C | 10 | 179 s; 244 s | 0,4 min; 0,5 min |
| V-B | 180 | 8 880 s; 10 321 s | **19,9 min; 23,2 min** |
| **Total** | **215** | **9 876 s; 11 515 s** | **22,2 min; 25,8 min** (teto × 3 = 77,5 min) |

Eficiência de paralelismo medida no HotStuff: 7,43× com 8 processos (`maxCelulasDeSimulacaoParalelas = 8`). Mais ~2 min de build, 4 repetições de determinismo e análise: **≈ 30 min, abaixo de 1 h, no teto × 1; o pior caso × 3 passa de 1 h**.
**Limites:** proxy não medido para o Tendermint; V-B tem **1 JVM** (o proxy conta 2: pessimista); custo com ataque, n = 13 e a taxa de alturas do Tendermint **não medidos**; memória: o Tendermint retém O(n) votos por (altura, round) e **não tem teto** hoje (a PARIDADE o cria): **8 paralelos de n ≤ 31 devem caber; não medido**; disco ≈ 0,4 a 1 MB por célula (mais `por-no-por-altura.csv.gz`).
**Rodar nesta ordem na etapa 2:** (1) as 6 previstas a abortar (**só** para conferir o regex); (2) `va`; (3) `vc`; (4) o resto de `vb`.

## 8. O que depende do pino (e o que o invalida)
0. **Checagem da PARIDADE, feita em 20/11/2026 (sem ataque e sem atraso):** o HEAD `45425dab` (antes da paridade) e a árvore com a paridade, n = 4, 7, 10, 16 e 31, 120 s, seed 1, `-XX:hashCode=2`, `--tm-*` do V-A, `MVPComparison` com 3 arquivos por caso: saída **idêntica byte a byte** nos 5 casos. **Não cobre** V-B (com ataque), nem outras sementes, nem o observador. O `ObservadorTm` agora lê `--tm-max-future-horizon` da linha de comando (valor de `config/fatores-*.json`, 1000) e grava `descartes_FUTURE_BEYOND_HORIZON_total`; no caminho feliz (n = 4, 20 s) o contador é 0. **O SHA do observador mudou** e só vale no pino.
1. **Todos os números previstos acima** valem para `bc07355`; a **PARIDADE de teto** (Tendermint passa a ter teto de retenção) pode alterar a saída. Checagem pré-registrada: **no caminho feliz o teto de retenção não pode ser atingido** (sem ataque e sem timeouts). **Hoje o código tem um contador** (`peakFutureMessages` do núcleo; o `ObservadorTm` o grava como `pico_mensagens_de_altura_futura_max_por_no` e o `max_lead_de_alturas`); **se a PARIDADE acrescentar um contador de descarte por teto, o observador deve lê-lo** (**ajuste do observador no pino e novo SHA**). Sem contador de descarte, a checagem é **indireta**: se `INVALID_VALUE`/`OLD_HEIGHT` aparecerem sem falha, o caminho feliz não foi feliz.
2. **O sorteio dos ids bizantinos** (BYZ-UNICA: gerador único) e, portanto, **as células que abortam** (seção 5).
3. **A autoentrega (D2)**: a regra do `isLocalSelfDelivery` hoje é do hook; o pino pode mudá-la.
4. **A API que o observador usa** (`getHeightLedger`, `getCore().discardCount`, `TendermintTimeoutEvent.getKind`, `ByzantineConfig.withExactCount`): reconferir a compilação no pino e re-hashear.
5. **A tabela de artefatos e o hash do PREREGISTRO** (seção 11): só depois do pino.
6. **Os custos** (`custo-estimado-tm.py`) e o `versao-simulador.json`.
**Se a PARIDADE alterar a saída do Tendermint, este V-ABC repete**; um resultado sobre `bc07355` **não** vale para o pino.

## 9. Decisões que dependem do humano
1. **Parâmetros `--tm-*` "a definir"** (seção 4): timeouts (3/1/1 s, delta 0,5 s), assinatura de voto e de proponente (64 B), overhead (10 B). Valores provisórios; um valor diferente muda o resultado de latência, **não** a classe de complexidade.
2. **`MIN_EXEC = 6` com `k_exec = 4` em n = 4, f = 2** (seção 5): aceitar o INCONCLUSIVO, ou fixar outro mínimo **antes dos dados**, ou pedir mais sementes.
3. **V-C numérico inviável** (proposta, seção 3.4 e `FONTES-V-C.md`): confirmar; ou fornecer os pares (vazão, latência) das figuras 9.1 e 9.3 da tese de Buchman marcados como "lidos de figura" e decidir se o regime (longa distância, tx de 250 B, timeouts a 1 ms) é comparável.
4. **P4b**: fecha-se só com V-A, ou exige a evidência de *locking* (PoLC) e votos nil (seção 3.2)? **Não os verifiquei.**
5. **V-B para o Tendermint com o critério inteiro f\* = ⌊(n−1)/3⌋+1 e vivacidade binária**: o humano os fixou para o HotStuff em 16/11; **aplicar ao Tendermint é minha proposta**. **Risco declarado (seção 3.3):** n = 4, f = 1 pode perder vivacidade por limitação do broadcast direto sem gossip; é um resultado do **modelo**, não de ~1/3.
6. **Conferência do PBFT** (seção 10): **fora desta rodada**; decidir se abre tarefa própria.
7. **Pino e commit** do pré-registro (precedente `06c67b5`): este agente **não commita**.

## 10. Conferência do PBFT: FORA desta rodada (e por quê)
O pedido era o bloco V-A do PBFT (O(n²) agregado; por nó O(n)) "se o código permitir". **O código não permite sem alterar `src/`:**
1. **O `MVPComparison` não tem `case "PBFT"`**: o despacho de protocolos (`runProtocol`) cobre ARQ, MCO2, TREE, AMBIFY, OFFSET, EARTH, DAGSPER, TENDERMINT e HOTSTUFF; `PBFTLANScenario` é **importada mas nunca instanciada** em `MVPComparison`. O PBFT só roda via `ARQ`
   (`ArqReddVotingScenario`/`HybridNetworkScenario`, uma topologia híbrida de n nós de rede, **não** o PBFT puro de n validadores) ou por testes JUnit.
2. **O PBFT não tem `NetworkHeightLedger`**: mede por bloco recebido pelo **primeiro nó** (`addBlockTraffic`), com **autoentrega contada** (o broadcast do `PBFTP2P` inclui o remetente entre os vizinhos), o que viola a regra 6 e é o alvo da tarefa **D2 (autoentrega)**, **ainda não feita**. Não há soma da rede por altura no PBFT.
3. **Um observador externo não corrige isso**: sem alterar `src/`, o PBFT não expõe contadores por altura nem a soma da rede; qualquer fórmula que eu escrevesse seria **estimativa**, que a regra 6 proíbe.
**Proposta (aguarda o humano):** conferir o PBFT **depois** de D2 e PARIDADE (que mexem exatamente aí) e da tarefa que dê ao PBFT o `NetworkHeightLedger`; a granularidade já declarada (agregado O(n²); por nó O(n), `PBFTBlockVote` recebido por todos) fica registrada **como previsão por código**, **não** como critério desta rodada.

## 11. O que NÃO foi verificado (limites)
- **Nenhuma célula foi simulada**; todas as previsões vêm de leitura de código, e a fórmula **nunca foi conferida com dado** (nem num ponto antigo).
- **O PINO não existe**; tudo que depende dele (seção 8) pode mudar.
- Que o `ObservadorTm` **não perturba** a simulação (só se verifica na etapa 2, em V-A e V-C; **em V-B não há segunda JVM**).
- **Que o regex do aborto casa com o log real** (nunca vi um aborto do Tendermint).
- **O custo** de qualquer célula de Tendermint, com ou sem `-XX:hashCode=2`; **a taxa de alturas por segundo**.
- **Que 3/1/1 s não disparam timeout sem falha** (estimativa por análise, não por piloto).
- **A previsão de risco de V-B** (n = 4, f = 1 sem vivacidade) vem do Javadoc de um teste com poucas sementes (1, 7, 8, 42) e config de teste; **não é dado desta rodada**.
- **Segurança** (dois commits conflitantes) **não** é medida por V-B. A distância Raft (Fabric) × BFT (JABS) segue valendo (`AGENTS.md`): V-ABC valida o **instrumento**, não afirma nada sobre o Fabric.
- **Distâncias do Tendermint real:** sem gossip, sem `timeout_commit`, sem POL em bytes; LAN simulada; Java 25 em vez de 17.
- Independência entre sementes consecutivas (MersenneTwister por semente, como nas rodadas anteriores).
- Nenhum número publicado de Tendermint em LAN é VERIFICADO (`FONTES-V-C.md`); a fonte 5 (docs do CometBFT) e a 6 (Cosmos) são **NÃO VERIFICADAS**.

## 12. Etapa 2: comandos exatos (só depois do pino e do commit do pré-registro)
Raiz do repositório = diretório de trabalho. `V=experimentos/validacao/tendermint`.
```
0.  python3 $V/scripts/hash-preregistro-tm.py estado                     # exit 0 = pino definido
    python3 $V/scripts/hash-preregistro-tm.py conferir --artefatos        # exit 0 obrigatório
    git status --short -- $V                                              # sem alteração não commitada
1.  python3 $V/scripts/checar-sementes-tm.py                              # exit 0
2.  python3 $V/scripts/preparar-tm.py                                     # build em /tmp/opencode/val-tm a partir do pino; grava $V/versao-simulador.json
3.  for b in va vc vb: python3 $V/scripts/gerar-celulas-tm.py gerar $b --saida $V/celulas/$b --simulador-commit <COMMIT_PINADO>
    python3 $V/scripts/gerar-celulas-tm.py conferir-manifesto $b $V/celulas/$b/manifesto.json   # exit 0
    python3 $V/scripts/listar-vb-tm.py --build /tmp/opencode/val-tm       # regera as células que abortam no pino
4.  PILOTO DE REGEX: as 6 células previstas a abortar (rodar_celula.py célula a célula); conferir o log real contra o regex de analisar-vb-tm.py; errata datada ANTES de qualquer outra célula se não casar.
5.  python3 $V/scripts/executar-lote-tm.py $V/celulas/va --saida $V/dados-brutos/va --paralelo 8     # sem espiar resultado parcial
    python3 $V/scripts/executar-lote-tm.py $V/celulas/vc --saida $V/dados-brutos/vc --paralelo 8
    python3 $V/scripts/executar-lote-tm.py $V/celulas/vb --saida $V/dados-brutos/vb --paralelo 8 --pular-existentes   # exit 3 esperado (abortos pela regra)
6.  rodar as 4 células de determinismo de novo e comparar (versão -tm de comparar-determinismo.py, a escrever no pino)
7.  python3 $V/scripts/analisar-va-tm.py $V/dados-brutos/va --vc $V/dados-brutos/vc --saida $V/analise-va.json
    python3 $V/scripts/analisar-vb-tm.py $V/dados-brutos/vb --saida $V/analise-vb.json
```
Depois: `PARECER-TECNICO.json` (campos da skill: `afirmacao`, `impacto` = ALTO, `requisito`, `fontesPrimarias`, `n_celulas_esperadas` = **215**, `n_celulas_obtidas`) e entrega ao loop 3. **O parecer não inclui o "porquê está certo".**
**Nada nesta lista foi executado.** O que **foi** executado como dry-run: `preparar-tm.py --dry-run-head`, `gerar-celulas-tm.py gerar/conferir-manifesto` (em `/tmp`), `dry-run-comandos-tm.py` (TUDO OK), `listar-vb-tm.py`, `checar-sementes-tm.py`, `analisar-va-tm.py --autoteste`, `analisar-vb-tm.py --autoteste`.

## 13. O que é pós-hoc (mistura proibida)
Resultado primário = **só as células novas** dos blocos `va`, `vb`, `vc`. **Nada** do HotStuff entra no veredito do Tendermint (o V-ABC do HotStuff serviu só para copiar a estrutura e para a previsão de risco). **Pós-hoc (rotulado e fora do veredito):** outro limiar, outro T/timeout, tolerância F/E diferente, exclusão de célula,
qualquer conclusão sobre n = 64 ou 128, **qualquer comparação Tendermint × HotStuff × PBFT**, qualquer número publicado não VERIFICADO, e **qualquer reparametrização do timeout depois de ver dado**.

---

### Artefatos (SHA-256)

**PENDENTE: a tabela de artefatos é gravada pelo comando `hash-preregistro-tm.py listar <caminho>...` no pino e só então entra aqui.** Enquanto `COMMIT_PINADO = a definir`, este arquivo **não tem hash**.
Artefatos previstos (sem hash agora): `config/fatores-{va,vc,vb}.json`, `celulas-esperadas.json`, `checagem-sementes-*.json`, `FONTES-V-C.md`, `harness/ObservadorTm.java`, `vb-celulas-abortam-previsto.json`, `custo-estimado-tm.json`, `scripts/*.py`
e os `scripts/jabs/gerar-grid.py` e `scripts/jabs/validar-complexidade.py` (usado pela regra S).
