# Índice do protocolo experimental

> **Fonte oficial do desenho experimental: `.experimento/`** (decisão humana). Arquivos: `PROTOCOLO-EXPERIMENTAL.md`, `0003-escopo-protocolos.md`, `piloto.yaml`, `bateria1.yaml`, `bateria2.yaml`. Este índice **aponta** para eles; não os reescreve e não os corrige. Onde há conflito, vale `.experimento/` e o conflito vai ao humano (seção 3).
>
> Verificado em 2026-10-28 contra o repositório (`src/main/java/jabs`, `docs/adr`, `especificacoes`, `experimentos`, `scripts`, `.ia/rodadas`). Decisão de escopo registrada em `docs/adr/0009-escopo-de-protocolos-do-estudo.md` (origem: `.experimento/0003-escopo-protocolos.md`).
>
> Regra de lacuna: prazo, limiar e valor normativo não aparecem aqui. Onde falta, `a definir com o humano`.
>
> **Atualização 2026-10-29.** Entrou em `.experimento/` o `ADR-0000-redesenho-escalas.md` (Status: **Proposta**, 2026-10-29), que "substitui parcialmente" fatores e níveis e bloqueia a bateria principal até o aceite dos coautores. A regra acima ("onde há conflito, vale `.experimento/`") **não decide** esse caso, porque os dois documentos estão em `.experimento/`. Os conflitos estão na **seção 4**; qual documento prevalece é pergunta ao humano (4(k)). Até lá, as seções 1 a 3 descrevem o desenho de 2026-10-28 (PROTOCOLO + YAML + ADR-0009 do repositório). Os parâmetros que o ADR-0000 manda fixar antes da bateria estão em `docs/adr/0010-parametros-de-instrumento-do-estudo.md` (Proposto, todos `a definir com o humano`).

> **Atualização 2026-10-29 (decisões do humano).** O humano respondeu às perguntas abertas: mandou **seguir as cláusulas do ADR-0000 onde ele define algo** e manter o resto como está; deu as fórmulas do modelo de demanda; propôs a conversão para MMPP-2; esclareceu o que são o "pré-registro anterior" e os "resultados já gerados"; e mandou registrar o conflito n = 4 e as métricas ausentes. Tudo está na **seção 6** (acrescentada; as seções 1 a 5 ficam como estavam, e as linhas afetadas ganharam uma nota "Resposta de 2026-10-29"). O ADR-0000 continua **Proposta** na fonte; o aceite formal dos coautores é do humano. O índice **não** reescreve `.experimento/`.

## 1. Desenho em uma tabela (conferido nos YAML)

| Lote | Fatores | Conta | Células |
|---|---|---|---|
| Piloto (`piloto.yaml`) | protocolo {pbft, hotstuff, tendermint} × n {25,100,400} × carga {estacionario, sazonal}; mix 100-0 fixo | 3×3×2 | **18** |
| Bateria 1 (`bateria1.yaml`) | 6 protocolos × n {25,50,100,200,400} × carga (2) × mix {100-0, 70-30, 50-50}; exclui nakamoto n=400 | 6×5×2×3 = 180, menos 6 | **174** |
| Bateria 2 (`bateria2.yaml`) | 6 protocolos × f {0,10,20,33,40,51}; n=100, estacionário, 100-0, ataque `equivocacao` | 6×6 | **36** |

Sementes: `10` nos três YAML, marcadas **provisórias**. O r definitivo vem do piloto; não há valor aqui.

## 2. Pré-condições P1–P8: estado verificado hoje

Fonte das pré-condições: `PROTOCOLO-EXPERIMENTAL.md` §0. "Nenhuma célula da bateria principal roda antes de P1–P8."

| # | Pré-condição (fonte) | Estado | Evidência no repositório |
|---|---|---|---|
| P1 | ADR-0001 aceito | **Atendida na letra, assunto diferente** | `docs/adr/0001-reserva-buffer-reversao.md` está Aceito (2026-10-25), mas trata de buffer/reversão. O "ADR-0001" do protocolo parece ser o do tráfego medido, que aqui é o ADR-0006 (Aceito). Ver 3(b). |
| P2 | Escopo com Tendermint, fator A com 6 níveis | **Atendida** | YAML com 6 níveis; `docs/adr/0009-escopo-de-protocolos-do-estudo.md` (Aceito). O ADR "0003" citado no protocolo é o 0009 aqui. **Acréscimo 2026-10-30:** o texto desta linha (6 níveis) está **SUPERADO** pela emenda do ADR-0009 (2026-10-30, por decisão do humano): o fator A passa a 5 níveis (pBFT, Tendermint, HotStuff, PoSA e PoS como âncoras; Nakamoto/PoW fora), conforme o ADR-0000. Estado de P2 conforme o ADR-0009 emendado: **atendida quanto ao escopo (Tendermint no escopo, Opção C)**; os YAML ainda listam 6 níveis (não corrigidos, `.experimento/` não é editado) e as contagens 174/36/18 estão superadas por E1 = 240 × r (E2, validação e piloto: `a definir com o humano`). |
| P3 | D1: PBFT mede tráfego real; fórmula e `Random(42)` removidos | **Atendida** | `PBFT.addBlockTraffic` (`PBFT.java:147`); hook em `PeerBlockchainNode.java:153-166`; sem `Random(42)` nem `numAllParticipants * 2 * 1024` em `PBFT.java`; loop 2 T1 e T2 aprovados (ADR-0006), `--verificar-gate loop2-jabs-codigo` libera. Ressalvas abaixo. |
| P4a | D3a: HotStuff implementado, V-A em O(n) | **Não atendida** | Nenhuma ocorrência de HotStuff/Tendermint em `src/main/java` (só em docstring de `scripts/jabs/gerar-grid.py`, `gauntlet-loop.config.json` e skills). |
| P4b | D3b: Tendermint implementado, V-A em O(n²), *locking* e votos nil | **Não atendida** | idem. |
| P5 | D2: V-A, V-B e V-C documentados | **Não atendida** | `experimentos/validacao/` e `experimentos/calibracao/` vazios. `scripts/jabs/validar-complexidade.py` existe (linear/quadrático) mas nenhum CSV de V-A arquivado. Sem registro de V-B nem V-C para pBFT, Casper, Parlia ou Nakamoto. O "gate liberado" do loop 2 é do código (T1–T5), não substitui o portão V-A/B/C que `gauntlet-loop.config.json` exige à parte. |
| P6 | D4: dois perfis de carga com taxa média igualada | **Não atendida** | Não há perfil sazonal nem parâmetro de perfil. `HybridNetworkScenario.java:264-297` gera 5 a 20 transações por segundo, sempre igual; os processos Poisson (`AbstractTxPoissonProcess`) têm intervalo médio constante (`--tx-interval`, padrão 5,0 em `MVPComparison`). Sazonal só existe como texto (REQ-03 em `dominio-redd.md`, skill da ponte). |
| P7 | D0: requisito → métrica → limiar, rastreável a norma | **Só rascunho** | `especificacoes/dominio-redd.md` §6 (REQ-01..10) com todos os limiares `a definir`; §4 declara que o mapeamento para normas é hipótese, sem cláusula. ADR-0005 (Aceito) escolheu **não fixar limiar**. Ver 3(j). |
| P8 | D5: pré-registro congelado em commit datado | **Não atendida** | Não existe `pre-registro/` nem commit de congelamento. Faltam insumos: r (piloto), corte do transiente (piloto), limiares (P7). |

**Atualização 2026-10-29 sobre P1–P8** (a tabela acima fica como estava; abaixo só o que mudou ou foi aceito):

- **P1.** A equivalência de numeração está na tabela de 3(b): o "ADR-0001" do protocolo (medição unificada de tráfego) é o `docs/adr/0006` (Aceito, 2026-10-25). Fica atendida **pelo conteúdo** do 0006, não pelo arquivo `0001-*`.
- **P4a.** A linha acima diz "nenhuma ocorrência de HotStuff/Tendermint em `src/main/java`". Isso envelheceu: hoje existem `jabs/consensus/config/HotStuffConsensusConfig.java` e `jabs/ledgerdata/hotstuff/*` (`HotStuffBlock`, `HotStuffBlockWithTx`, `HotStuffTx`, `HotStuffVote`, `HotStuffQcMessage`, `HotStuffQuorumCertificate`), da tarefa `D3a-1-hotstuff-dados-config` (`.ia/rodadas/loop2-jabs-codigo.jsonl`, linhas 28 a 32; a última registra a rodada 4 autorizada pelo humano só para o tamanho do QC). **Não há classe do algoritmo, cenário nem hook de tráfego do HotStuff**: P4a continua **não atendida**. Tendermint: nenhuma ocorrência em `src/` (busca feita). P4b continua não atendida.
- **P7 (redação proposta, sugestão aceita; não altera `.experimento/`).** Manter o ADR-0005 (Aceito, opção 3) e reescrever P7 assim: *"D0 concluída: tabela requisito → métrica → limiar, em que cada limiar é um parâmetro `a definir com o humano`, com fonte quando houver e rótulo 'descritivo' quando não houver, e com a faixa de sensibilidade de cada limiar congelada no pré-registro (P8). Verificação objetiva: no pré-registro, todo limiar tem valor ou `a definir`, faixa de sensibilidade e rótulo (normativo com fonte | descritivo)."* Os valores das faixas continuam `a definir com o humano` (ADR-0010).

