# Critérios congelados: PILOTO-timeout-hotstuff-confirmacao

Congelado em **2026-11-13**, ANTES de qualquer dado novo desta confirmação. O hash SHA-256 deste arquivo é gravado em
`CRITERIOS-congelados.sha256` (por `scripts/hash-criterios.py`) antes de gerar e rodar a primeira célula; qualquer
mudança posterior invalida a confirmação e exige novo arquivo datado.

Antes deste congelamento foram lidos **só dados do piloto** (visões por célula e tempo de parede, em
`scripts/dimensionar.py`, e a lista de sementes já usadas, em `scripts/checar-sementes.py`). Nenhuma célula nova foi
rodada e nenhum número da confirmação existe ainda.

## 1. Origem e natureza

- Decisão do humano: `docs/adr/0015-sessao-de-decisao-parametros.md`, "Registro 13/11/2026", itens 1 a 3. O critério
  A3 original do piloto (D_max ≤ T/2) falhou em n = 16 com m = 3 (D_max/T = 0,629) e **permanece registrado como
  falha**; não é reescrito e m não sobe. Esta confirmação aplica o critério **novo** abaixo.
- É a **confirmação** do piloto `PILOTO-timeout-hotstuff-por-n` (commit 394d738; emenda d574e60). Não é bateria de
  produção e nenhum número daqui compara protocolos. Portão `loop2-jabs-codigo`: FECHADO (verificado em 2026-11-13,
  `V-ABC-hotstuff` e outras tarefas em `PENDENTE_REVISAO_HUMANA`); executada por ordem expressa da tarefa, como o
  piloto. O registro de rodada e de portão é do orquestrador.

## 2. Critério novo (A3')

Para cada n em {4, 16, 64, 128}, no grupo definido em §3:

> **A3'**: `UB(n) ≤ T(n) / 2`, onde `UB(n)` é o **limite superior do IC95 bilateral (2,5 % por cauda), por
> estatística de ordem, do quantil p99,99 de D** do grupo, e `T(n) = 3 × D̂ p99,99`.

