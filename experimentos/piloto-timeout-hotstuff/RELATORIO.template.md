# RELATÓRIO — PILOTO-timeout-hotstuff-por-n

Gerado a partir de `RELATORIO.template.md` + `scripts/gerar-tabelas.py` (as tabelas vêm dos JSON de análise, sem transcrição manual).
Autor: `jabs-experimento` (proponente do loop 3). Este relatório contém as conclusões do proponente; o critic recebe só `PARECER-TECNICO.json` (fontes primárias), não este arquivo.

## 0. Resumo

1. **O critério de aceite congelado NÃO foi atendido.** Com m = 2, A3 (D_max ≤ ½ T) falha em todos os n. Com m = 3 (o máximo que a regra permite), A1 (zero timeouts) e A2 (mínimo de visões) passam em todos os n, mas **A3 falha em n = 16** na verificação com sementes novas (D_max = 0,6856 s contra T/2 = 0,545 s; razão 0,629). A regra congelada diz para não subir de m = 3 e pedir decisão humana: é o que este relatório faz (§6).
2. **Valores candidatos** de `--hs-view-timeout` (L = 100, m = 3, ECDSA 64 B individual, política mais lenta entre ROTATING e STABLE): **n = 4: 0,918 s; n = 16: 1,090 s; n = 64: 2,065 s; n = 128: 3,396 s.** Aceitos por A1, A2 e A3 em n = 4, 64 e 128; **não aceitos em n = 16**. Nenhum deles é decisão: o valor numérico é do humano.
3. **O timeout depende fortemente de L.** Com n = 128, a duração média de visão vai de 0,40 s (L = 10) a 0,87 s (L = 100) e 2,96 s (L = 500). O T de L = 100 não serve a L = 500 para n ≥ 16 (D_max/T entre 0,85 e 0,96 sem disparar timeout, mas sem folga) (§5.3).
4. **Em STABLE com o líder (nó 0) bizantino, o sistema não finalizou nenhum bloco em 600 s em 28 de 28 células**, e cerca de 99 % das visões de cada réplica terminam por timeout. Em STABLE o líder é sempre o nó 0 (`HotStuffConsensusConfig.leaderFor`), então o timeout troca de visão mas não troca de líder (§8).
5. **O ataque SILENT não existe para o HotStuff no repositório.** O `MVPComparison` recusa SILENT, WITHHOLD e DOUBLE_SIGN para HOTSTUFF; só EQUIVOCATION está implementado. Usei EQUIVOCATION (evidência em `conferencia.json`).
6. **`--hs-batch-size` é o único controle de lote do HotStuff.** O líder propõe sempre lote cheio, não há fila nem tempo máximo T. O T = 2 s do registro 12/11 não se aplica (§9.1).
7. **Achado de instrumento (custo):** com `-XX:hashCode=2` (exigido por `--load-record`), a mesma célula com L = 500 e n = 4 (30 s simulados) levou 58,1 s de parede contra 0,56 s sem a flag, com saída idêntica (§9.2). Isso afeta o custo das baterias de produção que liguem o registro de carga.

## 1. Portão e escopo (declaração)

- `registrar-rodada.py --verificar-gate loop2-jabs-codigo` retornou **GATE FECHADO** em 2026-11-12, com `V-ABC-hotstuff` e `PILOTO-timeout-hotstuff-por-n` em `PENDENTE_REVISAO_HUMANA`. O portão V-A/V-B/V-C do HotStuff **não está registrado**.
- Executei mesmo assim porque a tarefa o determinou e porque a sequência decidida no ADR-0015 (registro 11/11) põe este piloto (passo 3) **antes** de `V-ABC-hotstuff` (passo 4): o timeout é parâmetro obrigatório daquele portão. Tratei como **piloto de calibração de instrumento**, não como bateria de produção, e nenhum número daqui compara protocolos. Se o orquestrador ou o humano entenderem que isso viola a regra "não iniciar bateria sem portão", os dados ficam como estão e nada deve ser usado até a decisão.
- Não alterei `src/`, `pom.xml`, `docs/adr` nem li `rede-fabric/` ou chaves. Todo o escrito está em `experimentos/piloto-timeout-hotstuff/`. Não registrei rodada nem memória (é do orquestrador).
- Os critérios foram congelados em `CRITERIOS-congelados.md` antes da medição principal. Eles foram escritos depois de 24 células de sonda (1 semente; preservadas em `dados-brutos/sonda/`), usadas só para dimensionar a duração e conferir o observador.