**Ressalvas de P3.** (i) Os níveis do YAML (`pbft`, `casperffg`, `parlia`, `nakamoto`) não são chaves de `MVPComparison`: lá existem `ARQ`, `MCO2`, `AMBIFY`, `OFFSET`, `EARTH`, `TREE`, `DAGSPER`. A correspondência está `a definir com o humano`. (ii) O PBFT hoje tem proponente fixo (`PBFTLocalLANNetwork.java:97`, `min(10, n-1)`), sem validação de primário nem view change; o ADR-0007 (Aceito, opção 2) cobre isso na T6, que ainda não foi aberta (nenhuma linha T6 em `.ia/rodadas/`).

### Pontos que você supunha, conferidos

| Você supunha | Resultado |
|---|---|
| HotStuff e Tendermint não existem | **Confirmado.** |
| PBFT mede tráfego (T1) | **Confirmado.** |
| V-A/B/C não executados para os protocolos do estudo | **Confirmado** pela ausência de artefato. Não consigo afirmar que nunca foram rodados à mão; só que não há registro. |
| Sem perfil sazonal | **Confirmado.** |
| Sem mix público/privado | **Corrigido, em parte.** Existe `HybridNetworkScenario` com `privateTransactionPercentage` (0 a 100, padrão 30), usado só pelo cenário do ARQ (`MVPComparison.java:254-262` fixa 30,0) e pelo `ByzantineSweep` no caso VOTING. Mas as transações geradas **não entram na rede simulada**: são amostradas, contadas em `metrics.recordPublicTransaction/recordPrivateTransaction` e registradas no `AccessControlManager` (`HybridNetworkScenario.java:275-288`), sem broadcast. Pela regra 6 do projeto, isso não é tráfego medido. Os três níveis do fator E (0, 30, 50 por cento privado) cabem no parâmetro, mas não nos demais cinco protocolos. |
| D0 só tem rascunho | **Confirmado.** |

## 3. Inconsistências a resolver pelo humano

Listadas **sem corrigir a fonte**. Cada item diz o que está em conflito e o que se pede ao humano.

### (a) Contagem de células do piloto e das baterias
- `PROTOCOLO` §2 (tabela Volume): piloto 18 células, 10 sementes, 180 execuções.
- `PROTOCOLO` §4 Etapa A: "12 células × 10 sementes = 120 execuções".
- `PROTOCOLO` §1 (layout): piloto 12, bateria 1 com 144, bateria 2 com 30.
- Conferido: `piloto.yaml` = 3×3×2 = **18**; `bateria1.yaml` = 6×5×2×3 = 180 − 6 (nakamoto n=400 × 2 cargas × 3 mix) = **174**; `bateria2.yaml` = 6×6 = **36**. §2 e o ADR de origem (12→18, 144→174, 30→36) batem com os YAML. 12, 144, 30 e 120 parecem valores anteriores ao Tendermint: 12 = 2×3×2 (dois protocolos) e 30 = 5×6 (cinco protocolos) reproduzem a conta; **144 não consegui reproduzir** com os fatores atuais (cinco protocolos dariam 150). É inferência minha, não consta na fonte.
- Pedido: confirmar 18/174/36 como valores vigentes e autorizar a correção do texto de `.experimento/` (§1 e §4), que não toco.

### (b) Colisão e deriva de numeração de ADR
- `.experimento/0003-escopo-protocolos.md` (ADR-0003) × `docs/adr/0003-ajuste-correspondente-e-registro-externo.md` (Aceito). Resolvido por renumeração: **ADR-0009**.
- Deriva mais ampla: o protocolo cita ADR-0001 como o ADR sensível que "implica refazer simulações publicadas", o ADR de origem cita "(ADR-0001)" ao exigir `addBlockTraffic` sem fórmula fechada, e §4 Etapa B cita "escolhas estatísticas (ADR-0005)". Aqui o 0001 é buffer/reversão, o tráfego medido é o 0006 e o 0005 é prazos/limiares. O protocolo fala de "cinco ADRs"; o conteúdo dos origens 0002 e 0004 não está em `.experimento/`.
- Pedido: tabela de equivalência entre a numeração do protocolo e a do repositório; dizer onde fica o ADR de escolhas estatísticas (não existe hoje).

### (c) Skills e scripts citados que não existem
Comparado a `.agents/skills/` e `scripts/`:

| Citado em `.experimento/` | Existe? | Mais próximo que existe |
|---|---|---|
| skill `jabs-consensus-extension` | não | `.agents/skills/jabs-protocolo-builder` |
| skill `jabs-tendermint` | não | idem |
| `scripts/gerar_configs.py` | não | `scripts/jabs/gerar-grid.py` (entrada JSON; chaves `protocolos`, `n`, `carga`, `f`, `sementes`, `duracao_s`; **sem** `base`, `excluir`, `mix`; `f` é contagem absoluta e descarta `f*3 >= n`, logo não gera f = 33, 40, 51 por cento) |
| `scripts/welch_warmup.py` | não | nenhum |
| `scripts/coletar_saidas.py` | não | nenhum (o manifesto de `gerar-grid.py` existe, a conferência não) |
| `scripts/dimensionar_replicacoes.py` | não | nenhum |
| `scripts/anova_alocacao.py` | não | nenhum |
| `scripts/pareto.py` | não | nenhum |
| `./executar_lote.sh` | não | nenhum |
| `validar_complexidade.py` | nome diferente | `scripts/jabs/validar-complexidade.py` (hífen; só ajusta linear/quadrático; não tem o "detector de fórmula fechada" que o protocolo descreve, só uma mensagem de diagnóstico) |
| `metodologia-framework-consenso-redd.md`, `secao-metricas-pt.tex` | não estão no repositório | nenhum |
| layout `arq-redd-experimento/{specs,configs,raw,analise,pre-registro,logs}` | não existe | repositório usa `experimentos/{calibracao,validacao}`; specs em `.experimento/` |
| `analise/consolidado.parquet` | formato | Python 3.12 sem pandas (`AGENTS.md`); parquet exige dependência fora da biblioteca padrão |

- Pedido: dizer se esses scripts serão criados (e por qual agente: `jabs-experimento` escreve em `experimentos/`, não em `scripts/`) ou se o protocolo muda para os existentes; onde ficam os arquivos que o protocolo situa fora do repositório.

### (d) "Loop 1 / Loop 2 / coautores" do §7 contra os loops reais
`gauntlet-loop.config.json` tem `loop1-chaincode`, `loop2-jabs-codigo` (3 rodadas) e `loop3-resultado-decisao` (sem reenvio; impacto BAIXO sem critic, MEDIO arbiter, ALTO/CRITICO humano). O §7 usa outra numeração:

| Linha do §7 | Diz | Leitura provável no repositório (proposta, não decisão) |
|---|---|---|
| Patch de tráfego D1, CRÍTICO | "Loop 2 (correção, 2 iterações) → coautores" | `loop2-jabs-codigo`, orçamento é 3 rodadas, não 2; já fechado (T1). "Coautores" = humano. |
| *Locking* do Tendermint, ALTO | "Loop 1 contra a especificação" | é código de simulador: `loop2-jabs-codigo` (critério 3), não `loop1-chaincode` |
| Resultado do V-A, ALTO | "Loop 1; divergência → coautores" | `loop3-resultado-decisao`; ALTO vai direto ao humano, sem arbiter |
| "C_B cresce com n²", ALTO | "Loop 1 contra o código" | `loop3-resultado-decisao` |
| Corte do transiente e r, MÉDIO | "Loop 1; divergência → árbitro" | `loop3-resultado-decisao`; MEDIO aciona arbiter |
| Scripts de análise, MÉDIO | "Loop 2" | `loop2-jabs-codigo` cobre só `src/main/java/**`; scripts em `scripts/` ou `experimentos/` ficam fora do escopo declarado. Pedido de decisão. |

- Pedido: confirmar o mapeamento, ou alterar o escopo de `loop2` para scripts de análise (mexe em `gauntlet-loop.config.json`, fora do meu escopo).

### (e) Chave da configuração de paralelismo
- `PROTOCOLO` §5: `enxame.config.json.coordenador.maxCelulasParalelas` (8).
- Real: `enxame.config.json` → `coordenador.maxCelulasDeSimulacaoParalelas` = 8 (também citada assim em `.agents/skills/jabs-experimento-doe/SKILL.md`). O valor bate; o nome não.

### (f) Vocabulário de perfil de carga e de ataque
- Carga: `especificacoes/dominio-redd.md` REQ-03 diz "pico sazonal"; `PROTOCOLO` §2 diz **`sazonal-rajada`**; os três YAML usam **`sazonal`**; `gerar-grid.py` usa `estacionaria` e `sazonal`; os YAML usam `estacionario`. Além disso a definição do perfil (forma, amplitude, "taxa média igualada") não está escrita em nenhum arquivo, e "rajada" e "sazonal" não são a mesma forma. Pedido: um nome único e a definição operacional (valores `a definir com o humano`).
- Ataque: `PROTOCOLO` §3 e `bateria2.yaml` usam **`equivocacao`**; o código tem `ByzantineConfig.AttackType.EQUIVOCATION`. O construtor com `String` aceita qualquer texto, e `PBFT.java:310,347` compara `"EQUIVOCATION".equals(attack)` (sensível a caixa). Pela leitura do código, `equivocacao` não casaria e **não geraria erro**, deixando o nó honesto (não executado). Pedido: tabela de tradução oficial e falha explícita para valor desconhecido (tarefa de loop 2).

### (g) Nota de estado desatualizada
- `PROTOCOLO` linha 7: "Estado em 2026-10-24. Os cinco ADRs continuam em Proposto ... mantém D0, D1 e D4 formalmente bloqueadas".
- Hoje: ADR-0001..0007 estão Aceitos (0001..0005 em 2026-10-25), o ADR-0008 está Proposto, o ADR-0009 é novo e Aceito, e D1 (T1 e T2 do loop 2) está feita. A nota deve ser datada de novo ou removida.