- **Quantil pontual**: nearest-rank, posto `ceil(0,9999 · N)` (base 1), sobre as amostras D(h) empilhadas do grupo.
- **IC por estatística de ordem** (mesma aritmética de `scripts/emenda-nmin.py` e de `ic_ordem` em
  `scripts/analisar.py` do piloto): E = nº de observações acima do quantil verdadeiro ~ Bin(N, r), r = 1 − q = 10⁻⁴.
  `UB` = estatística de ordem de posto `N − e`, com `e` o maior inteiro tal que P(Bin(N, r) ≤ e) ≤ 0,025. O limite
  inferior é a estatística de posto `N − e'`, com `e'` o menor inteiro tal que P(Bin(N, r) ≤ e') ≥ 0,975.
- **Duas leituras de T** (ambas reportadas; o veredito primário é a (a)):
  - **(a) T do piloto (primário)**: `D̂` = o **maior** p99,99 entre ROTATING e STABLE do piloto, L = 100, medição:
    0,3058 / 0,3631 / 0,6881 / 1,1318 s para n = 4 / 16 / 64 / 128, logo `T` = **0,918 / 1,090 / 2,065 / 3,396 s**
    (os T candidatos do ADR-0015, registro 13/11, item 3). Número fixo, vindo de dados anteriores a esta confirmação.
  - **(b) T da própria confirmação (secundário, informativo)**: `D̂` = p99,99 nearest-rank do grupo da confirmação
    (só ROTATING, pois só ROTATING é rodado) e `T = 3 × D̂`. **Aviso de circularidade**: `UB` e `D̂` saem da mesma
    amostra; a razão `UB / D̂` é uma propriedade da amostra, e (b) não substitui (a).
- **Veredito por n**: PASSA se `UB ≤ T/2`; FALHA se `UB > T/2`; INCONCLUSIVO se não existe `UB` (§6). **Aceite
  global**: PASSA nos quatro n pela leitura (a). Falha em um n = não aceito, sem reabrir o critério, sem subir m,
  sem trocar o quantil.
- Reportados como **diagnóstico**, sem poder de decisão: o limite inferior; D_max; `m_mín = 2·UB/D̂` (a razão de
  timeout mínima que o critério exigiria sobre estes dados; **não** é proposta de m); bootstrap percentil (B = 2000,
  semente 20261112) só como contraste, sem crédito, pois é limitado pelo máximo.
- **Aviso sobre o poder do critério (declarado agora, não depois)**: com N de 36 887 a 55 714, `e` = 0, ou seja,
  **`UB` = o máximo da amostra**, que **coincide numericamente com D_max** do grupo. Só com N ≥ 55 715 o `UB` passa
  a ser o segundo maior valor, e com N ≥ 72 246 o terceiro (aritmética conferida por `scripts/analisar-confirmacao.py`).
  Com o N desta confirmação (§4, ≈ 43 mil), A3' dá na prática o mesmo veredito que D_max ≤ T/2 sobre uma amostra
  nova e maior. O que muda é a justificativa (o máximo passa a ser limite superior de IC95 de p99,99, válido a partir
  de 36 887) e o tamanho da amostra. Se `UB/D̂` ficar grande (o piloto deu 1,06 a 1,74 em amostras de 43 mil), A3'
  pode falhar de novo; isso é o resultado, não um defeito a corrigir.

## 3. Grupo (unidade de análise)

- **Grupo** = (política = ROTATING, L = 100, n), um por n; N = nº de visões pós-transiente empilhadas das sementes do grupo.
- **Visão**: uma por altura h (4 fases do líder da visão, HotStuff básico), D(h) = máximo entre as réplicas que
  finalizaram h de `t_final_local(h) − t_final_local(h−1)` do MESMO nó (`Rmax_s` do observador). Definição idêntica à
  do piloto (`CRITERIOS-congelados.md` do piloto, "Definições").
- **Transiente**: descartam-se as alturas h ≤ 50 de cada célula (como no piloto; critério a priori).
- Fora do grupo, e por isso não rodados: STABLE, L = 10 e 500, ataque, sensibilidade a overhead.

## 4. N alvo e dimensionamento

- **N alvo por grupo: N ≥ 36 887 visões pós-transiente** (`emenda-nmin.json`: p99,99, r = 10⁻⁴, IC95 bilateral, para o
  máximo ser limite superior).
- Do piloto (`scripts/dimensionar.py` → `dimensionamento.json`, ROTATING, L = 100, medição): cada célula rende
  **mín. 2161 / 2162 / 2162 / 2163 visões pós-t0** em n = 4 / 16 / 64 / 128, com as durações do piloto 368 / 460 / 941 /
  1807 s simulados (≈ 5,88 / 4,70 / 2,30 / 1,20 visões por s simulado).
- **Sementes por n: 20** (18 bastariam: 18 × 2161 = 38 898; 20 dão margem de ≈ 17 %). **N esperado mínimo =
  43 220 / 43 240 / 43 240 / 43 260.** Durações iguais às do piloto, para que o formato da célula seja o mesmo.
- **Regra de parada (declarada antes, para não haver parada oportunista)**: o número de sementes não é aumentado
  nem reduzido depois de ver D. Exceção única: se algum grupo ficar com N < 36 887 **por célula faltante ou
  incompleta** (código de saída ≠ 0, CSV vazio), rodam-se as sementes reserva 21 a 24 desse n, escolhidas só por
  índice; nunca por resultado.
- **Orçamento**: ≈ 14 min de parede estimados (8 JVMs em paralelo; 80 células); se passar de ~1 h de parede ou de
  memória, para-se e relata-se o custo.

## 5. Sementes e independência

### 5.1 Sementes

- `semente = semente_base + n·1000 + replicação`, `replicação` = 1..20 (`scripts/jabs/gerar-grid.py`);
  **`semente_base = 42345`**, ou seja, as sementes de execução são 46346..46365, 58346..58365, 106346..106365 e
  170346..170365 (n = 4 / 16 / 64 / 128). `MVPComparison` recebe `--seed = semente − n·1000` e usa
  `runSeed = --seed + n·1000 + 0`; o observador recebe `--run-seed = semente`.
- **Disjuntas das do piloto**: o piloto usou `semente_base` 12345 (medição) e 22345 (verificação), mais células de ataque,
  sonda e instrumento. `scripts/checar-sementes.py 42345 20` comparou com **todos** os `cfg.json` do piloto: 0 colisões
  (a base 32345 foi **descartada** por colidir com as sementes das células de ataque).

### 5.2 O que conta como visão independente, e a hipótese

- **Hipótese de independência (H-ind), declarada, não provada**: as visões D(h) de **sementes diferentes** são
  independentes e identicamente distribuídas dentro do grupo. Dentro de **uma** célula (uma semente), as visões
  consecutivas **não** são independentes por construção: compartilham o estado das filas de enlace e das réplicas.
  O piloto observou que a réplica atrasada em uma altura finaliza a seguinte quase junto (`Rmin` de 0 a 0,01 s em 3 dos
  4 picos). Logo, o **N efetivo** do IC é menor que o N contado se houver dependência serial.
- **O que a estatística de ordem assume**: E ~ Bin(N, r) exige observações independentes e contínuas. Nesta confirmação
  declaro **N = nº de visões empilhadas** como N nominal; a validade do IC depende de H-ind.
- **Diagnóstico de H-ind (reportado, sem poder de veto)**: (i) autocorrelação de D de lag 1 a 5 dentro das células;
  (ii) pares de excedências adjacentes de D > p99 do grupo, observados contra o esperado sob independência
  (`N_exced · P(exced)`), e as mesmas contagens para D > p99,9; (iii) concentração do máximo do grupo: a fração das
  excedências de p99,9 que cai numa mesma célula. Não há limiar aprovado/reprovado inventado: os números são
  reportados, e se (ii) mostrar razão observado/esperado nitidamente > 1, o parecer **declara o N efetivo como
  menor que o nominal e o veredito como condicionado a H-ind**, sem mudar o veredito numérico.

### 5.3 Efeito da semente compartilhada (ameaça 3 do RELATÓRIO do piloto)

- No piloto, ROTATING e STABLE do mesmo (L, n) e os três L usavam as **mesmas** sementes (números aleatórios comuns;
  `D̂` = máximo das duas políticas de amostras pareadas). Aqui **só ROTATING e só L = 100** são rodados: **não há
  compartilhamento de semente entre políticas nem entre L dentro da confirmação**, e esta ameaça deixa de afetar `UB`.
- O que continua compartilhado: (1) o **T de (a)** vem de `D̂` do piloto, que é o máximo de duas políticas pareadas, então é
  possivelmente um pouco mais alto que o p99,99 de ROTATING sozinho (viés a favor de passar; reportado ao comparar com
  (b)); (2) as sementes dos quatro n são **inteiros distintos** (o n entra na soma), então os grupos não repetem fluxo
  aleatório entre n; (3) sementes consecutivas de `MersenneTwister` (commons-math3; `RandomnessEngine`) são tratadas como
  independentes, que é prática comum mas **não verificada aqui**.
- Dependência entre os quatro n **não** é usada em nenhum cálculo (cada n tem veredito próprio).

## 6. Se o IC não tiver limite superior

- Sem `UB` (N < 36 887 no grupo por qualquer motivo, mesmo após a reserva de §4), o veredito do n é **INCONCLUSIVO**:
  **não é PASSA nem FALHA**, o bootstrap **não** é usado como substituto, e a falta de `UB` não é preenchida com o
  máximo por conveniência. O parecer registra o N obtido, o N faltante e o custo em sementes para chegar a 36 887, e
  devolve a decisão ao humano.
- Se `UB` existir mas `D_max` do grupo > T para alguma visão, houve timeout possível; A1 (§7) decide.

## 7. Demais itens de aceite, reportados

- **A1' (timeouts espúrios)**: a confirmação roda com `--hs-view-timeout = T de (a)` por n (não 60 s), de modo que
  `HotStuff#getTimeoutAdvances` somado nos nós conte timeouts **espúrios** (esperado 0; célula honesta, sem falha).
  `STALE_VIEW_TIMEOUT` não conta (timer obsoleto de visão já decidida). Zero é o esperado; qualquer valor ≠ 0 é
  reportado como falha de A1', com a célula e a altura.
- **Conferência independente**: em cada célula, `visão_final(réplica) − altura_máxima_finalizada ∈ {0, 1}` para toda
  réplica; um timeout somaria 1. Reporta-se o histograma.
- **A2'**: N ≥ 36 887 por grupo (e ≥ 2000 visões pós-t0 por célula, como o piloto em L = 100).
- **Equivalência de instrumento**: MD5 do CSV por bloco (sem `BlockHashCode`) do `MVPComparison` igual ao do observador,
  por célula.
- **Esperadas × obtidas**: 80 células esperadas (4 n × 20 sementes); manifesto gerado por script.

## 8. Configuração fixa (igual ao piloto, L = 100, ROTATING)

`--protocols=HOTSTUFF`, `--trials=1`, uma semente por JVM, `--hs-batch-size=100`, `--hs-tx-size=512`,
`--hs-signature-mode=INDIVIDUAL`, `--hs-individual-signature-size=64`, `--hs-aggregated-signature-size=96` e
`--hs-aggregated-share-size=96` (sem efeito em INDIVIDUAL; valores operacionais **sem fonte, a definir com o humano**),
`--hs-message-overhead=10` (idem, sem fonte), `--hs-voter-encoding=BITMAP`, `--hs-max-future-view-horizon=1000`,
`--hs-leader-policy=ROTATING`, `--hs-view-timeout` = T de (a) por n, sem falha injetada (sem `--attack`), rede
`LAN100MNetworkStats` (código), regime saturado sem fila (ameaça 2 do RELATÓRIO do piloto).

- **JVM**: `java -Xmx2g`, **sem** `-XX:hashCode=2`. Esta flag só é exigida por `--load-record`, que nem o `MVPComparison`
  (como invocado) nem o observador do piloto usam; ela custou 4 a 104× em parede no piloto. A equivalência de saída
  sem a flag foi validada no piloto (4 de 4) e é conferida de novo célula a célula aqui (MD5 observador × oficial).
  A flag **não é exigida pelo harness do observador** (`ObservadorHotStuff.java`, linha 40: não usa coleção hash).
- **Versão do simulador**: `src/` e `pom.xml` **idênticos** aos do commit `9f305dd` (último commit que tocou `src/` ou
  `pom.xml`; confirmado por `git log -- src pom.xml`); a confirmação é construída a partir do HEAD atual (`d574e60`)
  por `scripts/preparar.py`, que registra `git rev-parse HEAD` e o estado da árvore em `versao-simulador.json`.
  Por decisão 5 do registro 13/11, a confirmação é repetida se o commit do simulador mudar a saída.
- **Java**: OpenJDK 25 (o alvo do `pom.xml` é 17): **ameaça declarada**, não testada em 17.
- **Granularidade**: D por réplica (máximo entre réplicas que finalizaram a altura), por visão; Tb e Dprimeiro do CSV oficial
  são do primeiro nó a finalizar e são secundários.

## 9. Fora do escopo desta confirmação (não se promete nada)

Valor final de `--hs-view-timeout` (do humano), SILENT e backoff, tamanhos agregados BLS, tamanho DER da assinatura
ECDSA no Fabric (medição da `redd-ponte`), correção de `HotStuffTx.hashCode` (loop 2), T por L e a política STABLE.