## 2. Configuração exata

Simulador: `jabs.example.MVPComparison`, `--protocols=HOTSTUFF`, `--trials=1`, uma semente por JVM. Rede: `HotStuffLocalLANNetwork` com `LAN100MNetworkStats` (latência Pareto, média 20 ms, forma 5; banda 100 Mbps constante; lido no código).

| Flag | Valor | Significado e faixa (lidos em `MVPComparison.parseHotStuffConfigIfRequested` e `HotStuffConsensusConfig`) | Fonte do valor |
|---|---|---|---|
| `--hs-view-timeout` | **60 s na medição** (nenhum dispara); T proposto na verificação | double finito > 0; timer por visão, constante, sem backoff (PROVISÓRIO no código) | tarefa; proposta vem da análise |
| `--hs-max-future-view-horizon` | 1000 | long > 0; visões futuras retidas | tarefa |
| `--hs-batch-size` (L) | **100** (central); 10 e 500 (sensibilidade) | int > 0; transações por bloco, lote sempre cheio | registro 12/11 |
| `--hs-tx-size` | 512 B | int > 0 | registro 12/11 |
| `--hs-signature-mode` | INDIVIDUAL | enum INDIVIDUAL/AGGREGATED | tarefa |
| `--hs-individual-signature-size` | 64 B | int > 0; assinatura de cada voto e de cada assinante no QC | registro 12/11 (ECDSA, RFC 8032); tamanho DER no Fabric NÃO VERIFICADO |
| `--hs-aggregated-signature-size` | 96 B | só lido em AGGREGATED (`qcSignatureSize`) | **valor operacional do piloto, sem fonte, a definir com o humano**; sem efeito em INDIVIDUAL (V2 abaixo) |
| `--hs-aggregated-share-size` | 96 B | só lido em AGGREGATED (`voteSignatureSize`) | **valor operacional do piloto, sem fonte, a definir com o humano**; sem efeito em INDIVIDUAL |
| `--hs-message-overhead` | 10 B | int ≥ 0; bytes de cabeçalho por mensagem | **valor operacional do piloto, sem fonte, a definir com o humano** (ADR-0015: fixture igual ao Tendermint); sensibilidade em §5.4 |
| `--hs-leader-policy` | ROTATING e STABLE | ROTATING: líder = visão mod n; STABLE: sempre o nó 0 | tarefa |
| `--hs-voter-encoding` | BITMAP | único valor do enum; ceil(n/8) B por QC | único implementado |
| `--attack` / `--byzantine-count` | só nas células de ataque: EQUIVOCATION, f ∈ {1, ⌊(n−1)/3⌋} | única opção implementada para HotStuff | §8 |

Fatores: n ∈ {4, 16, 64, 128} (E1, ADR-0000); política ∈ {ROTATING, STABLE}; L ∈ {10, 100, 500}; 10 sementes por (política, L, n).