### Outras encontradas na verificação (fora da lista original)

- **(h) pBFT é "líder estável" no plano, mas o ADR-0007 o faz rotativo.** O ADR-0007 (opção 2) define primário `(altura + trocas) % n`, o que tira o pBFT da célula "líder estável". Hoje o proponente é fixo (`PBFTLocalLANNetwork.java:97`). Com seleção aleatória de bizantinos na Bateria 2, o proponente fixo pode cair entre eles. Pedido: dizer qual política de liderança o pBFT do estudo tem.
- **(i) A ablação não é nível do fator A.** Os YAML trazem seis valores e nenhum "hotstuff com líder estável". Falta dizer se vira sétimo nível, lote à parte ou sensibilidade, e qual o custo em células (`a definir com o humano`).
- **(j) ADR-0005 × limiares de D0.** O protocolo (§4 Etapa B item 2 e Etapa D "deslocar os limiares de D0") espera limiares; o ADR-0005 (Aceito, opção 3) decidiu não fixá-los. P7 ("rastreável a norma") e a análise de sensibilidade de limiares ficam sem insumo. Pedido: reabrir o ADR-0005 ou reescrever P7 como comparação relativa.
- **(k) "Seis itens" do pré-registro.** P8 e o layout dizem "os seis itens"; §4 Etapa B lista **oito**.
- **(l) Modelo de ataque não é idêntico entre protocolos.** §3 exige o mesmo ataque em todos. No código, `EQUIVOCATION` tem ramo em PBFT, Parlia e CasperFFG, mas em `BitcoinNode.java:58-61` é tratado como retenção (WITHHOLD) no Nakamoto. HotStuff e Tendermint não existem. Isto compromete a Bateria 2 e é o critério 4 da bar do loop 2. Pedido: definir o que "equivocação" significa em PoW.
- **(m) Piloto cobre 3 de 6 protocolos.** O corte do transiente é "o maior entre os protocolos, para todos" (§4 A.1), mas o piloto não roda casperffg, parlia nem nakamoto.
- **(n) Duração e custo.** `duracao_sim_s: 7200` em todos os YAML sem fonte de custo medido; §8 diz que a estimativa só existe depois de medir uma célula. Não há medição arquivada.
- **(o) DAGsper/TangleIOTA fora do escopo, mas o código permanece.** Ver ADR-0009. Remoção do código, se desejada, é decisão do humano.

**Resposta de 2026-10-29 à pendência 3(b) (tabela de equivalência, sugestão aceita pelo humano; o texto de 3(b) acima fica como estava):**

| Numeração em `.experimento/` | Assunto | No repositório | Situação |
|---|---|---|---|
| ADR-0001 (protocolo e ADR-0000 o citam) | medição unificada de tráfego | `docs/adr/0006-pbft-trafego-medido.md` | Aceito (2026-10-25) |
| ADR-0003 | escopo de protocolos | `docs/adr/0009-escopo-de-protocolos-do-estudo.md` | Aceito (2026-10-28) |
| ADR-0002 | pré-registro | **não existe** | `pre-registro/` também não existe (P8) |
| "ADR-0005, escolhas estatísticas" (`PROTOCOLO` §4 Etapa B, item 8) | escolhas estatísticas | **não existe**. `docs/adr/0005` é prazos e limiares, outro assunto | as escolhas do ADR-0000 (decisão 8) são o conteúdo que esse ADR teria; onde registrar: `a definir com o humano` |
| ADR-0004 | não consta em `.experimento/` | não há equivalente identificado. `docs/adr/0004` (Raft ou BFT) é outro assunto | conteúdo desconhecido; não assumir |
| ADR-0000 (redesenho das escalas) | redesenho | **colide com `docs/adr/0000-template.md`**. Não foi registrado no repositório (está Proposta e só em `.experimento/`) | número no repositório: `a definir com o humano` quando for aceito |

## 4. Conflitos entre o ADR-0000 (Proposta, 29/10) e os specs/ADR-0009 (28/10)

> Fontes comparadas: `.experimento/ADR-0000-redesenho-escalas.md` (Proposta, 2026-10-29) contra `PROTOCOLO-EXPERIMENTAL.md`, `0003-escopo-protocolos.md`, `piloto.yaml`, `bateria1.yaml`, `bateria2.yaml` (todos de 2026-10-28) e `docs/adr/0009`. Nada abaixo escolhe um desenho. Prefixo **C-** para não colidir com as letras (a) a (o) da seção 3.
>
> Conferência aritmética: 5×4×3×2×2 = **240** (confere com o ADR-0000); 3×3×2 = 18, 6×5×2×3 − 6 = 174 e 6×6 = 36 (confere com os YAML, ver 3(a)). A correção do texto de `.experimento/` (§1 e §4 do PROTOCOLO) depende de autorização humana explícita; eu não edito lá.
>
> O ADR-0000 diz substituir "protocolo × regime × n ∈ {12, 36, 60} × φ" (pré-registro anterior) e dimensionar validadores por "1 nó/1.000 ha". **Nenhum arquivo do repositório contém esse desenho**: os YAML de 28/10 usam n ∈ {25, 50, 100, 200, 400} e `pre-registro/` não existe (P8). Há portanto três desenhos citados (o de 12/36/60, o de 28/10 e o do ADR-0000), e só dois estão no repositório. Pergunta em C-k.

