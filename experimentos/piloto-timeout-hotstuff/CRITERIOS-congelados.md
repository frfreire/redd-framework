# Critérios congelados: PILOTO-timeout-hotstuff-por-n

Congelado em **2026-11-12**, ANTES da medição principal (as sondas de custo/forma, 12 + 12 células de 1 semente,
foram rodadas antes só para dimensionar duração e conferir o observador; os critérios abaixo não dependem delas
exceto pela tabela de cadência usada para calcular a duração de cada célula).

## Natureza e portão

- Piloto de calibração de instrumento (ADR-0015, "Registro 12/11", passo 3 da sequência; `--hs-view-timeout` é
  parâmetro obrigatório do V-ABC-hotstuff, passo 4). **Não é a bateria de produção** e nenhum número daqui compara
  protocolos.
- Portão `loop2-jabs-codigo`: FECHADO (V-ABC-hotstuff = PENDENTE_REVISAO_HUMANA, verificado em 2026-11-12 por
  `registrar-rodada.py --verificar-gate loop2-jabs-codigo`). Este piloto foi executado por ordem expressa da tarefa; o
  registro do portão e de qualquer rodada é do orquestrador.

## Definições

- **Visão** do HotStuff básico: uma por altura (quatro fases PREPARE/PRE-COMMIT/COMMIT/DECIDE do líder da visão).
- **D (duração de visão), medida primária**: para cada altura h, `D(h) = max` sobre os nós que finalizaram h de
  `t_final_local(h) − t_final_local(h−1)` do MESMO nó (para h = 1, `t_final_local(0) = 0`, instante de `start()`).
  É o que o timer de cada réplica compara (o timer é armado ao entrar na visão, que a réplica deixa ao finalizar por
  DECIDE). Escolhi o máximo entre réplicas porque o timeout dispara se QUALQUER réplica estourar. Fonte: observador
  externo `harness/ObservadorHotStuff.java` (coluna `Rmax_s`).
- **Medidas secundárias do CSV oficial do MVPComparison** (as únicas que ele emite): `Tb` = `BlockFinalizationTime`
  (finalização do primeiro nó − criação do bloco) e `Dprimeiro(h) = Time(h) − Time(h−1)` (intervalo entre finalizações
  do primeiro nó a finalizar alturas consecutivas). O MVPComparison NÃO emite duração de visão por réplica nem
  contagem de timeouts: por isso o observador.
- **Transiente descartado (t0)**: as 50 primeiras alturas de cada célula (critério a priori; conferido por
  comparação média das alturas 1 a 50 × demais, reportada no relatório). t0 em segundos = `t_final(50)` de cada célula,
  registrado em `resultado`.
- **D̂ p99,99(n)**: quantil por nearest-rank, posto `ceil(0,9999·N)` (base 1), sobre as amostras `D(h)` empilhadas das
  10 sementes (pós-transiente) de um mesmo (política, L, n). Calculado só se `N ≥ 10^4`; senão reporta-se o maior
  quantil confiável e declara-se que p99,99 não é estimável. IC: bootstrap percentil (B = 2000, semente 20261112) e IC
  exato por estatística de ordem (binomial, nível 95 %).
- **D_max**: maior `D(h)` pós-transiente observado no (política, L, n) empilhado.

## Fatores e níveis (todos fixados pelo ADR-0015 / registro 12/11, exceto onde marcado)