- **Carga:** não há gerador de transações no HotStuff do JABS (`HotStuffNode.processNewTx` é vazio de propósito; o bloco é "lote cheio" amostrado em `BlockFactory`). O rótulo `carga=estacionaria` nos nomes de célula é nominal (vem de `gerar-grid.py`); o regime real é **saturado** (o líder propõe a próxima visão assim que há quórum). A duração medida é, portanto, a de bloco cheio, sem espera por fila.
- **Duração simulada por célula (L = 100):** 368 / 460 / 941 / 1807 s para n = 4 / 16 / 64 / 128 (alvo de 2050 alturas mais 8 % de margem). L = 10: 176 / 190 / 245 / 395 s. L = 500: 245 / 512 / 1671 / 3303 s.
- **Transiente descartado (t0):** as 50 primeiras alturas de cada célula. Em L = 100 isso corresponde a t0 ≈ 8,3 / 10,4 / 21,2 / 40,7 s (n = 4 / 16 / 64 / 128). Conferência (L = 100): a duração média nas alturas 2 a 50 difere da média do regime em ≤ 1,8 % (maior diferença: ROTATING n = 128, 0,8834 contra 0,8681 s; `analise-medicao-L100.json`, campos `D_media_transiente_2a50` e `D_media_regime`), então o corte de 50 é conservador. Não repeti a conferência em L = 10 e 500.
- **Granularidade:** D (duração de visão) é **por réplica**, tomando o máximo entre as réplicas que finalizaram cada altura. `Tb` e `Cb` do CSV oficial são do **primeiro nó a finalizar** cada altura (cabeçalho `#` do CSV bruto). Quando uso Tb ou o intervalo entre finalizações do primeiro nó, o rótulo é "Tb" ou "Dprim.".
- **Versão do simulador:** `git rev-parse HEAD` = `9f305ddb678d5a456daee6cf7390daedc83d1876`; árvore **limpa** em `src/`, `pom.xml`, `scripts/` no momento do build (`versao-simulador.json`). Build numa cópia em `/tmp/opencode/piloto-hs` (sem `rede-fabric`), classpath por `mvn dependency:build-classpath`. Java OpenJDK 25.0.5 (o alvo do pom é 17), Maven 3.8.7.
- **JVM:** `java -Xmx2g` **sem** `-XX:hashCode=2` (a flag só é exigida por `--load-record`, que não uso; o motivo da exclusão é custo, §9.2). Validei que a saída é idêntica com e sem a flag em 4 comparações.

## 3. Método

### 3.1 Por que há duas JVMs por célula

O CSV por bloco do `MVPComparison` traz só o **primeiro** nó a finalizar cada altura, e **não emite a contagem de timeouts de visão disparados**, que é o critério de aceite. Não alterei o simulador. Usei `harness/ObservadorHotStuff.java`, **fora de `src/`**, que monta o mesmo cenário que `MVPComparison.runHotStuffScenario` (mesma semente, mesmo config, mesmo logger de CSV por bloco) usando só a API pública já compilada, e acrescenta um `AbstractLogger` passivo que anota a finalização local de cada nó e lê `HotStuff#getTimeoutAdvances` ao final.

Cada célula roda: (1) `MVPComparison` (caminho oficial, gera o CSV bruto); (2) o observador (mesma semente). **Equivalência:** o MD5 do CSV por bloco sem a coluna `BlockHashCode` é igual nas duas JVMs em **650 de 650** células com blocos finalizados (`conferencia.json`).

### 3.2 Definição de D (duração de visão)

`D(h)` = máximo, entre as réplicas que finalizaram a altura h, de `t_final_local(h) − t_final_local(h−1)` da mesma réplica (para h = 1, o instante de `start()` é 0). É o que o timer de cada réplica compara: o timer é armado ao entrar na visão (`HotStuff.armViewTimer`, em `start()` e em `endView`) e a réplica deixa a visão ao finalizar por DECIDE. Usei o máximo porque o timeout dispara se **qualquer** réplica estourar. Em nenhuma célula honesta houve finalização com salto de altura (`finalizacoes_com_salto_de_altura` = 0).

### 3.3 Contagem de timeouts

`timeouts_disparados_total` = soma de `HotStuff#getTimeoutAdvances()` nos nós (`ReplicaCore.timeoutAdvances`, incrementado em `advanceViewByTimeout`). O contador `STALE_VIEW_TIMEOUT` **não** conta: é o timer obsoleto de visão já decidida e existe em toda visão.
**Conferência independente do contador:** em todas as 590 células honestas (todos os lotes exceto ataque), a visão final de cada réplica menos a altura final vale 1 (518 células) ou 0 a 1 (72 células, quando uma réplica ainda não avançou no fim da simulação). Cada timeout somaria 1 a essa diferença, então ela confirma que não houve timeout sem depender do contador.

### 3.4 Execução e rastros