| Item | Specs YAML + PROTOCOLO + ADR-0009 (28/10) | ADR-0000 (Proposta, 29/10) | Impacto | Quem decide |
|---|---|---|---|---|
| **C-a** Protocolos | 6 níveis: pbft, hotstuff, tendermint, casperffg, parlia, nakamoto (ADR-0009, decisão 1; P2) | 5 níveis: pBFT, Tendermint, HotStuff, **PoSA e PoS como âncoras** de validade externa. **PoW excluído** ("viola R1, R2 e R4") | Muda a decisão 1 do ADR-0009 e P2 (6 para 5). **R1 a R4 não estão definidos em nenhum arquivo do repositório** (busca feita): a exclusão do PoW não é verificável aqui. PoSA e PoS provavelmente são `parlia` e `casperffg` (a confirmar; ver L11 da auditoria) | humano e coautores |
| **C-b** Validadores n | 25, 50, 100, 200, 400 (piloto: 25, 100, 400) | E1: 4, 16, 64, 128 ("faixa de governança, log"). Validação **fora do ajuste**: 256, 400, 800 | **n = 4 não roda para PoSA/PoS hoje**: Parlia e Casper exigem n ≥ 10, senão o setup da rede não termina (AGENTS.md; `docs/adr/0006`, débito 3; auditoria D). Afeta 2 protocolos × 3 L × 2 R × 2 W = **24 das 240 células**. Ressalva: o limite é sobre o total de nós da rede; se N for lido como nº de validadores (com nós não validadores a mais), a restrição muda de lugar. Hoje "n" significa 1,0 n, 1,3 n ou 0,3 n conforme protocolo e CLI (auditoria L10), então a pergunta é aberta. HotStuff aceita n ≥ 4 (`HotStuffConsensusConfig.MIN_NODES`). n = 800: custo `NAO_MEDIDO` (auditoria D). O ADR-0000 não diz com quais protocolos se valida | humano |
| **C-c** Perfis de carga | `estacionario` e `sazonal` (PROTOCOLO §2: `sazonal-rajada`) | fator **R Regime: Poisson vs MMPP-2 de mesma média** | **Nome:** `sazonal` = sinônimo de `sazonal-rajada` (sugestão aceita). **Substituição proposta, não decidida:** o ADR-0000 troca esse fator por Poisson vs MMPP-2. "Estacionário" do YAML equivale, no código, a Poisson homogêneo (auditoria C.1), mas isso é leitura minha, não consta no ADR-0000. MMPP-2 não existe no simulador | humano e coautores |
| **C-d** Fatores | A protocolo, B n, C perfil, **E mix público/privado (100/0, 70/30, 50/50)** | A, N, **L demanda (λ_B, 10λ_B, 100λ_B)**, R regime, **W enlace por nó (institucional, restrito)**. **E some** | L e W são fatores novos sem valor fixado (ver ADR-0010). W exige banda por nó parametrizável e igual entre protocolos; hoje só o PBFT/ARQ tem banda constante (100 Mbps), os demais usam distribuições regionais (auditoria B.2, L8). L exige taxa de transação igual entre protocolos, que hoje difere (0,2; 2; 10; ~12,5 tx/s, auditoria L13) e o PBFT/ARQ não tem gerador de transações (L14). Sobre E: o mix hoje não gera tráfego na rede simulada (ressalva do índice), então sua saída elimina essa pendência, mas não as de L13 a L15 | humano e coautores |
| **C-e** Contagem de células | piloto **18**; bateria 1 **174**; bateria 2 **36**; 210 células na rodada principal; ~2.100 execuções com r = 10 provisório | E1 **240 × r**; E2 φ × regime "para n no centro da faixa"; pontos de validação 256, 400, 800 | E2 **não é contável**: faltam protocolos, "centro da faixa" (4..128 em log não tem um centro único declarado), L e W de E2. Com r = 10 provisório, E1 daria 2.400 execuções (conta, não decisão). O ADR-0000 declara os resultados antigos como "piloto" para calcular r, mas vêm de outro desenho; se o piloto de 18 células do PROTOCOLO continua exigido, é pergunta | humano |
| **C-f** Bateria 2 / robustez | f ∈ {0, 10, 20, 33, 40, 51} %, 6 protocolos, n = 100, estacionário, 100/0, ataque `equivocacao` | E2: **φ ∈ {0; 0,10; 0,30}** × regime | O maior φ do ADR-0000 (0,30) fica abaixo de 1/3: **nenhuma célula do E2 atinge a ruptura BFT** que o portão V-B (PROTOCOLO §6: ruptura em ~1/3 e ~1/2, ±1 ponto percentual) precisa observar. Ou V-B roda fora do E2, ou os níveis mudam. Conversão φ para contagem de nós (arredondamento `ceil`, auditoria A.3) a definir | humano |
| **C-g** Métricas e critérios | as sete de `secao-metricas-pt.tex` (arquivo **não está no repositório**; não consigo enumerar as sete). Citadas em scripts do PROTOCOLO: `t_finalizacao`, `latencia_p95`, `bytes_por_tx`, `vazao_efetiva`, `tolerancia_bizantina`; Pdv no REQ-02 | T_b, C_b (total, por pacote), C_nó^max, Θ vazão, L_tx p95, U ocupação do enlace. **Critérios V1 (Θ ≥ λ_pico), V2 (L_tx ≤ L_max), V3 (U ≤ U_max)** | **Colisão de nome:** V1 a V3 (critérios de viabilidade) × V-A, V-B, V-C (portões de qualidade do PROTOCOLO §6). Estado no código: T_b existe; L_tx p95 existe (`getPercentileConfirmationLatency(95)`) mas com fallback que mistura duas definições de Tt (auditoria B.4); **Θ, U e C_nó^max não têm implementação** (busca por throughput, vazão, ocupação, utilização: sem resultado em `src/main/java/jabs`). C_b hoje é "bytes recebidos pelo nó que finalizou primeiro, por altura" (`EnhancedBlockFinalizationLogger.java:100`), não "total na rede". λ_pico não é definido. **V1 a V3 são veredito binário com limiar; o ADR-0005 (Aceito, opção 3) decidiu não fixar limiar** e rotular o descritivo como tal | humano |
| **C-h** Análise estatística | ANOVA (alocação de variância), Pareto, sensibilidade a limiares de D0; ICs sobrepostos ⇒ sem afirmação de diferença | ANOVA de efeitos fixos sobre Box-Cox, HC3, ART, Games-Howell, Holm, ω²; metamodelo log-log com cobertura do IP 95% e MAPE < 10%; fronteira n_max(λ) por inversão, confirmada por simulação | Python 3.12 sem pandas (AGENTS.md). Não verifiquei se numpy, scipy ou statsmodels estão instalados. Box-Cox, HC3, ART e Games-Howell sem biblioteca estatística é trabalho grande: dependência fora da biblioteca padrão é decisão. **O ADR-0000 cita "MAPE < 10%" na decisão e lista "o limiar de MAPE" entre os parâmetros a fixar**: inconsistência interna | humano e coautores |
| **C-i** Medição de tráfego do PBFT | `PROTOCOLO` P3: PBFT mede tráfego real (atendida) | Contexto 2: `PBFT.java` 122 a 135 estima `numAllParticipants * 2 * 1024` mais ruído. "ADR-0001 passa a ser pré-requisito bloqueante". Resultados antigos viram "piloto" | **Verificado no código atual: a estimativa já foi removida** (ver detalhe). Mas "medição por pacote em **todos** os protocolos" ainda **não vale**: HotStuff sem hook, Tendermint inexistente, Casper e Parlia contam só pacotes de bloco. Resultados antigos: ver detalhe | humano (status dos resultados antigos) |
| **C-j** Hierarquias | não aparecem em nenhum documento de 28/10 (busca feita) | "alternativa na discussão, sem simulação neste artigo" | Sem conflito. Ver detalhe sobre a citação da Lei 15.042/2024 | n/a (informar) |
| **C-k** Qual documento prevalece | `PROTOCOLO-EXPERIMENTAL.md` (28/10) é a "fonte oficial" (decisão humana registrada no cabeçalho deste índice e no ADR-0009) | ADR-0000 (29/10) é **Proposta** e diz bloquear a bateria principal até o aceite dos coautores | Os dois estão em `.experimento/`; a regra "vale `.experimento/`" não desempata. Até haver resposta, este índice trata 28/10 como vigente e o ADR-0000 como proposta | **humano** (pergunta) |

### Detalhe por item: o que NÃO depende da decisão

**C-a.** Vale nos dois desenhos: pBFT, Tendermint e HotStuff continuam no estudo, logo o plano complexidade × liderança do ADR-0009 (decisão 2), a ordem D3a antes de D3b (decisão 3), a rotação de líder como parâmetro (decisão 4) e a exclusão de DAGsper e TangleIOTA (decisão 5) valem inalteradas. HotStuff e Tendermint exigem os mesmos hooks bizantinos nos dois. O que muda é só a decisão 1 (número de níveis) e P2. O ADR-0000 não menciona a ablação (HotStuff com líder estável); a sugestão aceita (lote à parte) vale nos dois.

**C-b.** Vale nos dois: HotStuff e Tendermint não têm o limite n ≥ 10; o pBFT com n = 4 finaliza com 3 votos (teste de quórum da T2). O requisito "n ≥ 10 para Parlia/Casper" é fato do código, independe do desenho; o desenho define apenas se ele é bloqueante. Faltando decisão sobre o significado de n (L10), nenhum dos dois desenhos é executável de modo comparável.

**C-c.** Vale nos dois: gerador de carga **parametrizável** e determinístico por semente; igualdade de taxa média entre perfis (P6; no ADR-0000, "mesma média"); registro da submissão de cada transação para que Tt não caia no fallback; RNG dedicado da carga é desejável (auditoria C.2). O ADR-0000 pede também o índice de dispersão, que a ponte já exigia.

**C-d.** Vale nos dois: banda por nó e tamanho de transação como **parâmetros uniformes** entre protocolos (os YAML já os listam como fixos, L8); taxa de transação igual entre protocolos.

**C-e.** Vale nos dois: o manifesto de células e a conferência de completude ("zero células faltantes"), r vindo de um piloto, e o raw sem agregação.

**C-f.** Vale nos dois: **ataque único** entre protocolos (definição aceita, ver ADR-0010 PI-12), **falha explícita** para ataque desconhecido (hoje cai no honesto sem erro), tradução `equivocacao` para `EQUIVOCATION`, seleção de nós bizantinos pareada por semente com universo de sorteio unificado. HotStuff e Tendermint precisam respeitar os hooks de `ByzantineConfig` antes de qualquer bateria bizantina.

**C-g.** Vale nos dois: **T_b**, **C_b medido por pacote** (regra 6 do projeto), L_tx p95 desde que Tt seja medido, e a declaração de semente, versão do simulador e granularidade em todo resultado. Só a granularidade de C_b ("por nó que finalizou" contra "total na rede" contra "máximo por nó") precisa de decisão.

**C-h.** Vale nos dois: raw sem agregação, remoção do transiente com corte único (maior entre protocolos), ANOVA de efeitos fixos para alocação de variância, e que pesos entre métricas derivem da ANOVA (medição, não escolha post hoc). A sensibilidade a limiares permanece nos dois, mas conforme a sugestão aceita sobre P7 os limiares são parâmetros `a definir`, com faixas congeladas no pré-registro.

**C-i. Verificação no código atual (leitura, nada executado):**
- `PBFT.java` não contém `numAllParticipants * 2 * 1024`, nem `Random(42)`, nem ruído gaussiano (buscas feitas em `src/main/java/jabs`; o único `Random(42)` restante é um comentário em `TangleIOTA.java:22`, fora do escopo). Nas linhas 122 a 135 do arquivo atual há métodos de consulta (`isPrepared`, `hasAcceptedPrePrepare` etc.); as linhas citadas pelo ADR-0000 pertencem a uma versão anterior.
- O tráfego do PBFT agora é soma de `packet.getSize()`: `PBFT.addBlockTraffic` (`PBFT.java:147`), `PBFT.addConsensusMessageTraffic` (`:158`), chamados no hook de voto de `PeerBlockchainNode.java:155-167`. Foi a tarefa T1 do loop 2 (`docs/adr/0006`, "Fechamento da tarefa 1").
- Convenção do ADR-0006: "tráfego recebido por nó". Por isso o PBFT conta o broadcast que inclui o remetente (passa pela rede); isso difere do que o ADR-0000 chama "C_b total, medido por pacote". Ambos podem ser compatíveis, mas a soma sobre todos os nós não existe hoje.
- **Pendências para a condição "medição por pacote em todos os protocolos":** (1) HotStuff: só dados e config (`HotStuffConsensusConfig`, `ledgerdata/hotstuff/*`), sem algoritmo nem hook. (2) Tendermint: nada em `src/`. (3) Casper e Parlia: `PeerBlockchainNode.java:49-55` soma só pacotes de **bloco**; o ramo de votos (`:150-186`) trata apenas PBFT e DAGsper; não encontrei caminho que some o tamanho de votos de Casper ou Parlia. Se confirmado por execução, C_b de PoS e PoSA exclui os votos e não é comparável ao de pBFT. (4) Casper e Nakamoto ainda contam autoentrega local (débito 8 do ADR-0006).
- **Resultados antigos "passam a piloto"?** O ADR-0000 diz que "estimam a variância para calcular r e validam o simulador". O ADR-0006 já os declara inválidos: C_B do PBFT/ARQ anterior à T1 é impacto **CRÍTICO** (humano decide), e Tb/C_B anteriores à T2 valem como quórum acima de 2f+1. Um desvio-padrão calculado sobre uma métrica estimada com ruído gaussiano de 15% não dimensiona r para C_b. As duas classificações só são compatíveis se "piloto" significar "não publicável, útil só para validar o simulador". Além disso, **não há resultado arquivado no repositório** (`experimentos/validacao/` e `experimentos/calibracao/` vazios): os "resultados já gerados" estão fora daqui. Decisão sobre o status deles: humano.
- **Resposta de 2026-10-29 (decisão 4; o texto acima fica como estava).** "Resultados já gerados" são **os que estão sendo produzidos agora**; **não há resultado arquivado neste repositório hoje**. Portanto a premissa "resultados antigos passam a piloto" (ADR-0000, Consequências) **não se aplica a nenhum arquivo existente**. Ela só passará a valer para um resultado depois que ele for arquivado com semente, versão do simulador e granularidade declaradas (regra 6 do `AGENTS.md`); até lá não há o que classificar como piloto. A observação acima sobre o ADR-0006 (C_B do PBFT/ARQ anterior à T1 é CRÍTICO) continua valendo para qualquer resultado gerado com código anterior à T1/T2, mas hoje não há instância arquivada a que aplicá-la. O "pré-registro anterior" também foi esclarecido (seção 6.4).

