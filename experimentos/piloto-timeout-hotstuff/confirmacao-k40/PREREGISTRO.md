# PRÉ-REGISTRO: PILOTO-timeout-hotstuff, rodada de confirmação K=40 (n=64 e n=128)

Data do registro: 2026-11-13. Tarefa: `PILOTO-timeout-hotstuff-K40`, etapa 1 de 2. Autor: `jabs-experimento` (proponente).
Decisão do humano que origina a rodada (aprovada): K=40 sementes **novas** por n, só n=64 e n=128, ROTATING, L=100, sem falha,
sobre o simulador do HEAD (`src/` idêntico ao de `9f305dd`).

**Nada foi simulado nesta etapa.** Não existe dado desta rodada. O que foi executado: leitura de dados antigos, conferência de
sementes, geração de manifesto e build **de teste em `/tmp/opencode`** (compilação, sem simulação), e o `--autoteste` do script
de análise sobre os dados antigos da confirmação K=20 (só imprime; não grava na pasta). Este arquivo é imutável depois que o
orquestrador o commitar com `PREREGISTRO.sha256`; qualquer mudança exige novo pré-registro (`hash-preregistro.py` recusa
sobrescrever o hash).

## 0. O que eu já sabia dos resultados anteriores (divulgação)

Conheço os resultados abaixo antes de fixar a regra. Isto **não é cego**: K=40 foi escolhido depois de ver que n=64 falhou por
uma célula de 20. Os números são os de `confirmacao/PARECER-TECNICO-v2.json` e `confirmacao/analise-confirmacao-v2.json`.

| n | T do piloto (m=3) | T/2 | confirmação K=20: UB (=maior máximo, j=20) | veredito | replicação R K=20: UB | veredito | células com M_c > T/2 (conf / R) |
|---|---|---|---|---|---|---|---|
| 4 | 0,918 | 0,459 | 0,3479 | PASSA | 0,3143 | PASSA | 0 / 0 |
| 16 | 1,090 | 0,545 | 0,5048 | PASSA | 0,6856 | **FALHA** | 0 / 1 |
| 64 | 2,065 | 1,0325 | **1,1348** (célula s10) | **FALHA** | 0,8945 | PASSA | 1 / 0 |
| 128 | 3,396 | 1,698 | 1,1518 | PASSA | 1,1970 | PASSA | 0 / 0 |

- Os dois maiores seguintes de n=64 na confirmação foram 0,8027 e 0,7966 s; na R, o maior foi 0,8945 s. Por isso eu sei que,
  com K=40 e j=38 (o terceiro maior máximo), **um único outlier como o de 1,1348 s não reprova n=64** neste desenho: reprova se
  ≥3 células de 40 passarem de T/2. Isso é consequência do desenho estatístico (UB para o quantil de M_c com K maior), mas foi
  escolhido sabendo disso. Não escondo.
- A união pós-hoc confirmação+R (K=40 por n, **células já usadas, não novas**) deu j=38 e UB = 0,3143 / 0,5048 / 0,8027 / 1,1646
  (n=4/16/64/128): todos ≤ T/2. Esses dados **não** entram nesta rodada (seção 6).
- Secundário (N efetivo, fórmula do v2) na confirmação K=20: DEFF = 1,142 / 1,056 / 1,552 / 16,583; N_eff = 37 883 / 40 946 /
  27 865 / 2 609 (alvo 36 887). Autocorrelação de corpo em n=128: ρ₁ = 0,60, truncamento no lag 27. Indice de cauda por corridas
  em p99: θ̂ = 0,981 / 0,981 / 0,986 / 0,981; em p99,9 (autoteste, K=20): θ̂ = 1,0 em n=64 e n=128.
- Zero timeouts em todas as 80 células da confirmação (T por n) e nas 80 de R (timeout de 60 s).
- Previsão feita **agora**, antes dos dados: com K=40 o secundário **provavelmente** não decidirá. n=128: DEFF ≈ 16 dá N_eff ≈ 5,2 mil
  (< 36 887, "INCONCLUSIVO"). n=64: DEFF ≈ 1,55 a 1,61 dá N_eff ≈ 53,7 a 55,7 mil, **na beira** do limiar 55 715 (e_sup passa de 0 para 1).
  O aceite **não** depende do secundário (seção 1.4).