- Células geradas por `scripts/gerar-celulas.py` (que chama `scripts/jabs/gerar-grid.py` do repositório) e executadas por `scripts/executar-lote.py` (até 8 JVMs em paralelo, o limite do `enxame.config.json`). Cada célula tem `cfg.json`, `comando-mvp.txt`, `comando-observador.txt`, logs, `resultado.json` (códigos de saída, tempo de parede, memória, MD5 do CSV sem `BlockHashCode`), o **CSV bruto gzip** (`hotstuff-<n>v-t0.csv.gz`) e `obs/` (`observador-por-altura.csv.gz`, `observador-resumo.json`, `observador-trocas-por-timeout.csv.gz`, CSV do observador).
- **Semente:** `semente = semente_base + n·1000 + replicação` (`gerar-grid.py`). `MVPComparison` recebe `--seed = semente − n·1000` e usa `runSeed = --seed + n·1000 + 0`; o observador recebe `--run-seed = semente`. A semente está em `cfg.json` e em `stdout-mvp.log`. Mesma semente-base e mesmas replicações nas duas políticas e nos três L. **Consequência:** ROTATING e STABLE do mesmo (L, n) **não são amostras independentes**.
- **Esperadas × obtidas:** 678 × 678, sem célula faltante (`conferencia.json`). Os 28 `rc_mvp ≠ 0` são exatamente as células STABLE com líder bizantino (falha alta deliberada do `MVPComparison` por zero blocos, §8); o observador terminou com código 0 nas 678.
- **Explorações não preservadas** (em `/tmp`, apagadas ao reconstruir a cópia): fumaça de custo, uma corrida com `-XX:hashCode=2` (391 s de parede para 60 s simulados, n = 4, L = 500) e uma corrida de 20 000 s simulados (n = 16, L = 500) que estourou memória com `-Xmx2g`. Servem de contexto para o limite de duração, não de dado.

## 4. Resultados da medição (sem falha)

Nas tabelas, "D" é a duração de visão por réplica (máximo entre réplicas), "Tb" é a latência de bloco do CSV oficial (criação → finalização no primeiro nó) e "Dprim." é o intervalo entre finalizações consecutivas do primeiro nó. Quantis por nearest-rank (posto `ceil(p·N)`). p99,99 aparece porque N ≥ 10^4 em todos os grupos (a estimabilidade é discutida em §7). Contagem de visões = N (pós-t0).

### 4.1 Caso central, L = 100

{{T:Medição L=100 (caso central)}}

### 4.2 Sensibilidade, L = 10

{{T:Medição L=10}}

### 4.3 Sensibilidade, L = 500

{{T:Medição L=500}}

Leitura: D é sempre maior que Tb (por exemplo, n = 16, L = 100: 0,230 contra 0,167 s) porque D inclui o NEW_VIEW e a propagação da proposta antes de o bloco ser criado. **Tb não serve de base para o timeout.** Timeouts disparados na medição: 0 nas 240 células (60 s de timeout, bem acima de D_max).

## 5. Proposta de timeout e verificação do critério de aceite

### 5.1 Regra (congelada antes da medição)

`T(n, L) = m × D̂ p99,99(n, L)`, com D̂ = o **maior** p99,99 entre ROTATING e STABLE; m = 2 e, se algum item de aceite falhar, m = 3; se falhar com m = 3, não subir (relatar). Aceite no caso L = 100: **A1** zero timeouts nas células honestas da verificação (sementes novas, `semente_base = 22345`, timeout = T proposto); **A2** mínimo de visões (≥ 2000 por célula em L = 100, ≥ 1000 em L = 10/500, e N ≥ 10^4 por grupo); **A3** D_max ≤ ½ T.

### 5.2 Propostas por n e L (medição)

{{T:Propostas de timeout}}

O código escolheu **m = 3** pela medição: com m = 2, A3 falha em todos os n e todos os L (`propostas-timeout.json`).

### 5.3 Verificação com sementes novas (L = 100)

{{T:Aceite (L=100, m=3 e m=2)}}

- **A1:** passa. 0 timeouts disparados nas 80 células honestas da verificação com T de m = 3 (172 744 visões somadas nas duas políticas). Com os T de m = 2 sobre as mesmas sementes novas, também 0 timeouts, mas com margem de apenas 6 % em n = 16 (D_max = 0,6856 contra T = 0,727 s).
- **A2:** passa. Mínimo de 2131 visões pós-t0 por célula e 21 348 por grupo (medição e verificação); em L = 10 e L = 500 o mínimo é 1068 e 1070 por célula e 10 712 e 10 716 por grupo.
- **A3:** passa em n = 4, 64 e 128 e **falha em n = 16**. Em n = 16 a medição deu D_max = 0,5106 s (razão 0,468, passaria) e a verificação deu 0,6856 s (razão 0,629). O pico é uma única altura (`ver_m3_rotating_L100_n16_..._s07`, h = 692, e quase idêntico em STABLE s07): uma réplica finalizou 0,49 s depois das demais (`Fmax − Fmin` = 0,4939 s), enquanto as outras seguiram no ritmo normal (`diagnostico.json`).
- **Instabilidade do critério A3:** D_max é o máximo da amostra, que cresce com o número de visões. Os máximos observados valem de 1,06 a 1,74 vezes o p99,99 da própria amostra (§7). Um critério que depende do máximo pode falhar para qualquer m finito se a amostra for grande o bastante. Isso é uma observação sobre o critério congelado, **não** uma mudança dele.