**C-j.** A citação "Lei 15.042/2024: registro central sob um órgão gestor e entidades credenciadas" (ADR-0000, Contexto 3) **não foi verificada por mim**: o texto da lei não foi consultado e ela não aparece em `dominio-redd.md` (a seção 4 de lá é hipótese e não cita cláusula). Tratar como afirmação do ADR-0000 a confirmar, não como fato do domínio. Se o registro é central, isso toca a pergunta 5 de `dominio-redd.md` §7 (Raft ou BFT, ADR-0004). Arquiteturas hierárquicas ficam fora do escopo de simulação em qualquer cenário.

**C-k. Pergunta ao humano:** qual documento prevalece até o aceite do ADR-0000? (1) `PROTOCOLO-EXPERIMENTAL.md` (28/10, "fonte oficial") continua vigente e o ADR-0000 é só proposta; (2) o ADR-0000 passa a vigorar para o desenho e o PROTOCOLO é revisado (autorização para alterar `.experimento/`, que eu não toco); (3) os dois convivem como desenhos alternativos, com o ADR-0009 anotado. Enquanto isso, o aceite dos coautores é condição escrita no próprio ADR-0000 ("bloqueia a bateria principal"), e a pré-condição P8 também não está atendida.

**Resposta de 2026-10-29 a C-k (o texto acima fica como estava):** nem (1), nem (2), nem (3) literalmente. A regra do humano é de **precedência por cláusula**: vale o ADR-0000 onde ele define algo; o que ele não define permanece como está. Ver seção 6.1. O aceite formal dos coautores continua do humano.

## 5. Pendências: estado após as sugestões aceitas em 2026-10-29

> Atualização das pendências da seção 3 sem remover nenhuma. "Aceita" quer dizer que o humano aceitou a sugestão como direção; os valores e o nome dos parâmetros continuam `a definir` e vão ao `docs/adr/0010-parametros-de-instrumento-do-estudo.md`. Nenhuma tarefa foi aberta por mim.

| Pendência (seção 3) | Estado em 2026-10-29 | Ainda aberto |
|---|---|---|
| (a) 18/174/36 | **Conferem aritmeticamente.** Os valores de 12, 144, 30 e 120 do PROTOCOLO continuam sem reprodução | Correção do texto de `.experimento/` depende de **autorização humana explícita**; sem ela, não edito **[Acréscimo 2026-10-30] Estado: SUPERADO quanto à vigência:** 18/174/36 são números de 28/10; vale E1 = 240 × r (ADR-0000), ver ADR-0009 emendado. |
| (b) Numeração de ADR | **Tabela de equivalência** registrada (fim da seção 3) | Onde fica o ADR de escolhas estatísticas; número do ADR-0000 no repositório |
| (c) Scripts e skills inexistentes | **Aceito:** scripts do protocolo em `experimentos/scripts/`, escritos por `jabs-experimento` (cujo escopo de escrita é `experimentos/**`). Raw em **CSV/JSONL** (sem pandas); Parquet **opcional** | Skills `jabs-consensus-extension` e `jabs-tendermint` continuam inexistentes. Quem revisa script em `experimentos/` (ver (d)). Dependência estatística (C-h) |
| (d) Mapeamento dos loops | sem mudança | Confirmação do mapeamento; scripts de análise ficam fora do escopo do `loop2-jabs-codigo` |
| (e) Chave `maxCelulasParalelas` | sem mudança | Só o nome da chave diverge |
| (f) Vocabulário de carga | **Aceito:** `sazonal` = sinônimo de `sazonal-rajada`. **Proposto, não decidido:** o ADR-0000 troca o fator por Poisson vs MMPP-2 e adiciona o fator L (C-c, C-d) | Definição operacional, A e T da sazonalidade, parâmetros do MMPP-2 (ADR-0010 PI-10, PI-11) |
| (f) Vocabulário de ataque | **Aceito:** tradução `equivocacao` para `EQUIVOCATION`; **falha alta** em ataque desconhecido; **ataque único:** nó bizantino emite duas mensagens conflitantes a subconjuntos disjuntos de honestos (ADR-0010 PI-12) | Implementação é tarefa do loop 2 (`jabs-builder`), não aberta. Coordenação entre bizantinos (L5) e o que a definição significa em PoW (se Nakamoto ficar) |
| (g) Nota de estado | sem mudança | PROTOCOLO linha 7 continua desatualizada |
| (h) Líder do pBFT | **Aceito:** parametrizar a política de liderança do pBFT; o estudo usa **líder estável** (troca só por falha); o primário rotativo do ADR-0007 vira **modo alternativo**; exige **ADR complementar** a ser aceito pelo humano (ADR-0010 PI-14) | "Troca só por falha" exige *view change* por falha, que o ADR-0007 excluiu (opção 2 sem timeout; a opção 3 só abre por novo ADR). Sem isso, líder bizantino ou silencioso para a rede |
| (i) Ablação | **Aceito:** a ablação é **lote à parte** (HotStuff com líder estável). Não é sétimo nível nem sensibilidade | Tamanho do lote (células) e r: `a definir` |
| (j) ADR-0005 × limiares | **Aceito:** manter o ADR-0005 e **reescrever P7** com limiares como parâmetros `a definir com o humano` e faixas de sensibilidade congeladas no pré-registro (redação proposta na seção 2) | Os valores das faixas (ADR-0010). V1 a V3 e MAPE do ADR-0000 também são limiares (C-g) |
| (k) "Seis itens" × oito | sem mudança | O ADR-0000 acrescenta itens ao pré-registro (decisão 7, V1 a V3) |
| (l) Ataque não idêntico entre protocolos | **Aceito:** ataque único (ver (f)) e **unificar o universo de sorteio** de bizantinos para pareamento por semente | Qual universo (todos os nós, só validadores): `a definir` (ADR-0010 PI-13) |
| (m) Piloto cobre 3 de 6 | o ADR-0000 declara os resultados antigos "piloto" (C-e, C-i) | Se há piloto no novo desenho |
| (n) Duração e custo | sem mudança; n = 800 do ADR-0000 agrava o custo desconhecido | Medir uma célula por protocolo |
| (o) DAGsper e TangleIOTA | **Aceito:** manter o código, **marcado "fora do escopo do estudo"** | A marcação em si (comentário ou documento) é tarefa de quem escreve em `src/`; não fiz |

**Pendências novas (2026-10-29):**

- **(p)** Qual documento prevalece: PROTOCOLO (28/10) ou ADR-0000 (29/10, Proposta). Ver C-k. **Bloqueia a decisão de todo o resto da seção 4.** **Respondida em 2026-10-29 (seção 6.1):** precedência por cláusula; o ADR-0000 vale onde define algo.
- **(q)** Existência do "pré-registro anterior" (n ∈ {12, 36, 60} × φ) e dos "resultados já gerados": fora do repositório (C-e, C-i). dizer onde estão ou se são de outro projeto. **RESOLVIDA em 2026-10-29 (seção 6.4):** o "pré-registro anterior" era a v1 do rascunho do humano, não pré-registro formal; os "resultados já gerados" são os que estão sendo produzidos agora e nenhum está arquivado aqui.
- **(r)** Definição de R1 a R4 (ADR-0000) para poder verificar a exclusão do PoW (C-a). **Respondida em 2026-10-29 (seção 6.1):** R1 a R4 **permanecem não definidos neste repositório**; a exclusão do PoW é decisão do ADR-0000, registrada como tal.
- **(s)** Parâmetros de instrumento do estudo: `docs/adr/0010-parametros-de-instrumento-do-estudo.md` (Proposto; todos `a definir com o humano`).
- **(t)** Status de V-B no desenho do ADR-0000 (φ máximo 0,30, C-f) e granularidade de C_b ("total" × "por nó" × "máximo por nó", C-g).
- **(u)** Votos de Casper e Parlia fora do C_b, se confirmado por execução (C-i): afeta qualquer comparação de C_b com o pBFT nos dois desenhos.
- **(v)** Comparação justa de tamanho de voto: o voto do pBFT não carrega assinatura (hash mais 10 B de cabeçalho; 42 B no teste `PBFTTrafficMeasurementTest`), enquanto o voto do HotStuff soma tamanho de assinatura (`HotStuffVote.computeSize`). Unificar `s_v` é pedido do ADR-0000 (ADR-0010 PI-08).

