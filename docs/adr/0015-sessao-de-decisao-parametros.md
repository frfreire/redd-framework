# ADR-0015: Sessão de decisão dos parâmetros "a definir com o humano" (tabela única)

- **Estado:** Proposto
- **Data:** 2026-11-09
- **Decisores:** (humanos). Este ADR **não decide nada**: prepara a decisão.
- **Trava:** a bateria de produção e o pré-registro só iniciam com os parâmetros da Seção A (instrumento) decididos e registrados em ADR próprio ou em acréscimo a este (ADR-0010: "fixar em dois momentos", instrumento antes do piloto).

## Contexto

O ADR-0010 e o índice (`especificacoes/protocolo-experimental-index.md`) deixam vários valores como `a definir com o humano`. Cada um está espalhado em PI-xx diferentes, e o código do simulador já recusa padrão para a maioria (falha alta no `build()`). Este ADR junta tudo numa **tabela única** para uma sessão de decisão. Para cada parâmetro, a tabela dá: onde o código o consome, em que estado está, fonte candidata, se é medido ou só escolhido, impacto, dependências, risco e recomendação de como decidir.

**Regras desta tabela (valem para todas as linhas):**

1. **Nenhum valor é preenchido.** Onde aparece número, ele é (a) um fato do código, (b) uma medição declarada, ou (c) um valor de uma fonte lida, sempre rotulado. Não é proposta de valor.
2. **Rótulos de fonte.** `[FONTE]` = li o texto (URL ou arquivo, com data). `[HIPÓTESE]` = conhecimento geral que **não** li nesta tarefa (ex.: tamanho de uma assinatura por padrão de um esquema). `nenhuma` = não há fonte candidata.
3. **MEDIDO vs ESCOLHIDO.** `MEDIDO` = existe medição na aplicação Fabric. `ESCOLHIDO` = convenção declarada, sem medição. Medido não é "verdade do estudo": vale só para o que foi medido (ver Ressalva M).
4. **Estado no código** (verificado em 2026-11-09): `obrigatório` (build/flag falha sem valor), `padrão silencioso` (há valor padrão que entra sem decisão), `hardcoded` (constante no código, sem flag), `ausente` (o modelo não representa o parâmetro).
5. **Lacuna normativa não se inventa.** Nenhuma norma (VCS/Verra, ART-TREES, CORSIA, SBCE) fixa tamanho de voto, timeout de consenso ou lote. Onde a coluna diz `nenhuma`, o valor é escolha de engenharia do estudo e precisa de justificativa registrada.

**Dados MEDIDOS disponíveis (fabric-rede, 2026-11-09).** Rede de desenvolvimento, identidades de teste do cryptogen, 2 endossos por transação. Tamanho do envelope de transação em bytes:

| Operação | Envelope (B) |
|---|---|
| aposentarCredito | 7031 |
| emitirCredito | 5612-5614 |
| registrarProjeto | 4698-4699 |
| submeterRelatorioMRV | 5727-5728 |
| verificarRelatorioMRV | 5431-5433 |
| transferirCredito | 5528-5529 |

Bloco com 1 tx = envelope + ~1027 B.

**Ressalva M (vale para todo uso destes números).** O certificado do creator (PEM de ~843 B) e os dos endossantes estão **dentro** do envelope. Logo a "assinatura do Fabric" **não é um número isolado de 64 B** e o envelope **não é** `s_tx` de uma transação de consenso BFT genérica. Os números valem **só para este conjunto de identidades de teste e estes argumentos de chamada**. Com certificados de produção (cadeia, atributos, outra CA) o envelope muda. Não há medição com 3 endossos nem com argumentos maiores.

**Nota sobre HotStuff no `MVPComparison`.** Verificado por busca (2026-11-09): `MVPComparison.java` tem flags `--tm-*` para o Tendermint e **nenhuma** flag para o HotStuff. O HotStuff existe como `HotStuffConsensusConfig` (builder) e `HotStuffLocalLANNetwork`, sem ponto de entrada de linha de comando para os parâmetros abaixo. "Consumido em" das linhas HotStuff é, portanto, o builder, não uma flag.

**Convenções da tabela.** IDs `PI-xx`/`L-xx` são os já existentes (ADR-0010, auditoria). `A-xx` é identificador **novo, local a este ADR**, para linhas sem ID prévio. Não há `RM-xx` citado em nenhum documento que li para estes parâmetros; não inventei.

## Seção A: PARÂMETROS DE INSTRUMENTO (consumidos pelo código do simulador)

### A.1 Tamanhos de voto e de assinatura

| ID | Parâmetro | Consumido em | Estado no código | Fontes candidatas | Medido (ponte) / Escolhido | Impacto | Dependências | Risco de escolher mal | Recomendação |
|---|---|---|---|---|---|---|---|---|---|
| A-01 (PI-16, PI-08) | Tendermint `signatureSizeBytes` (voto: prevote/precommit) | `TendermintConsensusConfig`; flag `--tm-signature-size` | obrigatório sem padrão; exige > 0 | [FONTE] código: o valor 64 B (Ed25519/ECDSA) aparece só em teste e comentário do builder, "suposição" (ADR-0010 PI-16). [HIPÓTESE] tamanho por padrão de cada esquema. Medição Fabric: a assinatura do Fabric está dentro de um envelope com certificado PEM (~843 B), não é 64 B isolado (Ressalva M) | ESCOLHIDO. Ponte: PARCIAL (extrai do envelope só o tamanho da assinatura X.509 do Fabric, útil se o estudo modela o Fabric). Teste: unidade do builder + bytes de voto no `MVPComparisonTendermintTest` | ALTO: voto trafega todos-para-todos por rodada, então a assinatura domina o tráfego de consenso | `s_v = 32 (hash) + overhead (A-03) + assinatura`. Depende do esquema (pergunta Q1) | 64 B vs assinatura com certificado (centenas de B) muda a ordem de grandeza do tráfego e pode inverter a comparação entre protocolos | **Fixar por convenção declarada** (Q1) e **varrer como fator de sensibilidade** (ao menos 2 níveis; [HIPÓTESE] do autor: o número mínimo de níveis não vem de fonte, e o PROTOCOLO de 28/10 só fixa `esquema_assinatura: individual` com "agregada" como sensibilidade). Medir só se Q1 = "modela o Fabric" |
| A-02 (PI-16, nota 09/11) | Tendermint `proposerSignatureSizeBytes` (proposta) | `TendermintConsensusConfig`; flag `--tm-proposer-signature-size` | obrigatório sem padrão, **separado** de A-01; exige > 0 | idem A-01. Fabric: [HIPÓTESE] o bloco é assinado pelo orderer; tamanho não medido isoladamente (só o total do bloco com 1 tx: envelope + ~1027 B) | ESCOLHIDO. Ponte: PARCIAL (idem A-01). Teste: `MVPComparisonTendermintTest` (flag aceita e exigida) | MÉDIO: uma proposta por rodada, mas leva o bloco inteiro; a assinatura é pequena frente ao lote | Mesmo esquema de A-01, salvo decisão em contrário | Reusar o valor do voto por inércia reintroduz o padrão implícito que a nota de 09/11 eliminou | **Decidir explicitamente se é igual ao voto** (Q1); registrar a igualdade como decisão, não como default |
| A-04 (PI-16) | HotStuff `individualSignatureSizeBytes` | `HotStuffConsensusConfig.Builder`; sem flag no `MVPComparison` | obrigatório sem padrão | [HIPÓTESE] 64 B (comentário do builder). Fabric: ver A-01 | ESCOLHIDO. Ponte: PARCIAL. Teste: `HotStuffVote.computeSize` (unidade) | ALTO: parcela de cada voto e do QC no modo INDIVIDUAL | Esquema (Q1); `signatureMode` (A-08); n (QC leva quorumSize(n) = 2f+1 assinaturas) | Valor diferente do usado no Tendermint tira a comparação de HotStuff x Tendermint do mesmo esquema | **Usar o mesmo valor de A-01** salvo decisão contrária (Q1) |
| A-05 (PI-16) | HotStuff `aggregatedSignatureSizeBytes` | idem A-04 | obrigatório sem padrão | [HIPÓTESE] 96 B, BLS12-381 G2 (comentário do builder). Fabric não agrega: sem medição | ESCOLHIDO. Ponte: NÃO. Teste: `qcSignatureSize` (unidade) | MÉDIO: só importa no modo AGGREGATED (sensibilidade do PROTOCOLO de 28/10) | `signatureMode = AGGREGATED` (A-08) | Subestimar elimina a vantagem do agregado, que é o ponto da sensibilidade | **Citar fonte** (especificação do esquema BLS adotado) se AGGREGATED entrar; senão fica fora |
| A-06 (PI-16) | HotStuff `aggregatedShareSizeBytes` (parcela do voto no modo agregado) | idem A-04 | obrigatório sem padrão | [HIPÓTESE] igual ao tamanho de uma assinatura do esquema. Sem medição | ESCOLHIDO. Ponte: NÃO. Teste: `voteSignatureSize` (unidade) | MÉDIO: tráfego de voto no modo agregado | A-05; A-08 | Mesma de A-05 | Decidir junto com A-05 |
| A-07 (PI-15) | HotStuff `voterEncoding` do QC | `HotStuffConsensusConfig` (enum `VoterEncoding`, só `BITMAP`) | obrigatório; **um único valor implementado** (bitmap `ceil(n/8)` B, decisão humana na D3a-1) | [FONTE] ADR-0010 PI-15 (bitmap decidido, valor final pendente). Paper do HotStuff: [HIPÓTESE], não li | ESCOLHIDO. Ponte: NÃO. Teste: `voterIdentifiersSize` (unidade) | BAIXO: n/8 B por QC (por ex. 16 B para n = 128) frente a assinaturas | Conjunto fixo de n validadores (permissionado) | Lista de IDs cresceria com n e tiraria parte da vantagem do agregado | **Fixar por convenção declarada** (confirmar bitmap) |
| A-08 (PI-08/PI-16) | HotStuff `signatureMode` (INDIVIDUAL / AGGREGATED) | `HotStuffConsensusConfig`; `DEFAULT_SIGNATURE_MODE = INDIVIDUAL` | **padrão silencioso** (o builder já nasce com INDIVIDUAL, então a checagem de "obrigatório" nunca falha) | [FONTE] PROTOCOLO de 28/10: `esquema_assinatura: individual` fixo, "agregada" como sensibilidade (citado no ADR-0010 PI-16) | ESCOLHIDO (convenção do PROTOCOLO). Ponte: NÃO | ALTO: define qual fórmula de tráfego do QC vale | Q1; A-04 a A-06 | O padrão decide em silêncio; resultado "individual" pode ser lido como achado | **Fixar por convenção declarada** e tornar o campo obrigatório (tarefa do loop 2, não aberta aqui) |
| A-11 (PI-08) | pBFT: assinatura no voto | `PBFTBlockVote`, `PBFTPrepareVote`, `PBFTCommitVote` | **ausente**: voto = hash + 10 B, sem assinatura (42 B no teste de tráfego, auditoria B.2) | [FONTE] ADR-0010 PI-08 e auditoria B.2 | Só ESCOLHIDO (ausência é falta de modelo, não decisão). Ponte: NÃO | ALTO: pBFT fica sistematicamente mais barato que Tendermint/HotStuff em bytes de voto, por construção | A-01; unificar muda a saída do pBFT (nota de não regressão, critério 7 da bar do loop 2) | Comparar protocolos com modelos de voto diferentes atribui ao consenso uma diferença que é de instrumento | **Decidir** se o pBFT passa a carregar assinatura (Q2); se não, declarar a assimetria no pré-registro |

### A.2 Overhead de mensagem, s_tx e hash

| ID | Parâmetro | Consumido em | Estado no código | Fontes candidatas | Medido (ponte) / Escolhido | Impacto | Dependências | Risco de escolher mal | Recomendação |
|---|---|---|---|---|---|---|---|---|---|
| A-03 (PI-08) | Tendermint `messageOverheadBytes` | `TendermintConsensusConfig`; flag `--tm-message-overhead` | obrigatório sem padrão; exige >= 0 | nenhuma (cabeçalho do protocolo); [HIPÓTESE] o 10 vem do pBFT | ESCOLHIDO. Ponte: NÃO. Teste: `MVPComparisonTendermintTest` | BAIXO: soma fixa por voto/proposta | Mesmo valor de A-10 e A-12 para comparar protocolos | Valores diferentes entre protocolos viram diferença de instrumento | **Fixar por convenção declarada**, igual nos três protocolos (Q3) |
| A-10 (ADR-0010, nota 09/11) | HotStuff `DEFAULT_MESSAGE_OVERHEAD_BYTES = 10` | `HotStuffConsensusConfig.java:55,234` | **padrão silencioso** (builder inicia com 10 sem decisão) | [FONTE] comentário do código: "mesmo overhead do voto PBFT"; sem norma | ESCOLHIDO. Ponte: NÃO | BAIXO: igual a A-03 | A-03, A-12 | Padrão não registrado entra no resultado sem rastro | **Fixar por convenção declarada** e tornar obrigatório (Q3) |
| A-12 (PI-08) | pBFT `PBFT_VOTE_SIZE_OVERHEAD = 10` | `PBFTBlockVote.java:11` (usado em `PBFTPrePrepareVote`/`PrepareVote`/`CommitVote`) | **hardcoded**, sem flag | [FONTE] código; sem norma | ESCOLHIDO. Ponte: NÃO | BAIXO | A-03, A-10 | Mudar só aqui quebra a não regressão do pBFT | **Fixar por convenção declarada**: manter 10 e declarar, ou parametrizar (Q3) |
| A-13 (PI-08, L8) | `s_tx` Tendermint e HotStuff (tamanho fixo da transação) | Tendermint: flag `--tm-tx-size` e `batchSizeFor`; HotStuff: argumento `txSizeBytes` de `BlockFactory` (`BlockFactory.java:142`, `TransactionFactory.sampleHotStuffTransaction`), sem flag | obrigatório sem padrão (tx > 0) | [FONTE] YAML `tamanho_tx_bytes: 512` (valor de spec, não medição, ADR-0010 PI-08). **MEDIDO** (fabric-rede, 09/11): envelopes 4698 a 7031 B, ver Contexto. Ressalva M | **MEDIDO na aplicação** (mas só para identidades de teste, 2 endossos). Ponte: SIM. Teste: reexecutar o script de medição e comparar com o valor adotado | ALTO: tx de 512 B vs ~5-7 kB muda o lote por bloco (`floor(blockKB·1024/s_tx)`) e o tráfego em ~10x | Q4 (qual tx representa o estudo); A-23 (lote); certificados | Adotar 512 B quando a aplicação gera 5-7 kB subestima tráfego e favorece todos os protocolos; adotar o envelope pode superestimar (certificado é fixo) | **Medir** (já feito) e **decidir** entre 512 convencional, envelope por operação ou média ponderada (Q4); varrer como fator se houver dúvida |
| A-14 (PI-08, L8) | `s_tx` do pBFT | `TransactionFactory.sampleTransaction` (`TransactionFactory.java:59-68`, distribuição do Bitcoin) | **hardcoded**: distribuição herdada do Bitcoin; YAML 512 não é aplicado. Há também distribuição Ethereum para outros protocolos | [FONTE] código; mediana da distribuição não computada aqui | Hoje nem ESCOLHIDO nem MEDIDO. Ponte: SIM (o valor de A-13 pode substituir a distribuição) | ALTO: pBFT amostra tx de tamanho diferente dos demais protocolos, quebrando o pareamento | A-13; não regressão do pBFT | Resultado do pBFT não comparável com o resto | **Decidir** (Q4) e abrir tarefa do loop 2 para uniformizar; até lá declarar a assimetria |
| A-15 | `TENDERMINT_BLOCK_HASH_SIZE = 32` | `TendermintBlock.java:14`, usado em `TransactionFactory.java:93` e no tamanho do voto | **hardcoded** | [HIPÓTESE] 32 B = SHA-256, igual ao hash usado no pBFT (comentário "Standard SHA256 hash size") | ESCOLHIDO. Ponte: NÃO. Teste: unidade do tamanho do voto | BAIXO: 32 B fixos por mensagem | Esquema de hash do estudo | Baixo; só importa se o hash mudar | **Fixar por convenção declarada** (SHA-256) |

### A.3 Tendermint: temporizadores e lote

Fonte comum das linhas A-16 a A-22: **[FONTE]** `config/config.go` do CometBFT (branch `main`, lido em 2026-11-09, versão não fixada), `DefaultConsensusConfig()`: `TimeoutPropose` 3000 ms, `TimeoutProposeDelta` 500 ms, `TimeoutPrevote` 1000 ms, `TimeoutPrevoteDelta` 500 ms, `TimeoutPrecommit` 1000 ms, `TimeoutPrecommitDelta` 500 ms, `TimeoutCommit` 1000 ms; `TestConsensusConfig()`: 40/1, 10/1, 10/1 ms e commit 10 ms. São os **padrões do software**, não valores derivados de LAN nem de norma. O artigo (Buchman et al., arXiv:1807.04938) é citado no código, e eu **não** o li. Duração no simulador: `base + round·delta` (`TendermintTimeouts`); todo delta deve ser finito e > 0 (liveness).