**Sensibilidade a L usando o T de L = 100 (m = 3), sementes novas:**

{{T:Sensibilidade a L com o T de L=100}}

Nenhum timeout disparou. Mas para L = 500 e n ≥ 16 a razão D_max/T fica entre 0,852 e 0,964: sem folga. Os T próprios de cada L (m = 3, tabela de §5.2) são 1,023 / 1,829 / 5,211 / 9,691 s em L = 500, e 0,827 / 0,905 / 1,459 / 2,000 s em L = 10. **Esses T não foram verificados com sementes novas** (só o de L = 100 foi).

### 5.4 Sensibilidade ao overhead de mensagem (valor sem fonte)

`--hs-message-overhead` de 10 B para 100 B, ROTATING, L = 100, 3 sementes pareadas (`sens-overhead.json`): a média de D sobe 0,31 % em n = 16 (0,2303 → 0,2310 s) e 0,69 % em n = 128 (0,8683 → 0,8742 s); o máximo muda na quarta casa. Conclusão: o timeout não depende desse valor dentro da faixa testada. `--hs-aggregated-*` não altera nada em INDIVIDUAL (MD5 do CSV idêntico com 96 B e 200 B, `verificacao-instrumento.json`).

## 6. O que fica sem aceite e o que pedir ao humano

1. **Aceite não atendido em n = 16.** Opções (decisão do humano, não minha): (a) subir m acima de 3, o que a regra congelada proíbe sem decisão. Para A3 valer em n = 16 sobre estas amostras bastaria T ≥ 1,371 s (= 2 × D_max empilhado), isto é, m ≥ 3,78 sobre o D̂ da medição (0,3631 s) ou m ≥ 3,48 sobre o p99,99 empilhado (0,3945 s); mas D_max cresce com N; (b) trocar A3 por um critério sobre quantil em vez de máximo (por exemplo p99,999 com IC), com justificativa; (c) aceitar A3 só em n ∈ {4, 64, 128} e tratar n = 16 à parte. **Nenhuma foi aplicada.**
2. **Cota de risco:** em ~43 mil visões por (n, política) (medição + verificação) nenhuma teve D ≥ T(m = 3); pela regra de três isso dá cota superior de ≈ 7 × 10⁻⁵ por visão a 95 % (aproximada: visões dentro de uma célula não são i.i.d.; e a medição, com timeout de 60 s, só informa que D < T, o que equivale a "nenhum timeout teria disparado"). Uma corrida de 7200 s com n = 4 tem ~40 mil visões; logo, em produção, um timeout espúrio ocasional não está descartado.
3. **T depende de L** (§5.3): o valor de `--hs-view-timeout` tem de ser fixado **por (n, L)**, e L ainda é parâmetro em aberto no ADR-0015.

## 7. Estimabilidade do p99,99 (N ≥ 10^4)

Com N ≈ 21,6 mil por grupo (L = 100) e ≈ 10,8 mil (L = 10, 500), o p99,99 por nearest-rank **é calculável** (posto 21 632 de 21 634, por exemplo), mas **é estimado a partir de 2 a 4 amostras da cauda**. O IC95 por estatística de ordem não tem limite superior quando N < 36 887 (o máximo não limita o quantil por cima); por isso nas tabelas o superior aparece como ∞. O IC por bootstrap percentil tem o máximo como teto e, em quantis extremos, tende a subestimar a incerteza (reportado, mas sem crédito).

**Suplemento pós-hoc (rotulado; não altera o aceite):** empilhando medição e verificação do mesmo L e política (20 sementes, N ≈ 43 mil) o limite superior existe:

{{T:Suplemento empilhado}}