| Item | Valor | Origem |
|---|---|---|
| n | {4, 16, 64, 128} | E1, ADR-0000 |
| política de líder | ROTATING e STABLE (flag `--hs-leader-policy`) | tarefa |
| L (`--hs-batch-size`) | caso central 100; sensibilidade {10, 500} | registro 12/11 |
| `--hs-tx-size` | 512 B | registro 12/11 |
| `--hs-signature-mode` | INDIVIDUAL | tarefa (evita valores agregados sem fonte) |
| `--hs-individual-signature-size` | 64 B | registro 12/11 (ECDSA, RFC 8032); tamanho DER no Fabric NÃO VERIFICADO |
| `--hs-aggregated-signature-size`, `--hs-aggregated-share-size` | 96 B | **valor operacional do piloto, sem fonte, a definir com o humano**; sem efeito em INDIVIDUAL (só `voteSignatureSize/qcSignatureSize(mode)` os lêem) |
| `--hs-message-overhead` | 10 B | **valor operacional do piloto, sem fonte, a definir com o humano** (ADR-0015: fixture igual ao Tendermint); sensibilidade barata {10, 100} em n ∈ {16, 128} |
| `--hs-voter-encoding` | BITMAP | único valor do enum |
| `--hs-max-future-view-horizon` | 1000 | tarefa |
| `--hs-view-timeout` na medição | 60 s (grande, nenhum dispara) | tarefa |
| duração, sementes, nº de visões | abaixo | **escolha do piloto, a justificar** |

## Número mínimo de visões

- Por célula: **≥ 1000 visões pós-transiente** (L = 10 e 500) e **≥ 2000** (L = 100, caso central).
  Justificativa: 10 sementes × 1000 = 10^4 amostras, o mínimo para o posto `ceil(0,9999 N)` ser o 2º maior valor e
  não o máximo; no caso central dobra-se para 2·10^4 para que o p99,99 e seu IC não dependam de uma só amostra.
- Por (política, L, n): `N ≥ 10^4` para declarar p99,99 estimável.
- Célula que ficar abaixo do mínimo é reportada como falha de dimensionamento (não é descartada em silêncio).
- Sementes: 10 por (política, L, n). `semente = semente_base + n·1000 + replicação` (gerar-grid.py), mesma
  semente-base e mesmas replicações em todas as políticas e L (números aleatórios comuns). Medição:
  `semente_base = 12345`, réplicas 1..10. Verificação com o timeout proposto: `semente_base = 22345` (sementes
  disjuntas, fora da amostra).

## Regra do timeout e critério de aceite

1. Proposta: `T(n, L) = m × D̂ p99,99(n, L)`, com `D̂` = **maior** entre as duas políticas (um timeout só por n e L).
   m = 2; se algum item do critério falhar, m = 3; se falhar com m = 3, NÃO subir mais: relatar e pedir decisão.
2. Aceite (todos):
   - **A1**: ZERO timeouts disparados em todas as células honestas da verificação (sementes novas, `--hs-view-timeout`
     = T proposto), medido por `HotStuff#getTimeoutAdvances` somado nos nós. O contador `STALE_VIEW_TIMEOUT` NÃO
     conta (é o timer obsoleto de visão já decidida; existe em toda visão).
   - **A2**: mínimo de visões por célula e por grupo (acima), nas células da medição e da verificação.
   - **A3**: `D_max ≤ ½ T`, com `D_max` do grupo empilhado da medição (e conferido na verificação).
3. Caso de aceite = **L = 100** (central) para as quatro n; L = 10 e 500 são sensibilidade: reporta-se o T que cada
   L exigiria e se o T de L = 100 serviria (esperado: não serve para L = 500).
4. Com falha injetada (STABLE + ataque) o critério A1 não se aplica: só se registra o comportamento.

## Falha injetada

- Ataque do repositório: o `HotStuff` só implementa **EQUIVOCATION** (`supportedAttacks()`; `MVPComparison` recusa
  SILENT/WITHHOLD/DOUBLE_SIGN para HOTSTUFF). Usa-se EQUIVOCATION com `--byzantine-count`.
- `--byzantine-count` ∈ {1, ⌊(n−1)/3⌋}. O conjunto é sorteado por `ByzantineConfig.withExactCount(n, f, ataque, runSeed)`;
  como em STABLE o líder é sempre o nó 0, as células são estratificadas pelas sementes em que 0 ∈ conjunto
  (líder bizantino) e 0 ∉ conjunto (líder honesto). As sementes são escolhidas só por essa propriedade do sorteio
  (listadas pelo observador `--listar-byz-faixa`), nunca por resultado.
- `--hs-view-timeout` nessas células = T proposto (m final) para o n, L = 100. Comparador mínimo: ROTATING com f = 1.