| ID | Parâmetro | Consumido em | Estado no código | Fontes candidatas | Medido (ponte) / Escolhido | Impacto | Dependências | Risco de escolher mal | Recomendação |
|---|---|---|---|---|---|---|---|---|---|
| A-16 (PI-18) | Tendermint `timeout_propose` base | flag `--tm-propose-timeout`; `TendermintTimeouts.proposeBaseS` | obrigatório, > 0 | Fonte comum acima (3000 ms padrão; 40 ms de teste) | ESCOLHIDO. Ponte: NÃO (mede-se no próprio simulador). Teste: piloto sem falha com contagem de timeouts disparados = 0 | ALTO: base curta demais dispara troca de rodada sem falha real; longa demais domina a latência sob falha | Latência da rede simulada (`LAN100MNetworkStats`) mais tempo de montar/propagar o bloco; A-23 | Ou rodadas espúrias (Tb inflado) ou liveness lenta sob proposer bizantino | **Medir no piloto** a latência de ida e volta sem falha e escolher múltiplo declarado; **varrer como fator** se o múltiplo não tiver fonte |
| A-17 | Tendermint `timeout_propose` delta | flag `--tm-propose-delta` | obrigatório, > 0 | Fonte comum | ESCOLHIDO. Ponte: NÃO | MÉDIO: só age a partir da rodada 1 | A-16 | delta = 0 é recusado pelo código; delta muito pequeno atrasa a convergência sob falha | **Fixar por convenção declarada** (Q5) |
| A-18 | Tendermint `timeout_prevote` base | flag `--tm-prevote-timeout` | obrigatório, > 0 | Fonte comum (1000 ms) | ESCOLHIDO. Ponte: NÃO. Teste: piloto (idem A-16) | ALTO (mesmo mecanismo de A-16, na fase de prevote) | A-16; latência; n | idem A-16 | idem A-16 |
| A-19 | Tendermint `timeout_prevote` delta | flag `--tm-prevote-delta` | obrigatório, > 0 | Fonte comum | ESCOLHIDO. Ponte: NÃO | MÉDIO | A-18 | idem A-17 | idem A-17 |
| A-20 | Tendermint `timeout_precommit` base | flag `--tm-precommit-timeout` | obrigatório, > 0 | Fonte comum (1000 ms) | ESCOLHIDO. Ponte: NÃO. Teste: piloto | ALTO | A-16; latência; n | idem A-16 | idem A-16 |
| A-21 | Tendermint `timeout_precommit` delta | flag `--tm-precommit-delta` | obrigatório, > 0 | Fonte comum | ESCOLHIDO. Ponte: NÃO | MÉDIO | A-20 | idem A-17 | idem A-17 |
| A-22 | Tendermint `timeout_commit` (espera após decidir) | nenhum (`TendermintCore.java:65`: "sem timeoutCommit") | **ausente**: o modelo começa a próxima altura ao decidir | [FONTE] CometBFT: `TimeoutCommit` 1000 ms por padrão (`config.go`); existe também `SkipTimeoutCommit` | Só ESCOLHIDO (decisão de modelagem). Ponte: NÃO | ALTO: no CometBFT o intervalo entre blocos tem esse piso; sem ele o Tendermint simulado é mais rápido que o real em vazão | Q6 | Comparar o Tendermint simulado com o real, ou com protocolos com intervalo de bloco, sem este piso, favorece o Tendermint | **Decidir** (Q6): manter ausente e declarar, ou abrir tarefa do loop 2 com parâmetro obrigatório |
| A-23 (PI-08) | Tendermint lote (`batchSize`, transações por bloco) | flag `--tm-batch-size`; `batchSizeFor(blockSizeKb, txSizeBytes)` | obrigatório, > 0 | [FONTE] `rede-fabric/configtx/configtx.yaml`:170-174 (lido): `BatchTimeout 2s`, `MaxMessageCount 10`, `PreferredMaxBytes 512 KB`, `AbsoluteMaxBytes 99 MB`. É **configuração** da rede de desenvolvimento, não medição de desempenho | ESCOLHIDO (a config Fabric é referência, não medida). Ponte: PARCIAL (ocupação real do bloco mede-se; o valor de projeto não). Teste: tamanho de bloco gerado no `MVPComparisonTendermintTest` | ALTO: lote x s_tx dá o tamanho da proposta, que domina o tráfego do líder | A-13; demanda (Parte B) | Lote grande sem demanda para enchê-lo mede um bloco que não existe | **Citar fonte** (config Fabric, se Q7 = "espelhar o Fabric") ou **varrer como fator** |

### A.4 HotStuff: visão, lote e líder

| ID | Parâmetro | Consumido em | Estado no código | Fontes candidatas | Medido (ponte) / Escolhido | Impacto | Dependências | Risco de escolher mal | Recomendação |
|---|---|---|---|---|---|---|---|---|---|
| A-24 (PI-18) | HotStuff `viewTimeoutSeconds` | `HotStuffConsensusConfig.Builder`; sem flag no `MVPComparison` | obrigatório, finito e > 0 | [FONTE] comentário do código: "o artigo não fixa valor". Paper do HotStuff: [HIPÓTESE], não li. Referência de ordem de grandeza: padrões CometBFT (A-16) são de outro protocolo | ESCOLHIDO. Ponte: NÃO. Teste: piloto sem falha, trocas de visão = 0 | ALTO: mesmo mecanismo de A-16 | Latência de rede simulada; `leaderPolicy` (A-09) | Troca de visão espúria ou liveness lenta | **Medir no piloto** + **varrer como fator**, com a mesma regra de múltiplo usada em A-16 |
| A-25 | HotStuff lote (`batchSize`) | `HotStuffConsensusConfig.Builder`; `batchSizeFor` | obrigatório, > 0 | idem A-23 | ESCOLHIDO. Ponte: PARCIAL (idem A-23) | ALTO | A-13; A-23 | idem A-23 | **Decidir junto com A-23** para o mesmo lote nos dois protocolos |
| A-09 (PI-14) | HotStuff `leaderPolicy` (ROTATING / STABLE) | `HotStuffConsensusConfig`; `DEFAULT_LEADER_POLICY = ROTATING` | **padrão silencioso** (builder inicia com ROTATING) | [FONTE] ADR-0010 PI-14: estudo usa líder estável no pBFT; HotStuff `STABLE` é a ablação | ESCOLHIDO. Ponte: NÃO | ALTO: rotação a cada visão ou líder estável muda a latência e o tráfego do líder | PI-14 (política do pBFT, ADR complementar); A-24 | Comparar pBFT estável com HotStuff rotativo mistura política e protocolo | **Fixar por convenção declarada** (Q8), consistente com PI-14, e tornar obrigatório |

### A.5 Poda e justificação (POL)

| ID | Parâmetro | Consumido em | Estado no código | Fontes candidatas | Medido (ponte) / Escolhido | Impacto | Dependências | Risco de escolher mal | Recomendação |
|---|---|---|---|---|---|---|---|---|---|
| A-26 | Janela de poda `getHeight() - 1000` | `Tendermint.java:314`, `PBFT.java:486`, `HotStuff.java:460` (três locais, mesma constante) | **hardcoded**, sem flag | nenhuma | ESCOLHIDO (implícito). Ponte: NÃO. Teste: nenhum específico | BAIXO: libera memória; não entra no tráfego (informado) | Só importa se algum run passar de 1000 alturas e depender de bloco podado | Se uma run longa precisar de bloco podado (por ex. sincronização), falha ou resultado divergente | **Fixar por convenção declarada**: manter 1000, registrar em `config` do raw; revisar se qualquer cenário superar 1000 alturas |
| A-27 (PI-16, nota 09/11) | Proposta Tendermint carrega a justificação (POL, 2f+1 prevotes) como bytes | `TendermintProposal` (leva só o inteiro `validRound`) | **ausente**: sem bytes da POL | [FONTE] nota de 09/11 no ADR-0010: pode subdimensionar propostas com `validRound >= 0`. Artigo: [HIPÓTESE], não li | Só ESCOLHIDO. Ponte: NÃO. Teste: contar propostas com `validRound >= 0` num piloto com falha | MÉDIO: só ocorre após rodada > 0 (poucas propostas sem falha); sob ataque pode pesar em O(n) por proposta | A-01 (cada prevote da POL tem assinatura); frequência de rodadas > 0 | Subdimensiona o tráfego do Tendermint exatamente nos cenários de falha | **Decidir** (Q9): modelar a POL como bytes (cresce com n), ou declarar a limitação |


### A.6 Perguntas fechadas (uma por decisão)

Formato: A / B / C / outra. **Recomendação** em negrito. "Outra" é sempre admitida.

**Q1. Esquema de assinatura do estudo (define A-01, A-02, A-04, A-05, A-06).** O estudo modela:
- A) o esquema de uma implementação genérica de BFT (assinatura curta isolada, valor a citar de fonte do esquema escolhido);
- B) o Fabric como medido (assinatura X.509 com certificado no envelope, Ressalva M);
- C) A como base e B como nível de sensibilidade (dois níveis do fator);
- outra.
**Recomendação: C.** Razão: o PROTOCOLO de 28/10 já fixa `individual` e o Fabric real carrega certificado; só C mostra se o ranking de protocolos sobrevive à troca. O valor numérico de cada nível continua `a definir com o humano`; nenhum esquema foi adotado por mim.

**Q2. pBFT recebe assinatura no voto (A-11)?**
- A) Sim, passa a usar o mesmo A-01 (muda a saída do pBFT; exige nota de não regressão, critério 7 da bar do loop 2);
- B) Não; declarar a assimetria no pré-registro;
- C) Parametrizar como obrigatório, sem padrão; o legado (sem assinatura) só roda se o valor `0` for escrito explicitamente em teste de não regressão;
- outra.
**Recomendação: C** (comparação justa sem perder o baseline).

**Q3. Overhead de mensagem (A-03, A-10, A-12).** (Correção 09/11 após critic: A-15, `TENDERMINT_BLOCK_HASH_SIZE = 32`, é o tamanho do hash do bloco, não overhead, e fica fora desta pergunta; é uma constante de formato.)
- A) Um único valor declarado, igual nos três protocolos, obrigatório em todos;
- B) Manter 10 B hardcoded no pBFT e padrão no HotStuff, declarando-os;
- C) Por protocolo, com valores distintos;
- outra.
**Recomendação: A** (remove os dois padrões silenciosos); o valor segue `a definir com o humano`, sem fonte normativa.

**Q4. `s_tx` (A-13, A-14).**
- A) 512 B fixo por convenção (valor do YAML), em todos os protocolos;
- B) Envelope medido por operação (4698 a 7031 B), com mistura de operações dada pela Parte B;
- C) 512 B no caso base e o envelope medido como nível de sensibilidade;
- outra.
**Recomendação: C.** Razão: o 512 não tem medição; o envelope tem, mas contém certificado de teste e não vale para outras identidades (Ressalva M). Nos dois casos o pBFT precisa deixar de usar a distribuição do Bitcoin (tarefa do loop 2, a abrir).

**Q5. Deltas dos temporizadores (A-17, A-19, A-21).**
- A) Mesmo delta nos três tipos, igual a uma fração declarada da base;
- B) Adotar proporções dos padrões do CometBFT (500 ms de delta para bases de 3000/1000/1000 ms), só como convenção e não como valor;
- C) Varrer como fator;
- outra.
**Recomendação: A** (delta > 0 só para liveness; não é fator de interesse).

**Q6. `timeout_commit` (A-22).**
- A) Manter ausente e declarar que a vazão do Tendermint simulado é um limite superior;
- B) Abrir tarefa do loop 2 com parâmetro obrigatório, valor `a definir`;
- C) Ausente e comparar só latência de finalização (Tb), não vazão;
- outra.
**Recomendação: A**, e B só se a vazão virar métrica de decisão.

**Q7. Lote (A-23, A-25) em relação à configuração do Fabric.**
- A) Espelhar o `configtx.yaml` do Fabric (10 mensagens ou 512 KB, o que vier primeiro), nos dois protocolos;
- B) Escolher o lote por convenção independente do Fabric;
- C) Derivar da demanda da Parte B (lote = o que a demanda enche em um intervalo de bloco);
- outra.
**Recomendação: A**, com declaração de que é referência de configuração, não medição; C só depois da Parte B.

**Q8. Política de líder do HotStuff (A-09).**
- A) ROTATING (HotStuff do artigo) como caso base e STABLE como ablação;
- B) STABLE como caso base (coerente com o pBFT estável de PI-14) e ROTATING como ablação;
- C) Os dois como fator;
- outra.
**Recomendação: B**, para pareamento com o pBFT; torna o campo obrigatório no builder.

**Q9. POL como bytes na proposta Tendermint (A-27).**
- A) Modelar (tamanho cresce com o nº de prevotes da POL e com A-01);
- B) Não modelar e declarar a limitação, avaliando o efeito no piloto com falha (contagem de propostas com `validRound >= 0`);
- outra.
**Recomendação: B primeiro** (medir a frequência), A se a frequência for relevante.

**Q10. Modo de assinatura e codificação dos votantes do HotStuff (A-08, A-07, A-05, A-06).**
- A) INDIVIDUAL fixo e bitmap, AGGREGATED fora do estudo;
- B) INDIVIDUAL no caso base e AGGREGATED como sensibilidade (PROTOCOLO de 28/10), bitmap nos dois;
- C) Lista de identificadores no lugar do bitmap;
- outra.
**Recomendação: B**, com `signatureMode` obrigatório. Se B, A-05 e A-06 precisam de fonte do esquema agregado.

**Q11. Janela de poda (A-26).**
- A) Manter 1000 e registrar na configuração gravada do raw;
- B) Parametrizar;
- outra.
**Recomendação: A**, com verificação de que nenhuma run excede 1000 alturas.

**Q12. Método para as bases dos temporizadores e para `viewTimeoutSeconds` (A-16, A-18, A-20, A-24).**
- A) Medir a latência de ida e volta no piloto sem falha e adotar um múltiplo declarado, igual nos dois protocolos;
- B) Adotar os padrões do CometBFT (3000/1000/1000 ms) por convenção;
- C) Varrer como fator;
- outra.
**Recomendação: A**, com critério de aceite no piloto: zero timeouts disparados sem falha. B só se o objetivo for reproduzir o CometBFT; C se o múltiplo não tiver justificativa.

### A.7 Resumo da seção A

| Contagem | Valor | Linhas |
|---|---|---|
| Parâmetros listados | 27 | A-01 a A-27 |
| Com **padrão silencioso** | 3 | A-08 (`signatureMode`), A-09 (`leaderPolicy`), A-10 (overhead HotStuff = 10) |
| **Hardcoded** | 4 | A-12 (pBFT overhead 10), A-14 (s_tx do pBFT), A-15 (hash 32), A-26 (poda 1000) |
| Silencioso ou hardcoded (soma) | **7** | |
| **Ausente** no modelo (não é padrão nem constante) | 3 | A-11 (assinatura do pBFT), A-22 (`timeout_commit`), A-27 (POL) |
| Obrigatório sem padrão (falha alta) | 17 | A-01 a A-07, A-13, A-16 a A-21, A-23 a A-25 (17 + 3 + 4 + 3 = 27) |
| **Mensuráveis pela ponte** (SIM) | 2 | A-13, A-14 |
| Mensuráveis **em parte** (PARCIAL) | 5 | A-01, A-02, A-04 (só a assinatura do Fabric dentro do envelope), A-23, A-25 (ocupação real do bloco) |
| Só escolhidos, sem medição possível na aplicação | 20 | demais |

Observações: (i) a ponte hoje só tem o envelope por operação e o tamanho do bloco, medidos em rede de desenvolvimento; não há medição de latência de LAN, de tamanho de assinatura isolada nem de ocupação de bloco sob carga; (ii) a Parte B (demanda, MMPP-2, W_nó, limiares, saturação, ataque, PAGE_SIZE, ADR-0005) é anexada depois, no marcador abaixo; (iii) nenhum valor foi decidido.

## Seção B: PARÂMETROS DE DEMANDA, MEDIÇÃO E DESENHO

**Escopo e método da Seção B (2026-11-09).** Leitura integral de ADR-0010, 0012, 0013, 0014, 0005, ADR-0000 de `.experimento/`, `bateria1/2.yaml`, `piloto.yaml`, auditoria (seções C a E), partes do `PROTOCOLO-EXPERIMENTAL.md` (§2, §3, Etapas A e B) e do índice (seção 6). Leitura pontual de código (`grep` e trechos de `NetworkSizing`, `ByzantineConfig`, `ByzantineSweep`, `MVPComparison`, `RedContract`, `LimitesContrato`). Nada foi executado. **Não li:** o conteúdo de `LinkLoadRecorder.java` além do comentário de colunas, as normas (VCS/ART/CORSIA/SBCE), o `core.yaml` do Fabric, a documentação do gRPC. Fatos do ADR-0014 sobre gRPC e `totalQueryLimit` vêm do texto do ADR (`[FONTE-ADR]`), não da fonte primária.

**IDs.** `B-xx` é local a este ADR; o original vem entre parênteses (PI-xx, RM-xx, L-xx, Q do ADR-0012). Rótulos de fonte e regras 1 a 5 da Seção A valem aqui. Acréscimos:

- **Coluna "Medido / Escolhido":** `PONTE-SIM` (a aplicação Fabric mede), `PONTE-PARCIAL` (mede parte), `PILOTO` (mede-se no próprio simulador, em piloto; é trabalho de `jabs-experimento`, não da ponte), `ESCOLHIDO` (convenção, sem medição possível). A ponte mede a **aplicação**; ela não mede demanda real de operadores (a rede de desenvolvimento não tem usuários).
- **Estado "ausente":** inclui parâmetros que só existem no pré-registro ou no ADR e que nenhum código lê.
- **Descoberta (cálculo [C] sobre as fórmulas do PI-00; não é decisão):** como λ̄ = N_op·κ/P e λ_pico = η·λ̄·P/W_janela, o simulador só enxerga **λ̄ (= λ_B × nível), η, a razão W_janela/P e a escala absoluta de tempo**. N_op, κ e P **separados** não alteram nenhuma saída (só a decomposição documental e, para κ, a mistura de operações). Ver B-02 a B-08 e Q14.
- **Descoberta 2 [C]:** `duracao_sim_s = 7200` nos YAML e `--duration` padrão 600 s. Se P e W_janela forem calendários reais (semanas, meses; ADR-0005 diz que prazos de relato e verificação são "ordem de meses"), uma corrida de 7200 s **vê no máximo um estado** do MMPP-2 e nunca um ciclo completo. Isso força decidir entre compressão temporal declarada e corridas muito mais longas (B-04, B-06, B-29, Q15).
- **Identidade entre A e B:** o lote L de B-50 é o mesmo parâmetro de A-23/A-25 (`batchSize` do Tendermint e do HotStuff). Decidir os três juntos.

### B.1 Modelo de demanda e MMPP-2