O p99,99 muda pouco para n = 4 e 128 e visivelmente para n = 64 (0,6855 → 0,6518 s, ROTATING) e n = 16 (0,3584 → 0,3945 s). O máximo vale de 1,06 a 1,74 vezes o p99,99. **Conclusão: p99,99 é estimável apenas grosseiramente; o intervalo de confiança do p99,99 inclui valores até ~1,5 vezes o ponto.** Para uma estimativa estável seriam necessárias ≥ 10^5 visões por (política, L, n), o que custa em n = 128 algo como 4 a 5 vezes a duração usada aqui.

Cauda observada (sem extrapolação; ROTATING, L = 100, 20 sementes):

{{T:Cauda de D (ROTATING, L=100)}}

Tentei uma hipótese de cauda Pareto com um pacote lento no caminho crítico (`scripts/cauda.py`); ela **superestimou 5 a 20 vezes** as contagens observadas e foi **descartada**. Não há extrapolação de P(D > T) neste relatório.

Padrão dos picos (o maior D de cada n, `diagnostico.json`): a altura de pico tem `Fmax − Fmin` de 0,17 s (n = 4), 0,49 s (n = 16), 0,50 s (n = 64) e 0,44 s (n = 128), isto é, **uma réplica** finaliza bem depois das outras, enquanto as alturas vizinhas têm espalhamento de 0,02 a 0,10 s (exceto a seguinte ao pico de n = 16, com 0,28 s). Em 3 dos 4 picos a altura seguinte tem `Rmin` entre 0 e 0,01 s (a réplica atrasada finaliza duas alturas quase juntas); em n = 128 não (`Rmin` = 0,46 s). **Não investiguei a causa dentro do simulador** (fila de enlace, latência Pareto, ou outra).

## 8. Comportamento com falha injetada (STABLE com ataque)

Ataque: **EQUIVOCATION** (único implementado para HotStuff; SILENT, WITHHOLD e DOUBLE_SIGN são recusados pelo `MVPComparison`, com a mensagem "ataque X nao implementado para HOTSTUFF (so EQUIVOCATION)", `conferencia.json`). Células: 88, duração 600 s, L = 100, T = T(m = 3) do n. Sementes escolhidas **só** pelo sorteio bizantino (`withExactCount`), estratificadas por 0 ∈ conjunto (**líder bizantino**) ou 0 ∉ conjunto (**líder honesto**), 4 por estrato (`config/selecao-ataque.json`; conferido em `diagnostico.json`: 88 de 88 coerentes). Em ROTATING (comparador, só f = 1) o líder gira, então os dois "estratos" são apenas 4 + 4 sementes distintas por n.

{{T:Ataque (EQUIVOCATION)}}

Leitura (descritiva; o critério de zero timeouts **não se aplica** com falha):

- **STABLE, líder bizantino (nó 0):** 0 blocos em 600 s em todas as 28 células (n = 4 a 128, f = 1 e f = ⌊(n−1)/3⌋). Quase toda visão de toda réplica termina por timeout (0,989 a 0,997 por réplica por visão). O `MVPComparison` falha alto ("finalizou 0 blocos") nessas 28 células; os números vêm só do observador. Em STABLE o líder é fixo, então a troca de visão por timeout **não restaura a vivacidade**. É consequência da política e da ausência de substituição de líder; não verifiquei se isso é intencional no desenho do `STABLE` (ablação do protocolo experimental).
- **STABLE, líder honesto, f réplicas bizantinas equivocando votos:** progresso normal e **0 timeouts** em 28 de 28 células, inclusive com f = ⌊(n−1)/3⌋ (n = 128, f = 42: 735 alturas em 600 s, D_max = 1,02 s).
- **ROTATING com 1 bizantino:** progresso contínuo, com vazão reduzida (n = 4: 2,07 alturas/s contra 5,93 com líder honesto em STABLE; n = 16: 3,53 contra 4,80; n = 128: 1,19 contra 1,22; STABLE com líder honesto e f = 1, `analise-ataque.json`, que não é o mesmo regime de política, só uma referência). A fração de visões terminadas por timeout, por réplica, é **1/n** (0,249 / 0,062 / 0,015 / 0,008 para n = 4 / 16 / 64 / 128, contra 1/n = 0,25 / 0,0625 / 0,0156 / 0,0078): as visões em que o bizantino é líder estouram o timeout, as demais não. D_max (1,16–1,26 / 1,35–1,43 / 2,55–2,57 / 4,26–4,28 s para n = 4 / 16 / 64 / 128) fica acima de T, o que é consistente com visões terminadas por timeout (que duram pelo menos T).
- Efeito do ataque sobre D das visões honestas: não analisei. Cada célula de ataque tem `observador-por-altura.csv.gz` para isso.