- Críticas já registradas (loop 3, `PILOTO-timeout-hotstuff-confirmacao-v2`): o UB só limita o p99,99 de D sob **H-cel** (sem
  agrupamento de extremos dentro da célula), que não é provada; e o congelamento por mtime/hash não tinha proveniência imutável
  (esta rodada resolve com commit do pré-registro **antes** da etapa 2).

## 1. Hipóteses e critério de aceite por n

### 1.1 Hipóteses (uma por n, cada uma decidida sozinha; aceite global = os dois passam, interseção, sem correção de multiplicidade)
- **H1 (n=64)**: o quantil p=0,9999 de D (duração de visão por réplica, alturas h > 50, sem falha, ROTATING, L=100, `--hs-view-timeout`=2,065 s) é ≤ T/2 = **1,0325 s**.
- **H2 (n=128)**: o mesmo, com T = 3,396 s, T/2 = **1,698 s**.
- T é o do piloto (m=3, leitura (a) do v1; `experimentos/piloto-timeout-hotstuff/propostas-timeout.json`). **Circular com o piloto**: T sai do D̂ do piloto; esta rodada não valida m. Nenhum T, m, quantil, nível, truncamento ou limiar muda depois de ver dado.

### 1.2 Unidade, dado e estatística
- Unidade independente: a **célula** (uma semente). Visões dentro da célula não são tratadas como independentes.
- Por célula c: D_c(h) = `Rmax_s` de `obs/observador-por-altura.csv.gz`, h > 50; m_c = nº de visões (esperado ≈ 2 162 a 2 163); M_c = max_h D_c(h).
- K = 40 por n. Quantil de M_c: **u = 0,9999^m̄** com m̄ = média dos m_c do grupo (≈ 2 163 ⇒ **u = 0,80548**), pois sob visões independentes dentro da célula P(M_c ≤ x) = F(x)^m_c (derivação em `confirmacao/CRITERIOS-congelados-v2.md` §1.2).
- IC95 bilateral por estatística de ordem (idêntico ao v2, função `ic_ordem_celulas`): limite superior = M_(j) com **j o menor índice tal que P(Bin(K,u) ≤ j−1) ≥ 0,975**; inferior = M_(i) com i o maior tal que P(Bin(K,u) ≤ i−1) ≤ 0,025.
- **Índice derivado (K=40, u=0,80548)**: P(Bin(40;0,80548) ≤ 36) = 0,9659 < 0,975 e P(Bin ≤ 37) = 0,9902 ≥ 0,975 ⇒ **j = 38**; i = 27; K_min = ⌈ln 0,025 / ln u⌉ = 18. j = 38 vale para qualquer m̄ entre 1 854 e 2 279, logo é insensível à pequena variação de m_c. Se K obtido < 40 (célula faltante), j é recalculado pela mesma regra com o K obtido (K=39→37, 38→36, 37→35, 36→34, 35→33); sem substituir célula (seção 3).
- **UB_prim = M_(38)** = o **terceiro maior** máximo de célula.

### 1.3 Critério de aceite (por n)
**PASSA ⇔ UB_prim = M_(38) ≤ T/2**, com A1' (zero timeouts espúrios) e instrumento íntegros. Equivalente exato: **no máximo 2 das 40 células têm M_c > T/2**.
Leitura honesta do que isso certifica: passar mostra, com 97,5 % de confiança unilateral, P(M_c > T/2) < 1−u = 19,5 % por célula (Clopper-Pearson de 2/40 dá teto 0,169; de 3/40, 0,204 > 0,1945). Sob H-cel isso equivale a P(D > T/2) ≤ 10⁻⁴ por visão. **Sem H-cel, não é afirmação sobre o p99,99 de D** (seção 2).

### 1.4 O que decide e o que só rotula
- Decide o aceite: **somente o primário** (esta seção), veredito pré-registrado por n (seção 3).
- Rotulam, ao lado e sem alterar o veredito PASSA/FALHA: evidência de H-cel (seção 2) e secundário por N efetivo (2.3).

## 2. Tratamento declarado da H-cel e do N efetivo secundário

### 2.1 H-cel
H-cel = "não há agrupamento de extremos dentro da célula (índice extremal θ = 1)". Sob dependência positiva (θ < 1), P(M_c ≤ x) = F(x)^(m_c θ) e o quantil u de M_c corresponde a F = 0,9999^(1/θ) < 0,9999, ou seja, M_(j) **subestima** o p99,99 de D. H-cel **não é provada nem testável com estes dados** com poder útil; é tratada por evidência descritiva e por sensibilidade, nunca como prova.

