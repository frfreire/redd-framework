# Protocolo Experimental — execução

Framework de seleção de protocolo de consenso para registros REDD+.
Operacionaliza `metodologia-framework-consenso-redd.md` para execução concreta:
o que rodar, em que ordem, com que critério de parada, e como verificar.

> **Estado em 2026-10-24.** Os cinco ADRs continuam em `Proposto`. Pela regra do
> `AGENTS.md`, isso mantém D0, D1 e D4 formalmente bloqueadas. Este protocolo
> está pronto para executar **a partir do aceite**; a primeira ação do projeto
> continua sendo a reunião de decisão, não código.

---

## 0. Pré-condições de execução

| # | Pré-condição | Verificação objetiva |
|---|---|---|
| P1 | ADR-0001 aceito | `docs/adr/0001-*.md` com `Estado: Aceito` |
| P2 | ADR-0003 aceito — escopo com Tendermint | fator A com 6 níveis; D3 cobre HotStuff **e** Tendermint |
| P3 | D1 concluída — PBFT mede tráfego real | `grep addBlockTraffic` em `PBFT.java`; fórmula e `Random(42)` removidos |
| P4a | D3a — HotStuff implementado | passa no V-A com ordem O(n) |
| P4b | D3b — Tendermint implementado | passa no V-A com ordem O(n²); *locking* e votos nil verificados |
| P5 | D2 concluída — portão de instrumento | V-A, V-B e V-C documentados |
| P6 | D4 concluída — perfis de carga | dois perfis com taxa média igualada |
| P7 | D0 concluída — requisito→métrica→limiar | tabela rastreável a norma |
| P8 | D5 — pré-registro congelado | commit datado com os seis itens |

**Nenhuma célula da bateria principal roda antes de P1–P8.** Esta é a trava que
separa o protocolo de um exercício de produzir números.

---

## 1. Layout de diretórios

```
arq-redd-experimento/
├── specs/
│   ├── piloto.yaml            # 12 células  × sementes
│   ├── bateria1.yaml          # 144 células × sementes
│   └── bateria2.yaml          # 30 células  × sementes
├── configs/
│   ├── piloto/                # gerado por gerar_configs.py
│   ├── bateria1/
│   ├── bateria2/
│   └── <lote>/manifesto.json  # inventário de células — base da conferência
├── raw/                       # saída bruta por célula, SEM agregação
│   ├── piloto/ bateria1/ bateria2/
├── analise/
│   ├── consolidado.parquet
│   ├── welch/                 # gráficos e ponto de corte
│   ├── anova/ pareto/ sensibilidade/
├── pre-registro/
│   └── 2026-MM-DD-congelamento.md   # os seis itens + hash do spec
└── logs/execucoes/<timestamp>-<lote>.json
```

Regra de ouro do `raw/`: **nunca agregar na coleta.** Guardar a lista de
latências individuais por transação é o que permite calcular p95/p99 depois.
Agregar cedo custa o resultado mais interessante do estudo.

---

## 2. Matriz de fatores e volume real

### Bateria 1 — desempenho e escalabilidade (A × B × C × E)

| Fator | Níveis | n.º |
|---|---|---|
| A — Protocolo | pBFT, HotStuff, **Tendermint**, CasperFFG (PoS), Parlia (PoSA), Nakamoto (PoW) | 6 |
| B — Validadores (n) | 25, 50, 100, 200, 400 | 5 |
| C — Perfil de carga | estacionário, sazonal-rajada | 2 |
| E — Mix público/privado | 100/0, 70/30, 50/50 | 3 |

6 × 5 × 2 × 3 = 180, menos 6 excluídas (Nakamoto em n=400, custo proibitivo —
declarar como limitação) = **174 células**.

Os três protocolos de votação ocupam células distintas do plano
complexidade × liderança, o que permite decompor o efeito em vez de confundi-lo:

| | Líder estável | Rotação de líder |
|---|---|---|
| **O(n²)** | pBFT | **Tendermint** |
| **O(n)** | *(vazia — ablação do HotStuff fecharia)* | HotStuff |