| ID | Parâmetro | Consumido em | Estado no código | Fontes candidatas | Medido (ponte) / Escolhido | Impacto | Dependências | Risco de escolher mal | Recomendação |
|---|---|---|---|---|---|---|---|---|---|
| B-01 (PI-10 proposta; PI-00; ADR-0013 dec.1) | Regime R = MMPP-2: aceitar ou não a conversão proposta (estrutura, não valor) | gerador único de tx do ADR-0013 (a criar); pré-registro | **ausente**: `grep MMPP` em `src/main` não acha nada; só existe Poisson homogêneo (`AbstractPoissonProcess`) | [FONTE] ADR-0010 PI-10: álgebra conferida, média estacionária = N_op·κ/P exata; limite: durações exponenciais, "uma janela por período" só vale em média | ESCOLHIDO (estrutura). Teste: contagem de tx por semente (ADR-0013, verif. 2) | ALTO: metade das 240 configs do E1 (fator R, 2 níveis) depende dele [C] | Q13; gerador único (ADR-0013); B-09 | Aceitar sem declarar o limite (prazo real é determinístico) expõe a representatividade; rejeitar apaga o fator R do ADR-0000 | **Decidir a estrutura** (Q13) e registrar o limite exponencial no pré-registro |
| B-02 (PI-02) | N_op (operadores e participantes ativos) | cálculo de λ̄ e λ_pico (decomposição); nenhum código | **ausente** | nenhuma (`a definir`). ADR-0000 (contexto 3) cita registro central sob órgão gestor e entidades credenciadas (Lei 15.042/2024); **citação não verificada** (índice 6.7, item 9) | ESCOLHIDO. Ponte: NÃO (rede de desenvolvimento sem usuários) | MÉDIO: só entra pelo produto com κ/P; sozinho não muda a saída [C] | B-03, B-04, B-07; Q14 | Faixa sem fonte vira opinião; mas errar N_op sozinho não altera o simulador se λ_B for fixado direto | **Não decidir isolado:** fixar λ_B (B-07); N_op fica como decomposição documental com fonte do humano (Q14) |
| B-03 (PI-03; PI-00) | κ (tx de registro por participante por período) e **pesos das 4 categorias** (relato, transferência, conciliação, aposentadoria) | decomposição de λ̄; mistura de operações (tamanho por operação, A-13) | **ausente**; o modelo conta as 4 como uma só taxa | nenhuma. [FONTE] medição fabric-rede: envelope por operação 4698 a 7031 B (Seção A, Ressalva M) | A **mistura e o tamanho por operação** são PONTE-PARCIAL; κ em si é ESCOLHIDO | MÉDIO: κ só entra no produto; a mistura define o s_tx médio (A-13, Q4) | B-07; A-13; Q4 | Contar as 4 operações como iguais subestima s_tx (aposentadoria é a mais cara medida: 7031 B) | **Medir** a mistura só se Q4 escolher "envelope por operação"; senão declarar κ como parte de λ_B |
| B-04 (PI-06) | P (duração do período de compromisso) | decomposição de λ̄; **escala de tempo do MMPP-2** (1/σ1 = P − W_janela) | **ausente** | [FONTE] ADR-0005: prazos regulatórios de relato e verificação são da ordem de meses; nenhum número fixado. Normas não lidas aqui | ESCOLHIDO. Ponte: NÃO | **ALTO**: define a escala de tempo; com P real e `duracao_sim_s` = 7200 não há ciclo completo [C] | B-06, B-29, B-28; Q15 | P real (meses) torna o MMPP-2 inobservável; P comprimido distorce a leitura "regulatória" | **Decidir por Q15** (compressão temporal declarada). Não separar do par (P, W_janela) |
| B-05 (PI-04) | η (fração das tx do período que cai na janela) | λ_pico = η·λ̄·P/W_janela; λ2 do MMPP-2 | **ausente** | nenhuma (`a definir`) | ESCOLHIDO. Ponte: NÃO | ALTO: junto com W/P define a razão pico/média (η·P/W) | B-06, B-08; restrição W/P < η < 1 | η ≤ W/P degenera em Poisson (λ1 = λ2); η → 1 zera λ1 | **Faixa de sensibilidade** com fonte do humano (Q14); verificar a restrição de validade por célula (B-08) |
| B-06 (PI-05) | W_janela (duração da janela antes do prazo de conciliação) | 1/σ2 do MMPP-2; λ_pico | **ausente** | nenhuma. ADR-0005: ordem de meses para prazos de relato (não é janela de conciliação; **não confundir**) | ESCOLHIDO. Ponte: NÃO | ALTO: define a razão W/P e a escala do pico | B-04, B-05; Q15 | Mesmo risco de B-04 (inobservável se real) | **Decidir junto com B-04** (Q15). Registrar a **razão** W/P como o parâmetro de forma |
| B-07 (PI-01) | λ_B (λ̄ no cenário base) e os 3 níveis (λ_B, 10λ_B, 100λ_B) | fator L do E1; âncora de toda a escada | **ausente**; a taxa nominal hoje difere por protocolo (0,2; 2; 10; ~12,5 tx/s; auditoria L13) | nenhuma. PI-01 opções: (a) dado de registro real, (b) transações do contrato medidas (**não feito**), (c) faixa de sensibilidade | ESCOLHIDO. Ponte: NÃO como demanda; mede só **capacidade e forma** da aplicação (não é demanda) | **ALTO**: âncora de L, de λ_pico, da escada (B-22) e de V1 | B-02 a B-06 (produto); Q14, Q15; ADR-0013 (gerador único) | Valor sem fonte contamina todo o E1; níveis 10x e 100x fora da capacidade de um protocolo geram só saturação | **Decidir o valor de λ_B diretamente** (a decomposição N_op·κ/P é documental) e declarar a fonte (Q14); tratar como faixa se não houver fonte |
| B-08 (PI-09a; restrição de PI-10) | λ_pico e razão λ_pico/λ̄ = η·P/W_janela; **regra de validade** 0 < W_janela < P e W_janela/P < η < 1 | V1 (Θ ≥ λ_pico); parâmetro λ2 do MMPP-2; pré-registro | **ausente** (fórmula definida pelo humano, valor `a definir`) | [FONTE] ADR-0010 PI-00, PI-09a, PI-10 | Derivado de B-05, B-06, B-07 (não é escolha independente) | ALTO: V1 depende dele | B-05, B-06, B-07 | Célula fora da regra de validade gera MMPP-2 degenerado ou taxa negativa | **Verificar a regra por célula no pré-registro**; nenhum valor próprio a decidir |
| B-09 (PI-10; ADR-0013 verif. 2; auditoria C.2.7) | Tolerância da igualdade de média Poisson × MMPP-2 e **janela de contagem do índice de dispersão** | teste do gerador; análise; ponte (exige índice de dispersão) | **ausente**; tolerância "a definir" no ADR-0013 | [FONTE] ADR-0013 verif. 2; auditoria C.2 item 7. Dispersão teórica do MMPP-2: **não calculada** | PILOTO (a média observada por semente e a dispersão saem do próprio gerador) | MÉDIO: sem ela o teste de "mesma média" não tem critério de aceite | B-01, B-11; Q16 | Tolerância frouxa deixa passar gerador com média errada; apertada demais reprova por ruído amostral | **Derivar do erro-padrão amostral** (Q16), não fixar número arbitrário; janela de contagem escolhida no piloto |
| B-10 (PI-11) | Perfil sazonal (amplitude A, período T, forma, nome) | `perfil_carga: [estacionario, sazonal]` nos 3 YAML; `gerar-grid.py` | **ausente** no simulador (nunca implementado); resíduo nos YAML/script | [FONTE] ADR-0010 PI-11 **NÃO USADO** (decisão B3, 2026-10-29); índice 6.8 | Encerrado (sem valor a decidir) | BAIXO: só o resíduo documental importa | B-01 (substituto) | Deixar o YAML com `sazonal` induz gerar células de um perfil inexistente | **Nenhuma decisão de valor.** Corrigir YAML/`gerar-grid.py` é tarefa separada (`.experimento/` não é editado aqui) |
| B-11 (ADR-0013 dec.1, a confirmar) | Fluxo de aleatoriedade **próprio** do gerador de tx | gerador único | **ausente**; hoje o RNG é compartilhado entre tx, latência e blocos (auditoria C.1.5) | [FONTE] ADR-0013 dec.1: recomendação do ADR, **não** está entre as 4 decisões do humano | ESCOLHIDO (estrutura). Teste: mesma semente, mesmo λ, mesma sequência em todos os protocolos | ALTO: sem RNG próprio, variar λ ou regime desloca os sorteios de rede e bizantinos e quebra o pareamento | PI-13; B-37, B-38 | Compartilhar o RNG confunde efeito de carga com mudança de trajetória | **Confirmar** a recomendação do ADR-0013 (Q13) |

### B.2 Enlace por nó (W_nó)

| ID | Parâmetro | Consumido em | Estado no código | Fontes candidatas | Medido (ponte) / Escolhido | Impacto | Dependências | Risco de escolher mal | Recomendação |
|---|---|---|---|---|---|---|---|---|---|
| B-12 (PI-07) | W_nó **institucional** (banda de subida; latência e perda se couber) | fator W do E1; `LAN100MNetworkStats` | **hardcoded**: 100 Mbps constante, só em PBFT/HotStuff/Snow (`LAN100MNetworkStats.java:10`); Casper e Parlia usam distribuição de 6 regiões (auditoria B.2). YAML: `largura_banda_mbps: 100` como fixo | nenhuma (`a definir`). [FONTE] ADR-0012 item 7: U e Θ só são comparáveis entre protocolos que partilham a LAN | ESCOLHIDO. Ponte: NÃO (rede Docker local não representa enlace de produção) | **ALTO**: U e Θ dependem do enlace; sem banda uniforme o fator W não é comparável | tarefa do loop 2 (banda uniforme por nó, **não aberta**); Q17; B-15 | Valores sem fonte: U_max e a fronteira n_max(λ) ficam condicionais | **Decidir primeiro se a banda uniforme entra antes da bateria** (Q17); valor com fonte do humano ou faixa |
| B-13 (PI-07) | W_nó **restrito** | idem | **ausente** (não existe segundo nível; há uma constante) | idem | ESCOLHIDO. Ponte: NÃO | ALTO (idem) | B-12 | Níveis muito próximos não separam efeito; muito distantes colocam o restrito fora da região útil | **Decidir junto com B-12** (Q17) |

### B.3 Limiares e rótulos de análise

| ID | Parâmetro | Consumido em | Estado no código | Fontes candidatas | Medido (ponte) / Escolhido | Impacto | Dependências | Risco de escolher mal | Recomendação |
|---|---|---|---|---|---|---|---|---|---|
| B-14 (PI-09) | L_max (limite da latência p95 L_tx, V2) | critério V2; pré-registro | **ausente** (nenhum código o lê; L_tx p95 sem fallback também não está implementado, PI-20) | nenhuma. [FONTE] ADR-0005: nenhuma norma fixa prazo de latência de consenso; prazos regulatórios são em meses | ESCOLHIDO (ou "descritivo"). Ponte: NÃO | ALTO: V2 decide viabilidade e a fronteira n_max | Q19; B-18; PI-20 | Limiar sem fonte vira opinião e muda a fronteira inteira | **Faixa de sensibilidade congelada**, rotulada "descritivo" (Q19), coerente com ADR-0005 e P7 |
| B-15 (PI-09) | U_max (limite da ocupação do enlace, V3) | critério V3 | **ausente** | [FONTE] ADR-0012: o `.tex:116` dá 0,7 como "por exemplo", sem fonte (valor citado só como exemplo do artigo, **não adotado**) | ESCOLHIDO | ALTO: idem | B-12, B-24; Q19 | Idem | Idem B-14 |
| B-16 (PI-09; ADR-0000 dec.8) | Limiar de MAPE do metamodelo log-log | análise (validação fora da faixa) | **ausente** | [FONTE] ADR-0000: "MAPE < 10%" na decisão 8, "limiar de MAPE" na lista de parâmetros: **divergem dentro do próprio ADR-0000** (ADR-0010 PI-09 não adota os 10%) | ESCOLHIDO | MÉDIO: define aceite do metamodelo | B-42 (pontos 256/400/800); Q19 | Fixar após ver o ajuste é post hoc | **Congelar no pré-registro** antes de rodar a validação (Q19) |
| B-17 (ADR-0012 perg. 1; PI-09a) | Qual V1 vale: `Θ ≥ λ_pico` (ADR-0000) ou `Q(kP) ≤ ε_Q` (`.tex`); **ε_Q** | critério V1; Θ e fila | **ausente** (Θ não implementado; fila de pendentes não existe) | [FONTE] ADR-0012, Contexto: as duas fontes divergem; o `.tex` diz que Q(kP) é "menos restritivo" | ESCOLHIDO (qual); ε_Q ESCOLHIDO | ALTO: muda o que se mede e o que depende da fila | ADR-0013 (fila); B-22, B-23; Q18 | Escolher Q(kP) sem fila implementada trava; escolher Θ sem saturação idem | **Decidir qual vale** (Q18); ε_Q só depois |
| B-18 (PI-09, PI-20; P7) | **Estatuto** dos limiares (normativo com fonte × descritivo), faixa de sensibilidade congelada, e **nomes CV1 a CV3** | pré-registro (P7, P8) | **ausente** (decisão documental) | [FONTE] ADR-0005 (Aceito, opção 3); redação de P7 aceita (índice, seção 2); ADR-0010 PI-20 (sugestão CV1 a CV3, **não confirmada**) | ESCOLHIDO | ALTO: condiciona B-14 a B-16 e a leitura de V1 a V3 | Q19 | Veredito "atende/não atende" sem fonte contradiz ADR-0005 | **Confirmar** descritivo + faixa; confirmar CV1 a CV3 (Q19) |
| B-19 (ADR-0005) | Limiares Tb, Pdv, vazão, falha tolerada, janela submissão→verificação; borda P1 e Q2 | loop 3; `submeterRelatorioMRV` (borda) | **ausente** (não há constante de prazo em `chaincode/`: busca sem resultado) | [FONTE] ADR-0005 **Aceito** (2026-10-25): opção 3 "não fixar"; borda P1; REJEITADO ocupa (Q2) | Já decidido (sem valor) | BAIXO agora: só reabre se o humano quiser um limiar normativo | Q19 | Reabrir sem fonte normativa repete a lacuna | **Nada a decidir**, salvo o humano reabrir; manter como comparação relativa |
| B-20 (L18) | Limiar de segurança por tipo de consenso (BFT 1/3, PoS 1/3 ou 1/2, PoW 1/2+1) | `SimulationMetrics.java:445-447,479-482` | **hardcoded**, inconsistente entre ramos (POS: 1/3 se BFT empírico > 0, senão 1/2) | [FONTE] auditoria L18 (2026-10-29; não reverifiquei as linhas). Nenhuma norma | ESCOLHIDO. Ponte: NÃO | ALTO para a bateria 2 (E2): define "seguro" | PI-12; B-34; Q20 | Um limiar diferente por ramo faz φ não ser comparável | **Decidir** (Q20): unificar ou reportar a curva Pdv(φ) sem veredito |
| B-21 (ADR-0005; L18) | `--max-pdv-pct` (Pdv máxima aceita, em %) | `ByzantineSweep.java:269,332` | **padrão silencioso**: `getOrDefault("max-pdv-pct","0.5")` | nenhuma (0,5 % sem fonte) | ESCOLHIDO. Ponte: NÃO | ALTO (E2): define `secure` | B-20; ADR-0005 | O 0,5 % decide em silêncio o que é "seguro" | **Tornar obrigatório** (tarefa do loop 2) ou eliminar o veredito (Q20) |

### B.4 Saturação, janela de medição, transiente, duração e replicações

Estado do registro (lido): `--load-record` e `--load-record-t0` existem no `MVPComparison` (`:52,163-181`); ligado sem t0 **falha alto**; Θ sai `-1`/`NOT_MEASURED` por construção (`LinkLoadRecorder.java:41,283`). A fila de pendentes não existe (`grep` sem resultado).