### 2.2 Evidência de H-cel reportada (descritiva; calculada por `analisar-k40.py`, função `evidencia_hcel`)
1. **θ̂ por corridas** (excedências do limiar não precedidas imediatamente por excedência na mesma célula, ÷ excedências), em q = 0,99 e **q = 0,999** (≈ 87 excedências por n; a cauda mais próxima de 10⁻⁴ que ainda tem contagem útil), mais IC de Clopper-Pearson **aproximado** (runs/excedências não são independentes; só ordem de grandeza).
2. **θ̂ de Ferro-Segers** (intervalos entre excedências dentro da célula; fórmula na docstring de `theta_ferro_segers`), em q = 0,99 e 0,999. É um segundo estimador, independente do de corridas.
3. **θ\* (limiar de sensibilidade)**: maior θ no qual o índice do limite superior sobe de j = 38 para 39, calculado exatamente pela binomial com u^θ: **θ\* = 0,857** (j = 40 para θ entre 0,652 e 0,426; sem limite superior algum abaixo de θ ≈ 0,426). Leitura: **se o θ de cauda real for ≥ 0,857, j = 38 continua válido; abaixo disso o UB do aceite subestima** e o índice correto seria o 39 ou o 40.
4. **Tabela de sensibilidade** do UB a θ ∈ {1; 0,95; …; 0,5} (u^θ → j → M_(j) → ≤ T/2?) e o menor θ da grade em que ainda passa.
5. **Rótulo H-cel (regra fixa, descritiva)**: "NÃO CONTRADITA" se **ambas** as estimativas pontuais de θ em q = 0,999 (corridas e Ferro-Segers) forem ≥ θ\* = 0,857; senão "NÃO SUSTENTADA". Não é um teste de hipótese, tem baixo poder perto de θ = 1, e uma estimativa em p99,9 não é a cauda de 10⁻⁴. Por isso o rótulo só muda o texto do veredito: PASSA vs "PASSA condicionado a H-cel" (seção 3); nunca converte PASSA em FALHA nem o contrário.

### 2.3 N efetivo secundário (mesma fórmula do v2, função `neff` do `analisar-confirmacao-v2.py`, não editada)
- Empilhado do grupo (todas as visões h > 50 das 40 células), N = Σ m_c ≈ 86,5 mil. Autocorrelação amostral pooled ρ_k de lag k, só pares dentro da mesma célula, μ e variância globais do empilhado.
- Truncamento: K_tr = menor k ≥ 1 com ρ_k < 2/√N; teto k_max = 200 (marca "truncado_no_teto"). S = Σ_{k<K_tr} ρ_k; **DEFF = max(1, 1+2S); N_eff = N/DEFF**.
- IC por ordem de visões com N_eff: e_sup(⌊N_eff⌋) pela binomial b(N_eff, 10⁻⁴); −1 ⇒ **INCONCLUSIVO (sem UB)**; 0 ⇒ UB_sec = máximo do empilhado; 1 ⇒ segundo maior (limiares: 36 887 → e_sup 0; 55 715 → 1; 72 245 → 2; auto-checados).
- Rótulo de robustez (igual ao v2): "PASSA robusto" (primário e secundário PASSA); "PASSA condicionado a dependência" (primário PASSA, secundário INCONCLUSIVO ou FALHA); "FALHA" (primário FALHA). **O rótulo não altera o aceite.**
- Se N_eff estiver a <5 % de 36 887, rótulo "borderline". Também reportados: N_necessário = ⌈36 887·DEFF⌉ visões e as células equivalentes.
- Como o secundário reprova ou não decide com dependência de corpo conhecida (seção 0), a divergência primário × secundário é **esperada e já declarada**; a decisão sobre o peso do secundário é do humano e não é decidida por esta rodada.

### 2.4 Contrastes rotulados (não decidem)
Bootstrap por célula (B=2000, semente 20261113; só contraste, anticonservador no extremo); Clopper-Pearson de P(M_c > T/2); IC v1 de visões empilhadas (N ≈ 86,5 mil ⇒ e_sup = 2 ⇒ UB = terceiro maior **visão**), para confronto; θ̂ de S2 (corridas em p99).

## 3. Desfechos pré-registrados (por n, ordem de precedência)