## 6. Decisões do humano em 2026-10-29

> Acrescentada em 2026-10-29. Registra as respostas do humano às perguntas abertas, sem remover nada das seções 1 a 5. Valores numéricos continuam `a definir com o humano`. Detalhe dos parâmetros: `docs/adr/0010-parametros-de-instrumento-do-estudo.md` (PI-00 a PI-20).

### 6.1 Precedência (respostas às perguntas 1 e 2; fecha C-k e (p), responde (r))

- **Regra do humano:** usar as cláusulas do ADR-0000 (`.experimento/ADR-0000-redesenho-escalas.md`) **onde ele define algo**; manter **como está** o que ele **não** definiu.
- **Efeito sobre os conflitos da seção 4:** onde o ADR-0000 define (protocolos, n, fatores L/R/W_nó, métricas, critérios V1 a V3, análise), ele é a cláusula a seguir. Onde ele não define (R1 a R4, valores de λ_B, N_op, κ, η, W_janela, P, W_nó, L_max, U_max, MAPE, s_v, s_tx), **nada é inventado** e vale o que já estava, ou `a definir com o humano`.
- **PoW:** a exclusão do PoW (e, portanto, a mudança de 6 para 5 níveis de protocolo, C-a) é **decisão do ADR-0000** ("viola R1, R2 e R4"). **R1 a R4 não estão definidos neste repositório** (busca já feita, seção 4 C-a) e não os inventei; a exclusão é registrada como decisão do ADR-0000, não como conclusão verificável aqui. O ADR-0009 (decisão 1, 6 níveis) e P2 divergem do ADR-0000; o humano mandou seguir o ADR-0000, mas **não alterei** o ADR-0009 (Aceito) nem `.experimento/`. Reconciliar o ADR-0009 é pendência de decisão formal do humano (novo ADR ou emenda).
- **Status do ADR-0000:** continua **Proposta** na fonte. O humano mandou segui-lo; **o aceite formal dos coautores é do humano** e não foi dado por nenhum agente. A condição escrita no ADR-0000 ("bloqueia a bateria principal até aceite dos coautores") permanece, bem como a pré-condição P8.
- **PoSA/PoS × parlia/casperffg:** o ADR-0000 não diz qual protocolo do JABS corresponde a cada um. Continua "provavelmente Parlia e Casper (a confirmar)"; não decidi.

### 6.2 Modelo de demanda (resposta à pergunta 3)

Fórmulas do humano (registradas em ADR-0010, PI-00 e PI-09a):

```
λ̄ = N_op·κ / P        λ_pico = η·N_op·κ / W_janela        λ_pico/λ̄ = η·P / W_janela
```

- N_op: operadores e participantes ativos. κ: transações de registro por participante por período de compromisso (relatos, transferências, conciliações, aposentadorias). P: duração do período de compromisso. η: fração dessas transações que cai na janela que antecede o prazo de conciliação. W_janela: duração dessa janela. λ_B: valor de λ̄ no cenário regulatório base. Com P em segundos, λ̄ sai em tx/s.
- Os três níveis do fator L (λ_B, 10λ_B, 100λ_B) são cenários de **N_op·κ/P** em três ordens de grandeza.
- **η e W_janela NÃO entram em λ_B** (só determinam a forma temporal).
- **Colisão de nomes fechada:** **W_janela** = duração da janela (demanda); **W_nó** = enlace por nó (fator W do E1). Fecha a ambiguidade marcada em PI-05. Também não confundir **P** (período) com as pré-condições P1 a P8.
- **Valores** de N_op, κ, P, η, W_janela: `a definir com o humano`, fontes `a definir`.

### 6.3 Conversão para MMPP-2 (PROPOSTA do humano, "sugiro acrescentar"; **não aceita**)

```
1/σ2 = W_janela      1/σ1 = P − W_janela      π_alto = σ1/(σ1+σ2) = W_janela/P
λ2 = η·N_op·κ / W_janela      λ1 = (1−η)·N_op·κ / (P − W_janela)
```

- **Estado:** proposta a entrar no pré-registro. Não a tratei como aceita.
- **Conferência da álgebra (feita; passos em ADR-0010 PI-10):** π_alto = W_janela/P confere; (1−π)λ1 + πλ2 = N_op·κ/P = λ̄ **exatamente**, para quaisquer valores válidos; a referência Poisson usa a mesma λ̄. Conferido também por aritmética racional exata em Python, com valores ilustrativos descartados (não são parâmetros do estudo).
- **Restrição de validade para o pré-registro:** 0 < W_janela < P e **η > W_janela/P** (e η < 1 para λ1 > 0). Com η = W_janela/P, λ1 = λ2 = λ̄ e o MMPP-2 degenera em Poisson.
- **Limite do modelo:** durações dos estados são exponenciais; "uma janela por período" vale **em média**, não em cada período. O prazo real é determinístico; o MMPP-2 não o reproduz.
- Se aceito, os parâmetros do MMPP-2 deixam de ser livres e passam a ser função de (N_op, κ, P, η, W_janela). Não calculei o índice de dispersão teórico.

### 6.4 "Pré-registro anterior" e "resultados já gerados" (resposta à pergunta 4; **RESOLVIDA**)

- Os fatores **protocolo × regime × n ∈ {12, 36, 60} × φ** eram a **v1 do RASCUNHO do humano, "a fixar no pré-registro"**; **não** são pré-registro formal. Não existe pré-registro (P8 continua não atendida).
- Os **"resultados já gerados"** são os **que estão sendo produzidos agora**. **Não há resultado arquivado neste repositório hoje.**
- **Reflexo no índice:** nota em C-e/C-i; a premissa "resultados antigos passam a piloto" (ADR-0000, Consequências) não tem objeto hoje; a pendência (q) está resolvida.

### 6.5 Conflito n = 4 em PoSA/PoS (decisão 6)

- O ADR-0000 pede n ∈ {4, 16, 64, 128} para todos; Parlia e Casper exigem n ≥ 10 (`AGENTS.md`, ADR-0006). Afeta **24 das 240 células** (2 protocolos × 3 L × 2 R × 2 W_nó).
- Registrado como **conflito de capacidade do simulador, a resolver por tarefa do `jabs-builder`** (não aberta por mim). Opções, **ao humano, sem escolha minha**: (1) corrigir o setup P2P compartilhado (muda a saída de Casper/Parlia/Nakamoto; exige ADR e não regressão); (2) declarar n ≥ 10 como limitação e usar **n = 16** como menor n para PoSA/PoS. Detalhe em ADR-0010, PI-19.
- **Acréscimo 2026-10-29 (ADR-0011).** O humano **determinou corrigir o setup P2P** (opção 1) e **rejeitou manter só a limitação** (opção 2). A opção de implementação (A, B ou D) está `a definir com o humano` em `docs/adr/0011-setup-p2p-n-pequeno.md` (Proposto; tarefa `P2P-1-setup-n-pequeno`, `loop2-jabs-codigo`). Pendência do humano: criar baseline do código de hoje (repositório sem HEAD) para a não regressão. Efeito nas duas leituras: no ADR-0000 (sem PoW), só n = 4 em PoSA/PoS trava (24 células); na leitura de 28/10, o Nakamoto em n = 25 cai numa zona não medida. A frase "Parlia/Casper exigem n ≥ 10" (`AGENTS.md`) só é atualizada depois do aceite e da implementação.
- **Acréscimo 2026-10-30 (ADR-0011 ACEITO; os dois itens acima descrevem o estado de 2026-10-29).** O humano decidiu a opção **A+D** e aceitou o ADR-0011; a tarefa `P2P-1-setup-n-pequeno` foi **APROVADA pelo critic na rodada 2** (180 testes, número do registro), baseline = commit `e703e3d` (a "pendência de baseline" acima está encerrada). Em termos do índice: (i) **a opção 1 foi a escolhida**; a opção 2 segue rejeitada; (ii) "Parlia e Casper exigem n ≥ 10" **deixou de valer para o setup P2P** (em C-b e na seção 4 o texto fica como histórico do estado anterior); `AGENTS.md` ainda diz isso e **não foi atualizado** (texto sugerido na seção "Consequências pendentes" do ADR-0011); (iii) as 24 células de n = 4 deixam de travar **pelo P2P**, mas o `n` da linha de comando ainda vira 3 nós (1 validador no Parlia): a **conversão de n do `MVPComparison` é tarefa separada, ainda não aberta**, e o significado de n (L10) segue aberto, portanto o n = 4 **não é comparável** entre protocolos até lá; (iv) em n pequeno a topologia é malha completa e deve ser declarada no pré-registro; (v) o **Nakamoto saiu do estudo** por decisão do humano, o que torna a "zona não medida" de n = 25 da leitura de 28/10 e o n = 16 em `BitcoinCoreP2P` assunto de higiene, e **deixa o ADR-0009 (decisão 1, seis níveis) por reconciliar** com o humano; (vi) a evidência (31/31 IDENTICO onde a baseline termina; 17 execuções que travavam agora terminam) vale com 4 JVMs simultâneas. **Não confirmado:** `OFFSET` n = 4 e n = 16 como mudança de saída (nunca executados), CA-03 com `-XX:hashCode`, CA-02 pelo `MVPComparison`, finalização com 2 stakeholders. Detalhe: `docs/adr/0011-setup-p2p-n-pequeno.md`.

### 6.6 Métricas ausentes e nomes V1 a V3 (decisão 7)