| ID | Parâmetro | Consumido em | Estado no código | Fontes candidatas | Medido (ponte) / Escolhido | Impacto | Dependências | Risco de escolher mal | Recomendação |
|---|---|---|---|---|---|---|---|---|---|
| B-22 (RM-07; ADR-0012 perg. 7) | Escada de saturação: valores λ_1 < λ_2 < …, **passo** e **teto** | script da bateria de saturação (`experimentos/`, a criar); gerador único | **ausente** | nenhuma. [FONTE] ADR-0012 RM-07: valores `a definir`; se nenhum degrau satura, `NOT_SATURATED` e Θ censurado (> teto) | PILOTO: a capacidade de cada protocolo se descobre no simulador (escada adaptativa). Ponte: PARCIAL (a vazão do orderer Raft, se medida, dá ordem de grandeza **do Fabric**, não do BFT) | ALTO: define se Θ é medido ou censurado | B-07 (âncora); B-17; B-23; ADR-0013 | Teto baixo censura Θ; escada fina custa corridas (uma por degrau, por célula) | **Decidir a regra da escada** (geométrica a partir de λ_B, parada ao saturar) e deixar o teto para o piloto (Q21) |
| B-23 (RM-07) | Critério de "cresce sem limite": (i) inclinação de Q(t) na 2ª metade, (ii) Q(t1) > múltiplo de L, (iii) platô de ConfRate (< ε) | análise do Θ; `ThetaStatus` | **ausente** | [FONTE] ADR-0012 RM-07 (3 candidatos, sem valor). (iii) só existe se o bloco for dimensionado pela fila (ADR-0013 dec.4) | ESCOLHIDO (qual critério); constantes (nível de significância, múltiplo, ε) PILOTO | ALTO: erra Θ em ambos os sentidos | ADR-0013 (fila); B-50 (L); B-22 | (i) sem teste estatístico confunde rajada MMPP-2 com saturação; (ii) depende de L; (iii) não olha Q | **Escolher (i) com teste estatístico** como primário e (iii) como conferência (Q22); constantes `a definir` |
| B-24 (RM-04; ADR-0012 perg. 3) | U de **corrida** (máx. da média por nó) × U de **bloco** (máx. por intervalo `I_b`) alimentando o V3 | `LinkLoadRecorder` (colunas `U_run_max`, `U_block_max`); V3 | **ausente** na parte de bloco (`U_block_max` previsto, M1a-2/M1b preenchem); as duas serão registradas | [FONTE] ADR-0012 RM-04: com líder rotativo U_corrida dilui o pico do líder | ESCOLHIDO | ALTO: muda a leitura da hipótese do gargalo do líder | B-39 (política de líder); B-15 | U_corrida com líder rotativo esconde o gargalo que o artigo quer testar | **U_bloco para V3**, U_corrida registrada (Q23); definir `I_b` junto |
| B-25 (RM-02; perg. 5) | Corte de C_nó: instantâneo da 1ª finalização × total do bloco até o fim | `EnhancedBlockFinalizationLogger` (coluna 15 `CnodeSentMax_B`) | **ausente** (previsto) | [FONTE] ADR-0012 RM-02 (recomendado: instantâneo, comparável ao C_B) | ESCOLHIDO | MÉDIO: COMMIT tardio fica de fora no instantâneo | B-24 | Subestima C_nó do líder; comparação com C_B perderia o pareamento se mudar | **Instantâneo** (recomendado no ADR-0012), com a limitação declarada (Q23) |
| B-26 (RM-01; perg. 4) | O pacote que o PBFT envia a si mesmo (pela fila) conta em S_i e U? | `PBFTP2P` (inclui o próprio nó); `LinkLoadCounters` | **hardcoded** (passa pela fila, logo conta no modelo) | [FONTE] ADR-0012 contexto 11 e RM-01 (recomendado: sim, com coluna própria) | ESCOLHIDO | BAIXO: 1 pacote em n; mas HotStuff não faz o mesmo (autoentrega local) → assimetria | Q23 | Assimetria PBFT × HotStuff em bytes enviados | **Contar, com coluna própria** (Q23) |
| B-27 (RM-10; perg. 10) | Formato e granularidade da saída (colunas por bloco × arquivos por corrida e por nó × os dois) | `LinkLoadRecorder`; análise | parcial: `<prefix>-corrida.csv` e `-nos.csv` já têm cabeçalho no código (`:286`); coluna 15/16 no `-t0.csv` | [FONTE] ADR-0012 RM-08 e RM-10 (desenho recomendado) | ESCOLHIDO | BAIXO: não muda número, muda o consumo | `nao_regressao.py` só aceita 1 coluna extra (dono não definido, perg. 8; ver também B-66) | Extensão do script sem dono trava a prova de não regressão | **Confirmar o desenho recomendado**; atribuir dono do script (Q27, Q36) |
| B-28 (RM-06; perg. 6) | Janela de medição: `t0` (`--load-record-t0`), duração mínima `W_min`, se `t0` é igual para todos os protocolos | `MVPComparison --load-record-t0` (obrigatório sem padrão); pré-registro (verificação de duração mínima da janela ≥ `W_min`) | **obrigatório sem padrão** (falha alto) para `t0`; `W_min` **ausente** | [FONTE] ADR-0012 RM-06 | PILOTO (Welch dá o fim do transiente; `W_min` pela estabilidade de U e ConfRate) | ALTO: janela curta mede transiente; longa custa tempo | B-29, B-32, B-04 (escala de tempo) | `t0` por protocolo diferente reintroduz o viés que a remoção quer evitar (PROTOCOLO A.1: adotar o **maior** corte para todos) | **Um `t0` único**, vindo do Welch do piloto (Q25) |
| B-29 (L16; `warmup_descartar`; PROTOCOLO A.1) | Transiente descartado (ponto de corte) | YAML `warmup_descartar: true`; `scripts/welch_warmup.py` (citado, **não existe** em `scripts/jabs/`); `t0` | **ausente** no simulador (corte é pós-processamento); `SimulationMetrics` descarta as amostras **mais antigas** acima de 10.000 | [FONTE] PROTOCOLO A.1; auditoria L16 | PILOTO. Ponte: NÃO | ALTO: não descartar infla a latência das famílias de votação (PROTOCOLO A.1) | B-28; B-32; log bruto sem descarte (tarefa loop 2) | Corte por protocolo ou antes da fila encher distorce Tb | **Welch no piloto, corte único** (Q25); exige log bruto completo (L16) |
| B-30 (PROTOCOLO §2, A.2; L6) | Número de sementes/trials r, **precisão-alvo** do dimensionamento (`--precisao 0.05` no exemplo do PROTOCOLO), e política de semente | YAMLs `sementes: 10 # PROVISORIO`; `--trials`, `--seed` | **padrão silencioso**: `MVPComparison` `--trials`=5, `--seed`=12345; `ByzantineSweep` `--trials`=30, seed = base + n·1000 + t (`:144`) | [FONTE] PROTOCOLO: "cortar replicações invalida as comparações por IC"; r vem de A.2. 0.05 é exemplo do comando, não decisão | PILOTO (variância → r) | ALTO: define poder e custo (ADR-0009: 240 × r) | B-29 (corte antes de dimensionar); piloto | r provisório de 10 usado como definitivo; trials padrão diferentes entre os dois `main` | **r do piloto** (Q26); tornar `--trials`/`--seed` obrigatórios no pré-registro; precisão-alvo é decisão do humano |
| B-31 (RM-09; perg. VT-11) | Tolerância da verificação da eq. `util` (U medido × `8·C_nó·ν_b/K_nó`) | teste VT-11; análise | **ausente** | [FONTE] ADR-0012 RM-09 e VT-11; diferença estrutural T1 − Tn explicada no contexto item 5 | ESCOLHIDO | BAIXO: é só verificação | B-24 | Tolerância apertada reprova por T1 − Tn; frouxa não verifica | **Derivar da diferença T1 − Tn esperada** em vez de número arbitrário |
| B-32 (`duracao_sim_s`; `--duration`) | Duração da simulação | YAMLs `duracao_sim_s: 7200`; `MVPComparison --duration`; `ByzantineSweep --duration` | **padrão silencioso**: `--duration`=600 em ambos; 7200 no YAML | [FONTE] YAMLs; auditoria D: poda em 1000 alturas (A-26) pode agir em 7200 s | PILOTO (tempo de parede por célula é `NAO_MEDIDO`) | ALTO: define quantos ciclos do MMPP-2 cabem [C] e o custo | B-04, B-06, B-28, B-29; custo por célula (L19) | 7200 s contra P real não cobre nenhum ciclo; duração longa × n=800 estoura custo | **Decidir após Q15** e após medir custo por célula; sempre explícita |

### B.5 Ataque bizantino, fração, universo e liderança

Estado do código (lido): `ByzantineConfig.validateAttackName` **já falha alto** para tipo desconhecido, nulo ou fora da grafia exata (`ByzantineConfig.java:87-99`), o que fecha o aspecto de código da L1; a tradução `equivocacao`→`EQUIVOCATION` é do gerador de configurações (comentário `:79-81`). `ByzantineSweep.resolveAttack` usa `EQUIVOCATION` quando `--attack` falta (`:44-51`).

| ID | Parâmetro | Consumido em | Estado no código | Fontes candidatas | Medido (ponte) / Escolhido | Impacto | Dependências | Risco de escolher mal | Recomendação |
|---|---|---|---|---|---|---|---|---|---|
| B-33 (ADR-0000 dec.4; L4; PROTOCOLO §3) | Fração bizantina φ: níveis, **regra de conversão para contagem** (arredondamento) e `n` do E2 | `ByzantineSweep --max-byz`, passo; YAML bateria2 `f` | **hardcoded/padrão**: `--max-byz`=n/2; passo = max(1, n/100) (`:85,118`); POS sorteia `ceil(stakeholders·f/n)` (`:484`); YAML tem f {0,10,20,33,40,51} (desenho de 28/10) | [FONTE] ADR-0000 dec.4: φ ∈ {0; 0,10; 0,30}; **"n no centro da faixa" sem n definido** (ADR-0009). Arredondamento `ceil` em ponto flutuante não verificado (L4) | ESCOLHIDO. Ponte: NÃO | ALTO: define o E2 inteiro | B-43 (n); B-37; Q34 | 0,10·n não inteiro: arredondar de forma diferente por protocolo muda f efetivo | **Decidir n do E2 e a regra de arredondamento única** (Q34) |
| B-34 (PI-12; L2) | **Definição operacional de EQUIVOCATION**: tamanho e escolha dos subconjuntos disjuntos de honestos | `PBFT.java:372-405` (`half = max(1, n/2)` dos vizinhos, metades por índice); equivalentes nos outros protocolos | **hardcoded** no PBFT (metade/metade por índice, falha explícita se não dá para dividir); Nakamoto trata como retenção (`BitcoinNode:58-61`; fora do escopo) | [FONTE] ADR-0010 PI-12: definição aceita como direção ("duas mensagens conflitantes a subconjuntos disjuntos"); PROTOCOLO §3: "ataque forte" | ESCOLHIDO. Ponte: NÃO | **ALTO**: o ataque tem de ser o mesmo em todos os protocolos para a comparação valer | B-35, B-36; paridade de hooks HotStuff/Tendermint (P4) | Partição desigual muda a capacidade do ataque por protocolo | **Decidir a partição** (metade/metade determinística por índice × aleatória) e fixar igual em todos (Q28) |
| B-35 (PI-12; L3) | Abrangência: todas as mensagens (proposta, cada voto) × só a fase crítica | hooks de cada protocolo | **ausente/assimétrico**: PREPARE sem ramo bizantino no PBFT; DOUBLE_SIGN só no COMMIT (L3) | [FONTE] auditoria L3 (2026-10-29) | ESCOLHIDO | ALTO: muda quais fases o atacante controla | B-34 | Fases cobertas diferentes por protocolo | **Decidir** junto com B-34 (Q28) |
| B-36 (L5; PI-12) | Coordenação entre bizantinos (manter ou remover a exigência do PROTOCOLO §3) | hooks | **ausente** (decisão local por nó) | [FONTE] PROTOCOLO §3 exige; auditoria L5 diz que não existe | ESCOLHIDO | MÉDIO: ataque coordenado é pior caso | B-34 | Manter a exigência sem código contradiz o que se mede | **Decidir** manter (nova tarefa do loop 2) ou remover do pré-registro (Q28) |
| B-37 (PI-13; L4) | Universo do sorteio de bizantinos (todos os nós × só validadores/votantes) | `ByzantineConfig.selectByzantineValidators`; cenários | **hardcoded por protocolo** (todos no ARQ/Parlia, stakeholders no Casper) | [FONTE] ADR-0010 PI-13 (unificar, aceito como direção) | ESCOLHIDO | ALTO: o mesmo φ vale coisas diferentes | B-43; B-38 | "Todos os nós" ≠ "validadores" quando n_total ≠ n_validadores | **Só validadores/votantes** é o que torna φ comparável (Q30); depende de Q32 |
| B-38 (PI-13; L4) | RNG dedicado e **aninhado entre valores de f** para o sorteio | `ByzantineConfig` (`randomSeed`) | aninhamento só testado no DAGsper (`DAGsperScenarioTest:190-199`) | [FONTE] auditoria L4 | ESCOLHIDO | MÉDIO: pareamento por semente | B-11 | Conjuntos não aninhados mascaram o efeito de f | **RNG dedicado aninhado** (Q30) |
| B-39 (PI-14; ADR-0009 dec.4) | Política de líder: pBFT **estável** (decidido como direção), **líder inicial**, sorteio de bizantinos com ou sem o líder, tamanho do lote da ablação | `PBFTLocalLANNetwork:97` (`min(10, n-1)`); `HotStuffConsensusConfig.leaderPolicy` | **hardcoded** no pBFT: proponente `min(10,n-1)` no agendador, nó 0 na view local (divergência no ADR-0007) | [FONTE] ADR-0010 PI-14; ADR-0009 (ablação = lote à parte, tamanho `a definir`) | ESCOLHIDO. Ponte: NÃO | ALTO: muda U, C_nó e a leitura do gargalo | A-09 (Q8); B-24; B-40 | Comparar estável × rotativo mistura política e protocolo | **Decidir junto com Q8** (Q31) |
| B-40 (ADR-0007; PI-14; índice 6.7 pergunta 5) | Timeout de view change do pBFT estável e se abre a opção 3 do ADR-0007 | pBFT (view change **não existe**) | **ausente** | [FONTE] ADR-0007 (**Aceito**, opção 2: sem view change); índice 6.8 B8 "pBFT mantém a opção 2" | ESCOLHIDO | ALTO para E2 (falha de líder): sem view change, líder bizantino silencioso não é tolerado | B-39; B-34 | Estudar robustez bizantina com pBFT sem view change mede outra coisa | **Decidir** se E2 inclui falha de primário (Q31); se sim, abre ADR e timeout (`a definir`) |

### B.6 Tamanho de rede n e conversão de n

Estado do código (lido): `NetworkSizing` com `--n-semantics=legacy|nodes`: **flag ausente = `legacy`** (comportamento antigo, truncado: n=4 vira 1+2 = 3 nós em Casper/Parlia; n=16 → 15; n=64 → 63; n=128 → 127); valor inválido falha alto; `nodes` faz `n` ser o total de nós, com `mineradores = max(1, round_half_up(0,3 n))` e a proporção 30/70 marcada no próprio código como "a definir com o humano" (`NetworkSizing.java:15-19`). No `ByzantineSweep` o legado dá `n` stakeholders + 0,3 n mineradores (1,3 n nós).

| ID | Parâmetro | Consumido em | Estado no código | Fontes candidatas | Medido (ponte) / Escolhido | Impacto | Dependências | Risco de escolher mal | Recomendação |
|---|---|---|---|---|---|---|---|---|---|
| B-41 (PI-19; L10; N1) | **Semântica de n**: `legacy` × `nodes` (o que "n" significa em cada protocolo) | `--n-semantics` em `MVPComparison` e `ByzantineSweep`; YAML | **padrão silencioso**: flag ausente = `legacy` (`NetworkSizing.java:52`); `ByzantineSweep` tem cópia da regra (`:436`) | [FONTE] `NetworkSizing.java` (lido); ADR-0010 PI-19 (acréscimo 30/10): a conversão é "tarefa separada, ainda não aberta", n=4 **não é comparável** até lá; ADR-0006 (tabela de débitos) | ESCOLHIDO. Ponte: NÃO | **ALTO**: com `legacy`, o mesmo "n" é nº de nós diferente por protocolo e o E1 inteiro (fator N) fica não comparável | N1 sem veredito de não regressão (B-66); B-44; B-37; Q32 | Rodar o E1 com a flag ausente produz a comparação inválida que o ADR-0000 quer corrigir | **Decidir `nodes` como semântica do estudo** e tornar a flag obrigatória na bateria (Q32); `legacy` só em não regressão |
| B-42 (ADR-0000 dec.5; ADR-0009) | Níveis de **n do E1** {4, 16, 64, 128} e **pontos de validação** {256, 400, 800}: com **quais protocolos, L, R, W_nó** se valida | gerador de configs; pré-registro | n do E1: definido no ADR-0000 (Proposta). Validação: **ausente** | [FONTE] ADR-0000 dec.5 e ADR-0009: validação "não diz com quais protocolos nem com quais L, R, W_nó"; custo de n=800 `NAO_MEDIDO` (auditoria D) | PILOTO (custo por célula; L19). Ponte: NÃO | ALTO: n=800 em PBFT é ~n² pacotes por fase [C, estrutural, auditoria D]; pode ser inviável | B-32 (duração); B-30 (r); custo por célula | Validação sem custo medido pode não terminar | **Medir custo de uma célula por protocolo e n** (piloto) antes de decidir o que se valida (Q33) |
| B-43 (ADR-0009; ADR-0000 dec.4) | **n do E2** ("centro da faixa") e **n do piloto** (`piloto.yaml` usa {25,100,400}, fora da faixa do ADR-0000) | pré-registro; YAML | **ausente** (YAML de 28/10: n=100 na bateria 2) | [FONTE] ADR-0009: E2 não define qual n nem quais protocolos, L, W_nó; piloto: "contagem e desenho `a definir`" | ESCOLHIDO | ALTO: o E2 não é executável sem n | B-33; B-41; Q33 | n=100 não pertence à faixa {4,16,64,128} | **Decidir n do E2 e desenho do piloto** (Q33) |
| B-44 (N1; `NetworkSizing.java:16`) | Decomposição mineradores/stakeholders (30/70) em Casper e Parlia no modo `nodes` | `NetworkSizing`; cenários | **hardcoded** (herdada do `MVPComparison`, sem fonte) | [FONTE] código; ADR-0006 divida C1 (denominador S exclui mineradores; não alterado) | ESCOLHIDO. Ponte: NÃO | ALTO: define quem vota e quantos validadores há em PoSA/PoS | B-41; B-37; Q32 | Com n=4: poucos validadores; finalização com 2 stakeholders **não verificada** (ADR-0010 PI-19) | **Decidir** quem são "validadores" em PoSA e PoS (Q32); verificar finalização em n pequeno |
| B-45 (L11; ADR-0009 6.1) | **Correspondência** PoSA/PoS ↔ Parlia/CasperFFG e **qual variante** (MCO2 × TREE; EARTH/POW do sweep) | gerador de configs; YAML; `MVPComparison` | **ausente** (mapeamento só em comentário/humano) | [FONTE] índice 6.1 ("provavelmente Parlia e Casper, a confirmar"); auditoria B.2 (MCO2 e TREE diferem só em `averageBlockInterval` 15 s × 60 s) | ESCOLHIDO | ALTO: fator A do E1 | B-41 | PoS ≠ Casper se a variante muda o intervalo de bloco | **Decidir o mapeamento** (Q32) |
| B-46 (ADR-0009) | Contagem de células de E2, validação, ablação (lote à parte, tamanho e r) e piloto | pré-registro; `gerar-grid.py` | **ausente** | [FONTE] ADR-0009 (todas `a definir com o humano`); E1 = 5×4×3×2×2 = 240 × r [C] | ESCOLHIDO | MÉDIO: orçamento de CPU | B-42, B-43, B-30; custo por célula | Orçamento sem custo medido | **Decidir depois do piloto de custo** (Q33) |
| B-47 (L8; YAML base) | Parâmetros "fixos" herdados dos YAML: topologia (`aleatoria`), `tamanho_bloco_kb` 1024, `tamanho_tx_bytes` 512, `mix` público/privado | YAMLs; simulador | **hardcoded e não uniforme**: topologia = malha completa (PBFT) × grau 8 (Bitcoin) × grau ≤ 10 (Ethereum) (auditoria L8); em n pequeno vira malha completa (ADR-0011) | [FONTE] auditoria L8; ADR-0011 (declarar no pré-registro) | ESCOLHIDO. `tamanho_*` ver A-13/B-56 | MÉDIO: parâmetro "fixo" que não é aplicado é fictício (auditoria L8) | Q4; B-50 | Declarar fixo no pré-registro o que o código não aplica | **Declarar no pré-registro só o que o código aplica** (Q33); o resto como limitação |

### B.7 Formação de bloco (ADR-0013) e prova de não regressão

Estado do código (lido): pBFT propõe a cada 5,0 s (`PBFTLocalLANNetwork.java:111`, segundo o ADR-0013); HotStuff lote cheio; Casper/Parlia por gás; fila de pendentes **não existe**. Fonte de configuração do Fabric (lida em `rede-fabric/configtx/configtx.yaml:170-174`): `BatchTimeout 2s`, `MaxMessageCount 10`, `AbsoluteMaxBytes 99 MB`, `PreferredMaxBytes 512 KB`; é configuração de desenvolvimento, não medição.