## 9. Achados de instrumento

### 9.1 `--hs-batch-size` é o único controle de lote; T = 2 s não se aplica

`HotStuff.proposeBlock` chama `BlockFactory.sampleHotStuffBlockWithTx(simulator, creator, parent, config, txSizeBytes, config.getBatchSize())` (lote **cheio**), sem fila de transações pendentes e sem tempo máximo de formação do bloco. `HotStuffNode.processNewTx` é vazio de propósito. Logo, o único controle do tamanho de bloco é `--hs-batch-size` e o T = 2 s do registro 12/11 não tem onde atuar. As flags `--enable-tx` e `--tx-interval` do `MVPComparison` não são lidas pelo ramo HotStuff.

### 9.2 `-XX:hashCode=2` e custo

Mesma célula (ROTATING, L = 500, n = 4, 30 s simulados, semente 16346; `dados-brutos/validacao/verificacao-instrumento.json`): **0,56 s** de parede sem a flag e **58,12 s** com `-XX:+UnlockExperimentalVMOptions -XX:hashCode=2`; CSV por bloco (sem `BlockHashCode`) com MD5 idêntico. Para L = 100 a razão é de 4 a 5,5 vezes (0,57 → 2,32 s em n = 4; 0,56 → 3,07 s em n = 16); para L = 500, de 21 vezes em n = 16 (0,57 → 12,07 s) a 104 vezes em n = 4 (0,56 → 58,12 s). São uma só corrida por ponto, sem repetição. Causa **provável e não investigada**: com `hashCode=2` o `identityHashCode` é constante e os `HashSet<HotStuffTx>` do lote degeneram (o `BlockFactory` monta `new HashSet<>()` com `HotStuffTx` sem `hashCode` próprio). **Efeito:** `--load-record=true` exige essa flag, então baterias com registro de carga e L = 500 podem custar 1 a 2 ordens de grandeza a mais; é dado de entrada para o piloto de custo (Q33-A). Não é mudança de código minha; a decisão (corrigir `HotStuffTx.hashCode` ou aceitar o custo) é do `jabs-builder`/humano.

### 9.3 Validações de instrumento

`dados-brutos/validacao/verificacao-instrumento.json`: V1 (com/sem `hashCode=2`: 4 de 4 idênticos), V2 (`--hs-aggregated-*` irrelevante em INDIVIDUAL: idêntico), V3 (overhead 10 → 100 B **altera** a saída, como deve).

## 10. Ameaças à validade

1. **Modelo de rede único.** LAN 100 Mbps, latência Pareto (média 20 ms, forma 5), banda constante, malha completa, sem perda. Não é a rede do Fabric; o D medido não vale para outra topologia. Não verifiquei se o modelo inclui tempo de CPU (assinar/verificar); D contém apenas rede e filas de enlace.
2. **Regime saturado, sem fila.** O líder propõe lote cheio imediatamente; com a fila do ADR-0013 (lotes parciais) D tende a ser menor, e o T daqui seria conservador. A conclusão precisa ser refeita quando a fila existir.
3. **A semente é compartilhada** entre políticas e entre L, então ROTATING e STABLE não são independentes; D̂ = máximo das duas não é a cauda de uma amostra de 40 sementes.
4. **Cauda pesada e p99,99 com pouca informação** (§7). O aceite A3 depende do máximo amostral (§5.3).
5. **n = 16 é onde a verificação discordou da medição.** Uma única altura de 692 decidiu o resultado. O resultado de n = 4, 64 e 128 poderia mudar com outro conjunto de sementes.
6. **Duração limitada:** a memória de 2 GB não comportou 20 000 s simulados em n = 16/L = 500 (exploração não preservada). As células do piloto têm ≤ 3303 s simulados.
7. **Observador externo.** O CSV oficial coincide com o do observador em 650 de 650 células com blocos, mas a contagem de timeouts e `D` vêm do observador, não do caminho oficial. Em células sem nenhum bloco (28), só o observador existe.
8. **Java 25** (alvo declarado do JABS: 17).
9. **Equivocação ≠ SILENT.** O efeito da falha do líder sobre o timeout foi observado com EQUIVOCATION; não é comparável a um líder silencioso ou em crash.
10. **Conjuntos bizantinos** provisórios (ADR-0015 Q28, Q30, Q34): sorteio em [0, n), todos os nós.