- **Θ, U, C_nó^max e L_tx p95 (sem fallback)** do ADR-0000 **não existem** no simulador: **ele precisará ser estendido (tarefa do `jabs-builder`, não aberta)**. V1 (Θ ≥ λ_pico), V2 (L_tx ≤ L_max), V3 (U ≤ U_max) dependem de **L_max, U_max** (PI-09) e de λ_pico (agora com fórmula, PI-09a; valor `a definir`).
- **Colisão de nome** V1 a V3 × V-A/B/C. **Sugestão a confirmar pelo humano:** renomear os critérios de viabilidade para **CV1, CV2, CV3** no pré-registro. Não renomeei nada na fonte. Detalhe em ADR-0010, PI-20.

**Decisão do humano de 2026-11-05 (D8, D4, D5; registro de rodadas, linha 115).** O texto acima fica como histórico. **[H]** = decisão do humano, sem fonte.

| Critério (pré-registro) | Definição | Nome antigo | Mensurável quando |
|---|---|---|---|
| **CV1a** | Θ ≥ λ_pico (λ_pico = η·N_op·κ/W_janela, ADR-0010 PI-09a) | V1 do ADR-0000 | Θ sob saturação: M1b (ADR-0012 RM-05, RM-07), depois da M2 |
| **CV1b** | Q(kP) ≤ ε_Q, Q = pendentes ao fim do k-ésimo período de compromisso | V1 do `.tex` | **só depois da M2** (a fila de pendentes não existe hoje; exige medir a fila a cada kP) |
| **CV2** | L_tx ≤ L_max | V2 | L_tx p95 sem o fallback de Tt (auditoria B.4) |
| **CV3** | U ≤ U_max, com **U_bloco** (ADR-0012 RM-04) | V3 | M1a-2 (U_bloco); M1a-1 já mede U_corrida |

- **Os dois V1 ficam** (CV1a e CV1b); a divergência `.tex` × ADR-0000 deixa de ser "qual vale" e passa a ser "como se combinam" (conjunção, ou reporte separado): **`a definir com o humano`**. Idem **ε_Q** e o **valor de λ_pico**.
- **V-A, V-B, V-C do repositório (portões de qualidade) ficam.** CV1 a CV3 só no pré-registro; nada foi renomeado em `.experimento/` nem no `.tex`.
- **U_max e L_max NÃO são fixados.** São **parâmetros de pré-registro com análise de sensibilidade** (exemplo do humano para U_max: {0,5; 0,7; 0,9}, **exemplo, não fixação**); o artefato reportado é a **fronteira de viabilidade em função de U_max** (n_max(λ̄; U_max)). L_max será **ancorado depois na latência de commit medida pela ponte** (hoje `NAO_MEDIDO`). O 0,7 do `.tex:116` continua só exemplo. Os dois seguem `a definir com o humano` (ADR-0010 PI-09; ADR-0012).
- **Pendência de linguagem do artigo [L]:** `main.tex:290-294` e `04-requisitos-escalas.tex:112-116` ainda dizem V1/V2/V3 e dizem que V1 do `.tex` é "menos restritivo" que `Θ ≥ λ_pico`. Com CV1a e CV1b mantidos juntos, essa frase precisa de revisão do autor; arquivo de correções, não editado.

### 6.7 Perguntas que continuam ABERTAS (decisão 5)

| # | Pergunta | Onde |
|---|---|---|
| 5 | Timeout do *view change* do pBFT estável (valor; abre ou não a opção 3 do ADR-0007) | PI-14 |
| 6 | Esquema de assinatura: o do Fabric (X.509 da organização) ou o de uma implementação HotStuff/Tendermint? Define os tamanhos em PI-16 e s_v em PI-08 | PI-16, PI-08 |
| 7 | Dependências estatísticas autorizadas (numpy, scipy, statsmodels?) para Box-Cox, HC3, ART, Games-Howell | PI-17, C-h |
| 8 | Autorização para corrigir 18/174/36 em `.experimento/` (§1 e §4 do PROTOCOLO); sem ela não edito | 3(a) |
| 9 | Citação da Lei 15.042/2024 ("registro central sob órgão gestor e entidades credenciadas") **a confirmar**; não verificada | C-j |

Seguem abertos também, por consequência das decisões acima: escolha entre as opções de PI-19 (n = 4); confirmação de CV1 a CV3 *(confirmados em 2026-11-05, com CV1a/CV1b: ver 6.6)*; aceitação (ou não) da proposta MMPP-2 de 6.3; reconciliação formal do ADR-0009 (6 níveis) com o ADR-0000 (5 níveis); correspondência PoSA/PoS com Parlia/Casper; todos os valores numéricos de PI-01 a PI-09; número do ADR-0000 no repositório (colide com o template).

### 6.8 Decisões do humano em 2026-10-29 (acréscimo)

- **B3:** o perfil sazonal e a razão pico/média ρ_p como fator de experimento saem do desenho (remover); o **E2 é o do ADR-0000** (robustez: φ ∈ {0; 0,10; 0,30} × regime). ρ_p = ηP/Δ segue como definição do MMPP-2; η, Δ, P seguem como parâmetros. ADR-0010: **PI-11 marcado NÃO USADO**.
- **B8:** o pBFT mantém a **opção 2 do ADR-0007**, sem view change.
- **B9:** o ataque do estudo é **EQUIVOCATION**.
- **Declaração de IA:** adiada.
- **Tarefas abertas:** `C1-casper-quorum-correcao` e `D3a-2a-2`.

### 6.9 Emenda do ADR-0009 (2026-10-30, por decisão do humano)

- O humano pediu que o ADR-0009 fosse atualizado se falasse de temas fora do estudo previsto (o do ADR-0000). `docs/adr/0009-escopo-de-protocolos-do-estudo.md` foi **emendado**: **Estado: Aceito, com emenda de 2026-10-30**; o texto de 28/10 está preservado na seção "Histórico (superado em 2026-10-30)".
- **Fecha** a pendência "reconciliar o ADR-0009 (6 níveis) com o ADR-0000 (5 níveis)" da seção 6.1 e 6.7, **quanto ao texto do ADR**. Continuam abertas: aceite formal do ADR-0000 pelos coautores, correspondência PoSA/PoS com Parlia/CasperFFG, autorização para corrigir `.experimento/` (os YAML ainda têm 6 níveis), e as contagens de E2, validação, ablação e piloto (`a definir com o humano`).
- **Itens afetados (campo Estado):** P2 (seção 2): atendida quanto ao escopo, texto de 6 níveis SUPERADO. C-a e C-e (seção 4): a leitura do ADR-0000 passa a ser a vigente para protocolos, n e contagem; o texto das linhas fica como histórico. Seção 5, (a): números de 28/10 SUPERADOS. (o): DAGsper e TangleIOTA continuam fora do escopo, e o Nakamoto passa a fora também.
- Estado do código em 2026-10-30 (leitura): HotStuff tem código desde o commit `325f4e3` mas não roda ponta a ponta (sem `case HOTSTUFF` no `MVPComparison`); P4a e P4b continuam não atendidas. Baseline de não regressão: `325f4e3` (ADR-0006).

### 6.11 Formação de bloco e demanda (decisões do humano de 2026-10-30; ADR-0013, Proposto)

Registro completo, opções e verificação em `docs/adr/0013-formacao-de-bloco-e-demanda.md`. Aqui só o que o índice precisa.

- **Decidido (estrutura, não valor):** (1) um **gerador único** de transações, determinístico por semente, para os cinco protocolos (Poisson, depois MMPP-2); (2) **fila de pendentes por nó** com regra de fechamento comum **lote máximo OU tempo máximo, o que ocorrer primeiro**; (3) **mesmo s_tx** para todos (valor = PI-08); (4) **bloco dimensionado pelas tx realmente na fila**, nunca sorteado.
- **Fecha a lacuna L14** da auditoria (qual grandeza o perfil de carga modula no PBFT): **a taxa de chegada de transações** (λ), na entrada do gerador único. O perfil sazonal saiu (B3, PI-11 NÃO USADO); o regime é **Poisson × MMPP-2 de mesma média**.
- **Estado do código (leitura):** pBFT sem gerador e com bloco sorteado da distribuição Bitcoin, proposta fixa de 5,0 s; HotStuff com lote cheio sem fila; Casper e Parlia com Poisson homogêneo e fechamento por gás; Tendermint inexistente; **MMPP-2 inexistente**.
- **Mudança intencional de saída** em pBFT, Casper e Parlia (e HotStuff, ainda sem dado arquivado): nenhum número anterior é comparável.
- **Não regressão:** baseline `325f4e3` (`/tmp/opencode/base-325f4e3`). Achado de 2026-10-31 (tarefa N1): Casper, Parlia e Nakamoto são sensíveis à sequência de `System.identityHashCode` da thread principal (`memPool` em `HashSet`; ADR-0006, débito 5). A prova exige base e atual com `-XX:+UnlockExperimentalVMOptions -XX:hashCode=2` **ou** corrigir a causa (`LinkedHashSet`, que muda a saída do Casper e exige ADR). Evidência: `src/test/resources/nao-regressao/n1-conversao-n/diagnostico-hash/`. Veredito da N1 segue **PENDENTE**.
- **Dependências:** gerador MMPP-2 (não existe); tarefa N1 (conversão de n) com veredito; **ADR-0012** (Θ, U, C_nó), em elaboração: Θ sob saturação exige a fila deste ADR.
- **Efeito no artigo até a implementação:** ε_λ do **tráfego** não é informativa para pBFT e HotStuff (≈ 0 por construção do simulador); a comparação das escalas apoia-se em latência e tempo de finalização. Redação em LaTeX sugerida no ADR-0013, Consequências, item 5 (arquivo de correções não editado).
- **`a definir com o humano`:** lote máximo L; tempo máximo T (e se a proposta de 5,0 s do pBFT vira T); s_tx; λ_B e demais PI do ADR-0010; limite de gás do Ethereum contra lote; política da fila, capacidade e tratamento de fila cheia (sem descarte silencioso); início do relógio de T; quais nós têm fila. `tamanho_bloco_kb` (1024) do `bateria1.yaml` deixa de ser tamanho de bloco e, se mantido, vira L = tamanho_bloco_kb·1024/s_tx (a conferir).