Sem Tendermint, pBFT→HotStuff mudaria complexidade **e** política de liderança
ao mesmo tempo, e a afirmação "a linearidade é o que faz escalar" não se
sustentaria. A quarta célula (HotStuff com líder estável) é uma ablação de
custo marginal quase zero **se a rotação entrar como parâmetro desde o início
da implementação do HotStuff** — decisão a tomar antes do D3, não depois.

### Bateria 2 — robustez bizantina (A × D)

n fixo em 100, carga estacionária, mix 100/0, modelo de ataque único.

| Fator | Níveis | n.º |
|---|---|---|
| A — Protocolo | os mesmos 6 | 6 |
| D — Fração bizantina (f) | 0, 10, 20, 33, 40, 51 % | 6 |

= **36 células**.

### Volume

| Lote | Células | Sementes | Execuções |
|---|---|---|---|
| Piloto | 18 | 10 | 180 |
| Bateria 1 | 174 | *do piloto* | 174 × r |
| Bateria 2 | 36 | *do piloto* | 36 × r |

As 10 sementes nos specs são **provisórias**. O valor definitivo (r) sai do
dimensionamento no piloto (§4) e entra no pré-registro.

**Fixos na rodada principal** (sensibilidade depois): largura de banda,
topologia, tamanho de transação, esquema de assinatura.

---

## 3. Modelo de ataque bizantino

Ponto que revisor cobra e que precisa ser idêntico entre protocolos, sob pena
de a comparação ser inválida. O repositório já traz `ByzantineConfig` /
`ByzantineSweep` com os tipos SILENT, WITHHOLD, EQUIVOCATION, DOUBLE_SIGN.

Decisões a declarar no pré-registro:

- **Tipo adotado na Bateria 2:** `equivocacao` (mensagens conflitantes a
  subconjuntos distintos de honestos). É o ataque forte; silenciamento é mais
  brando e produziria limiares otimistas.
- **Seleção dos nós:** aleatória, com a mesma semente entre protocolos na
  mesma célula, para que a comparação seja pareada.
- **Coordenação:** nós bizantinos coordenam entre si.
- **Verificação de paridade:** confirmar que HotStuff (e Tendermint) respeitam
  os mesmos *hooks* bizantinos dos protocolos existentes antes da Bateria 2.

---

## 4. Sequência de execução

### Etapa A — Piloto (12 células × 10 sementes = 120 execuções)

Objetivo duplo: dimensionar replicações e determinar o corte do transiente.
Não produz resultado para o artigo.

```bash
python3 scripts/gerar_configs.py specs/piloto.yaml --saida configs/piloto
# executar as células (ver §5)
python3 scripts/coletar_saidas.py raw/piloto --manifesto configs/piloto/manifesto.json \
        --saida analise/piloto.parquet
```

**A.1 — Transiente (antes do dimensionamento, não depois).**

```bash
python3 scripts/welch_warmup.py analise/piloto.parquet \
        --valor latencia --grupo protocolo --saida analise/welch/piloto.png
```

Adotar o **maior** corte entre os protocolos, para todos. Cortes distintos por
protocolo reintroduzem o viés que a remoção pretende eliminar — e o viés não é
neutro: não descartar o aquecimento infla a latência das famílias de votação,
justamente a direção que desfavoreceria o Arq-REDD+.

**A.2 — Dimensionamento de replicações**, já sobre os dados com transiente
removido:

```bash
python3 scripts/dimensionar_replicacoes.py analise/piloto_sem_transiente.parquet \
        --metricas t_finalizacao latencia_p95 bytes_por_tx \
        --agrupar protocolo n --precisao 0.05 --custo-por-execucao-min <medido>
```

Adotar o máximo entre as métricas críticas. Se o número vier alto (>30), checar
se o transiente foi mesmo removido antes de orçar CPU — variância inflada
costuma ser bug de análise, não ruído genuíno.

### Etapa B — Pré-registro (D5)

Congelar em commit datado, antes de qualquer célula da bateria principal:

1. métricas e definições operacionais (as sete de `secao-metricas-pt.tex`);
2. limiares de requisito vindos de D0;
3. regras de decisão do procedimento de seleção;
4. critério de comparação (ICs sobrepostos ⇒ nenhuma afirmação de diferença);
5. matriz de fatores e níveis (hash dos `specs/*.yaml`);
6. número de replicações r vindo de A.2;
7. corte do transiente vindo de A.1;
8. tipo de ataque bizantino (§3) e escolhas estatísticas (ADR-0005).

O que **pode** ser derivado depois sem violar o pré-registro: os pesos entre
métricas, via alocação de variância da ANOVA. Isso é medição, não escolha
post hoc — e a distinção precisa estar escrita no artigo.

### Etapa C — Baterias

```bash
python3 scripts/gerar_configs.py specs/bateria1.yaml --saida configs/bateria1 --dry-run
python3 scripts/gerar_configs.py specs/bateria1.yaml --saida configs/bateria1
./executar_lote.sh bateria1
python3 scripts/coletar_saidas.py raw/bateria1 --manifesto configs/bateria1/manifesto.json \
        --saida analise/consolidado_b1.parquet
```

Idem para `bateria2`. O `coletar_saidas.py` confere o manifesto e **acusa
células faltantes** — célula que falhou em silêncio e virou dado ausente
enviesa a análise. Reexecutar as faltantes antes de prosseguir.

### Etapa D — Análise (D7)

```bash
# alocação de variância → pesos do framework
python3 scripts/anova_alocacao.py analise/consolidado_b1.parquet \
        --metrica t_finalizacao --fatores protocolo n perfil_carga mix --ordem-interacao 2
# repetir para latencia_p95, vazao_efetiva, bytes_por_tx

# fronteira de Pareto (não escore agregado)
python3 scripts/pareto.py analise/resumo_b1.csv --tratamento protocolo \
        --minimizar t_finalizacao bytes_por_tx --maximizar tolerancia_bizantina \
        --saida analise/pareto/b1.png
```

Sensibilidade: deslocar os limiares de requisito de D0 e verificar se o conjunto
recomendado muda. A robustez das recomendações às fronteiras é resultado.

---

## 5. Execução paralela e rastreabilidade

Cada célula é independente das demais — é o análogo, nesta pesquisa, das
centenas de achados da triagem SAST, e é onde o enxame rende. Paralelizar até
`enxame.config.json.coordenador.maxCelulasParalelas` (8), uma semente por
processo, sem estado compartilhado.

Regras herdadas do setup de segurança, que valem aqui:

- **Log por execução**, não log compartilhado: um JSON em
  `logs/execucoes/<timestamp>-<lote>.json`, aberto antes da primeira célula e
  fechado após a consolidação. É o que evidencia (ou impede) reprocessar um
  lote até obter o resultado desejado.
- **Detecção de divergência entre execuções**: ao reexecutar um lote já
  arquivado, comparar métrica a métrica por `celula_id`. Divergência após
  mudança de código é esperada; divergência sobre o mesmo commit é suspeita.
- **Ruflo é memória, não orquestrador.** A paralelização real vem do Task tool
  do OpenCode. Usar a memória para idempotência (não re-rodar célula já
  executada) e estado entre sessões.

Cada arquivo de configuração carrega commit do simulador, semente e data —
sem isso não há como reconstruir uma célula quando a análise acusar anomalia
meses depois.

---

## 6. Portões de qualidade (não negociáveis)

| Portão | Quando | Critério de aprovação |
|---|---|---|
| **V-A** complexidade de mensagens | antes de qualquer bateria | pBFT/Tendermint O(n²); HotStuff O(n). Rodar sobre **tráfego medido**, nunca estimado — o detector de fórmula fechada em `validar_complexidade.py` precisa ficar silencioso |
| **V-B** limiares teóricos | antes da Bateria 2 | ruptura em ~1/3 (BFT) e ~1/2 (probabilísticos), dentro de 1 ponto percentual |
| **V-C** confronto com a literatura | antes de publicar | latência/vazão de HotStuff em cenário canônico na mesma ordem de grandeza dos valores publicados; divergências discutidas |
| **Determinismo** | após cada implementação | mesma semente ⇒ mesma saída; duas sementes ⇒ saídas distintas |
| **Completude de lote** | após cada bateria | zero células faltantes contra o manifesto |