## 11. A definir com o humano

- O **valor** de `--hs-view-timeout` por (n, L) e o **m** final, e o destino do critério A3 depois da falha em n = 16 (§6).
- `--hs-aggregated-signature-size` e `--hs-aggregated-share-size` (96 B) e `--hs-message-overhead` (10 B): valores operacionais do piloto, sem fonte. Se `AGGREGATED` entrar no estudo, precisam de fonte do esquema BLS; o piloto não mediu AGGREGATED.
- Tamanho DER da assinatura ECDSA no Fabric (NÃO VERIFICADO); este piloto usou 64 B.
- Política de timeout sob falha (hoje constante, sem backoff, marcada PROVISÓRIA no código) e se `STABLE` deve ter substituição de líder.
- L, a política de líder de produção e qualquer ataque além de EQUIVOCATION (SILENT exigiria código novo no `jabs-builder`).
- Se o custo de `-XX:hashCode=2` com L = 500 deve ser corrigido no código (§9.2).

## 12. Reprodução

```bash
python3 experimentos/piloto-timeout-hotstuff/scripts/preparar.py                      # build em /tmp/opencode/piloto-hs a partir do HEAD
for f in medicao-L100 medicao-L10 medicao-L500; do
  python3 experimentos/piloto-timeout-hotstuff/scripts/gerar-celulas.py experimentos/piloto-timeout-hotstuff/config/fatores-$f.json \
      --saida experimentos/piloto-timeout-hotstuff/celulas/$f --simulador-commit 9f305ddb678d5a456daee6cf7390daedc83d1876
  python3 experimentos/piloto-timeout-hotstuff/scripts/executar-lote.py experimentos/piloto-timeout-hotstuff/celulas/$f \
      --saida experimentos/piloto-timeout-hotstuff/dados-brutos/medicao --paralelo 8
done
# analise e proposta
python3 experimentos/piloto-timeout-hotstuff/scripts/analisar.py experimentos/piloto-timeout-hotstuff/dados-brutos/medicao \
    --manifestos experimentos/piloto-timeout-hotstuff/celulas/medicao-L100/manifesto.json --saida experimentos/piloto-timeout-hotstuff/analise-medicao-L100.json
python3 experimentos/piloto-timeout-hotstuff/scripts/propor-timeout.py --analise <analise-medicao-L10/L100/L500.json> --saida experimentos/piloto-timeout-hotstuff/propostas-timeout.json
# verificacao (sementes novas), sensibilidade a L e overhead, ataque: ver config/fatores-verificacao-*.json, config/fatores-sens-*.json, scripts/gerar-celulas-ataque.py
python3 experimentos/piloto-timeout-hotstuff/scripts/verificar-aceite.py
python3 experimentos/piloto-timeout-hotstuff/scripts/gerar-tabelas.py
```

Comandos exatos de **cada** célula: `dados-brutos/<lote>/<id>/comando-mvp.txt` e `comando-observador.txt`. Modelo (célula `med_rotating_L100_n16_cargaestacionaria_f00_s01`):

```
java -Xmx2g -cp <classes:cp.txt> jabs.example.MVPComparison --protocols=HOTSTUFF --validators=16 --trials=1 --duration=460 --seed=12346 \
  --output=<dir> --csv-out=<dir>/mvp-results.csv --hs-view-timeout=60 --hs-max-future-view-horizon=1000 --hs-batch-size=100 --hs-tx-size=512 \
  --hs-individual-signature-size=64 --hs-aggregated-signature-size=96 --hs-aggregated-share-size=96 --hs-message-overhead=10 \
  --hs-signature-mode=INDIVIDUAL --hs-leader-policy=ROTATING --hs-voter-encoding=BITMAP
```

Tamanho em disco: ~185 MB (dados brutos gzip + logs). O orquestrador decide o que entra no git.