| ID | Parâmetro | Consumido em | Estado no código | Fontes candidatas | Medido (ponte) / Escolhido | Impacto | Dependências | Risco de escolher mal | Recomendação |
|---|---|---|---|---|---|---|---|---|---|
| B-48 (ADR-0013 §7) | Política da fila de pendentes (FIFO ou outra) | gerador/fila (a criar) | **ausente** | nenhuma; [FONTE] ADR-0013 (a definir) | ESCOLHIDO | MÉDIO: afeta Tt (latência da tx), não o tráfego | B-52 | Política não-FIFO altera a distribuição da latência p95 (V2) | **FIFO**, salvo razão de domínio (Q29) |
| B-49 (ADR-0013 §7) | Capacidade da fila e o que fazer quando enche (**sem descarte silencioso**) | fila (a criar) | **ausente** | [FONTE] ADR-0013: se descartar, contar e declarar; conservação verificada (verif. 5) | ESCOLHIDO | ALTO na saturação (E1 com 100λ_B) | B-22, B-23; B-50 | Capacidade finita mascara saturação (fila cheia ≠ fila crescendo sem limite, RM-07) | **Capacidade ilimitada medida com teto de alarme**, ou finita com descarte contado (Q29) |
| B-50 (ADR-0013 dec.2; A-23, A-25) | **L, lote máximo (tx por bloco)** | fila e regra de fechamento; no Tendermint `--tm-batch-size`, no HotStuff `batchSize` | **ausente** como regra de fila; hoje obrigatório sem padrão nos configs de Tendermint e HotStuff (A-23, A-25) | [FONTE] configtx (`MaxMessageCount 10`, `PreferredMaxBytes 512 KB`); ADR-0013: `L = tamanho_bloco_kb·1024/s_tx` = 2048 com 512 B (**conferir**; o 512 não é o PI-08 decidido) | **PONTE-PARCIAL**: ocupação real de bloco sob carga no orderer Raft. Valor de projeto: ESCOLHIDO | **ALTO**: define o bloco, o tráfego do líder e o teto de Θ | A-13 (s_tx); Q7; B-51 | L = 10 (espelho do Fabric) e L = 2048 diferem 200x [C]; bloco grande sem demanda mede um bloco que não existe | **Decidir com Q7 da Seção A e B-51 juntos** (Q24) |
| B-51 (ADR-0013 dec.2) | **T, tempo máximo de fechamento (s)** | fila e regra; pBFT (substitui a cadência de 5,0 s) | **hardcoded** hoje: 5,0 s no pBFT (`PBFTLocalLANNetwork.java:111`); Casper/Parlia: cadência do protocolo | [FONTE] configtx `BatchTimeout 2s`; ADR-0013: 5,0 s coincide com `--tx-interval`, **não relação** | PONTE-PARCIAL (tempo de corte do orderer sob carga baixa). Valor de projeto ESCOLHIDO | **ALTO**: com λ baixo, o bloco fecha por T e T domina a latência | B-50; B-55; A-16 (timeouts de consenso) | T grande inflama Tb em carga baixa; T pequeno gera blocos quase vazios (custo fixo M(n)·s_v domina) | **Decidir com L** (Q24); T independente da cadência de 5,0 s |
| B-52 (ADR-0013 §7) | Início do relógio de T (abertura do bloco anterior × chegada da 1ª tx pendente) | regra de fechamento | **ausente** | [FONTE] ADR-0013 (pergunta aberta) | ESCOLHIDO | MÉDIO: muda a latência em carga baixa | B-51 | Relógio desde a abertura do bloco anterior fecha blocos vazios | **Desde a chegada da 1ª tx pendente** (evita bloco vazio; exige Q ≥ 1 do ADR-0013) — Q29 |
| B-53 (ADR-0013 §7) | Quais nós têm fila (todos × só proponentes/validadores) e **como a tx chega ao proponente** (difusão ou não) | gerador; tráfego | **ausente** | [FONTE] ADR-0013 | ESCOLHIDO | ALTO: a difusão da tx entra ou não no tráfego medido (C_B) | B-11; B-47 | Sem difusão, o tráfego de gossip de tx não existe (e HotStuff/PBFT hoje não o têm) | **Decidir** (Q29); declarar se a difusão de tx conta no tráfego |
| B-54 (ADR-0013 §4) | Limite de **gás** do Ethereum (`MAXIMUM_BLOCK_GAS` = 12 500 000) contra lote em Casper/Parlia: (i) gás atributo constante, L substitui; (ii) gás segundo limite; (iii) remover | `EthereumMinerNode.java:27` | **hardcoded** | [FONTE] ADR-0013 (3 opções, sem escolha) | ESCOLHIDO | ALTO para PoSA/PoS: fechamento do bloco | B-50, B-51; A-14 | (ii) quebra a regra "comum"; (iii) muda o protocolo | **(i)** para manter regra comum (Q35) |
| B-55 (ADR-0013 §4) | A proposta de 5,0 s do pBFT vira T, é descartada ou permanece como cadência | `PBFTLocalLANNetwork.java:111` | **hardcoded** | [FONTE] ADR-0013 | ESCOLHIDO | ALTO: muda saída do pBFT (mudança intencional já declarada) | B-51 | Manter cadência fixa esconde a demanda (ε_λ ≈ 0 por construção) | **Descartar a cadência fixa**, T da regra comum (Q24) |
| B-56 (ADR-0013 §4) | `tamanho_bloco_kb` (1024) do `bateria1.yaml`: fica como fonte de L, ou sai do spec | YAMLs (`tamanho_bloco_kb`) | **hardcoded** no YAML; só o HotStuff o lê (`batchSizeFor`) | [FONTE] ADR-0013 §4 | ESCOLHIDO | MÉDIO: deixa de ser tamanho de bloco | B-50 | Manter com outro papel gera confusão | **Definir L em tx** e retirar `tamanho_bloco_kb` do pré-registro (Q24) |
| B-57 (ADR-0013 dec.1; PI-10) | Fluxo do MMPP-2 sobre o gerador único: **ordem de implementação** (Poisson primeiro, MMPP-2 depois) e "demanda" do E1 durante a janela | gerador | **ausente** | [FONTE] ADR-0013 §3 | ESCOLHIDO | MÉDIO (cronograma) | B-01 | Rodar E1 só com Poisson deixa o fator R vazio | Registrado em Q13; sem pergunta própria |
| B-66 (ADR-0013 Não regressão; ADR-0012 Verificação; N1) | **Forma da prova de não regressão** para Casper, Parlia, Nakamoto: JVM com `-XX:hashCode=2` nos dois lados **ou** corrigir `memPool` (`LinkedHashSet`, muda a saída do Casper e exige ADR); veredito da N1; confirmar o hash `325f4e3`; dono da extensão de `nao_regressao.py` | todas as tarefas do loop 2 que tocam gerador, fila, registro e conversão de n | **ausente** (critério PENDENTE) | [FONTE] ADR-0013 e ADR-0012 (evidência `diagnostico-hash/`; modo 2 testado em AMBIFY, MCO2 e OFFSET, semente 1; `hashCode=3` ainda diverge no AMBIFY); `git log` não pôde ser rodado nas sessões dos ADRs | ESCOLHIDO. Ponte: NÃO | **ALTO e destravador**: bloqueia B-41, B-50/B-51, B-22 e o registro de Θ | ADR-0011; ADR-0012 ordem (i) | Misturar mudança intencional com deslocamento de hash invalida a prova | **Decidir primeiro** (Q36) |

### B.8 Aplicação: paginação e limites de mensagem (ADR-0014)

Fatos conferidos agora no código: `RedContract.java:86` `PAGE_SIZE_MAXIMO = 100`; `LimitesContrato.java:20` `PAGE_SIZE_MAXIMO = 100`; `grep maxInboundMessageSize` em `api-gateway/src/main` sem ocorrência (canal gRPC sem limite explícito). Limites do peer, do shim e do cliente gRPC são `[FONTE-ADR]` (texto do ADR-0014, não reconfirmados).

| ID | Parâmetro | Consumido em | Estado no código | Fontes candidatas | Medido (ponte) / Escolhido | Impacto | Dependências | Risco de escolher mal | Recomendação |
|---|---|---|---|---|---|---|---|---|---|
| B-58 (ADR-0014) | **Valor** de `PAGE_SIZE_MAXIMO` | `RedContract.java:86`; `LimitesContrato.java:20`; textos OpenAPI (`RedContractController.java:75,141`); testes com literais | **hardcoded** (100), duplicado em 2 constantes + textos e literais de teste | [FONTE-ADR] 100 está abaixo de `internalQueryLimit` (1000) e `totalQueryLimit` (≥ 10 000); nenhuma norma | **PONTE-SIM**: tamanho em bytes da resposta por `pageSize` (1, 10, 50, 100) com histórico curto e longo; ainda não feito | MÉDIO: não afeta validade do crédito; afeta robustez e UX da API | B-61; B-62; Q37 | 100 sem medição pode estourar 4 MiB se `historico` for longo [HIPÓTESE, ADR-0014] | **Medir primeiro** (ponte), depois decidir (Q37); manter 100 enquanto isso |
| B-59 (ADR-0014 O5) | Limite **único** × **por consulta** (`listarCreditosPorProjeto` × `historicoCredito`) | idem | **hardcoded**, uma constante para as duas | [FONTE-ADR] itens de tamanhos muito diferentes (CreditoCarbono completo × `TransferenciaHistorico` de 4 campos) [HIPÓTESE sem medição] | PONTE-SIM (mesma medição de B-58) | BAIXO | B-58 | Uma constante serve mal às duas | **Decidir depois da medição** (Q37) |
| B-60 (ADR-0014 O3) | Limite **fixo no código** × **configurável por canal** | chaincode/gateway/rede | **hardcoded** | [FONTE-ADR] O3-A (estado no ledger) cria dado de governança e exige INV-10; O3-B (ambiente) **vedado ao contrato** pelo linter CC-004 | ESCOLHIDO | MÉDIO: O3-A altera superfície do contrato (loop 1) | B-58 | Configurável por canal exige quem-pode-mudar | **Fixo** (O1 ou O2) salvo necessidade operacional (Q37) |
| B-61 (ADR-0014 pendência 4) | **Teto do tamanho de `historico`** do crédito (campo sem limite no código) | `CreditoCarbono.historico` (cresce a cada transferência, emissão, aposentadoria) | **ausente** (sem teto no código lido) | [FONTE-ADR] sem teto, "o pior caso depende da vida do crédito"; nenhuma medição fecha o pior caso | ESCOLHIDO (política); PONTE-PARCIAL (tamanho por entrada) | **ALTO para a aplicação**: sem teto, nenhum limite de página é provado seguro; mexe no contrato (loop 1) | B-58, B-62 | Resposta que um dia passa de 4 MiB falha tarde, em produção | **Decidir** se limita e como (Q38) |
| B-62 (ADR-0014 O4) | `maxInboundMessageSize` do canal gRPC do gateway (padrão 4 MiB [FONTE-ADR]) | `FabricGatewayService.criarCanalGrpc` (`:111-126`) | **padrão silencioso** (não configurado; padrão da biblioteca) | [FONTE-ADR] 4 MiB no cliente Java (Javadoc 1.85.1; o projeto usa 1.83.1: **idêntico é hipótese**); peer e shim 100 MB | **PONTE-SIM**: teste com resposta > 4 MiB (`RESOURCE_EXHAUSTED` ou sucesso) | MÉDIO | B-58, B-61 | O Javadoc do contrato ("cabe com folga no limite do gRPC") só vale para os 100 MB | **Fixar explicitamente** o valor (Q38) após o teste |
| B-63 (ADR-0014 Verificação) | `totalQueryLimit` **efetivo** da imagem `fabric-peer:2.5` (100 000 × 10 000: a doc diverge) | `rede-fabric/docker-compose.yaml` (não define); `core.yaml` da imagem | **padrão silencioso** (herda a imagem) | [FONTE-ADR] a própria documentação do Fabric diverge | **PONTE-SIM** (ler `core.yaml` na imagem; é fato, não desempenho) | BAIXO: 100 está muito abaixo de qualquer das duas leituras | B-58 | Baixo | **Confirmar** (verificação) e, se quiser, fixar via variável de ambiente (fabric-rede) |

### B.9 Análise estatística, governança do pré-registro e prazos do ADR-0005

| ID | Parâmetro | Consumido em | Estado no código | Fontes candidatas | Medido (ponte) / Escolhido | Impacto | Dependências | Risco de escolher mal | Recomendação |
|---|---|---|---|---|---|---|---|---|---|
| B-64 (PI-17; índice 6.7 item 7) | Dependências estatísticas autorizadas (numpy, scipy, statsmodels?) para Box-Cox, HC3, ART, Games-Howell; formato do raw (CSV/JSONL decidido; Parquet opcional); quem revisa os scripts de análise | `experimentos/scripts/` (não existe `experimentos/`); `welch_warmup.py` e `dimensionar_replicacoes.py` citados no PROTOCOLO | **ausente** | [FONTE] ADR-0010 PI-17; `AGENTS.md`: Python 3.12 sem pandas; ADR-0000 dec.8 pede ANOVA/Box-Cox/HC3/ART/Games-Howell/Holm/ω² | ESCOLHIDO | ALTO: sem permissão a análise do ADR-0000 não roda só com a biblioteca padrão | B-29, B-30 | Implementar Games-Howell/ART à mão sem revisão arrisca erro silencioso | **Decidir autorizando um conjunto fixo e versionado** (Q39) |
| B-65 (índice 6.7 itens 8, 9; 4(k); PI-20) | **Governança**: aceite formal do ADR-0000 pelos coautores; **número** do ADR-0000 no repositório (colide com o template); autorização para corrigir 18/174/36 e `sazonal` nos YAML de `.experimento/`; confirmação da citação da Lei 15.042/2024 | pré-registro (P8); `.experimento/` | **ausente** | [FONTE] índice 6.1, 6.7 (item 9: citação **não verificada**) | ESCOLHIDO (ato do humano) | ALTO: P8 não é atendida sem o aceite | todas | Pré-registro sobre ADR Proposto pode ser invalidado por revisor | **Decidir** (Q40); não se faz por medição |

### B.10 Itens do `grep` NÃO classificados na Seção B

Método: contagem por arquivo de `a definir com o humano` em `docs/adr/`, `especificacoes/` e `.experimento/` (`.experimento/`: **0 ocorrências** da expressão exata nem de "a definir"; os YAML marcam `sementes: 10 # PROVISORIO`, coberto por B-30, e o PROTOCOLO deixa r e o corte para o piloto, B-29/B-30) e leitura das linhas dos arquivos que não eram da lista de entrada. Não classifiquei na Seção B o que é **domínio/chaincode** ou **fora do escopo do estudo**, por não ser demanda, medição ou desenho experimental. Não são "esquecidos"; ficam para uma seção futura de domínio (a Seção C deste ADR é a consolidação A+B), se o humano quiser a mesma tabela.

| Arquivo (nº de ocorrências) | Item | Por que não entrou na B |
|---|---|---|
| `0001-reserva-buffer-reversao.md` (3) | opção, percentual de buffer, crédito aposentado com área revertida | Domínio do crédito (reversão). Não li o texto além das 3 linhas do `grep`. |
| `0002-toneladas-inteiras-e-verificado.md` (1 "a definir") | direção do arredondamento; unidade do inteiro (1 = 1 tCO2e?); relação verificado × estimado | Domínio (INV de toneladas). Citado também em `dominio-redd.md:101,107`. |
| `0003-ajuste-correspondente-e-registro-externo.md` (5) | opção, quem registra a autorização, fim da aposentadoria (doméstico × internacional), integração SBCE, reuso de `hashEvidencia` | Domínio normativo (Art. 6). |
| `0004-ordenacao-raft-ou-bft.md` (2) | opção Raft × BFT; modelo de ameaça do orderer | Decisão da aplicação, não do estudo. A distância Raft × BFT é tema da ponte, não parâmetro. |
| `0006-pbft-trafego-medido.md` (3) | stake desigual no Casper; entrada do DAGsper (T5) no escopo; regra de conversão de n do `MVPComparison` | Os dois primeiros: fora do estudo (stake desigual não modelado; DAGsper fora, ADR-0009). O terceiro **está coberto** por B-41. |
| `0007-pbft-primario-e-view.md` (3) | opção, regra de rotação, timeout de view change | O ADR está **Aceito** (opção 2); as 3 linhas são resíduo de gabarito. A parte viva está em B-39 e B-40. |
| `0008-auditabilidade-do-ator-na-aposentadoria.md` (10) | opções (a)(b)(c), nível de identificação por norma, prazo de guarda (`blockToLive`), análise jurídica | Domínio e privacidade. |
| `0011-setup-p2p-n-pequeno.md` (1) | limiar `n-1 ≤ d` da opção B | Opção **não escolhida** (o ADR está Aceito com A+D). Histórico. |
| `0009-escopo-de-protocolos-do-estudo.md` (13) | parte: ratificação do nome `leaderPolicy`; "ADR-0005, escolhas estatísticas" (onde registrar); DAGsper/T5; número do ADR-0000 | Ratificação do nome: absorvida em B-39 (nome não afeta valor). Resto: B-65 (governança) e B-46 (células). Nada ficou sem destino, mas a linha 117 (onde registrar as escolhas estatísticas do ADR-0000) só tem destino parcial em B-65 e B-64. |
| `dominio-redd.md` (7) | REJEITADO ocupa o período; unidade do inteiro; verificado × estimado; aposentar fração; ajuste correspondente; limiares de requisito | Domínio. O último está em B-19 (ADR-0005). |
| `especificacoes/protocolo-experimental-index.md` (18) | repetições de PI-xx, RM-xx e ADR-0013 | Todas mapeadas em B-01 a B-66; não listadas uma a uma. |
| `auditoria-protocolo-experimental.md` (7) | warmup (B-29), mapeamento do fator A (B-45), RNG dedicado (B-11), grandeza do perfil no PBFT (resolvida pelo ADR-0013), sazonal (B-10), janela do índice de dispersão (B-09) | Todas mapeadas. |

**Lacunas L1 a L21 (auditoria E).** Cobertas por parâmetro: L2→B-34/B-35; L3→B-35; L4→B-37/B-38; L5→B-36; L6→B-33; L8→B-47; L9→A-01 (Seção A); L10/L11→B-41/B-45; L12/L13→B-10/B-07; L14→resolvida pelo ADR-0013 (grandeza = λ); L16→B-29; L18→B-20; L19→B-42. **Sem parâmetro a decidir** (são tarefas do loop 2, não decisões de valor): L1 (a validação de tipo de ataque já está no código: `ByzantineConfig.validateAttackName`), L7, L15, L17, L20, L21. **Observação:** o parser de `ByzantineSweep.resolveAttack` ainda cai em `EQUIVOCATION` quando `--attack` falta; é padrão silencioso do ataque (anotado em B.5 como contexto, e coberto por Q28/B-34).

### B.11 Resumo da Seção B