**Decisão do humano de 2026-11-05 (ADR-0013 e ADR-0014; registro de rodadas, linha 115).** O texto acima fica como histórico. **ADR-0013: aceito apenas quanto ao que o humano aceitou**; o que segue `a definir` está abaixo. **[H]** = decisão do humano.

- **Hash / `memPool`:** até a M2, registro ligado só sob `-XX:hashCode=2`; **dentro da M2** o `memPool` `HashSet` do `EthereumMinerNode` vira a fila de pendentes ordenada, o que **resolve o débito 5 do ADR-0006**; a mudança de saída de Casper e Parlia é declarada **uma vez**, com **nova baseline**: **`325f4e3` deixa de valer para Casper e Parlia**. (Nakamoto: o `memPool` do Bitcoin não está coberto; segue exigindo hash fixo.)
- **Gás em Casper e Parlia:** **opção (i)**: gás = atributo constante da tx; **L substitui o limite de fechamento** (`MAXIMUM_BLOCK_GAS` deixa de reger). **Declarar no artigo como escolha de desenho** (Casper e Parlia deixam de ser "Ethereum realista" em gás). Regra de fechamento em PoW/PoS aplica-se ao **conteúdo** do bloco no instante em que o protocolo o produz, **não como cadência** (confirmado). Valor do gás constante, papel de T e Q = 0 nesses protocolos: `a definir`.
- **L (ADR-0014):** **referência L = 10 tx** (= `MaxMessageCount` do `configtx.yaml`); **L fator de varredura {1, 10, 100, 500}, um fator por vez**; **T = 5 s mantido**. L/T = 2 tx/s com L = 10 [C]. **Nota para o artigo:** o `BatchTimeout` do Fabric do projeto é 2 s; o T do estudo é 5 s. **Ressalva:** L = 500 só fecha por lote para λ alto (L/T = 100 tx/s); a escada de saturação tem de atravessar o λ\* de cada L (ADR-0014, seção de 2026-11-05).
- **Ainda `a definir`:** valor único de L (fora da varredura); política e capacidade da fila; fila cheia; início do relógio de T; quais nós têm fila; valor do gás; papel de T e Q = 0 em Casper/Parlia; MMPP-2; fluxo de aleatoriedade próprio do gerador.

### 6.10 Registro de Θ, U e C_nó (decisão do humano de 2026-10-30; ADR-0012, Proposto)

Acrescentada fora de ordem numérica (a 6.11 já existia). Registro completo, opções e verificação em `docs/adr/0012-registro-theta-u-cnode.md`, agora preenchido; a 6.11 acima o chama de "em elaboração", estado de quando foi escrita. Identificadores estáveis **RM-01 a RM-10** (requisitos de medição) e **VT-01 a VT-11** (testes). Aqui só o que o índice precisa.

- **Decidido (opção (a)):** instrumentar o simulador **antes** da bateria principal. Rejeitadas: (b) calcular U a posteriori por `8·C_nó·ν_b/K_nó`; (c) restringir as fronteiras ao critério de latência.
- **Definições decididas:** C_nó = bytes **enviados** por nó (soma de `packet.getSize()` na fila de envio), por bloco, máximo entre os nós, **inclui o envio do bloco pelo líder** (≠ C_B, que é por nó que recebe). U = fração do tempo em que a fila de envio do nó fica ocupada, **medida no simulador** (a eq. do artigo vira só verificação). Θ = taxa de confirmação sustentada **sob saturação**, em execuções **separadas** de E1 (em E1 a taxa dá `min(λ, Θ)`).
- **Estado do código (leitura):** nenhuma das três existe; todo envio passa por `NetworkInterface.addToUpLinkQueue` (ponto único de instrumentação). **A fila de pendentes não existe** (ADR-0013, Proposto, sem código): Θ depende dela; C_nó e U não.
- **Sentinela:** `-1` = não medido, diferente de `0` (medido e nulo). Registro **desligado por padrão**; desligado, saída idêntica byte a byte; ligado, só acrescenta colunas ao final.
- **Janela:** `t0` explícito e obrigatório (sem transiente embutido); instantâneo dos contadores em `t0`, subtraído no fim. Duração mínima: `a definir`.
- **Não regressão:** baseline `325f4e3` (`/tmp/opencode/base-325f4e3`). ARQ/PBFT não é afetado pela sequência de hash (`resumo-base-k.txt`: ARQ:7:60 idêntico para k = 0 a 200 [E]). Casper, Parlia e Nakamoto exigem base e atual com `-XX:+UnlockExperimentalVMOptions -XX:hashCode=2` **ou** a correção da causa (ADR próprio); até lá, **PENDENTE**. A instrumentação não pode chamar `hashCode`/`identityHashCode` nem chavear coleções hash por `Node`/`Packet`/`Block`.
- **Testes exigidos:** U ≤ 1 (VT-01); bytes enviados = recebidos + descartados no destino + resíduo no fim (VT-02; a rede simulada não tem perda aleatória, só interface caída e pacotes em trânsito).
- **Colisão de nomes:** V1 a V3 (viabilidade) × V-A/B/C (portões do repositório). Sugestão **a confirmar**: **CV1, CV2, CV3**. **Achado novo:** o V1 do `.tex` (`Q(kP) ≤ ε_Q`) e o V1 do ADR-0000 (`Θ ≥ λ_pico`) **não são o mesmo critério**; qual vale no pré-registro está aberto.
- **Gargalo do líder (hipótese do artigo):** o registro torna o teste possível, não o prova. Com líder **rotativo** (pBFT hoje; HotStuff `ROTATING`), U de corrida dilui o pico do líder; U de bloco ou líder estável (ablação) é necessário. A tensão rotativo × estável do pBFT (ADR-0009) segue aberta.
- **`a definir com o humano`:** U_max (o 0,7 do `.tex` é exemplo); L_max; critério de saturação e escada de λ; duração mínima da janela e fim do transiente; U de corrida ou de bloco; corte de C_nó (primeira finalização × total do bloco); granularidade e formato de saída; se o pacote que o PBFT envia a si mesmo conta; tolerância da verificação da eq. do artigo; dono da extensão de `nao_regressao.py` para as colunas novas (o script só aceita uma coluna extra); banda por nó uniforme (PI-07) como pré-requisito de comparar U e Θ.
- **Ordem sugerida:** veredito da N1; tarefa A (C_nó e U, sem Θ); ADR-0013 implementado; tarefa B (Θ e saturação). Nenhuma tarefa foi aberta.

**Decisão do humano de 2026-11-05 (ADR-0012, seção homônima; registro de rodadas, linha 115).** Estado do ADR-0012: **continua Proposto** até U_max, ε_sat/teto da escada e W_min serem fixados. O texto acima fica como histórico. **[H]** = decisão do humano.

- **Critério de utilização:** registrar **U_corrida e U_bloco**; **U_bloco alimenta CV3** (6.6). **Pacote do nó para si pela fila** (PBFTP2P) **conta** em S_i e em U, com coluna própria **`SelfSentBytes`** (RM-11, novo). **Corte de C_nó** = instantâneo da primeira finalização da altura (comparável ao C_B); limitação declarada.
- **Saturação:** critério = **platô de ConfRate entre degraus**, confirmado pela inclinação de **Q(t)**. **ε_sat e teto da escada: `a definir com o humano` após corrida-piloto.** **Janela:** t0 = estabilização de Q(t) e da taxa de confirmação, por corrida-piloto; `|W| ≥ W_min` conferido na análise; **W_min `a definir` após a piloto.**
- **PBFT:** **líder rotativo é o padrão; líder estável (PI-14) é ablação**, simétrico ao HotStuff `ROTATING`/`STABLE`. **Isto contradiz o plano do ADR-0009 e a PI-14, que usam líder estável para o pBFT** (inconsistência devolvida ao humano na entrega; nada reescrito).
- **Ordem:** a **banda uniforme por nó (PI-07) entra antes da bateria ampla**; a **primeira bateria compara só protocolos na LAN100M** (PBFT, HotStuff, Snow; Casper/Parlia usam distribuição por regiões e ficam de fora dessa primeira bateria). Valores de W_nó seguem `a definir`.
- **Hash:** até a M2, registro ligado só vale sob `-XX:hashCode=2`; ver 6.11.
- **Tarefa a abrir (não aberta, não implementada):** `jabs-builder` estende o `nao_regressao.py` de `src/test/resources/nao-regressao/` para as **colunas 15 e 16 e para Windows**.
- **Andamento [L]:** M1a-1 (RM-01, 03, 06, 08, 09) **APROVADA**; M1a-2 (RM-02, RM-04) a fazer; M1b (RM-05, RM-07) depois da M2 (registro, linhas 109 a 116).
- **Continuam `a definir com o humano`:** U_max (por sensibilidade), L_max (ancorar na ponte), ε_sat, teto e degraus da escada, W_min, I_b e a estatística de U_bloco, ε_Q, formato de saída (RM-10), confirmação do hash `325f4e3`, tolerância da eq. `util`.