Verificações de integridade (todas reportadas por célula): rc=0 nas duas JVMs; esperadas × obtidas = 40 por n; **A1'** = `timeouts_disparados_total` = 0 em todas as células (`STALE_VIEW_TIMEOUT` não conta); equivalência do MD5 do CSV por bloco (sem `BlockHashCode`) entre `MVPComparison` e observador; `visão_final(réplica) − altura_final ∈ {0,1}`; sementes sem colisão (reconferidas na etapa 2).

| ordem | condição | veredito do n |
|---|---|---|
| 1 | alguma célula com timeout disparado (A1' ≠ 0) | **FALHA de A1'** (T inválido sem falha); **a célula com timeout NÃO é excluída** (excluir viesaria o UB para baixo); reporta-se célula e altura |
| 2 | MD5 do CSV divergente entre as duas JVMs, ou célula sem blocos | **INCONCLUSIVO (instrumento)** |
| 3 | não há UB (K obtido < K_min=18) | **INCONCLUSIVO (sem UB)**; não completar com outra amostra |
| 4 | UB_prim = M_(j) > T/2 (≥3 células com M_c > T/2 quando K=40) | **FALHA** |
| 5 | UB_prim ≤ T/2 e rótulo H-cel "NÃO CONTRADITA" | **PASSA** |
| 6 | UB_prim ≤ T/2 e rótulo H-cel "NÃO SUSTENTADA" | **PASSA condicionado a H-cel** (continua PASSA no primário; o texto diz que o p99,99 só é limitado se H-cel valer) |

- **Célula faltante / rc≠0**: pode ser **reexecutada uma vez com a mesma semente** (falha de infraestrutura; semente determinística) e a reexecução é declarada; **não** se substitui por outra semente. Se continuar faltando, K reduz (j recalculado, seção 1.2) e o relatório diz "K obtido = k < 40". Nada de excluir célula por ter M_c alto.
- **Aceite global**: "atendido" só se n=64 **e** n=128 forem PASSA ou PASSA condicionado, com A1' e instrumento íntegros. n=4 e n=16 **não** são tratados por esta rodada (seção 7, decisão 3).
- **Se n=64 falhar de novo** (≥3 células com M_c > T/2): o método **exige T_req = 2·UB_prim = 2·M_(38)** (a menor T em que o critério D ≤ T/2 passaria **com estes dados**; **circular**: o T sairia dos próprios dados). Reporto T_req por n e por método (primário; secundário se definido; N_necessário se inconclusivo), a razão T_req/T_piloto e o número de células acima de T/2. **Não proponho valor final de timeout nem m**; o parecer v2 (V2-5) registra que o ADR não permite subir m para passar. A decisão é do humano (impacto ALTO, loop 3).
- **Se n=128 falhar**: idem. **Se um passar e o outro falhar**: cada um com seu veredito, sem média.
- **Se H-cel for "NÃO SUSTENTADA" em algum n**: PASSA condicionado; reporto a tabela de sensibilidade (qual M_(j) viraria o UB para θ = 0,85; 0,8; 0,7; 0,65) para o humano pesar. Não troco o índice do aceite.

## 4. K, visões por n, N esperado e custo

| | n=64 | n=128 |
|---|---|---|
| K (células novas) | 40 | 40 |
| duração simulada (s) | 941 | 1 807 |
| visões pós-transiente (h>50) por célula (medido no piloto/confirmação) | 2 162 a 2 163 (m̄ ≈ 2 162,95) | 2 163 (todas) |
| **N esperado** (Σ m_c) | **≈ 86 518** (86 480 a 86 520) | **86 520** |
| u = 0,9999^m̄ | 0,80549 | 0,80548 |
| j (IC superior) / i (inferior) | 38 / 27 | 38 / 27 |
| T, T/2 (s) | 2,065 ; 1,0325 | 3,396 ; 1,698 |
| N alvo v1 (36 887) | superado 2,3× | superado 2,3× |

Total: **80 células, 160 JVMs** (MVPComparison + observador por célula).

**Custo estimado** (de `confirmacao/custo.json` e `resultado.json` das 80 células da confirmação K=20, mesma configuração):
- CPU por célula (2 JVMs em série): n=64 média 44,3 s (máx. 47,7); n=128 média 90,6 s (máx. 98,5). Soma: 40×44,3 + 40×90,6 = **5 396 s de CPU**.
- Eficiência medida do lote anterior: 3 092 s de CPU em 416 s de parede com 8 paralelos (7,43×). **Parede estimada ≈ 5 396/7,43 ≈ 726 s ≈ 12 min**, mais ~1 a 2 min de build e < 1 min de análise. **≈ 15 min no total, bem abaixo do limite de 1 h** (nesta máquina, `nproc` = 28; limite do enxame = 8 paralelos).
- Memória: RSS máx. por processo 742 MB (n=128; confirmação, `custo.json`) ⇒ 8 em paralelo ≈ 6 GB; `MemAvailable` medido agora ≈ 16,9 GiB. `-Xmx2g` por JVM.
- Disco: ≈ 384 KB por célula ⇒ ≈ 31 MB.
- Risco de custo: outro agente compilando na mesma máquina pode alongar a parede; a estimativa de CPU não muda.

## 5. Etapa 2: comandos exatos, semente, duração, flags, commit

**Pré-condições da etapa 2 (todas verificáveis)**: (a) este pré-registro **commitado** pelo orquestrador, com `PREREGISTRO.sha256`, **junto com os arquivos hasheados da seção 9** (inclusive os hoje **não versionados** de `confirmacao/`; ver seção 7, decisão 2); (b) `hash-preregistro.py conferir --artefatos` devolve 0; (c) **portão V-A/V-B/V-C do HotStuff**: **NÃO está registrado** (seção 7, decisão 1); (d) `git rev-parse HEAD:src` = `38820851e622b76a9f60e1a67520c60b48e2e6a5` (= `9f305dd:src`), `HEAD:pom.xml` = `32f934e332097d6bfed46830ff03132c36763670`; o `preparar-k40.py` **aborta** se divergir.

Raiz do repositório = diretório de trabalho. `P=experimentos/piloto-timeout-hotstuff`, `K=$P/confirmacao-k40`.

```
0.  python3 $K/scripts/hash-preregistro.py conferir --artefatos        # exit 0 obrigatório
    git status --short -- $K                                              # deve estar sem alteração não commitada
1.  python3 $K/scripts/checar-sementes-k40.py                            # exit 0 (0 colisões)
2.  python3 $K/scripts/preparar-k40.py                                   # build em /tmp/opencode/k40-hs A PARTIR DO GIT (git archive); grava $K/versao-simulador-k40.json
3.  python3 $P/scripts/gerar-celulas.py $K/config/fatores-k40-L100.json \
        --saida $K/celulas/k40-L100 --simulador-commit "$(git rev-parse HEAD)"
    python3 $K/scripts/especificar-celulas.py conferir-manifesto $K/celulas/k40-L100/manifesto.json   # exit 0
4.  python3 $K/scripts/executar-lote.py $K/celulas/k40-L100 --saida $K/dados-brutos/k40 --paralelo 8
    # só relatório de execução; NÃO analisar antes de terminar as 80 (sem espiar resultado parcial)
5.  python3 $K/scripts/custo-k40.py                                      # custo real -> $K/custo-k40.json
6.  python3 $K/scripts/analisar-k40.py                                   # confere o SHA do PREREGISTRO; grava $K/analise-k40.json
```
Depois: `PARECER-TECNICO-k40.json` (campos de `ParecerTecnico` da skill, `n_celulas_esperadas` 80, obtidas conforme o resultado) e entrega ao loop 3. Os `PARECER`/`RELATORIO` da etapa 2 só se escrevem depois de rodar o passo 6.

- **Semente**: `semente = semente_base + n·1000 + réplica`, **semente_base = 52345**, réplicas 1 a 40 ⇒ n=64: **116 346 a 116 385**; n=128: **180 346 a 180 385** (80 distintas). `MVPComparison` recebe `--seed = semente − n·1000` e `--trials=1`; o observador recebe `--run-seed = semente`. Verificado: 769 `cfg.json` lidos em `experimentos/` (204 sementes distintas já usadas, de 16 346 a 170 365: piloto, medição, verificação, ataque, sonda, validação, confirmação K=20 com base 42345 e R com bases 12345/22345): **0 colisões** (`checagem-sementes-preregistro.json`). O script oficial `confirmacao/scripts/checar-sementes.py 52345 40` também dá 0 colisões e 0 duplicadas.
- **Duração simulada**: n=64 → 941 s; n=128 → 1 807 s (mesmas da confirmação K=20).
- **Configuração fixa** (igual à confirmação): `--protocols=HOTSTUFF --trials=1 --hs-leader-policy=ROTATING --hs-batch-size=100 --hs-tx-size=512 --hs-max-future-view-horizon=1000 --hs-signature-mode=INDIVIDUAL --hs-voter-encoding=BITMAP --hs-individual-signature-size=64 --hs-aggregated-signature-size=96 --hs-aggregated-share-size=96 --hs-message-overhead=10`; sem `--attack` (f = 0); carga estacionária (regime saturado). **`--hs-view-timeout` = T do piloto por n: 2,065 (n=64) e 3,396 (n=128)** (não 60 s), para que A1' conte timeouts espúrios.
- **JVM**: `java -Xmx2g`, **sem `-XX:hashCode=2`**. Justificativa: a flag só é exigida por `--load-record`, que não é usado; o observador e o `MVPComparison` não o usam; sem a flag o `HashSet<HotStuffTx>` não degenera (custo até 104× em L=500 no piloto). Saída equivalente com e sem a flag foi verificada no piloto (4 de 4) e a equivalência entre as duas JVMs (MD5 sem `BlockHashCode`) é conferida em **cada célula** nesta rodada. Nenhuma exigência declarada em contrário.
- **Commit do simulador**: o build vem de `git archive` do HEAD (hoje `819931e3f21f35a67e3b430790a2f43a194a6021`; **o HEAD da etapa 2 será o commit deste pré-registro e ele é quem vai para `versao-simulador-k40.json` e para o campo `simulador_commit` de cada `cfg.json`**). O **código** é o de **`9f305ddb678d5a456daee6cf7390daedc83d1876`**: `src/` tem tree `38820851e622b76a9f60e1a67520c60b48e2e6a5` idêntico em HEAD e em `9f305dd` (`git rev-parse HEAD:src 9f305dd:src`; `git log 9f305dd..HEAD -- src pom.xml` vazio), `pom.xml` blob `32f934e3…`, observador `harness/ObservadorHotStuff.java` blob `4024e15b…`. **O build NÃO usa a árvore de trabalho**: há mudanças não commitadas de outro agente em `src/` (`Tx.java`, `EnhancedBlockFinalizationLogger.java`, `TxSequencer.java` e testes novos), que ficam de fora; o teste de build em `/tmp/opencode/k40-teste-build` listou-as em `mudancas_nao_commitadas…` do registro.
- Ambiente: OpenJDK 25.0.5 (alvo do pom: 17), Maven 3.8.7 (idem ao da confirmação); anotar na `versao-simulador-k40.json`.

## 6. O que é pós-hoc; mistura proibida

- **Resultado primário desta rodada = só as 80 células novas** (40 por n). **Nada** da confirmação K=20 (`conf_*`, base 42345), da replicação R (`med_*`/`ver_m3_*`) ou de qualquer outro conjunto entra no primário, no índice j, no N efetivo secundário, no rótulo H-cel ou no aceite. Nenhuma célula do piloto é reaproveitada.
- **Complementar rotulado, não decide, não entra no aceite** (calculado pelo script porque é barato, mas é **pós-hoc em sua escolha de apresentação**): empilhamento K40 + confirmação K=20 (K=60, j=55) e K40 + confirmação + R (K=80, j=72), por n, mesmos métodos; R filtrada a células com 0 timeouts (R foi gerada com timeout de 60 s). Rotulado "COMPLEMENTAR/PÓS-HOC".
- **Pós-hoc** (qualquer item abaixo): outro T, m, quantil, nível, truncamento de ρ_k ou índice j; excluir ou ponderar célula; bootstrap, Clopper-Pearson, θ̂ e a tabela de sensibilidade como critério; a comparação v1 × v2 × K40; a distribuição dos máximos por célula e a célula do maior; qualquer conclusão sobre n=4 e n=16 a partir desta rodada (não são simulados aqui); qualquer proposta de valor final de timeout.
- Se uma análise não pré-registrada for feita depois de ver o dado, ela entra no parecer com o rótulo "PÓS-HOC" e fora do veredito.

## 7. Decisões que dependem do humano (antes de o orquestrador commitar ou de eu iniciar a etapa 2)

1. **Portão V-A/V-B/V-C do HotStuff: NÃO registrado.** `V-ABC-hotstuff` está `PENDENTE_REVISAO_HUMANA` em `.ia/rodadas/loop2-jabs-codigo.jsonl`, `experimentos/validacao/` não existe e `registrar-rodada.py --verificar-gate loop2-jabs-codigo` sai com 1 (gate fechado). O piloto e a confirmação anteriores rodaram antes do V-ABC por ordem de sequência do ADR-0015 (piloto na posição 3, V-ABC na 4), e esta rodada é a continuação delas, mas eu **não** assumo essa cobertura: **a etapa 2 só começa com registro humano explícito de que esta rodada está dispensada do portão** (ou com o portão fechado).
2. **O que o commit do pré-registro tem de incluir.** `git status` mostra `experimentos/piloto-timeout-hotstuff/confirmacao/` **não versionado** (`??`). Esta rodada depende de `confirmacao/scripts/analisar-confirmacao{,-v2}.py` e de `confirmacao/CRITERIOS-congelados-v2.md` (hashes na seção 9); sem eles em git a proveniência imutável não fecha. Recomendo commitar **só** `confirmacao-k40/` e os arquivos de `confirmacao/` hasheados abaixo; **não** incluir as mudanças de `src/` do outro agente (se elas forem commitadas antes da etapa 2, `HEAD:src` muda e `preparar-k40.py` aborta, o que é a trava desejada: aí é preciso novo pré-registro ou o commit delas precisa preceder este).
3. **Escopo n=64 e n=128 apenas** (decisão aprovada). n=16 **falhou em R** (UB 0,6856 > 0,545) e passou na confirmação; n=4 passou nas duas. O aceite v2 de quatro n não fecha com esta rodada. Incluir n=16 (e n=4) custaria ≈ 9 min de CPU a mais (40×13,9 s) mas **muda o pré-registro**; se quiser, é antes do commit.
4. **O secundário** (seção 2.3) vai ficar INCONCLUSIVO em n=128 e na beira em n=64 (previsão da seção 0). O aceite desta rodada é o primário, como decidido; o humano decide depois o peso do rótulo de robustez.
5. **K=40 foi escolhido depois de n=64 falhar por uma célula.** Com j=38 o critério tolera 2 células > T/2 de 40 (≈ 5 %); isso é o que o IC de ordem ao nível 97,5 % dá, mas o humano deve saber que o desenho **é menos sensível a um outlier isolado** que o K=20.
6. **Se qualquer n falhar:** a decisão sobre T (m, T_req), sobre mais K e sobre aceitar PASSA condicionado é do humano (impacto ALTO ⇒ loop 3 sem arbiter).

## 8. Limites declarados (herdados, não repetidos em detalhe)
H-cel não é provada; K=40 dá um limite sobre o quantil de M_c, não sobre D diretamente; T é circular com o piloto; Java 25 (alvo do pom: 17); rede LAN100M simulada; regime saturado; sem falha injetada; só ROTATING e L=100; o D do observador exclui CPU; Raft (Fabric) não é BFT (`AGENTS.md`). Ameaças 3 a 8 do `PARECER-TECNICO.json` v1 e 1 a 7 do v2 continuam valendo. Independência entre células: sementes inteiras distintas, MersenneTwister por semente (conforme o v2); não verifico correlação entre sementes consecutivas.

## 9. Artefatos pré-registrados e SHA-256 (conferidos por `hash-preregistro.py conferir --artefatos`)

Caminhos relativos à raiz do repositório. Os arquivos de `confirmacao-k40/` **são especificação**; `celulas-esperadas.json` lista os ids, sementes, durações e timeouts das 80 células (a etapa 2 reproduz isso com `gerar-celulas.py` e confere com `especificar-celulas.py conferir-manifesto`); **nenhum dado foi gerado**. O hash do próprio `PREREGISTRO.md` fica em `PREREGISTRO.sha256` (não pode estar dentro dele).

| arquivo | SHA-256 |
|---|---|
| `experimentos/piloto-timeout-hotstuff/confirmacao-k40/config/fatores-k40-L100.json` | `2e1bee62495b2b65a39abec54cd6f11cb938445dd226b5b19f988bf1d51bf856` |
| `experimentos/piloto-timeout-hotstuff/confirmacao-k40/celulas-esperadas.json` | `40a97b54dccce01444220a5d312e5e4cb40179a73f789fbfa0fbb3883cafb3c1` |
| `experimentos/piloto-timeout-hotstuff/confirmacao-k40/checagem-sementes-preregistro.json` | `c1f81d0744f3f13193db4dfb49b1d70ed7744d66c0977dc88114b425eb8c2919` |
| `experimentos/piloto-timeout-hotstuff/confirmacao-k40/scripts/especificar-celulas.py` | `22d57bf9a66fac3dac08014787bf52664efeb265d2321bc2b2239650912ab7c2` |
| `experimentos/piloto-timeout-hotstuff/confirmacao-k40/scripts/checar-sementes-k40.py` | `188b0e1c43dba00f18a5e34b8d07a168bb1f8f1405a79636e3616147a7cb2264` |
| `experimentos/piloto-timeout-hotstuff/confirmacao-k40/scripts/preparar-k40.py` | `4829264f0a7fecd9d534dc618f6f9645f981e88026aa07990a97122e852cea0d` |
| `experimentos/piloto-timeout-hotstuff/confirmacao-k40/scripts/rodar_celula.py` | `d0769b363d3bb1882ecac7a8ad8bf5878ddd64bf54905f215baa505be12d1e53` |
| `experimentos/piloto-timeout-hotstuff/confirmacao-k40/scripts/executar-lote.py` | `6bef87c73de2cd4931bde233834907db41c13c5757d4f10c6f0352410cd105d0` |
| `experimentos/piloto-timeout-hotstuff/confirmacao-k40/scripts/custo-k40.py` | `811006a2505a490721542a38a6caa173339b78c0c9dd70c49543b65084aa5340` |
| `experimentos/piloto-timeout-hotstuff/confirmacao-k40/scripts/analisar-k40.py` | `b42880da6e240745cbbcdd43742808d236af385ae1316e9a4d2464c3a114d88e` |
| `experimentos/piloto-timeout-hotstuff/confirmacao-k40/scripts/hash-preregistro.py` | `3fc8ae8c4c085e47816e2ca0efa66e6ae235c3c8cdf6c108c1bead089f37f65b` |
| `experimentos/piloto-timeout-hotstuff/scripts/gerar-celulas.py` | `715a334afce4bd637d12274a48b864621e2cd33d4d994228ce9269cdbe7dc983` |
| `experimentos/piloto-timeout-hotstuff/scripts/executar-lote.py` | `885c101be4ccd2672430a5957fb36e986f790e50ac789e5896942f1d1b78d593` |
| `experimentos/piloto-timeout-hotstuff/scripts/rodar_celula.py` | `98fdb32c3511f75b349f3f24cb98ba23b1c876f12e689b4bb95cbb43fd2465f2` |
| `experimentos/piloto-timeout-hotstuff/harness/ObservadorHotStuff.java` | `6abfcd570435773642293a2aa4128e319ea8565fae1ae87c565e2e197c3270b4` |
| `experimentos/piloto-timeout-hotstuff/confirmacao/scripts/analisar-confirmacao.py` | `125dfcac3a033babe049240f03f6f389e03e519dc1f71f3afce5ca2ae2680110` |
| `experimentos/piloto-timeout-hotstuff/confirmacao/scripts/analisar-confirmacao-v2.py` | `5585914bfdb2ae265f1dff2c18a47da6fe9ae5ac2c47dc860d82e2846b72c103` |
| `experimentos/piloto-timeout-hotstuff/confirmacao/CRITERIOS-congelados-v2.md` | `6c07a928295fbf9f22ef3646dfe5ebb652a3ab579b5e899cde0474f98ed24b5e` |
| `scripts/jabs/gerar-grid.py` | `7fb2a83eefb5f642f1934281238579c0e762cad5302620512237e646616241fd` |

Autoria de código novo (escrito **antes** do hash, sem dado desta rodada): `analisar-k40.py` reaproveita sem editar `analisar-confirmacao-v2.py` (acrescenta só T por n, A1' sem exclusão de célula, conferências de instrumento, θ de Ferro-Segers, θ\*, tabela de sensibilidade, veredito pré-registrado e o complementar rotulado); foi exercitado só com `--autoteste` sobre os dados antigos (saída em `/tmp/opencode/analise-k40-autoteste.json`, fora da pasta), e reproduziu os números do v2 para a confirmação K=20 (n=64: UB 1,1348 FALHA; n=128: UB 1,1518 PASSA; N_eff 27 865 e 2 609).