| Contagem | Valor | Linhas |
|---|---|---|
| Parâmetros listados (B) | **66** | B-01 a B-66 |
| **Padrão silencioso** | 6 | B-21, B-30, B-32, B-41, B-62, B-63 |
| **Hardcoded** | 16 | B-12, B-20, B-26, B-33, B-34, B-37, B-39, B-44, B-47, B-51, B-54, B-55, B-56, B-58, B-59, B-60 |
| Silencioso ou hardcoded (soma) | **22** | |
| Obrigatório sem padrão (falha alta) | 1 | B-28 (`--load-record-t0`) |
| **Ausente** (nenhum código o lê ou ainda não existe) | 43 | demais |
| Mensurável pela **ponte** (SIM) | 4 | B-58, B-59, B-62, B-63 (todos da aplicação, ADR-0014) |
| Mensurável pela ponte **em parte** (PARCIAL) | 4 | B-03 (mistura de operações), B-50 e B-51 (ocupação e tempo de corte do bloco no orderer), B-61 (tamanho por entrada do histórico) |
| Mensurável por **piloto do simulador** (não é ponte) | 7 | B-09, B-22, B-28, B-29, B-30, B-32, B-42 |
| Só escolhidos | 51 | demais |
| Impacto ALTO / MÉDIO / BAIXO | 43 / 16 / 7 | |

Observações: (i) a ponte **não mede demanda real** (λ_B, N_op, κ, η): a rede de desenvolvimento não tem operadores. Ela mede tamanho de envelope e de bloco, ocupação do orderer e tamanho de resposta; (ii) as contagens vêm de uma passagem automática sobre as colunas "Estado no código", "Medido / Escolhido" e "Impacto" das linhas B-01 a B-66, conferida por leitura; (iii) onze dos 22 silenciosos/hardcoded **determinam o que é comparado entre protocolos** (B-12, B-20, B-21, B-33, B-34, B-37, B-39, B-41, B-44, B-51, B-55): o risco é resultado inválido sem erro; (iv) nenhum valor foi decidido. B-66 não segue a ordem numérica por ter sido acrescentado ao fechar a B.7.

## Seção C: CONSOLIDAÇÃO A + B

### C.1 Contagem consolidada (A + B)

| Contagem | A | B | A + B |
|---|---|---|---|
| Parâmetros | 27 | 66 | **93** |
| Padrão silencioso | 3 | 6 | 9 |
| Hardcoded | 4 | 16 | 20 |
| Silencioso ou hardcoded | 7 | 22 | **29** |
| Obrigatório sem padrão | 17 | 1 | 18 |
| Ausente | 3 | 43 | 46 |
| Ponte SIM | 2 | 4 | 6 |
| Ponte PARCIAL | 5 | 4 | 9 |
| Piloto do simulador | (Q12: 4 timeouts, ver A.6) | 7 | 7 a 11 |

### C.2 Tabela-resumo por decisão, ordenada por impacto e por quantos itens destrava

Cada linha é uma pergunta fechada (Q1 a Q12 na Seção A.6; Q13 a Q40 na Seção B.12). "Itens que dependem" = linhas que citam algum item da decisão (ou a própria Q) na coluna *Dependências*; contagem automática, mais um ajuste manual declarado abaixo. Ordenação: impacto máximo do grupo, depois nº de dependentes, depois nº de itens ALTO.

| # | Pergunta | Itens decididos | Impacto máx. | Nº ALTO | Itens que dependem (coluna Dependências) |
|---|---|---|---|---|---|
| 1 | Q36 Forma da prova de não regressão (N1) | B-66 | ALTO | 1 | 8: B-22, B-23, B-24, B-27, B-41, B-44, B-50, B-51 |
| 2 | Q15 P, W_janela e compressão temporal (duração) | B-04, B-06, B-32 | ALTO | 3 | 7: B-02, B-05, B-07, B-08, B-28, B-29, B-42 |
| 3 | Q4 s_tx (A) | A-13, A-14 | ALTO | 2 | 6: A-23, A-25, B-03, B-47, B-50, B-54 |
| 4 | Q12 Método das bases de timeout (A) | A-16, A-18, A-20, A-24 | ALTO | 4 | 5: A-09, A-17, A-19, A-21, B-51 |
| 5 | Q24 Regra de fechamento (L, T) | B-50, B-51, B-55, B-56 | ALTO | 3 | 5: B-23, B-47, B-49, B-52, B-54 |
| 6 | Q13 Gerador único, MMPP-2 e RNG próprio | B-01, B-11, B-57 | ALTO | 2 | 4: B-09, B-10, B-38, B-53 |
| 7 | Q25 Janela e transiente (t0 único) | B-28, B-29 | ALTO | 2 | 4: B-04, B-30, B-32, B-64 |
| 8 | Q33 n do E2, validação, piloto, células | B-42, B-43, B-46, B-47 | ALTO | 2 | 4: B-16, B-33, B-37, B-53 |
| 9 | Q30 Universo e RNG do sorteio bizantino | B-37, B-38 | ALTO | 1 | 4: B-11, B-33, B-41, B-44 |
| 10 | Q1 Esquema de assinatura (A) | A-01, A-02, A-04, A-05, A-06 | ALTO | 2 | 3: A-08, A-11, A-27 |
| 11 | Q7 Lote vs configtx (A) | A-23, A-25 | ALTO | 2 | 3: A-13, A-16, B-50 |
| 12 | Q14 Fonte de λ_B e da faixa de N_op, κ, η | B-02, B-03, B-05, B-07 | ALTO | 2 | 3: B-06, B-08, B-22 |
| 13 | Q21 Regra da escada de saturação | B-22 | ALTO | 1 | 3: B-17, B-23, B-49 |
| 14 | Q22 Critério de saturação | B-23 | ALTO | 1 | 3: B-17, B-22, B-49 |
| 15 | Q23 U de bloco, corte de C_nó, autopacote | B-24, B-25, B-26 | ALTO | 1 | 3: B-15, B-31, B-39 |
| 16 | Q26 r (sementes) e precisão-alvo | B-30 | ALTO | 1 | 3: B-42, B-46, B-64 |
| 17 | Q19 Estatuto dos limiares (descritivo) e CV1-CV3 | B-14, B-15, B-16, B-18, B-19 | ALTO | 3 | 2: B-12, B-24 |
| 18 | Q28 Ataque EQUIVOCATION (partição, fases, coordenação) | B-34, B-35, B-36 | ALTO | 2 | 2: B-20, B-40 |
| 19 | Q8 Líder do HotStuff (A) | A-09 | ALTO | 1 | 2: A-24, B-39 |
| 20 | Q32 Semântica de n, validadores e correspondência PoSA/PoS | B-41, B-44, B-45 | ALTO | 3 | 1: B-43 |
| 21 | Q17 Banda uniforme e níveis de W_nó | B-12, B-13 | ALTO | 2 | 1: B-15 |
| 22 | Q31 Líder do pBFT e view change | B-39, B-40 | ALTO | 2 | 1: B-24 |
| 23 | Q10 Modo de assinatura e votantes do QC (A) | A-05, A-06, A-07, A-08 | ALTO | 1 | 1: A-04 |
| 24 | Q18 Qual V1 vale | B-17 | ALTO | 1 | 1: B-22 |
| 25 | Q34 Níveis de φ e arredondamento | B-33 | ALTO | 1 | 1: B-43 |
| 26 | Q38 Teto do histórico e limite gRPC | B-61, B-62, B-63 | ALTO | 1 | 1: B-58 |
| 27 | Q20 Limiar de segurança e `--max-pdv-pct` | B-20, B-21 | ALTO | 2 | 0 |
| 28 | Q29 Fila: política, capacidade, relógio, nós | B-48, B-49, B-52, B-53 | ALTO | 2 | 0 |
| 29 | Q2 pBFT com assinatura no voto (A) | A-11 | ALTO | 1 | 0 |
| 30 | Q6 timeout_commit (A) | A-22 | ALTO | 1 | 0 |
| 31 | Q35 Gás do Ethereum vs lote | B-54 | ALTO | 1 | 0 |
| 32 | Q39 Dependências estatísticas | B-64 | ALTO | 1 | 0 |
| 33 | Q40 Governança do ADR-0000 | B-65 | ALTO | 1 | 0 |
| 34 | Q37 PAGE_SIZE_MAXIMO (valor, único, fixo) | B-58, B-59, B-60 | MÉDIO | 0 | 3: B-61, B-62, B-63 |
| 35 | Q16 Tolerância e janela do índice de dispersão | B-09 | MÉDIO | 0 | 1: B-01 |
| 36 | Q5 Deltas dos timeouts (A) | A-17, A-19, A-21 | MÉDIO | 0 | 0 |
| 37 | Q9 POL na proposta (A) | A-27 | MÉDIO | 0 | 0 |
| 38 | Q3 Overhead de mensagem (A) | A-03, A-10, A-12 | BAIXO | 0 | 1: A-01 |
| 39 | Q11 Janela de poda (A) | A-26 | BAIXO | 0 | 0 |
| 40 | Q27 Formato de saída e dono de nao_regressao.py | B-27 | BAIXO | 0 | 0 |

**Ajuste manual declarado.** Q36 ficou em 1º pela dependência explícita em B-22, B-23, B-24, B-27, B-41, B-44, B-50, B-51 (inclui o ADR-0012 e o ADR-0013, que citam a N1 como pré-requisito). Q32 aparece só com 1 dependente na contagem automática, mas é o pré-requisito do **fator N inteiro do E1 (240 configurações)** e de B-37, B-43, B-44: na ordem e nos "5 que mais destravam" ele sobe por julgamento, não pela contagem.

### C.3 Ordem proposta de decisão (respeita as dependências)

Fase 0, sem dependência, destrava as demais e a prova de não regressão:
1. **Q40** governança do ADR-0000 (aceite, número, correção dos YAML). Condição de P8.
2. **Q36** forma da prova de não regressão e veredito da N1.
3. **Q32** semântica de n, validadores e correspondência PoSA/PoS.
4. **Q39** dependências estatísticas (não depende de nada, mas o piloto precisa).

Fase 1, instrumento (Seção A), em paralelo com a fase 0 após Q36:
5. **Q1** (esquema de assinatura) → **Q2, Q10** (pBFT com assinatura; modo do QC).
6. **Q4** (`s_tx`), **Q3** (overhead), **Q8/Q31** (política de líder, juntas), **Q5, Q6, Q9, Q11**.

Fase 2, estrutura da demanda:
7. **Q13** (gerador único, MMPP-2, RNG) → **Q15** (compressão temporal: P, W_janela, duração) → **Q14** (fonte de λ_B, faixa de η).
8. **Q17** (banda uniforme e níveis de W_nó) e **Q19** (estatuto dos limiares, CV1-CV3), depois **Q18** (qual V1).

Fase 3, formação de bloco e medição (dependem de 1 a 8 e do piloto):
9. **Q7 e Q24** (lote e tempo, **juntas**, mesmo parâmetro de A-23/A-25), **Q35** (gás), **Q29** (fila).
10. **Q23** (U, corte de C_nó, autopacote), **Q25** (t0 único e transiente), **Q21 e Q22** (escada e critério de saturação), **Q16**, **Q27**.
11. **Piloto** (ver C.4) → **Q12** (bases dos timeouts), **Q26** (r), **Q33** (n do E2, validação, células).

Fase 4, robustez bizantina (depende de Q32, Q30, Q31):
12. **Q30** (universo do sorteio) → **Q28** (ataque) → **Q34** (níveis de φ e arredondamento) → **Q20** (limiar de segurança).

Trilha da aplicação, **independente** de todas as fases acima e executável em paralelo:
13. **Q37** e **Q38** (paginação e limites gRPC), depois da medição da ponte.

### C.4 O que se decide por MEDIÇÃO e o que se decide por ESCOLHA

**Por medição na aplicação (agente `redd-ponte`; fabric-rede para a leitura de `core.yaml`):**

| Item | O que medir | Ressalva |
|---|---|---|
| A-13, A-14 | tamanho de envelope por operação com mais identidades e 3 endossos | já medido uma vez (Seção A, Ressalva M); a medição ampliada ainda falta |
| A-01, A-02, A-04 | tamanho da assinatura X.509 do Fabric dentro do envelope | só se Q1 = "modela o Fabric" |
| A-23, A-25, B-50, B-51 | ocupação real de bloco e tempo de corte no orderer sob carga | é Raft (ADR-0004); informa L e T como referência, não os decide |
| B-03 | mistura de operações na carga | a ponte mede o tamanho; a proporção de κ é escolha |
| B-58, B-59, B-61, B-62, B-63 | tamanho em bytes da resposta por `pageSize` e por histórico; teste de resposta > 4 MiB; `totalQueryLimit` da imagem | ADR-0014, Verificação |

**Por medição no simulador (piloto, `jabs-experimento`; não é ponte):** B-09 (janela do índice de dispersão), B-22 (teto da escada), B-23 (constantes do critério), B-28 e B-29 (Welch: `t0` único), B-30 (r), B-32 (duração), B-42 e B-46 (custo por célula), A-16/A-18/A-20/A-24 (bases dos timeouts, regra do múltiplo declarado, Q12).

**Só por ESCOLHA (com fonte do humano, ou como faixa de sensibilidade):** B-01, B-02, B-04, B-05, B-06, B-07, B-08 (derivado), B-11 a B-21, B-24 a B-27, B-31, B-33 a B-41, B-43 a B-49, B-52 a B-57, B-60, B-64 a B-66, e os A-xx sem medição (A-03, A-05 a A-12, A-15, A-17, A-19, A-21, A-22, A-26, A-27). B-10 e B-19 estão encerrados.

### C.5 As 5 decisões que mais destravam

1. **Q36, forma da prova de não regressão (N1).** Bloqueia a fila de pendentes, o registro de Θ/U/C_nó (tarefas B do ADR-0012), a conversão de n e o MMPP-2: nada que mexa no simulador pode ser provado sem ela. Destrava 8 itens e, indiretamente, as fases 2 e 3.
2. **Q32, semântica de n.** Com `legacy` (padrão silencioso) o mesmo "n" é nº de nós diferente por protocolo, e o fator N do E1 inteiro (240 configurações) fica não comparável. Decide também quem é validador em PoSA/PoS e o par PoSA/PoS ↔ Parlia/Casper.
3. **Q15, compressão temporal (P, W_janela, duração).** Sem ela, o MMPP-2 é inobservável em corridas de 7200 s; destrava B-02, B-05, B-07, B-08, B-28, B-29, B-42 e define o custo da bateria.
4. **Q4, `s_tx` (junto com Q1, a assinatura).** Define o tamanho do bloco no pBFT, HotStuff e Tendermint, o lote e a escala de tráfego (~10x entre 512 B e o envelope medido). Destrava A-23, A-25, B-03, B-47, B-50, B-54.
5. **Q24 (com Q7), regra de fechamento (L, T).** Faz a demanda entrar no consenso; sem ela, ε_λ do tráfego sai ≈ 0 por construção e Θ não existe. Destrava B-23, B-47, B-49, B-52, B-54 e a escada de saturação.

Próximos da lista: Q12 (4 timeouts; depende do piloto), Q13 (gerador único e RNG próprio), Q25 (t0 único), Q33 (n do E2 e do piloto), Q30 (universo bizantino).

### B.12 Perguntas fechadas Q13 em diante

Formato: A / B / C / outra. **Recomendação** em negrito. "Outra" é sempre admitida. Nenhuma opção contém valor numérico escolhido por mim; números que aparecem são fatos de fonte, rotulados.

**Q13. Gerador único de tx, regime MMPP-2 e RNG próprio (B-01, B-11, B-57).**
- A) Aceitar a conversão MMPP-2 do PI-10 como regime 2, RNG dedicado do gerador, Poisson implementado primeiro e MMPP-2 depois;
- B) Aceitar o MMPP-2, mas com o RNG compartilhado do cenário (como hoje);
- C) Rejeitar o MMPP-2 e adotar outro regime de mesma média (por exemplo determinístico periódico);
- outra.
**Recomendação: A.** Razão: o ADR-0013 já pede o RNG próprio (pareamento por semente) e o MMPP-2 tem a álgebra conferida (média exata); o limite exponencial dos estados entra no pré-registro. C só se o humano considerar o prazo determinístico essencial.

**Q14. Fonte de λ_B e da faixa de N_op, κ, η (B-02, B-03, B-05, B-07).**
- A) Valor de λ_B e faixa de η vindos de dado de registro real, com fonte trazida pelo humano; N_op e κ ficam como decomposição documental;
- B) Derivar de transações do contrato medidas pela ponte (a ponte mede forma e tamanho, **não** demanda);
- C) Faixa de sensibilidade sem ponto central, com o resultado declarado condicional;
- outra.
**Recomendação: C**, trocando por A se houver fonte. Razão: não há dado de registro no repositório (PI-01); B não é possível como demanda.

**Q15. Escala de tempo de P, W_janela e duração de simulação (B-04, B-06, B-32).**
- A) **Compressão temporal declarada**: P e W_janela em escala de simulação, preservando a razão W_janela/P e η (não o calendário), com `duracao_sim_s` cobrindo vários períodos;
- B) Calendário real e corridas muito mais longas (custo `NAO_MEDIDO`);
- C) Abrir mão do MMPP-2 e do ciclo (só Poisson);
- outra.
**Recomendação: A.** Razão: o fato calculado é que 7200 s não cobre um período se P for calendário (ADR-0005: prazos regulatórios na ordem de meses). Quantos períodos por corrida: `a definir`, resolvido no piloto.

**Q16. Tolerância da igualdade de média e janela do índice de dispersão (B-09).**
- A) Derivar do erro-padrão amostral da contagem por semente;
- B) Número fixo no pré-registro;
- C) Conferir só a esperança pela fórmula e pela contagem determinística de uma semente;
- outra.
**Recomendação: A.** Razão: um limite fixo sem relação com a variância reprova ou aprova por acaso.

**Q17. Banda por nó uniforme e níveis de W_nó (B-12, B-13).**
- A) Banda uniforme por nó entra **antes** da bateria (tarefa do loop 2) e W_nó tem dois níveis com fonte;
- B) Manter a LAN de 100 Mbps constante e comparar U/Θ só entre os protocolos que a usam;
- C) W_nó como fator só nos BFT e PoSA/PoS ficam sem U e Θ;
- outra.
**Recomendação: A** (ADR-0012, item 7: sem isso U e Θ não se comparam entre protocolos). Valores e fonte de W_nó: `a definir com o humano`.

**Q18. Qual V1 vale (B-17).**
- A) `Θ ≥ λ_pico` (ADR-0000);
- B) `Q(kP) ≤ ε_Q` (artigo, `.tex`);
- C) Os dois, com nomes distintos (CV1a e CV1b);
- outra.
**Recomendação: C.** Razão: as fontes divergem e o `.tex` diz que Q(kP) é menos restritivo; reportar os dois evita escolher o mais favorável. CV1b só depende da fila implementada e de Q15.