---

## 7. Validação adversarial (Gauntlet Loop)

Todo artefato de impacto médio ou superior passa por builder → critic antes de
ser considerado pronto, nas três famílias de modelo (`opencode.json`). Aplicado
a este protocolo, os pontos de entrada são:

| Artefato | Impacto | Caminho |
|---|---|---|
| Patch de medição de tráfego (D1) | **CRÍTICO** | Loop 2 (correção, 2 iterações) → depois coautores |
| Implementação do *locking* do Tendermint | ALTO | Loop 1 contra a especificação, não contra o código do builder |
| Resultado do portão V-A | ALTO | Loop 1; divergência → coautores |
| Afirmação "C_B cresce com n²" | ALTO | Loop 1 contra o código, não contra a prosa |
| Corte do transiente e r | MÉDIO | Loop 1; divergência → árbitro |
| Scripts de análise | MÉDIO | Loop 2 |

O critic re-deriva a partir da fonte primária — código, dados brutos, teoria —
sem receber a conclusão do builder. Foi esse mecanismo que, manualmente, pegou
o bug de tráfego: conferir contra `PBFT.java`, não contra a Tabela 12.

---

## 8. Orçamento computacional

A estimativa só existe depois de medir o tempo de uma célula. O
`dimensionar_replicacoes.py` aceita `--custo-por-execucao-min` e projeta o
total. Ordem de grandeza a esperar:

- Piloto: 180 execuções — serve também para medir o custo unitário.
- Baterias: (174 + 36) × r execuções. Com r = 10, 2.100; com r = 30, 6.300.

Se o custo ficar proibitivo, a saída correta é **reduzir níveis de fator**
(ex.: cortar n=400, ou o mix para dois níveis), nunca reduzir replicações
abaixo do dimensionamento — cortar replicações invalida as comparações por IC.

---

## 9. Limitações a declarar no artigo

- Rede homogênea, latências determinísticas, sem perda de pacotes — distante de
  regiões remotas com conectividade intermitente, onde projetos REDD+ operam.
  Mitigação parcial: cenário de sensibilidade com perda e latência assimétrica.
- pBFT em 400 nós é extrapolação assintótica, não implantação plausível
  (complexidade quadrática limita implantações reais a dezenas de nós). É
  também por isso que o HotStuff entra: torna a faixa alta significativa.
- Nakamoto excluído em n=400 por custo.
- Fronteira em rede e consenso: o framework seleciona consenso, não resolve
  adoção — cujas barreiras dominantes são socioeconômicas e institucionais.
- Loop 2 do Gauntlet ainda sem validação empírica contra artefato real; o
  primeiro candidato é o patch de D1.

---

## 10. Ordem de ataque recomendada

1. **Reunião de ADRs** — destrava tudo. ADR-0001 é o mais sensível (implica
   refazer simulações publicadas); ADR-0003 define o volume das baterias.
2. **D1** (tarefa zero) + **D0** e **D4** em paralelo (documental e dados
   públicos, sem dependência de código).
3. **D3** — caminho crítico de engenharia, agora com dois protocolos:
   **D3a HotStuff** (skill `jabs-consensus-extension`) e **D3b Tendermint**
   (skill `jabs-tendermint`). Implementar HotStuff primeiro: ele valida o
   portão V-A no sentido O(n) e destrava o contraste central. Se o prazo
   apertar, o Tendermint é o que escorrega — e a limitação a declarar passa a
   ser o confundimento complexidade × liderança.
4. **D2** (portões V-A/V-B/V-C).
5. **Piloto** → **D5 pré-registro** → **Baterias** → **D7 análise**.
6. **D8** framework, com o teste de adequação do Arq-REDD+ reportando também
   onde o pBFT perde.