**Q19. Estatuto dos limiares L_max, U_max, MAPE e nomes CV1 a CV3 (B-14, B-15, B-16, B-18, B-19).**
- A) Todos **descritivos**, com faixa de sensibilidade congelada no pré-registro e nomes CV1 a CV3;
- B) Reabrir o ADR-0005 para limiares normativos com fonte;
- C) A para L_max e U_max e valor único de MAPE confirmado e pré-registrado;
- outra.
**Recomendação: A.** Razão: coerente com ADR-0005 (Aceito, opção 3) e com a redação de P7 já aceita. O "MAPE < 10 %" do ADR-0000 é valor proposto na fonte, divergente da lista do próprio ADR; o 0,7 de U_max é exemplo do `.tex`: nenhum dos dois foi adotado.

**Q20. Limiar de segurança por consenso e `--max-pdv-pct` (B-20, B-21).**
- A) Eliminar o veredito "seguro/inseguro" e reportar a curva Pdv(φ) com limiar só descritivo;
- B) Manter o veredito, com `--max-pdv-pct` obrigatório e declarado;
- C) Unificar o limiar de segurança entre os ramos (L18) e declarar sua origem;
- outra.
**Recomendação: A**, e C se o veredito for mantido. Razão: o 0,5 % padrão e os limiares 1/3, 1/2 inconsistentes entre ramos não têm fonte.

**Q21. Escada de saturação (B-22).**
- A) Geométrica a partir de λ_B, parando no primeiro degrau saturado;
- B) Linear, passo fixo;
- C) Adaptativa: geométrica até saturar, bissecção em torno da fronteira;
- outra.
**Recomendação: A.** Razão: parar ao saturar economiza corridas; razão, passo e teto `a definir` no piloto. C só se Θ precisar de precisão.

**Q22. Critério de "cresce sem limite" (B-23).**
- A) (i) inclinação positiva de Q(t) na segunda metade da janela, com teste estatístico;
- B) (ii) Q(t1) acima de um múltiplo de L;
- C) (iii) platô de ConfRate entre degraus;
- outra.
**Recomendação: A** como critério primário e C como conferência. Razão: B depende de L e C não olha a fila. Atenção: sob MMPP-2 a fila oscila na rajada; por isso Θ se mede só em regime Poisson (RM-07).

**Q23. U de corrida ou de bloco, corte de C_nó e pacote para si (B-24, B-25, B-26).**
- A) V3 sobre U_bloco;
- B) V3 sobre U_corrida;
- C) Registrar as duas, V3 sobre U_bloco; C_nó no instantâneo da primeira finalização (como o C_B); o pacote do PBFT para si conta, com coluna própria;
- outra.
**Recomendação: C** (ADR-0012, RM-02 e RM-04). Razão: com líder rotativo U_corrida dilui o pico do líder, que é a hipótese a testar. Vale revisar se Q31 mudar a política de líder.

**Q24. Regra de fechamento de bloco: L e T (B-50, B-51, B-55, B-56). Mesmo parâmetro de A-23/A-25 e Q7.**
- A) Espelhar o `BatchSize`/`BatchTimeout` do `configtx.yaml` do Fabric (referência de configuração, [FONTE] lida; não medição), nos cinco protocolos;
- B) L derivado de `tamanho_bloco_kb·1024/s_tx` (2048 com 512 B, ADR-0013, a conferir) e T independente;
- C) Varrer (L, T) no piloto como fator de sensibilidade e congelar depois;
- outra.
**Recomendação: A**, coerente com a recomendação da Q7. Risco declarado: o lote espelhado é duas ordens de grandeza menor que o lote do HotStuff atual [C: 10 × 2048], então ele satura em demanda baixa; por isso o piloto deve cobrir C. A cadência fixa de 5,0 s do pBFT é descartada; `tamanho_bloco_kb` sai do pré-registro.

**Q25. Janela de medição e transiente (B-28, B-29).**
- A) `t0` **único** para todos os protocolos, igual ao maior corte entre eles (Welch no piloto), com duração mínima declarada;
- B) `t0` por protocolo;
- C) `t0` por convenção (fração da duração);
- outra.
**Recomendação: A** (PROTOCOLO, Etapa A.1: cortes distintos reintroduzem o viés). Exige log bruto sem descarte (L16).

**Q26. Número de sementes e precisão-alvo (B-30).**
- A) r do dimensionamento do piloto (máximo entre as métricas críticas, sobre dados sem transiente), com precisão-alvo escolhida pelo humano;
- B) Manter o provisório dos YAML como definitivo;
- C) Replicação sequencial até atingir a precisão;
- outra.
**Recomendação: A.** O PROTOCOLO adverte que cortar r invalida as comparações por IC e que r > 30 sugere erro de análise. `--trials` e `--seed` devem ser obrigatórios na bateria (hoje têm padrões distintos nos dois `main`).

**Q27. Formato de saída e dono da extensão de `nao_regressao.py` (B-27).**
- A) Confirmar o desenho recomendado do ADR-0012 (colunas 15-16 no `-t0.csv`, mais `-corrida.csv` e `-nos.csv`);
- B) Só arquivos novos, sem coluna nova no `-t0.csv`;
- C) Só colunas;
- outra.
**Recomendação: A.** Quem estende `scripts/jabs/nao_regressao.py`: o humano indica (não verifiquei qual agente tem permissão de escrita em `scripts/`).

**Q28. Ataque EQUIVOCATION: partição, fases e coordenação (B-34, B-35, B-36).**
- A) Partição determinística em duas metades por índice dos vizinhos (como o PBFT faz hoje), em **todas** as fases de voto e na proposta, sem coordenação entre bizantinos;
- B) Partição aleatória por semente;
- C) A mais coordenação (mesmo par de valores conflitantes);
- outra.
**Recomendação: A** na primeira bateria, e decidir à parte se a exigência de coordenação do PROTOCOLO §3 é mantida (então, nova tarefa do loop 2) ou removida do pré-registro. Razão: A é o que o código já faz e é fácil de replicar nos outros protocolos.

**Q29. Fila de pendentes: política, capacidade, relógio de T e nós (B-48, B-49, B-52, B-53).**
- A) FIFO; capacidade sem limite, com alarme medido; relógio de T desde a chegada da primeira tx pendente; fila nos proponentes com **difusão** da tx contada no tráfego;
- B) FIFO; capacidade finita com descarte **contado**; fila em todos os nós;
- C) Fila global única, sem difusão;
- outra.
**Recomendação: A.** Razão: capacidade finita mascara a saturação (fila cheia ≠ fila crescendo), e sem difusão o tráfego de tx desaparece da medição. "Sem descarte silencioso" vale em qualquer opção.

**Q30. Universo e RNG do sorteio de bizantinos (B-37, B-38).**
- A) Só validadores/votantes, RNG dedicado, aninhado entre valores de f;
- B) Todos os nós, RNG dedicado;
- C) Manter o universo por protocolo e declarar;
- outra.
**Recomendação: A.** Razão: só assim φ vale o mesmo entre protocolos. Depende da definição de "validador" em Q32.

**Q31. Política de líder do pBFT e view change (B-39, B-40).**
- A) Líder estável **sem** view change (mantém a decisão B8: opção 2 do ADR-0007), declarando que o E2 não mede falha de primário;
- B) Líder estável e abre a opção 3 do ADR-0007 (view change, timeout `a definir`);
- C) Manter o primário rotativo atual e o estável como ablação;
- outra.
**Recomendação: A**, por consistência com B8 e com a recomendação da Q8 (estável como caso base). Consequência a aceitar: EQUIVOCATION do primário só atua se o primário for sorteado bizantino, e sem view change o protocolo não se recupera; se o humano quiser medir isso, é B.

**Q32. Semântica de n, validadores e correspondência PoSA/PoS (B-41, B-44, B-45).**
- A) `--n-semantics=nodes` como semântica do estudo, flag **obrigatória** na bateria, `legacy` só em não regressão; "validador" declarado por protocolo (Casper: todos votam; Parlia: só mineradores);
- B) Manter `legacy` e publicar a tabela de nós reais por protocolo e n;
- C) `nodes` com outra decomposição que a 30/70 herdada;
- outra.
Sub-decisão: PoSA = Parlia e PoS = CasperFFG (índice 6.1: "provavelmente", a confirmar) e qual variante (MCO2 ou TREE).
**Recomendação: A.** Razão: o ADR-0000 define n como nº de validadores; o `legacy` entrega n = 3 nós em n = 4. A decomposição 30/70 não tem fonte (está marcada no próprio código). Verificar a finalização com poucos stakeholders (PI-19, não verificada).

**Q33. n do E2, validação, piloto e contagem de células (B-42, B-43, B-46, B-47).**
- A) Decidir **depois** de um piloto de custo (uma célula por protocolo em alguns n), com a lista do que a validação cobre;
- B) Decidir agora o n do E2 em um dos níveis do E1 (16 ou 64; o centro em escala log de 4 a 128 é √512 ≈ 22,6 [C], que não é nível);
- C) Reaproveitar o `piloto.yaml` (n = 25, 100, 400; fora da faixa do ADR-0000);
- outra.
**Recomendação: A.** Razão: o custo de n = 800 é `NAO_MEDIDO` e o PBFT cresce com n² pacotes por fase [C, estrutural]. Declarar no pré-registro apenas topologia e tamanhos que o código de fato aplica (B-47).

**Q34. Níveis de φ e arredondamento para contagem (B-33).**
- A) φ ∈ {0; 0,10; 0,30} (ADR-0000), f = teto de φ vezes o universo em aritmética inteira, igual em todos os protocolos;
- B) Arredondar ao inteiro mais próximo;
- C) Manter a regra herdada por protocolo (hoje o POS usa `ceil(stakeholders·f/n)`);
- outra.
**Recomendação: A**, universo conforme Q30. Razão: arredondamentos diferentes mudam o f efetivo e quebram o pareamento (L4); o `ceil` em ponto flutuante não foi verificado.

**Q35. Gás do Ethereum contra o lote em Casper e Parlia (B-54).**
- A) Gás vira atributo constante da tx e L substitui o limite;
- B) Gás permanece como segundo limite (lote OU gás OU tempo);
- C) Remover o gás;
- outra.
**Recomendação: A**, por manter a regra de fechamento comum (ADR-0013 dec.2). B afasta a regra comum; C altera o protocolo.

**Q36. Forma da prova de não regressão e veredito da N1 (B-66).**
- A) JVM com `-XX:+UnlockExperimentalVMOptions -XX:hashCode=2` nos dois lados para Casper, Parlia e Nakamoto, depois de fechar o veredito da N1; confirmar o hash `325f4e3`;
- B) Corrigir a causa (`memPool` em `LinkedHashSet`): muda a saída do Casper, exige ADR e nova baseline;
- C) Declarar a não regressão desses três como PENDENTE e seguir;
- outra.
**Recomendação: A.** Razão: foi a única forma testada com sucesso (AMBIFY, MCO2, OFFSET, semente 1; `hashCode=3` ainda diverge). Limite: evidência de uma semente por caso; ampliar antes de generalizar. B só com ADR próprio.

**Q37. `PAGE_SIZE_MAXIMO` (B-58, B-59, B-60).**
- A) Manter 100 nas duas consultas, fixo no código (O1), até a medição da ponte;
- B) Outro valor derivado da medição de tamanho de item (O2);
- C) Separar as constantes por consulta (O5), depois da medição;
- outra.
**Recomendação: A hoje**, reavaliar com a medição. Razão: não há medição de resposta e o 100 está muito abaixo dos tetos de resultado (ADR-0014). Em qualquer caso, derivar os textos OpenAPI da constante (decisão de implementação).

**Q38. Teto do `historico` e limite de mensagem do gateway (B-61, B-62, B-63).**
- A) Limitar o histórico no contrato (teto, passa pelo loop 1);
- B) Não limitar e elevar `maxInboundMessageSize` do canal do gateway (O4), documentando;
- C) Medir primeiro (resposta > 4 MiB, tamanho por entrada, `totalQueryLimit` da imagem) e depois decidir A ou B;
- outra.
**Recomendação: C.** Razão: sem teto no histórico nenhuma medição fecha o pior caso, mas o valor do teto precisa do tamanho por entrada.

**Q39. Dependências estatísticas (B-64).**
- A) Autorizar `numpy`, `scipy` e `statsmodels` em versões fixas e registradas;
- B) Só biblioteca padrão, com testes e revisão por agente independente;
- C) Autorizar só `numpy` e `scipy`, o resto implementado;
- outra.
**Recomendação: A.** Razão: Box-Cox, HC3, ART e Games-Howell à mão são fonte de erro. Não verifiquei se há acesso a pacotes neste ambiente.

**Q40. Governança do ADR-0000 (B-65).**
- A) Aceite formal pelos coautores, número no repositório indicado pelo humano e autorização para corrigir os YAML de `.experimento/` (níveis, contagens, `sazonal`);
- B) Manter Proposto e pré-registrar assim, declarando o risco;
- C) Absorver o conteúdo em um ADR do repositório (novo número) e deixar o ADR-0000 como histórico;
- outra.
**Recomendação: A.** A citação da Lei 15.042/2024 (índice 6.7, item 9) segue **não verificada**.

### B.13 Estado desta seção

Nenhum valor foi decidido. Itens que **só** o humano pode fechar (ato, não medição): Q40, Q19, Q14 (a fonte de λ_B), Q17 (os valores de W_nó) e todo valor numérico de B-02 a B-08. As tarefas que cada decisão abriria no loop 2 (banda uniforme, gerador, fila, registro Θ/U, conversão de n, validação de ataque) **não foram abertas** por este ADR.



---
## Registro de decisão 09/11/2026 — Q28 (humano)

**Abrangência do EQUIVOCATION (B-35): mantida no Tendermint como proposta + prevote + precommit.** A divergência com o PBFT (que cobre PRE_PREPARE e COMMIT, mas não PREPARE; lacuna L3 da auditoria) passa a ser resolvida no PBFT, na tarefa `PBFT-prepare-equivocation` (loop 2). Efeitos: (i) a saída do PBFT **sob ataque** vai mudar (mudança intencional, a declarar no ADR-0006); sem ataque deve permanecer idêntica; (ii) exige nova não-regressão. **Continuam provisórios e em aberto:** partição (metades por índice, B-34) e coordenação entre bizantinos (B-36). Código: commit do EQUIVOCATION do Tendermint (D3b-tendermint-equivocation).


---
## Registro de decisão 10/11/2026 — `maxFutureViewHorizon` do HotStuff (humano)

**Decisão (opção 1): `maxFutureViewHorizon` de produção = 1000 visões, o mesmo número da janela de poda A-26 (1000 alturas em `Tendermint.java`, `PBFT.java`, `HotStuff.java`).** Diretriz do humano: o valor deve ser compatível com parâmetros de função semelhante no PBFT e no Tendermint.

Contexto do pedido: tarefa `D3a-2a-2c-3-hotstuff-troca-visao` (commit `45eb3e7`). O parâmetro é obrigatório e sem padrão em `HotStuffConsensusConfig`. A defesa descarta, contando em `FUTURE_VIEW_BEYOND_HORIZON`, a mensagem cuja visão excede a visão corrente da réplica em mais que o horizonte. A retenção fica limitada a `4·(H+1) − 1` entradas.

**Limites do que a opção 1 afirma (para não ler "compatível" como "equivalente"):**
1. **Não existe parâmetro equivalente no PBFT nem no Tendermint.** Verificado por leitura: o PBFT retém votos adiantados em `pendingPrepares`/`pendingCommits` sem teto; o Tendermint conta `futureMessages`/`peakFutureMessages` só para observação e declara "a memória não tem teto" (`TendermintCore`, Limites declarados). A única constante parecida é a poda A-26.
2. **A poda A-26 e este horizonte medem coisas diferentes.** A-26 libera blocos antigos (1000 alturas atrás). O horizonte limita mensagens à frente (1000 visões adiante). 1000 é uma convenção compartilhada, não uma derivação. Não há [FONTE] nem medição que o justifique além da escolha do humano. Classificação: ESCOLHIDO.
3. **Efeito sobre liveness:** réplica atrasada em mais de 1000 visões perde as propostas além do horizonte e, sem catch-up, os filhos falham em `UNKNOWN_PARENT` (limite declarado no javadoc de `ReplicaCore`). Em run curta (30 s, n=4 e n=7) o horizonte não é atingido: os testes e o `HotStuffCsvMain` já usam 1000 como fixture. Se algum cenário passar de 1000 visões, o valor passa a importar e deve ser revisto.
4. **Assimetria que permanece:** o HotStuff passa a ter teto de retenção e o PBFT/Tendermint, não. Um bizantino de visão/altura futura pode inflar a memória nos dois últimos. Para o objetivo de comparar protocolos isso é uma diferença de instrumento a declarar no pré-registro, ou a fechar com tarefa do loop 2 que dê o mesmo teto aos dois (**não aberta**).

**Efeitos:**
- Nenhum código muda agora. O valor 1000 entra na config explícita do estudo quando o HotStuff for ligado ao `MVPComparison` (2c-4); o raw de cada run deve registrar `maxFutureViewHorizon` junto de `viewTimeoutSeconds`.
- Linha nova da Seção A.4 (A-28): `maxFutureViewHorizon`, ESCOLHIDO, impacto BAIXO sem falha / MÉDIO sob líder bizantino, decidido nesta data.
- A-26 e este item passam a ser revisados juntos (Q11).

**Pendência registrada (humano, 10/11/2026): tarefa de paridade de teto no PBFT e no Tendermint.** Ao término da implementação do HotStuff (2c-4 e 2d concluídas), abrir tarefa do loop 2 (`PARIDADE-teto-retencao-pbft-tendermint`) que dê ao PBFT (`pendingPrepares`/`pendingCommits`) e ao Tendermint (`futureMessages`, alturas/rounds futuros) um teto de retenção equivalente ao `maxFutureViewHorizon` do HotStuff, com parâmetro obrigatório e sem padrão, valor 1000 conforme a decisão acima (revisar junto com A-26/Q11). Efeitos esperados: saída sem ataque e sem atraso deve permanecer idêntica (nova não-regressão, critério 7); saída sob atacante de retenção pode mudar e deve ser declarada no ADR-0006. Até lá, a assimetria segue declarada no pré-registro.


---
## Registro de decisão 10/11/2026 — Q30 e Q34: contagem bizantina e gerador de conjuntos (humano)

**O humano acatou as cinco recomendações do orquestrador.** Classificação: ESCOLHIDO (decisão de projeto, sem fonte externa).

| # | Decisão | Estado |
|---|---|---|
| 1 | **Entrada primária é o inteiro `f`.** Se o estudo precisar de φ (níveis do ADR-0000: 0; 0,10; 0,30), `f = ceil(φ·n)` em **aritmética inteira**, calculado uma vez e igual em todos os protocolos. Sem `double`. | Decidido. `--byzantine-count` do HOTSTUFF já segue isso. O legado ainda não (ver 2). |
| 2 | **Corrigir o `ceil` em ponto flutuante do `ByzantineConfig` legado**, em tarefa própria `BYZ-contagem-inteira-legado`. | Decidido; tarefa **não iniciada**. |
| 3 | **`f > ⌊(n−1)/3⌋` é declarado "fora da faixa"**, nunca arredondado para baixo em silêncio. Ex.: n=4 com φ=0,30 dá f=2 > 1. | Decidido. Ainda sem código: hoje o HOTSTUFF aceita `f <= n`. |
| 4 | **Gerador único de conjuntos** `(semente, universo, f) -> conjunto`, aninhado entre valores de f, usado por todos os protocolos. | Decidido; tarefa **não iniciada**. |
| 5 | **Universo do sorteio = só validadores/votantes** (Q30, opção A). | Decidido, **condicionado à Q32** (o que é "validador" em PoSA e PoS), ainda em aberto. |

**Evidência do defeito do legado (decisão 2).** Calculado em 10/11/2026: o laço de `ByzantineConfig` faz `ceil(n * (pct/100.0))` com `pct = 100·f/n` (como o `ByzantineSweep`) e devolve `f+1` em **1207 pares (n, f)** com n = 4..200 e f <= n/2 (n=7, f=1 -> 2; n=7, f=2 -> 3; n=14, f=1 -> 2; n=9, f=3 -> 4). Não é só regra de arredondamento de fração: o `ByzantineSweep` que pede f=1 em n=7 coloca 2 bizantinos no ARQ. Isso precede o commit `8c68084` e não foi introduzido por ele.

**Ordem e custo.** As decisões 2 e 4 mudam a saída de ARQ, Parlia e Casper **sob ataque** (sem ataque nada muda). Proposta de sequência, a confirmar pelo humano: executar depois de `V-ABC-hotstuff` e de `PARIDADE-teto-retencao-pbft-tendermint`, e **numa só tarefa de código com uma só não-regressão**, para não refazer a prova duas vezes. Se o humano preferir a decisão 2 isolada e antes, ela segue valendo como tarefa própria.

**Efeitos exigidos pela tarefa quando for executada:** (i) mudança intencional declarada no ADR-0006; (ii) nova não-regressão sem ataque (deve ser IDENTICA) e sob ataque (diferença esperada, documentada, com a contagem antes e depois); (iii) o `ByzantineSweep` passa a variar `f` inteiro direto, sem a ida e volta por percentual; (iv) decisão 3 vira falha alta ou rótulo explícito no CSV, não arredondamento.

**Enquanto isso (declarar no pré-registro):** o HOTSTUFF sorteia por `RandomnessEngine` dedicado (`semente ^ sal`, `ByzantineConfig.withExactCount`), e para a mesma semente o conjunto difere do de ARQ, Parlia e Casper. Comparações entre protocolos sob ataque valem para o **mesmo f, não para o mesmo conjunto**. Os valores de f efetivos do legado (com o defeito acima) devem ser lidos do CSV, não do rótulo.

**Fecha:** B-33 (parcial: arredondamento), B-37/B-38 (parcial: universo condicionado à Q32). **Q34 fica decidida; Q30 decidida exceto o universo, que depende da Q32.**


---
## Registro de decisão 10/11/2026 — Cb comparável, autoentrega, n e `--hs-*`, fixture (humano)

**O humano acatou as recomendações do orquestrador e a sequência.** Classificação: ESCOLHIDO. Origem: diagnóstico HS-CB (fase 1), sem código, relatório em `/tmp/opencode/diag` (efêmero; números abaixo são os que ficam).

**Achado do diagnóstico.** `Cb_avg` (n=4, semente efetiva 4001): HotStuff 847,4 B, Tendermint 7251,0 B (razão 8,6). A diferença é **artefato do ponto de medição**, não propriedade do protocolo nem bug: o `Cb` mede os bytes recebidos pelo primeiro nó que finaliza cada altura; no HotStuff é sempre o líder (não recebe a carga do bloco), no Tendermint é o líder em ~35% das alturas (n=4) e uma réplica nas demais (distribuição bimodal: 530 B e 10892 B). Extrator independente reproduziu 3469 alturas com 0 divergências; medido = analítico. Soma da rede por bloco: HotStuff 35703 B, Tendermint 33629 B (n=4; razão 0,94); 75246 e 71073 B (n=7). Fixtures: lote 20 e tx 512 B nos dois. Uma execução por célula, caminho feliz; Java 25.

| # | Decisão | Estado |
|---|---|---|
| D1 | **Cb comparável = soma da rede por bloco, sem corte local**, como medida principal. O `Cb` do primeiro nó a finalizar fica como coluna secundária **rotulada com a granularidade**. Reportar mediana e percentis além da média (a média do Tendermint mistura dois grupos). Declarar a granularidade no cabeçalho do `mvp-results.csv` por linha `#` (o `nao_regressao.py` ignora `#`). **Revisar a Q23**: registrar o `C_nó` na primeira finalização herdaria o mesmo viés no HotStuff. | Decidido; HS-CB fase 2. |
| D2 | **Autoentrega fora das métricas principais, em todos os protocolos; coluna própria nos que hoje a contam** (PBFT, e segundo ADR-0006 Casper e Nakamoto: **não reverificado no código**). Base: regra 6 do AGENTS.md (só pacote que passou pela rede simulada) e Q23/B-26. | Decidido; tarefa **não iniciada**; muda a saída desses protocolos (ADR-0006 e nova não-regressão). |
| D3 | **n e `--hs-*` conforme recomendados, com dois ajustes.** (a) V-B varre f de 0 até ao menos `⌊(n−1)/3⌋+1`; logo "fora da faixa" (decisão 3 de 10/11, Q34) vira **modo explícito, rotulado, com flag própria**, não falha alta incondicional: ninguém cai nele por engano e o V-B o usa de propósito. (b) **`STABLE` precisa de execução com ataque e com timeout antes dos portões** (todos os testes de troca de visão usaram `ROTATING`). | Decidido. |
| D4 | **Fixture canônica única** de lote e `tx-size` nos dois protocolos, em um só lugar compartilhado por testes e scripts; corrigir o README da 2d (`--tm-batch-size=10` com `--hs-batch-size=20`); **trava**: o `MVPComparison` avisa ou falha quando HOTSTUFF e TENDERMINT rodam juntos com `lote × tx-size` diferentes. | Decidido; HS-CB fase 2. |

**Níveis de n decididos (para V-A, V-B, V-C; não são os níveis do E1).**
- V-A (ordem de complexidade): n ∈ {4, 7, 10, 16, 31}.
- V-B (limiar bizantino): n ∈ {4, 7, 10, 13}, f de 0 a `⌊(n−1)/3⌋+1`.
- V-C (confronto com publicados): n = 4 e n = 16 em LAN; se não houver número comparável, declarar inviabilidade (a bar aceita).
- **Não usar n = 64, 128, 800 antes de piloto de custo (Q33-A).** n=4 não é comparável entre protocolos enquanto a semântica de n estiver em `legacy` (B-41); para o HotStuff, n é o número de validadores. Os níveis do E1 (ADR-0000: 4, 16, 64, 128) não mudam.

**`--hs-*` decididos como MÉTODO, não como número.**
- `--hs-leader-policy`: `STABLE` no caso base, `ROTATING` como ablação (Q8-B).
- `--hs-signature-mode`: `INDIVIDUAL`; `AGGREGATED` como sensibilidade (Q10-B). `--hs-voter-encoding`: `BITMAP`.
- `--hs-individual-signature-size` e `--hs-message-overhead`: mesmo valor do Tendermint (A-01, A-10). **O valor numérico continua `a definir com o humano`; 64 B e 10 B são fixtures sem fonte.**
- `--hs-aggregated-*`: só se `AGGREGATED` entrar, com fonte do esquema BLS (A-05, A-06). Fixtures 96 B sem fonte.
- `--hs-tx-size`: 512 B no caso base; envelope medido (4698 a 7031 B) como sensibilidade (Q4-C).
- `--hs-batch-size`: `L = bloco_kb·1024/s_tx`, igual ao Tendermint (Q7/A-23/A-25); valor **a definir com o humano**.
- `--hs-view-timeout`: múltiplo declarado da latência de ida e volta medida em **piloto sem falha, por n** (Q12-A), critério de aceite zero timeouts disparados sem falha. Latência da LAN simulada: Pareto, média 20 ms, 100 Mbps; serializar o PREPARE de um bloco de ~10 KB leva 2,5 ms (n=4) a 107 ms (n=128), logo o timeout **depende de n**. Valor **a definir**.
- `--hs-max-future-view-horizon`: 1000 (registro anterior de 10/11).

**Sequência decidida.** (1) HS-CB fase 2: soma da rede, mediana e percentis, cabeçalho `#`, trava de fixture. (2) HS-TT: `Tt_avg` medido, ou falha alta quando não medido. (3) Piloto de timeout por n, incluindo `STABLE`. (4) `V-ABC-hotstuff` com `jabs-experimento`. (5) Uma só tarefa de código para autoentrega (D2), contagem inteira (`BYZ-contagem-inteira-legado`) e gerador único (`BYZ-gerador-unico-conjuntos`), com uma só não-regressão, junto de `PARIDADE-teto-retencao-pbft-tendermint`.

**Limites.** Efeito de `Cb` por convenção é contrafactual onde envolve autoentrega (calculado por tamanho de mensagem, não medido). NEW_VIEW sem QC e voto nil (30 B por corrida no HotStuff n=4) não nomeiam bloco e ficam fora de qualquer Cb; destino a decidir. O `Cb` do HotStuff com `AGGREGATED` muda (1103,3 B no líder, n=4), com fixture sem fonte.


---
## Registro 10/11/2026 — autoria única e estratégia de conclusão (humano)

**O humano será o único autor do artigo.** Efeitos: (i) Q40 (governança do ADR-0000): onde o texto diz "aceite formal pelos coautores", o aceite é do humano, sozinho. **O ADR-0000 continua `Proposta` até o humano declarar o aceite**; este registro não o aceita. (ii) A lista de autores do ADR-0009 ("Caburlão, Kleber, <você>") deixa de ser pendência de nomes. (iii) Nada mais muda: lacuna normativa, valores de pesquisa e divergência de impacto ALTO/CRITICO continuam com o humano; o critic continua obrigatório.

**Humano delegou ao orquestrador a escolha da estratégia ("siga a melhor estratégia").** Escopo da delegação, como o orquestrador a entende: executar tarefas técnicas na ordem abaixo, dentro do orçamento de 3 rodadas, e escolher entre alternativas técnicas já recomendadas. **Não inclui** aceitar ADR, fixar valor numérico de pesquisa, fechar lacuna normativa, nem declarar `DECISAO_HUMANA_ACEITA` em nome do humano; esses pontos voltam a ele.

**Ordem de execução (menor risco primeiro; Bateria 2 antes da Bateria 1).**
1. `LOGGER-csv-flushBuffer-corrida` (instrumento; baterias rodam muitas JVMs).
2. `HS-TT` opção A: `Tt_avg` de HotStuff e Tendermint sai `-1` ("nao medido") quando todo o valor veio do fallback (finalização − criação do bloco = Tb); mudança intencional e declarada nessas duas colunas. Medição real fica para o gerador de carga.
3. `PBFT-prepare-equivocation` (ataque uniforme, critério 4).
4. `PILOTO-timeout-hotstuff-por-n` (inclui STABLE com ataque e timeout).
5. `V-ABC-hotstuff` e `V-ABC-tendermint` (e conferir o PBFT) com `jabs-experimento`.
6. Tarefa única: autoentrega + contagem inteira + gerador único de conjuntos + paridade de teto.
7. Piloto de custo (Q33-A).
8. Gerador de carga e fila (ADR-0013), depois que o humano decidir Q24, Q29, Q7, Q15, Q14.
9. Aceite do ADR-0000 e pré-registro congelado (humano).
10. Bateria 2 (robustez bizantina) e, depois, Bateria 1.

**Pendências só do humano (não executáveis pelo enxame):** Q14 (fonte de λ_B), Q17 (W_nó), Q15 (compressão temporal), Q24/Q29/Q7 (bloco, fila, lote), Q32 (validadores PoSA/PoS), Q33 (n do E2), Q1/Q10 (valores de assinatura, com fonte), limiares normativos do ADR-0005, aceite do ADR-0000, pré-registro, e a verificação da Lei 15.042/2024 citada no ADR-0000.


---
## Registro 12/11/2026 — parâmetros adotados a partir da revisão de literatura (humano)

**Decisão do humano:** "vamos usar as sugestões para os parâmetros". Fonte: revisão de literatura de 12/11 (agente `general`, somente leitura; PDFs lidos em /tmp, fontes marcadas VERIFICADA / NÃO VERIFICADA no relatório). Nenhum estudo tem LAN de 100 Mbps com latência Pareto e n até 128: os valores são **ordem de grandeza**, não valores transplantados.

| Parâmetro | Valor adotado | Fonte principal | Observação |
|---|---|---|---|
| L (lote máximo, tx) | varrer {10, 100, 500}; **caso central 100**; 2048 descartado como caso base | Gorenflo et al. 2019 (FastFabric, platô 50 a 500); Thakkar et al. 2018 | 10 espelha o `configtx` da rede de demonstração; 500 é o `sampleconfig` do Fabric 2.5. Cálculo próprio indica D de 7,3 s por visão com L=2048 e n=128 |
| T (tempo máximo, s) | **2 s**; varrer {1, 2} | `configtx` do Fabric; Thakkar et al. 2018 (1 s) | A cadência de 5,0 s do pBFT não tem fonte e é descartada (B-55). Início do relógio de T: sem fonte lida |
| `--hs-view-timeout` | `m × D̂ p99,99(n)` medido no **piloto sem falha, por n**; m = 2, subindo a 3 se o critério de aceite falhar | Buchman et al. 2018 (Tendermint, 2Δ por fase); Naor e Keidar 2024; DiemBFT v4 (4Δ) | O valor numérico só existe depois do piloto. Aceite: zero timeouts sem falha, mínimo de visões por célula e D_max ≤ ½ timeout |
| Política de timeout sob falha | base fixa por n; dobra com teto (HotStuff, PBFT); linear (Tendermint) | Yin et al. 2019; Castro e Liskov 2002; Buchman et al. 2018 | Decisão de comparabilidade; sem falha a política é irrelevante |
| s_tx | 512 B caso base; 2,9 KB e envelope medido (4698 a 7031 B) como sensibilidade | Gorenflo et al. 2019 | 512 B segue sem medição própria |
| Assinaturas | ECDSA 64 B; BLS 96 B (agregação) ou 48 B (curta) | RFC 8032; draft-irtf-cfrg-bls-signature-05 | BLS é Internet-Draft, não RFC. Tamanho DER de P-256 no Fabric: NÃO VERIFICADO |

**Continuam sem fonte e, portanto, abertos:** compressão temporal (Q15) e λ de MRV (Q14). A literatura não os cobre. Proposta do agente (ρ = λ/Θmax em {0,1; 0,5; 0,9; 1,1}) **não foi decidida**; fica como pendência do humano.

**Correções de citação:** o JABS é Yajam, Ebadi e Akhaee, IEEE TNSE 2024, DOI 10.1109/TNSE.2023.3282916 (não "Berendea 2022", que trata de gossip no Fabric). Corrigir onde estiver citado.

**Escopo.** Este registro fixa L, T, s_tx e a regra do timeout; **não** fixa o valor numérico do timeout (depende do piloto), nem Q7/Q24 como pré-registro: as pendências Q29 (fila), Q15, Q14, Q17, Q32, Q33, limiares do ADR-0005 e o aceite do ADR-0000 continuam com o humano. Os 5,0 s do pBFT não mudam no código até a tarefa de gerador de carga (ADR-0013).


---
## Registro 13/11/2026 — destino do critério A3 do piloto do timeout do HotStuff (humano)

**Decisão do humano:** acatou as sugestões do orquestrador sobre o resultado do piloto (`PILOTO-timeout-hotstuff-por-n`, impacto ALTO, critic concordou com o proponente).

1. **A3 (D_max ≤ T/2) falhou em n=16 com m=3 (D_max/T = 0,629; uma altura em 692).** O resultado fica registrado como falha; o critério original não é reescrito nem o m é elevado para fazê-lo passar.
2. **Critério novo, a congelar antes de qualquer dado novo:** limite superior do IC95 do p99,99 de D ≤ T/2, por estatística de ordem. Exige N ≥ 36887 visões independentes por grupo (`experimentos/piloto-timeout-hotstuff/emenda-nmin.json`; hoje ~21,6 mil). Rodada de confirmação só com ROTATING e L=100, sementes novas, ~2x mais sementes.
3. **T candidato (m=3, L=100):** 0,918 / 1,090 / 2,065 / 3,396 s para n = 4 / 16 / 64 / 128. Candidato, não valor fixado: o valor final depende da confirmação. L=100 é o caso central; L=10 e L=500 são sensibilidade, sem T único entre L.
4. **STABLE:** sem substituição de líder. É linha de base honesta; liveness perdida com líder fixo bizantino é por construção e essas células saem das comparações sob falha. SILENT (crash) e backoff do timeout: tarefa posterior, depois do timeout fixado (Raft do Fabric tolera parada, não falha bizantina).
5. **`-XX:hashCode=2` (custo de até 104x em L=500):** corrigir no código pelo loop 2 (`HotStuffTx.hashCode` por valor), primeiro confirmando a causa e procurando o mesmo padrão em outras classes. Alterações de saída exigem `checar-determinismo.py` e repetir o V-ABC. A confirmação roda sobre o commit fixo 9f305dd e é repetida se a saída mudar.
6. **Bytes sem fonte:** o overhead de 10 B tem sensibilidade baixa (0,31 % a 0,69 % em D) e não bloqueia. O tamanho da assinatura ECDSA no Fabric será medido pelo `redd-ponte` em transação real (substitui o 64 B e a estimativa DER). INDIVIDUAL é o modo principal; AGGREGATED só com fonte do esquema BLS.

**Continua com o humano:** valor final de `--hs-view-timeout` (após a confirmação), SILENT e backoff, fonte dos tamanhos agregados.
