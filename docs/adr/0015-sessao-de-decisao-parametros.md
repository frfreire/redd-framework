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


---
## Registro 16/11/2026 — K40 do timeout, V-ABC do HotStuff, flag fora da faixa BFT e Q32/Q29/Q15/Q14/Q33 (humano)

**Natureza deste registro.** Anexo datado, como os anteriores: não reescreve nada acima. Registra decisões do humano de 16/11/2026 e resultados que as motivaram. Rótulos: **[FONTE]** = li o arquivo citado nesta data; **[C]** = cálculo meu; **ESCOLHIDO** = decisão de projeto sem fonte externa (como no registro de 10/11). Onde digo "não confirmado", não achei a afirmação em fonte lida. As decisões do humano constam em `.ia/rodadas/loop2-jabs-codigo.jsonl` e `.ia/rodadas/loop3-resultado-decisao.jsonl` (entradas de 2026-11-16, citadas por tarefa); os números vêm dos pareceres do proponente (`jabs-experimento`), citados por caminho. Este registro não aceita o ADR-0000 nem fixa valor de pesquisa que o humano não tenha fixado.

### (a) K40: confirmação do timeout do HotStuff em n = 64 e 128 (loop 3, impacto ALTO, sem arbiter)

**Fonte do resultado:** [FONTE] `experimentos/piloto-timeout-hotstuff/confirmacao-k40/PARECER-TECNICO.json` (proponente, 13/11/2026; pré-registro `experimentos/piloto-timeout-hotstuff/confirmacao-k40/PREREGISTRO.md`, commit `06c67b5`, simulador de código igual ao de `9f305dd`). **Fonte das decisões:** `.ia/rodadas/loop3-resultado-decisao.jsonl`, tarefa `PILOTO-timeout-hotstuff-K40`, entrada do humano de 2026-11-16T13:52:24Z. [FONTE] O parecer de K40 aparece como arquivo **não versionado** em `git status` em 16/11/2026 (o pré-registro e os scripts estão versionados).

**Resultado medido (resumo; ROTATING, L=100, f=0, carga estacionária saturada, 80 células, 0 timeouts disparados):**

| n | T do piloto (s) | T/2 (s) | UB primário (s) | Veredito pré-registrado | Células com M_c > T/2 |
|---|---|---|---|---|---|
| 64 | 2,065 | 1,0325 | 0,7400 | PASSA | 0 de 40 |
| 128 | 3,396 | 1,698 | 1,1397 | PASSA | 0 de 40 |

[FONTE] K40-3 e K40-4. O secundário (N efetivo) em n = 128 ficou **INCONCLUSIVO** (N_eff 5 299 < 36 887; DEFF 16,33), previsto no pré-registro; em n = 64 passou (N_eff 51 066) (K40-6). O rótulo descritivo **H-cel** (θ̂ ≈ 1,0 em p99,9) é "NÃO CONTRADITA pela evidência descritiva" e **não é prova** (K40-5, ameaça A1). O parecer afirma que, pela regra pré-registrada, o veredito é "PASSA" e não "PASSA condicionado a H-cel"; a redação "condicionado a H-cel" é a do critic (entrada de 14/11 no mesmo `.jsonl`) e foi a adotada pelo humano abaixo.

**Decisões do humano (16/11/2026), ESCOLHIDO:**

1. **O critério primário por célula decide.** Resultado: **PASSA condicionado a H-cel** em n = 64 e n = 128, com H-cel como **hipótese de trabalho** (evidência descritiva, não prova). Isso responde a pergunta aberta do parecer sobre o peso do secundário (`a_definir_com_o_humano`, itens 2 e 3): em n = 128 o PASSA depende só do primário.
2. **T candidatos (m = 3, L = 100): 0,918 / 1,090 / 2,065 / 3,396 s para n = 4 / 16 / 64 / 128.** Candidatos, não valor final. Segundo o registro do humano, n = 4 e n = 16 ficam fixados **por decisão, sem nova rodada** (o parecer K40-10 diz que n = 16 falhou na confirmação anterior, com UB 0,6856 s > 0,545 s, e que n = 4 e n = 16 não foram tratados na K40).
3. **T é circular** (sai do D̂ p99,99 do próprio piloto) e **não houve teste com T menor** (K40-7, ameaça A2). O T mínimo "exigido pelo método" (2·UB: 1,480 s em n = 64 e 2,279 s em n = 128) é circular e **não é recomendação** (K40-7). Nada se afirma sobre o regime com T menor.
4. **Dispensa do V-ABC nesta rodada, registrada por escrito.** O humano já a havia comunicado em 13/11 (mesmo `.jsonl`, entrada de 2026-11-13T23:40:12Z: a rodada só mede D sem ataque, não compara protocolos, e o resultado não vai ao artigo antes do V-ABC). Este item fecha o desvio D1 do parecer K40 (que não achou registro escrito em `.ia/` nem em `docs/adr/`). O V-ABC do HotStuff foi aceito depois, na mesma data (item b); se a restrição "não vai ao artigo antes do V-ABC" já está satisfeita, é decisão do humano, **não registrada**.
5. As rodadas anteriores de confirmação (`PILOTO-timeout-hotstuff-confirmacao` e `-v2`) ficam **superadas** pela K40 e por esta decisão (mesmo `.jsonl`, entradas de 2026-11-16T13:52:24Z). O critério A3 de 13/11 continua registrado como falha em n = 16 (não foi reescrito).

**Limites que continuam valendo (K40, ameaças A5 a A9):** Java 25 (alvo do pom: 17); LAN simulada; sem falha injetada; só ROTATING e L = 100; sem `-XX:hashCode=2` na K40 (equivalência de saída conferida entre as duas JVMs, não contra uma execução com a flag); o resultado descreve o timeout de visão do HotStuff simulado, **não o Fabric** (Raft não é BFT, AGENTS.md).

### (b) V-ABC-hotstuff: resultado e decisões (loop 3, impacto ALTO, sem arbiter)

**Fontes:** [FONTE] `experimentos/validacao/hotstuff/PARECER-TECNICO.json` (V-A, V-A por nó, V-C ordinal; 16/11/2026), `experimentos/validacao/hotstuff/PARECER-TECNICO-VB.json` (V-B; 16/11/2026), `experimentos/validacao/hotstuff/PREREGISTRO.md` (emenda 1b e E7). Commits: pré-registro v2 `5b99d3e`; dados e parecer de V-A e V-C `26c98f6`; pré-registro E7 (libera V-B) `933813b`; dados e parecer de V-B `bc07355`. Simulador pinado: `76932b9` (`src/` tree `a1b464ee…`). `-XX:hashCode=2` nas duas JVMs de todas as células. Decisões do humano: `.ia/rodadas/loop3-resultado-decisao.jsonl` (tarefas `V-ABC-hotstuff-VA-VC`, `V-ABC-hotstuff-VB-preregistro`, `V-ABC-hotstuff-VB`) e `.ia/rodadas/loop2-jabs-codigo.jsonl` (tarefa `V-ABC-hotstuff`), entradas de 16/11/2026.

**P4a (ADR-0009/ADR-0015) fechado, modo INDIVIDUAL, ESCOLHIDO pelo humano:** **"O(n) em pacotes por bloco e O(n²) em bytes de consenso"**.

| Item | Medido [FONTE] PARECER-TECNICO.json | Papel |
|---|---|---|
| Pacotes por bloco (`rede_msgs`), n = 4, 7, 10, 16, 31 | 24, 48, 72, 120, 240 em todas as 25 células (= 8(n−1), erro 0); b = 1,11998; classe O(n) (VABC-3) | **decide** (A1) |
| Bytes de consenso (sem a carga do bloco) | 4 983; 13 806; 26 514; 63 390; 223 080 (erro 0 contra a fórmula); b = 1,85568; classe O(n²) (VABC-4) | **decide** (A2) |
| Bytes totais (com a carga do bloco) | b = 1,17026; classe O(n); termo quadrático 11,7 % em n = 31 (VABC-5) | **só rótulo** |

Veredito mecânico pré-registrado de V-A: CONSISTENTE (VABC-6). Por ser impacto ALTO, a aprovação é do humano; ele aceitou (item abaixo).

**Decisões do humano (16/11/2026), ESCOLHIDO:**

1. **P4a fechado como acima.** Os **bytes de consenso decidem**; os **bytes totais são O(n) e valem só como rótulo** (a pergunta "bytes de consenso ou totais?" do parecer, `a_definir_com_o_humano` item 2, fica respondida: consenso).
2. **A contagem de assinaturas nos QC NÃO é medida: só fórmula.** O número de assinaturas individuais por bloco, 3(n−1) + 5(n−1)q = 54, 168, 342, 870, 3 240 para n = 4, 7, 10, 16, 31 (q = 2f+1), é **fórmula do pré-registro, não saída do simulador**, e não decide. "O(n) em autenticadores" foi medido como **pacotes por bloco**, não como assinaturas (PARECER-TECNICO.json, ameaça A2; critic, entrada de 19:26:46Z do `.jsonl`).
3. **A fórmula foi derivada do mesmo código que gera o dado** (mesmo agente, mesmo `HotStuff.java`/`computeSize`). O erro 0 em 90 células mostra que o simulador faz o que o código diz; **não** mostra que o modelo reproduz o HotStuff do artigo nem que os tamanhos de assinatura (64 B; 96 B agregada, fixture sem fonte) são os de uma implementação real (ameaça A1).
4. **5 valores de n (4, 7, 10, 16, 31) bastam** para V-A. Limite declarado: expoente efetivo em n ≤ 31 é sensível às constantes (ameaça A4); não há n ≥ 64 nesta rodada. **t0 = 20 s aceito** (não derivado de Welch). V-B segue com n ∈ {4, 7, 10, 13} (entrada de 21:06:07Z).
5. **V-A por nó** (STABLE, líder = nó 0, 3 sementes por (modo, n); secundário): INDIVIDUAL **líder O(n²)** (b = 1,67350), **réplica O(1)** (b = 0,04043) (VABC-8). AGGREGATED fica como sensibilidade rotulada (O(n), fixture 96 B sem fonte; não decide).
6. **V-C numérico declarado INVIÁVEL** (nenhum número VERIFICADO de Yin et al. 2019, figuras ilegíveis; PREREGISTRO.md, E4). Fica **só o teste ordinal fraco**: Tb(n = 16) = 0,16737 s > Tb(n = 4) = 0,12965 s e vazão 481,0 < 600,8 tx/s, `ordem_ok = true` (VABC-11). **Não é confronto numérico, não valida magnitude** e não entra no veredito de V-A.

**V-B (limiar bizantino): resultado.** [FONTE] PARECER-TECNICO-VB.json. 180 células (n ∈ {4, 7, 10, 13} × f de 0 a ⌊(n−1)/3⌋+1 × 10 sementes, EQUIVOCATION, INDIVIDUAL, L = 100, ROTATING, 300 s): **176 VIVA, 4 NAO_EXECUTAVEL_POR_REGRA_DO_ATAQUE, 0 SEM_VIVACIDADE, 0 OUTRA_FALHA** (VB-3). As 4 células que abortam são todas n = 4, f = 2, pela regra `requireEquivocationSplit` (sementes 424348, 424349, 424352, 424354), exatamente as previstas na E7 antes dos dados (VB-2). `MIN_EXEC = 6`: n = 4, f = 2 ficou com k_exec = 6, ponto decidido. **Veredito mecânico no critério pré-registrado: INCONSISTENTE** nos quatro n e global, porque L(f*) = 1 (vivacidade mantida em f* = ⌊(n−1)/3⌋+1) onde o critério esperava L(f*) = 0 (VB-4).

**Decisão do humano (16/11/2026, entrada de 21:27:55Z), ESCOLHIDO:** V-ABC-hotstuff **fechado como "sem perda de vivacidade sob EQUIVOCATION com partição por índice (Q28)"**. Leitura que o humano acatou e que fica registrada com as ressalvas:

- **Não demonstra ruptura em ~1/3.** O resultado diz que, neste modelo de ataque, o HotStuff do simulador não perdeu vivacidade (≥ 1 altura finalizada com Fmin_s ≥ 20 s) em f* (VB-5). **Não mede segurança** (dois commits conflitantes não foram verificados). A teoria não garante vivacidade com f ≥ n/3; este resultado não a contradiz nem a confirma.
- **O ataque não é o pior de vivacidade.** A partição é por índice dos vizinhos (B-34 a B-36, provisória); com f* o líder pode cair na metade que recebe o voto real, e o voto bizantino conta (ameaça A1). A regra do ataque (`requireEquivocationSplit`) foi **mantida** pelo humano (entrada de 21:06:07Z); não encontrei decisão que torne B-34 a B-36 definitivos.
- **Vazão em f\* (descritivo, pós-hoc, não decide):** 5,7 % a 8,0 % da de f = 0 (n = 4: 0,057; n = 7: 0,072; n = 10: 0,066; n = 13: 0,080) (VB-6; ameaça A3). O critério binário não distingue isso de vazão normal; **limiar de vazão acima do binário só com pré-registro novo**.
- **Ataque mais forte** (silêncio ou voto dirigido ao líder): fica como **tarefa posterior, com novo pré-registro**. Não foi aberta por este registro.
- **n = 4, f = 2 vale condicionado ao nó 0 honesto:** as 6 células executáveis cobrem só 3 conjuntos bizantinos ({2,3}, {1,3}, {1,2}), todos com o nó 0 honesto; os 3 pares que contêm o nó 0 abortam pela regra para qualquer semente e não são observáveis neste desenho (VB-8; E7 a). O humano aceitou essa limitação em 16/11 (entrada de 21:06:07Z).
- **Resolução:** com n ≤ 13 a tolerância de 1 p.p. do PROTOCOLO não é testável; V-B só testa a igualdade inteira f* = ⌊(n−1)/3⌋+1 (E3; ameaça A5). f > f* não foi varrido.

**Rótulo do critic gravado de forma diferente do emitido.** O critic do loop 3 emitiu o rótulo **`DIVERGE`**; o script `registrar-rodada.py` **não aceita** esse valor, e a entrada de 2026-11-16T21:24:17Z foi gravada como **`CONFIRMADO`**, com a nota "ROTULO ORIGINAL DO CRITIC: DIVERGE (valor invalido no script)". A leitura de que o "DIVERGE" se refere a V-B não confirmar a ruptura esperada, e não a discordância com o proponente, **é do orquestrador**, não do critic. O critic reproduziu do bruto 176 VIVA, 0 SEM_VIVACIDADE, 4 NAO_EXECUTAVEL e k_exec 6/10, e alertou que o critério binário esconde degradação (alturas vivas 42 a 186 em 300 s). Quem lê o `.jsonl` deve usar este registro para não tomar "CONFIRMADO" por concordância plena. Fonte: `.ia/rodadas/loop3-resultado-decisao.jsonl`, tarefa `V-ABC-hotstuff-VB`. No V-A o critic tinha emitido `INCONCLUSIVO` (entrada de 19:26:46Z), pela lacuna das assinaturas nos QC (item 2 acima).

**Dados não commitados.** [FONTE] PARECER-TECNICO.json (D5) e PARECER-TECNICO-VB.json (D7, H7): os dados brutos e células eram não commitados nas etapas 2 e 3b; os commits `26c98f6` e `bc07355` os trazem segundo as mensagens. **Não confirmado** que `dados-brutos/` completo esteja versionado: `git status --short experimentos/validacao/hotstuff` não mostra pendência em 16/11/2026, mas não distingui "versionado" de "ignorado" (não rodei `git ls-files` sobre ele).

### (c) Flag `--allow-beyond-bft-threshold` (commit `76932b9`): emenda à decisão 3 de 10/11 e à D3(a)

**Fonte:** [FONTE] commit `76932b925719a2b83d6165b5a84d30f5501896ee` ("HotStuff: modo explicito fora da faixa BFT (--allow-beyond-bft-threshold) no MVPComparison (BYZ-fora-da-faixa-hotstuff, ADR-0015 decisao 3)"); código em `src/main/java/jabs/example/MVPComparison.java` (`resolveByzantineCount`, `beyondThresholdLines`); evidência `scripts/jabs/nr_byz_fora_faixa.py`; revisão do critic: `.ia/rodadas/loop2-jabs-codigo.jsonl`, tarefa `BYZ-fora-da-faixa-hotstuff` (APROVADO, 16/11).

**Texto de emenda.** A decisão 3 de 10/11 ("`f > ⌊(n−1)/3⌋` é declarado 'fora da faixa', nunca arredondado para baixo em silêncio") e o ajuste D3(a) (modo explícito, rotulado, com flag própria, não falha alta incondicional) ficam **implementados** assim, **só para o HOTSTUFF no `MVPComparison`**:

1. **Sem a flag**, `f > ⌊(n−1)/3⌋` para algum `n` **falha alto** antes de criar saída; a mensagem cita `n`, `f` e o limite.
2. **Com `--allow-beyond-bft-threshold=true`**, o modo roda e é **rotulado**: uma linha `# fora_da_faixa=true n=<n> byzantine_count=<f> limite_bft=<⌊(n−1)/3⌋>` por `n` fora da faixa, no cabeçalho de **`mvp-results.csv` (agregado) e de `mvp-results-cb.csv`** (o código acrescenta, depois do limite, um texto fixo de explicação na mesma linha `#`; os campos acima vêm primeiro). A linha também é impressa no stdout como `AVISO`.
3. **A flag não rotula o que está dentro da faixa:** com todo `f` dentro do limite, a saída é byte a byte a de antes (caso C de `nr_byz_fora_faixa.py`). O `nao_regressao.py` ignora linhas `#`; o `nr_byz_fora_faixa.py` **não** as ignora.
4. **`=true` sem ataque ativo falha alto** (seria ignorada em silêncio). Sem HOTSTUFF na lista de protocolos, a flag é **ignorada com aviso** no stderr, como `--attack`. Aritmética inteira.
5. **O V-B usa a flag de propósito** nos pontos f = f* (n = 4 f = 2, n = 7 f = 3, n = 10 f = 4, n = 13 f = 5; 40 das 180 células) e só neles (PREREGISTRO.md, E7 d).

**O que a flag não faz:** não resolve o aborto de `requireEquivocationSplit` em n = 4, f = 2 (PREREGISTRO.md, E6); não cobre ARQ, Parlia, Casper nem Tendermint (a decisão 3 vale para eles quando houver a tarefa `BYZ-contagem-inteira-legado`, **não iniciada**; registro de 10/11).

### (d) Q32, Q7/Q24, Q29, Q15, Q14, Q33 (decisões do humano de 16/11/2026, ESCOLHIDO)

**Fonte:** `.ia/rodadas/loop2-jabs-codigo.jsonl`, tarefa `Q32-Q29-Q15-Q14-Q33-decisoes`, entrada de 2026-11-16T21:27:55Z ("humano acatou"). Texto de cada pergunta: seções A.6 e B.12 deste ADR. Nenhum valor numérico novo além do já registrado em 12/11.

| Q | Decisão (como o humano acatou) | Observação |
|---|---|---|
| **Q32 A** | `--n-semantics=nodes` como semântica do estudo, **obrigatória na bateria**; `legacy` só em não regressão. "Validador" declarado por protocolo: **Casper, todos votam; Parlia, só mineradores**. **PoSA = Parlia e PoS = CasperFFG confirmados como correspondência.** | A **variante MCO2 × TREE segue a confirmar** (B-45; auditoria B.2: diferem só em `averageBlockInterval`, 15 s × 60 s). A Q32 destrava a condição da Q30-A (universo do sorteio = validadores/votantes, registro de 10/11). A decomposição 30/70 do modo `nodes` e a verificação da finalização com poucos stakeholders (PI-19) **não constam** na decisão registrada: seguem como estavam. |
| **Q7 e Q24** | **Resolvidas por L = 100 (varrer {10, 100, 500}) e T = 2 s (varrer {1, 2})**, os valores do registro de 12/11 deste ADR. | A decisão **não indica a letra** de Q7/Q24. L = 100 não é a opção A (espelhar o `configtx` do Fabric, 10 mensagens). O T = 2 s coincide com o `BatchTimeout 2s` do `configtx` (ADR-0015 A-23). Q7, Q24 e A-23/A-25 são o mesmo parâmetro (B-50). A cadência de 5,0 s do pBFT é descartada (B-55; registro de 12/11) e `tamanho_bloco_kb` sai do pré-registro (B-56, recomendação da Q24). |
| **Q29 A** | Fila de pendentes: **FIFO**; capacidade **sem limite, com alarme medido**; **relógio de T desde a chegada da primeira tx pendente**; fila **nos proponentes**, com a **difusão da tx contada no tráfego**. | "Sem descarte silencioso" segue valendo (ADR-0013). Fecha B-48, B-49, B-52, B-53. A implementação **não foi iniciada** (ver nota no ADR-0013). |
| **Q15 A** | **Compressão temporal declarada**: P e W_janela em escala de simulação, **preservando a razão W/P e η** (não o calendário); **nº de períodos por corrida decidido no piloto**. | Fecha o aspecto de forma de B-04/B-06/B-32. Os valores de P, W_janela e `duracao_sim_s` seguem **a definir** (piloto). |
| **Q14 C** | **Faixa de sensibilidade sem ponto central**; resultado **declarado condicional**. | Troca para A (valor de λ_B de dado real) só se o humano trouxer fonte (recomendação da própria Q14). B-02, B-03, B-05, B-07 ficam como faixa, sem número. |
| **Q33 A** | **Piloto de custo antes de fixar o n do E2**, validação e contagem de células. | O custo de n = 800 segue `NAO_MEDIDO`. A decisão de 10/11 de não usar n = 64, 128, 800 antes do piloto de custo continua valendo para V-A/V-B/V-C. |

### (e) O que continua com o humano (nada disto foi decidido neste registro)

1. **Aceite formal do ADR-0000** (Q40; o ADR-0000 continua `Proposta` até o humano declarar o aceite, registro de 10/11).
2. **Pré-registro das baterias** (Bateria 2 e Bateria 1; ordem de execução do registro de 10/11).
3. **Limiares do ADR-0005** (descritivos, Q19; nenhum limiar normativo foi fixado).
4. **Q1 e Q10:** valores de assinatura e de modo agregado, com fonte. 64 B, 96 B e 10 B seguem fixtures sem fonte onde não houver medição do `redd-ponte`.
5. **Q17:** banda uniforme por nó e níveis de W_nó.
6. **Variante MCO2 × TREE** da correspondência PoS = CasperFFG (item Q32).
7. **Valor final de `--hs-view-timeout`.** Os 4 T da decisão (a) são candidatos; o parecer K40 deixa em aberto aceitar o T candidato ou exigir validação em regime com T menor.
8. **SILENT (crash) e backoff do timeout:** tarefa posterior, depois do timeout fixado (registro de 13/11).
9. **Verificação da citação da Lei 15.042/2024** (índice 6.7, item 9; ADR-0000), ainda **não verificada**.

**Tarefas que este registro NÃO abre** (continuam listadas como não iniciadas nos `.jsonl`): ataque mais forte de V-B, tarefa única de código (autoentrega + contagem inteira + gerador único de conjuntos + paridade de teto), gerador de carga e fila (ADR-0013), piloto de custo.

## Opções para decisão, 17/11/2026

Esta seção reúne opções com recomendação para o humano decidir; **nada aqui está decidido**. Fatia 1 de 2 (Q13 e Q35); a fatia 2 acrescenta as demais questões abaixo. Rótulos: **[L]** lido no código ou no ADR (caminho:linha, árvore de trabalho de 17/11/2026), **[C]** calculado (à mão, salvo indicação), **[FONTE]** o que um ADR já registra, **ESCOLHIDO** o que o humano já decidiu, **NÃO VERIFICADO** o que não confirmei. **Nada foi executado nesta seção**: não compilei, não rodei o simulador nem script; as contas [C] foram feitas à mão sobre constantes lidas e precisam de conferência por script antes de virarem número de pesquisa. Nenhum valor numérico novo é escolhido aqui.

### Q13 (detalhamento). Gerador único de transações: entrada no JABS, RNG, regimes e o que continua aberto (B-01, B-11, B-57)

A Q13 original (seção B.12, `0015:492-497`) pedia só "aceitar MMPP-2 + RNG dedicado + Poisson primeiro". Ela segue **não decidida**: o registro de 16/11 decidiu Q14 C, Q15 A e Q29 A (`0015:919-930`), não a Q13 (`0013:185`, item 3: "para Q13, Q35 e Q36 não encontrei decisão registrada"). Abaixo a Q13 é aberta em quatro decisões, para o humano responder cada uma.

**Fatos lidos (todos [L]) que condicionam as opções**

1. **Hoje o gerador é Poisson homogêneo, por cenário, e só existe em 4 cenários.** `AbstractPoissonProcess` guarda um intervalo médio e um `RandomnessEngine` recebido no construtor; o próximo instante é `sampleExponentialDistribution(média)` (`AbstractPoissonProcess.java:7-31`, em :27-29). `TxGenerationProcessRandomNetworkNode` sorteia o nó com `network.getRandomNode()` e chama `node.generateNewTransaction()` (`TxGenerationProcessRandomNetworkNode.java:16-19`). Instanciado em `CasperEthereumNetworkScenario.java:122-127`, `ParliaBSCNetworkScenario.java:109-114`, `BitcoinGlobalNetworkScenario.java:176-182` e `DAGsperNetworkScenario.java:160-165`. Os cenários pBFT, HotStuff e Tendermint **não têm gerador** e `generateNewTransaction()` é vazio nos três nós (`PBFTNode.java:46-48`, `HotStuffNode.java:91-93`, `TendermintNode.java:72-74`). Não há MMPP em `src/main/java` (busca por "MMPP" e "Poisson" no fonte: só as classes acima e `BlockMiningProcess`).
2. **O fluxo de aleatoriedade é um só, compartilhado.** O cenário cria um `RandomnessEngine(seed)` (`AbstractScenario.java:74-76`), que é passado à rede (`Network.java:15,19-21`; no Casper, `CasperEthereumNetworkScenario.java:68-69`) e ao processo de tx (`:126`). A rede o usa para latência (`LAN100MNetworkStats.java:25`, Pareto), para sortear o nó de origem (`Network.java:24-26`), para o conteúdo da tx (`EthereumNode.java:72-77`) e para o peso do bloco (`EthereumMinerNode.java:61`). `RandomnessEngine` é um `MersenneTwister` (`RandomnessEngine.java:12-14`). Logo, variar λ muda quantos sorteios saem do fluxo e **desloca** latência, mineração e conteúdo. É o que o ADR-0013 quer evitar (`0013:72`) e o que B-11 registra (`0015:224`).
3. **Existe precedente de fluxo dedicado no próprio código:** `ByzantineConfig.byzantineStream(seed)` devolve `new RandomnessEngine(seed ^ BYZANTINE_STREAM_SALT)`, com o sal fixo `0x425A414E54494E45L`, "independente do engine do cenário (rede, blocos, transações)" (`ByzantineConfig.java:236-251`). O gerador poderia seguir o mesmo padrão, com sal próprio.
4. **A fórmula do MMPP-2 está no ADR-0010 (PI-10, proposta do humano, não aceita)** (`0010:104-125`): estado 1 base, estado 2 alto; `1/σ2 = W_janela`, `1/σ1 = P − W_janela`; `π_alto = W_janela/P`; `λ2 = η·N_op·κ/W_janela`; `λ1 = (1−η)·N_op·κ/(P−W_janela)`. A média estacionária confere exatamente com λ̄ (conferência de álgebra, `0010:114-119`). Validade: `0 < W_janela < P` e `W_janela/P < η < 1`; com `η = W_janela/P` o MMPP-2 **degenera em Poisson** (`0010:121`). Limite: durações de estado exponenciais, "uma janela por período" só vale em média (`0010:123`). Dispersão teórica **não calculada** (`0010:125`).
5. **O simulador só enxerga λ̄, η, W_janela/P e a escala absoluta de tempo** (descoberta [C] do ADR-0015, `0015:206`). Com Q14 C (faixa sem ponto central) e Q15 A (compressão temporal, razão W/P e η preservadas, nº de períodos por corrida decidido no piloto) o gerador **recebe** esses quatro como entrada; nenhum tem valor decidido.
6. **O estado inicial do MMPP-2 não está definido.** Busca por "estado inicial" e "initial state" em `0010`, `0013`, `0015`, `.experimento/*.md` e `especificacoes/*.md`: nenhuma ocorrência [L, busca feita por mim; não li `especificacoes/` inteira]. É lacuna real, tratada em Q13.3.
7. **Casper e Parlia hoje ignoram `--tx-interval`.** O ADR-0013 diz que o intervalo é o `--tx-interval` (padrão 5,0 s) (`0013:19`). No código, os cenários fixam o intervalo: 0,5 s no Casper (`CasperEthereumNetworkScenario.java:123`) e 0,1 s no Parlia (`ParliaBSCNetworkScenario.java:110`), e as chamadas de MCO2 e AMBIFY em `MVPComparison.java:1182-1189` e `:1239-1247` **não passam** `txInterval`. Só EARTH, OFFSET e DAGsper o recebem (`MVPComparison.java:992,1000,1004`). Correção de fato ao ADR-0013 (linha 19), a registrar na nota; não altero o ADR-0013 aqui.

#### Q13.1 Como o gerador entra no JABS

- A) **Um processo de chegada único** (classe nova em `jabs.simulator.event`), com regime selecionável (Poisson ou MMPP-2), instanciado por todos os cenários do estudo no lugar de `TxGenerationProcessRandomNetworkNode`. O núcleo é uma classe pura que, dados (semente, regime, λ̄, η, W/P, P), produz a sequência (instante, nó de origem) e é testável sem simulador. As classes Poisson atuais ficam, sob flag de legado, para protocolos fora do estudo (Nakamoto/OFFSET, EARTH e DAGsper) e para a não regressão;
- B) **Subclasse de `AbstractTxPoissonProcess`** que sobrescreve `timeToNextGeneration()` com taxa dependente de estado. Menor alteração aparente, mas o estado do MMPP-2 fica escondido num método chamado também pelos cenários para agendar o primeiro evento (`CasperEthereumNetworkScenario.java:127`), e a classe base amarra o gerador a "um nó" (`AbstractTxPoissonProcess.java:7-13`);
- C) **Pré-computar a trajetória de chegadas** antes da simulação (lista de instantes e origens por semente) e só agendar. Contagem por semente e verificação de média (ADR-0013, verificação 1 e 2, `0013:165-166`) viram triviais, mas a memória cresce com λ (nível de 100λ_B, valor ainda sem número) e a corrida longa de Q15 A;
- outra.

**Recomendação: A.** Razão: B esconde estado e herda um acoplamento que o ADR-0013 quer desfazer (gerador único, não por nó); C custa memória e não traz ganho que o núcleo puro de A já não dê, pois a classe pura também permite contar e conferir a média sem rodar a simulação. Em A, ligar o gerador aos três cenários sem gerador (pBFT, HotStuff, Tendermint) é a tarefa de fila de Q29 A; este item só fixa a **forma** de entrada.

#### Q13.2 Fluxo de aleatoriedade do gerador (B-11)

- A) **Fluxo dedicado** `RandomnessEngine(seed ^ SAL_TX)`, no padrão de `ByzantineConfig` (`:241-251`), com sal constante próprio, cobrindo **instantes, estado do MMPP-2 e nó de origem**. O conteúdo da tx não consome sorteio (tamanho fixo s_tx, ADR-0013 decisão 4, `0013:75`);
- B) Dedicado só para instantes e estado; o **nó de origem** continua em `network.getRandomNode()` (`Network.java:24-26`), no fluxo da rede. Contra: o nº de sorteios de origem no fluxo da rede passa a depender de λ e desloca latência e mineração, o mesmo problema que o fluxo dedicado resolve;
- C) Manter o fluxo **compartilhado** (como hoje). Contra: efeito de carga e mudança de trajetória se confundem (B-11, `0015:224`); quebra o pareamento por semente de PI-13;
- outra: **um sub-fluxo por componente** (instantes, estado, origem), cada um com sal próprio. Ganho: trocar o regime não altera a sequência de origens sorteadas nem o estado de um componente por causa de outro. Custo: três sais e mais código; o ganho só aparece se alguém quiser comparar sequências de origem entre regimes, o que não é requisito registrado.

**Recomendação: A.** Razão: atende o que o ADR-0013 pede (`0013:72`) com o padrão já testado no repositório. O pareamento que importa (rede e mineração iguais entre λ e regimes) vale em A. A sequência de chegadas **não** é a mesma entre Poisson e MMPP-2 (o MMPP-2 consome sorteios de troca de estado), e isso é inerente a regimes distintos, não defeito de A. **Limite que A não resolve:** código novo executado antes da simulação desloca a sequência de `System.identityHashCode` e muda a saída de Casper, Parlia e Nakamoto mesmo com fluxo dedicado (achado de 31/10, `0013:99-104`); a prova de não regressão desses protocolos segue dependente da Q36. NÃO VERIFICADO: se sementes irmãs com XOR de constante dão fluxos suficientemente independentes no `MersenneTwister` do commons-math (a decisão do ByzantineConfig já usa essa construção; não li o `setSeed` da biblioteca).

#### Q13.3 Regimes: aceitar o MMPP-2, ordem de implementação e estado inicial (B-01, B-57)

- A) **Poisson estacionário primeiro, depois MMPP-2 de PI-10**, com o MMPP-2 iniciando no estado sorteado da distribuição estacionária (`P(alto) = W_janela/P`), que custa um sorteio do fluxo dedicado e elimina o viés de início na média por semente. Poisson e MMPP-2 de mesma λ̄. O limite exponencial (`0010:123`) vai para o pré-registro;
- B) Igual a A, mas o MMPP-2 **inicia sempre no estado base** (fora da janela). Determinístico e simples; contra: a média por semente na parte inicial fica abaixo de λ̄, e a conferência "média de cada semente contra λ̄" (`0010:221`) passa a depender de t0 (Q25) e do nº de períodos (Q15 A);
- C) **Rejeitar o MMPP-2** e usar outro regime de mesma média, por exemplo periódico determinístico. Isso **reverte** a decisão B3 de 29/10 que tirou o perfil sazonal (`0010:127`, PI-11 "NÃO USADO"); só se o humano considerar a periodicidade exata essencial (`0010:123`);
- outra.

**Recomendação: A.** Razão: a álgebra de PI-10 vale para a média estacionária; começar nela evita um transiente que a análise teria de remover (Q25) só por causa do gerador. Dois cuidados, **ambos [C] qualitativos, NÃO VERIFICADOS neste repositório** (a dispersão do MMPP-2 não foi calculada, `0010:125`): (i) com poucos períodos por corrida (Q15 A deixa o nº para o piloto), a contagem por semente de um MMPP-2 varia mais que a de um Poisson de mesma média, pois a duração de cada estado é aleatória; a tolerância da igualdade de média tem de vir da dispersão do próprio gerador (Q16 A), não do erro-padrão de Poisson; (ii) a primeira entrega é só Poisson, e o fator R do E1 fica vazio até o MMPP-2 existir (B-57, `0015:308`).

#### Q13.4 Nós de origem das transações

- A) **Todos os nós da rede, uniforme**, como `getRandomNode()` faz hoje (`Network.java:24-26`), e a difusão até os proponentes entra no tráfego (Q29 A, `0015:919-930`);
- B) **Só os nós que votam ou propõem** (validadores, conforme Q32: no Casper todos votam, no Parlia só mineradores), uniforme entre eles;
- C) **Um nó cliente fixo** (origem única). Simples; contra: concentra o custo de difusão num nó e não representa muitos participantes (N_op);
- outra.

**Recomendação: A**, por manter o comportamento do código e a regra de universo do tráfego "difusão contada" sem criar um segundo universo de sorteio. Ressalva: com `--n-semantics=nodes` (Q32 A) o n do estudo é o total de nós, então A e B coincidem onde todos os nós são validadores (pBFT, HotStuff, Tendermint, Casper); só diferem no Parlia, onde `nodes` inclui não mineradores [L parcial: a composição mineradores/stakeholders vem de `MVPComparison.java:1239-1247`; não li `NetworkSizing`]. NÃO VERIFICADO: o efeito de A × B no tráfego.

**O que a Q13 continua a deixar aberto (nenhum valor é escolhido aqui):** λ̄ (faixa, Q14 C), η e W_janela/P e nº de períodos por corrida (Q15 A; piloto), tolerância da média e janela do índice de dispersão (Q16), o t0 único (Q25), a forma da não regressão (Q36). L = 100 {10, 100, 500} e T = 2 s {1, 2} e a fila FIFO sem limite com alarme, relógio de T desde a 1ª tx pendente, fila nos proponentes com difusão contada, **seguem como decididos** (`0015:804-830`, `0015:919-930`) e não são reabertos.

### Q35 (detalhamento). Limite de gás do Ethereum frente a L = 100 e s_tx = 512 B comuns (B-54)

A Q35 original (`0015:647-654`) recomendava A (gás constante, L substitui o limite) sem mostrar o tamanho do problema. Ela segue **não decidida** (`0013:185`, item 3). Abaixo, os fatos do código que mostram por que o limite **não é neutro** e as três opções pedidas.

**Fatos lidos (todos [L], salvo marcação)**

1. **O limite e o laço.** `MAXIMUM_BLOCK_GAS = 12500000` (`EthereumMinerNode.java:27`). `generateNewBlock()` percorre o `memPool` (um `HashSet<EthereumTx>`, `:23`) e **interrompe na primeira tx que estoura** o limite (`:51-59`, o `break` em `:54-56`). Não há regra de lote nem de tempo nesse laço. Parlia e Casper usam o mesmo método: o Parlia chama `creator.generateNewBlock()` (`Parlia.java:364-366`); o Casper herda a classe (`EthereumCasperMiner.java:10`). **DAGsper** também herda `EthereumMinerNode` (`EthereumDAGsperMiner.java:12`), então mexer ali o atinge; fora do estudo, mas entra na não regressão.
2. **Cada tx traz tamanho e gás de distribuições próprias.** `sampleEthereumTransaction` (`TransactionFactory.java:50-56`): tamanho de `ETHEREUM_TRANSACTION_SIZE_DISTRIBUTION`/`_BINS` (`:27-30`, `:43-45`) e gás de `BITCOIN_TRANSACTION_GAS_DISTRIBUTION` com `ETHEREUM_TRANSACTION_GAS_BINS` (`:32-41`; o nome da tabela de gás diz "BITCOIN" e é usada para o gás do Ethereum, `:55`). Os bins de gás vão de 21 001 a 12 475 587 (`:32-35`). O tamanho do bloco é `543 + Σ tamanho das tx + 36 por tio` (`EthereumBlockWithTx.java:21-26`; cabeçalho 543 em `BlockFactory.java:32`, hash+id de rede 36 em `:33`). **O gás não entra no tamanho do bloco**; `getTotalGas()` só é definido, sem chamador em `src/` (busca por `getTotalGas`: só a definição, `EthereumBlockWithTx.java:44`).
3. **A tabela de gás põe uma massa enorme no valor máximo.** `sampleDistributionWithBins` percorre `dist.length-1` entradas e, se o sorteio passa de todas, **devolve `bins[último]`** (`RandomnessEngine.java:30-41`). A tabela de gás tem 20 probabilidades (`TransactionFactory.java:37-41`) e 21 bins. **[C, à mão, NÃO EXECUTADO]** as 20 entradas somam ≈ 0,7997 (não 1) e as 19 testadas somam ≈ 0,7766; logo ≈ **22,3%** das tx saem com gás = **12 475 587**, quase o limite de bloco (12 500 000). Conta feita somando a tabela lida; precisa de conferência por script antes de citar o percentual.
4. **Consequência [C, à mão]: o limite de gás encolhe o bloco para uns poucos tx, não para 100.** Só as ≈22,3% de tx "gigantes" já dão gás médio ≥ 0,223 × 12 475 587 ≈ 2,8 milhões por tx; o gás médio por tx fica da ordem de 3 milhões (as demais bins contribuem pouco, por ordem de grandeza). Logo, em média cabem **da ordem de 4 tx** em 12,5 milhões, e o `break` na primeira estourada (item 1) pode encerrar antes. O limite, portanto, **governa** o tamanho do bloco do Casper e do Parlia, em vez de L = 100. Isso é estimativa de ordem de grandeza, **NÃO VERIFICADA por execução**. Complemento: a mesma regra de "resíduo no último bin" vale para o tamanho (13 probabilidades somam ≈ 0,9923; as 12 testadas, ≈ 0,9917 [C à mão]); ≈ 0,8% das tx sairiam com 296 985 B. Relevante só se a distribuição própria do Ethereum for mantida (opção ii).
5. **Capacidade que o gás deixa, comparada ao ritmo de geração.** O intervalo de bloco do MCO2 é 15,0 s e o do AMBIFY, 3,0 s (`MVPComparison.java:1182-1189` e `:1239-1247`); os cenários geram tx a cada 0,5 s e 0,1 s (`CasperEthereumNetworkScenario.java:123`, `ParliaBSCNetworkScenario.java:110`). **[C]** isso é cerca de 30 tx chegando por bloco em cada um, contra poucas unidades que cabem pelo gás (item 4): o `memPool` cresce e o Tt (submissão `EthereumNode.java:72-77` até finalização, `EnhancedBlockFinalizationLogger.java:179-185`) é dominado por espera na fila. Os valores de Tt do MCO2 (de 304 a 445 s, base e atual, em `n1-conversao-n/saida-final/termina-Casper-MCO2_50_600-s*.txt:7`) são compatíveis com isso mas **não provam** a causa (não rodei nada; Tb e Tt dependem também da finalização do Casper).
6. **A ordem de seleção é a do `HashSet`, não FIFO.** O laço itera o `HashSet` cuja ordem depende de `System.identityHashCode` (`EthereumDAGsperMiner.java:18-24` explica; o ADR-0013 registra o efeito, `0013:99-104`); só o DAGsper usa `LinkedHashSet` (`EthereumDAGsperMiner.java:24`). Em qualquer opção abaixo, **a fila FIFO da Q29 A só vale para o Casper e o Parlia se o contêiner mudar**, e trocar o `HashSet` muda a saída do Casper e exige ADR próprio (`0013:103`; Q36 B). Esta interação não é decidida aqui: afeta qual das tx da fila entra no bloco, não quantas.

**Q35. O que fazer com o limite de gás em Casper e Parlia**

- A (i) **Gás constante por tx, com limite inerte por construção.** Todo tx tem o mesmo gás g (valor `a definir com o humano`, com fonte). O limite de 12 500 000 permanece no código, mas a configuração **falha alto** se `g × L_max` > limite (isto é, g ≤ ⌊12 500 000 / L⌋: **[C]** 1 250 000 com L = 10, 125 000 com L = 100, 25 000 com L = 500, logo **g ≤ 25 000 cobre toda a varredura {10, 100, 500}**). Assim o bloco fecha por L (e pelo tempo, ADR-0013 dec. 2) e nunca pelo gás. O cabeçalho do CSV traz `#` com g e "limite de gás inerte". Efeito: bloco de L tx de 512 B ≈ 543 + L·512 B (**[C]** 51 743 B para L = 100, mais 36 B por tio), igual ao critério do bloco dos outros protocolos exceto pelo cabeçalho de 543 B;
- B (ii) **Manter a distribuição própria do Ethereum** (tamanho e gás), com o gás como segundo limite (lote OU gás OU tempo, Q35 B original). Contra: derruba as decisões 3 e 4 do ADR-0013 (mesmo s_tx, bloco dimensionado pela fila, `0013:74-75`); pelo item 4 o gás passaria a ditar o bloco (da ordem de 4 tx, não 100), e o Tt e o Cb do Casper e do Parlia deixam de ser comparáveis aos de pBFT, HotStuff e Tendermint. Só como **ablação rotulada** "Ethereum nativo", fora do E1;
- C (iii) **Desligar o limite**, com rótulo. O laço deixa de olhar o gás; o bloco fecha por L e T. O gás fica ignorado (campo sem uso, como `getTotalGas()` hoje). Cabeçalho `#`: "limite de gás desligado". Igual a A em efeito quando g × L ≤ limite, **sem a trava de configuração**: se o humano subir L (por exemplo L = 2048, já descartado como caso base, `0013:183`), o desvio passa **sem aviso**;
- outra.

**Efeito em Cb, Tt e comparabilidade entre protocolos**

| | Cb (tráfego por bloco) | Tt (latência da tx) | Comparável com pBFT, HotStuff, Tendermint? |
|---|---|---|---|
| **A (i)** | termo de disseminação ∝ L·s_tx, como nos demais ([C]; equação de decomposição, `0013:12`) | espera pela fila governada por L/T e pela cadência do protocolo | **Sim, no tamanho do bloco.** A cadência de bloco do Casper (15,0 s) e do Parlia (3,0 s) é do protocolo, não de T (`0013:118`, leitura do ADR, não confirmada pelo humano): diferença que o gás não resolve |
| **B (ii)** | ∝ unidades de tx (item 4) × tamanho amostrado; ordem de grandeza menor e variável | domina a espera por limite de gás | **Não**; ablação |
| **C (iii)** | igual a A | igual a A | Igual a A, sem a trava |

**Recomendação: A (i), com a trava de configuração.** Razão: mantém a regra de fechamento comum (ADR-0013 dec. 2), preserva o atributo de gás como invariante verificada (a configuração falha se g × L_max passar do limite), em vez do silêncio da opção C, e **não** deixa o gás ditar um bloco de uns poucos tx (item 4). O valor de g e a fonte ficam `a definir com o humano`; **não escolho número**. Dado de partida para a pesquisa de fonte: o menor bin da tabela do simulador é 21 001 (`TransactionFactory.java:32-35`), compatível com g ≤ 25 000; que 21 000 seja o gás base de uma transferência simples no Ethereum é conhecimento meu, **NÃO VERIFICADO em fonte neste repositório**. B só como ablação rotulada, se o humano quiser mostrar o desvio. C só se o humano preferir não ter a trava.

**Fica aberto (não decidido, fora desta questão):** (a) **ordem de seleção do memPool** (FIFO × `HashSet`), ligada à Q29 A e à Q36 B (item 6); (b) **cadência de bloco do Casper e do Parlia versus T** (o ADR-0013 deixa como "minha leitura a confirmar", `0013:118`): decide se o Tt do Casper e do Parlia se compara ao dos protocolos de lote e tempo; (c) **cabeçalho de 543 B** do Ethereum (`BlockFactory.java:32`) contra o cabeçalho dos outros protocolos, que não li; (d) **não regressão**: tocar `EthereumMinerNode` muda a saída de Casper, Parlia e DAGsper, e a prova segue dependente da Q36 (`-XX:hashCode=2`, ADR-0016 também exige a flag nas células Ethereum).

**Não confirmei / não executei (Q13 e Q35):** nada foi compilado nem rodado. Os percentuais e médias de gás e de tamanho do item 3 e do item 4 da Q35 vêm de soma manual das tabelas lidas; a conta precisa ser repetida por script. Não li `NetworkSizing`, o `setSeed` do `MersenneTwister` (independência de fluxos por XOR de semente), `hotStuffBlockSize` e o cabeçalho de bloco dos protocolos BFT (item (c)), `EthereumGethP2P` (efeito da difusão de tx no tráfego), nem as normas citadas na fatia. Não verifiquei por execução que os cenários Casper/Parlia ignoram `--tx-interval` (leitura de `MVPComparison.java:1182-1189`, `:1239-1247`); a leitura sustenta que a flag não chega a eles, mas não rodei. Não consegui confirmar o `git diff` das alterações não commitadas de `CasperEthereumNetworkScenario.java` e `ParliaBSCNetworkScenario.java` (a ferramenta bloqueou o comando); os trechos citados foram lidos na árvore de trabalho, não no `HEAD`.

---

## Fatia 2 de 2 (17/11/2026): decisões do humano já tomadas e questões Q36, variante PoS, Parlia/universo

**Decisões do humano de 17/11/2026 (ESCOLHIDO; registradas aqui sem alteração):**

1. **Parlia/universo = opção C:** f como percentual do **universo** de validadores de cada protocolo, rotulado com `# fora_da_faixa` quando exceder o limite BFT; tabela de f efetivo por n e protocolo no pré-registro; limitação declarada.
2. **Q13.1 a Q13.4 = opção A** (em cada uma): gerador adiado para depois da Bateria 2; MMPP-2 começa em estado sorteado da distribuição estacionária.
3. **Q35 = gás constante por tx com trava g·L ≤ limite** (opção A (i) acima): o valor de g fica **a definir com o humano**; o achado dos ≈22% de tx com gás máximo deve ser **repetido por script antes de ser aceito** (ver a nota em "Pendências" no fim desta fatia).

As questões abaixo seguem **não decididas**, salvo o que está nesta lista.

### Q36 (detalhamento). Forma da prova de não regressão e veredito da N1 (B-66)

**O texto define a Q36 com clareza**: `0015:654-659` (opções A, B, C, recomendação A), `0015:309` (B-66) e `0015:392` (1º na ordem; 8 dependentes: B-22, B-23, B-24, B-27, B-41, B-44, B-50, B-51). A Q36 tem três perguntas distintas, que a redação original junta e que abaixo separo. Nenhuma está decidida (`0013:185`, item 3).

**Fatos lidos [L] que mudam o enunciado da Q36 (o texto do ADR-0015 está parcialmente defasado)**

1. **O "veredito da N1" já existe no registro de rodadas.** `.ia/rodadas/loop2-jabs-codigo.jsonl:117` (2026-11-09T20:20:16Z): critic **APROVADO**, "nao-regressao IDENTICO base 325f4e3 x atual, **JVM padrao**, sementes 1 e 2: MCO2:10/50, AMBIFY:10/12, OFFSET:40, ARQ:10; ByzantineSweep POS/POSA/POW IDENTICO". Antes: rodada 1 rejeitada (`:89`, 12/18 divergentes sem a flag) e rodada 2 que removeu `CliArgs` e zerou as chamadas extras a `identityHashCode` no modo `legacy` (`:90`; `src/test/resources/nao-regressao/n1-conversao-n/README.txt:346-400`, "18 de 18 IDENTICO" com JVM padrão). O ADR-0013 (`0013:110`) e o ADR-0012 (`0012:124`) ainda dizem "N1 sem veredito"; o texto do 0015 (`0015:655`) idem. **Divergência de documentos, a registrar:** o `.jsonl` diz aprovada; os ADRs dizem pendente. Não alterei o ADR-0013 nem o ADR-0012.
2. **O modo `nodes` não tem baseline.** O README da N1 diz que o caminho `--n-semantics=nodes` "muda o n de propósito e não tem baseline" (`README.txt`, seção 5, linha "Limite", e seção 8). A prova de identidade vale só para o modo `legacy`. O que valida o `nodes` é a tabela executada de dimensionamento (`diagnostico-executado.txt`, 63 linhas OK, `README.txt` seção 1.3).
3. **A forma já está sendo praticada.** O ADR-0016 (Aceito em 13/11) adota que baterias e `GarantiaHashCode` exigem `-XX:+UnlockExperimentalVMOptions -XX:hashCode=2` e que "nenhum resultado de pesquisa pode vir de execução sem a flag" nos protocolos Ethereum (`0016`, Decisão e Consequências). O humano decidiu em 12/11 que a garantia de não regressão do registro (M1a) **vale com `-XX:hashCode` fixado** (`.ia/rodadas/loop2-jabs-codigo.jsonl:199`). `nao_regressao.py` aceita a flag repetível (`scripts/jabs/nao_regressao.py:67-72`, `:382`) e recusa o registro ligado sem ela (`:114-120`). Ou seja, A é de fato a prática desde 12/11; falta o **ato de decisão formal** (a Q36 pede essa formalização) e o escopo.
4. **O hash `325f4e3` não é confirmável hoje.** `git log --oneline -1 325f4e3` falha neste repositório ("unknown revision"): o histórico visível tem 39 commits, o mais antigo é `964e0ab` (2026-10-31), posterior à criação declarada de `325f4e3` (2026-10-30, `0006:125`). O worktree `/tmp/opencode/base-325f4e3` **não existe** (existe `/tmp/opencode/base-8c68084`, com `BASELINE.txt`: commit `8c680840925899e6fb3cbcf790b80a89e0ad3356`, árvore limpa). A baseline operacional recente é, portanto, o HEAD do momento (o ADR-0016 e `comparar-chave-tx.py` exigem build do HEAD como base). **Não consegui confirmar `325f4e3`**; é plausível que o histórico tenha sido reescrito, mas isso é inferência minha, **NÃO VERIFICADO**.
5. **Escopo da evidência.** Hash fixo foi testado em AMBIFY, MCO2 e OFFSET, semente 1 (`0012:36`); `hashCode=3` ainda diverge no AMBIFY (mesma fonte). A identidade da N1 em JVM padrão cobre 6 casos × 3 sementes + sweep POS/POSA/POW (`README.txt:420-440`), não HotStuff, Tendermint, TREE, EARTH, DAGsper (`README.txt`, seção 8). O mecanismo interno do JDK que faz carga de classe consumir `identityHashCode` **não foi investigado**, só o efeito foi medido (`README.txt:407-409`); depende do JDK 25 desta máquina (o pom mira 17).

**Q36.1 Forma da prova de não regressão para Casper, Parlia e Nakamoto**

- A) **`-XX:+UnlockExperimentalVMOptions -XX:hashCode=2` nos dois lados**, como regra permanente das provas de não regressão desses três e de DAGsper (herda `EthereumMinerNode`, ver Q35), com o relatório dizendo "com flag". Prova só a lógica; não elimina a sensibilidade (`0013:101-105`). Já é a prática (fatos 3);
- B) **Corrigir a causa**: `memPool` em `LinkedHashSet`. Muda a saída do Casper, exige ADR e nova baseline (`0013:103`); interage com a fila FIFO da Q29 A e com a ordem de seleção do Q35 item 6. Eliminaria a dependência da flag, ao custo de invalidar as saídas anteriores. O ADR-0016 trata a correção análoga do `Hash` por valor como "tarefa posterior, só se esta não bastar" (opção 3);
- C) **Declarar PENDENTE e seguir**, sem prova de identidade para os três. Contra: o `nao_regressao.py` e as baterias já exigem a flag; ficar sem prova trava todas as tarefas que tocam `MVPComparison`, `EthereumMinerNode`, gerador e fila;
- outra: **A + execução cruzada em JVM padrão** (como a N1 rodada 2) como evidência adicional, onde o código novo não roda antes da simulação; custo: dobra as execuções.

**Recomendação: A.** Razão: é a única forma testada com sucesso, já é exigida pelo ADR-0016 e pelo M1a, e é barata. Ressalvas: (i) a evidência é de uma semente por caso no hash fixo; a recomendação inclui **ampliar para 3 sementes e os 4 casos antes de generalizar** (o que a N1 em JVM padrão já tem, `README.txt:420-440`); (ii) B só com ADR próprio e só se o humano quiser eliminar a dependência da flag; (iii) a flag **muda a saída em relação a JVM padrão** nos protocolos Ethereum, então os números de pesquisa devem ser comparados só entre execuções com a flag (ADR-0016, Consequências).

**Q36.2 Veredito da N1**

- A) **Registrar a N1 como aprovada para o modo `legacy`** com base em `.jsonl:117` e `README.txt:420-440`, declarando como limites os fatos 2 e 5 (sem baseline para `nodes`; sem HotStuff, Tendermint, TREE, EARTH, DAGsper);
- B) **Reabrir a N1** e exigir uma segunda baseline (HEAD atual) em JVM padrão e com flag, para todos os protocolos do estudo;
- C) Manter "sem veredito" nos ADRs 0012 e 0013 (status quo);
- outra.

**Recomendação: A.** Razão: o critic aprovou com contexto limpo e o gap único da rodada 1 foi tratado. **Isto não é aprovação minha**: o veredito é do critic e do humano; a recomendação é só a de **fechar o registro**. Fica a conferir que `.jsonl:117` se aplica ao código hoje no HEAD (NÃO VERIFICADO: a N1 foi aprovada em 09/11 e `MVPComparison.java` mudou depois, p. ex. `--allow-beyond-bft-threshold` em `76932b9` e o `BYZ-UNICA` em `99d50cc`). Uma verificação de nova baseline (Q36.3) cobre isso.

**Q36.3 Baseline e dono da extensão de `nao_regressao.py`**

- A) **Baseline = o commit imediatamente anterior à tarefa** (HEAD no momento), construído de `git archive`, como já faz `comparar-chave-tx.py` (o ADR-0016 diz que o script recusa base que não seja o HEAD). O hash `325f4e3` é abandonado como baseline e mantido como histórico. Dono da extensão do script (colunas 15 e 16 do registro, B-27): o orquestrador atribui a uma tarefa do loop 2 com escopo em `scripts/jabs/` (o `jabs-builder` escreve em `src/` e `pom.xml`; o ADR-0012 registra que `scripts/` está **fora do escopo** do builder, `0012:160`);
- B) **Recuperar `325f4e3`** (se existir em outro clone ou remoto) e manter como baseline única. Contra: os 39 commits visíveis não o contêm; recuperar depende de algo que não sei se existe;
- C) **Baseline por tarefa e por protocolo**, sem baseline global: cada tarefa declara base e flag. Mais flexível; contra: perde a comparação entre tarefas e o pareamento da Bateria 2;
- outra.

**Recomendação: A.** Razão: usa só o que existe e reproduz a prática do ADR-0016. **Não decide** o dono do script: isso é da Q27 (ADR-0015, B-27), pendente. Limite: base × atual pelo HEAD só mede a tarefa corrente, não o acumulado desde a N1.

**O que a Q36 destrava, na ordem de `0015:392`:** B-22, B-23, B-24, B-27, B-41, B-44, B-50, B-51. Nenhum valor numérico é escolhido.

### Variante PoS = CasperFFG: MCO2 × TREE (B-45; índice 6.1; auditoria B.2)

A correspondência PoSA = Parlia e PoS = CasperFFG já está **ESCOLHIDA** (Q32 A, `0015:925`). O que segue aberto é **qual variante** do CasperFFG entra no fator A. Abaixo, o que a leitura do código mostra que de fato diferencia as duas.

**Fatos lidos [L] (árvore de trabalho de 17/11/2026)**

1. **As duas variantes constroem a mesma classe de cenário com os mesmos argumentos, exceto um.** `runMCO2Scenario` (`MVPComparison.java:1182-1189`) e `runTreeCycleScenario` (`:1210-1217`) chamam `new CasperEthereumNetworkScenario(nome, (int) seed, duration, **intervalo**, 14, sizing[0], sizing[1])`. Mesmos: classe, semente, duração, `checkpointSpace = 14`, mineradores e stakeholders (`mvpSizing`, `:993-996`). Diferem em: **intervalo médio de bloco 15,0 s (MCO2, `:1186`) contra 60,0 s (TREE, `:1214`)**; o nome do cenário (`"MCO2 - …"` contra `"TreeCycle - …"`, `:1183`, `:1211`); o rótulo passado ao registro de carga (`"MCO2"`/`"TREE"`, `:1191`, `:1220`).
2. **Há uma diferença que a auditoria B.2 não cita: TREE liga um segundo logger.** `scenario.AddNewLogger(new BlockFinalizationLogger(Paths.get(outputPath.replace(".csv", "-basic-log.csv"))))` (`MVPComparison.java:1219`); o MCO2 não faz isso. É um arquivo extra de saída. NÃO VERIFICADO: se esse logger consome `identityHashCode` ou altera a ordem de eventos (não li `BlockFinalizationLogger`); com `-XX:hashCode=2` a pergunta perde importância (Q36 A).
3. **O intervalo vira a escala de tempo da mineração.** `GlobalProofOfWorkNetwork.populateNetwork` calcula `hashPowerScale = dificuldadeGênese / (poderTotal × intervalo)` (`GlobalProofOfWorkNetwork.java:82-88`): o intervalo escala o poder de hash dos mineradores, e o bloco médio sai a cada `intervalo` s. Os cenários Casper herdam essa rede (`CasperFFGGlobalBlockchainNetwork.java:14`, estende `EthereumGlobalProofOfWorkNetwork`).
4. **Consequência declarável para o rótulo "PoS".** No JABS o CasperFFG é uma cadeia **minerada por prova de trabalho** (mineradores com poder de hash) com votos de finalização por checkpoint; os blocos não são propostos por validadores sorteados por estaca. [L] `CasperEthereumNetworkScenario.java:68-73` (rede PoW + `CasperFFGConfig`) e `GlobalProofOfWorkNetwork.java:72-83`. O pré-registro deve chamar a âncora de "CasperFFG do JABS (blocos minerados, finalização por checkpoint)", e não de "PoS" sem ressalva. A leitura de que isso afeta a validade externa da âncora é minha, **NÃO VERIFICADA**.
5. **Interação intervalo × tempo de finalização [C, qualitativa, NÃO MEDIDA]:** com `checkpointSpace = 14` blocos (`:1187`, `:1215`), a ordem de grandeza do tempo até um checkpoint é 14 × intervalo: ≈ 210 s com 15 s e ≈ 840 s com 60 s. Os 304 a 445 s de Tt do MCO2 com `duracao` de 600 s (`0015:1012`, `n1-conversao-n/saida-final/…:7`) já são metade da duração padrão; com TREE a duração padrão de 600 s (`MVPComparison.java:92`) mal cobriria um checkpoint. Os testes e scripts do repositório usam duração maior para TREE (3000 a 6000 s: `scripts/jabs/nr_byz_unica.py:102`, `nr_hscb_fase2.py:60`), o que é compatível, mas não prova nada.
6. **O sweep bizantino usa 15,0 s no POS:** `ByzantineSweep.java:219` (`15.0, 14`). Se a Bateria 2 usar o `ByzantineSweep` como motor do ataque para o CasperFFG, a variante implícita é a do MCO2. Não existe `TREE` no sweep (tipos: VOTING, POS, POW, POSA, DAGSPER, `ByzantineSweep.java:380-387`).
7. **Parlia, para comparação:** 3,0 s de bloco (`MVPComparison.java:1243`; `ByzantineSweep.java:265`). O YAML da Bateria 2 lista `casperffg` como um só nível (`.experimento/bateria2.yaml:15`), sem dizer a variante.
8. **O que a auditoria B.2 diz está confirmado, com o acréscimo do item 2.** MCO2 e TREE diferem em `averageBlockInterval` (15 × 60 s); mais o logger extra e o nome. Nada em `CasperFFG`, `EthereumCasperMiner` ou na rede muda entre as duas (as duas passam pelo mesmo `createNetwork`, `CasperEthereumNetworkScenario.java:66-99`). **NÃO VERIFICADO:** que `MVPComparison.java` e `Main.java:25-50` (onde "casper-ethereum" = 15 s e "casper-ignis" = 60 s) sejam as únicas diferenças em todo o repositório; li o caminho do estudo, não a suíte de testes inteira.

**Q-PoS. Qual variante do CasperFFG entra no fator A**

- A) **MCO2 (15,0 s)** como o nível `casperffg`. É a variante do `ByzantineSweep` (item 6), a das medições citadas em Q35 e a mais barata em duração de simulação (item 5);
- B) **TREE (60,0 s)**. Mesmo código, bloco 4× mais lento; só se o humano tiver motivo de domínio para essa cadência (nenhum registrado nos ADRs lidos);
- C) **As duas, com o intervalo de bloco como fator ou ablação rotulada** dentro de `casperffg` (uma só classe, um parâmetro). Mostra a sensibilidade ao intervalo, ao custo de células e de duração (item 5); exigiria decidir se a Bateria 2 recebe esse fator;
- D) **Outro intervalo** como parâmetro livre do cenário (o construtor aceita qualquer `double`, `CasperEthereumNetworkScenario.java:39-41`), declarado no pré-registro, com valor `a definir com o humano` e fonte;
- outra.

**Recomendação: A (MCO2), declarando o intervalo de 15,0 s no pré-registro e a nota do item 4 (blocos minerados).** Razão: é a variante que o motor de ataque já usa, e a diferença para TREE é só o intervalo (item 1), então a escolha **não** muda a estrutura do protocolo; ela muda a escala de tempo. Se o humano quiser mostrar a sensibilidade ao intervalo, C é a extensão natural, depois do piloto de custo (Q33 A). Isto **não escolhe** nenhum número novo: 15,0 e 60,0 s são constantes que o código já tem, **sem fonte de domínio** registrada (se 15 s representa algo do Ethereum real é conhecimento meu, **NÃO VERIFICADO neste repositório**).

**Efeito no pareamento:** o intervalo de bloco do Casper (15,0 s) e o do Parlia (3,0 s) já são constantes diferentes do protocolo (`0013:118`; Q35 "Fica aberto" (b)); a escolha A ou B não resolve essa assimetria entre protocolos.

### Parlia/universo (decisão C de 17/11/2026): tabela de f efetivo por n e protocolo para a Bateria 2

**O que isto é.** Implementação da decisão C (ESCOLHIDO acima): `f = ceil(pct × universo / 100)` em inteiros, com o **universo de cada protocolo**, e marca "dentro" ou "fora da faixa" contra `floor((u−1)/3)`. **Nada decide n**: n = 16, 64 e 100 são **ilustração declarada** (o n do E2 está `a definir`, Q33 A; 16 e 64 são níveis do E1 citados em Q33 B, 100 é o `n` do YAML, `.experimento/bateria2.yaml:9`). Os percentuais e os protocolos são os de `.experimento/bateria2.yaml:15-16`.

**Script e hash.** `docs/adr/anexos/0015-tabela-f-efetivo.py` (Python, biblioteca padrão), saída em `docs/adr/anexos/0015-tabela-f-efetivo.saida.txt`.
- sha256 do script: `f045ed56c1de4df3dcb72f2d359ddf9a0cf7db7b50c7714a00104e9da7842767`
- sha256 da saída: `b4baaf45a425da28d905f0dd9e88fa32c10b56b96eab3a9c8b9a3f1884f7eb72`
- Executado em 17/11/2026 com `python3 docs/adr/anexos/0015-tabela-f-efetivo.py` (rótulo: [C], **executado por script**, ao contrário das contas à mão das fatias anteriores). O script **não chama o simulador**: reproduz as fórmulas do código lido; a conferência contra o Java está nos itens de "Universos" abaixo, por leitura, não por execução.

**Universos usados (Q32 A) e a leitura que os sustenta [L]**

| Protocolo (nível YAML) | Universo u | Onde está no código |
|---|---|---|
| pbft, hotstuff, tendermint | n (todos os nós votam) | `ByzantineSweep.java:508-512` (`configFor`, VOTING: universo `[0,n)`) e `:533-541` (`universeSize` devolve `n` por padrão) |
| casperffg | n no modo `nodes` (todos os nós votam; `n` = mineradores + stakeholders) | `NetworkSizing.java:86-89` (`casperVoters` = `totalNodes`); `ByzantineSweep.java:513-516` (`configFor`, POS: todos os nós) e `:535-536` (`universeSize`) |
| parlia | **só os mineradores**: `max(1, (3n+5)/10)` em inteiros no modo `nodes` | `NetworkSizing.java:96-99` (`parliaValidators` = `miners`), `:133-139` (`nodes`: `(3L*n+5L)/10`, mínimo 1; `n<2` falha); `ByzantineSweep.java:517-520` (`configFor`, POSA: ids `[0, miners)`) e `:537-538` (`universeSize`) |
| nakamoto | n mineradores; o limiar BFT 1/3 **não se aplica** (`bftApplies` falso para POW) | `ByzantineSweep.java:510-511` (POW usa `[0,n)`) e `:562-564` (`bftApplies`) |

**O modo `nodes` é o que o estudo usa**, pela Q32 A (`0015:925`; `--n-semantics=nodes` obrigatória na bateria). A tabela do modo `nodes` coincide com o `legacy` em n = 64 e n = 100 para o Parlia (19 e 30 mineradores) e **difere em n = 16** (5 contra 4): o `legacy` é `(int)(n*0.3)` em `double` (`NetworkSizing.java:113-115`; teste no script, que usa `float` IEEE do Python igual ao `double` do Java).

**Tabela (saída do script, modo `nodes`).** "fora" = f > floor((u−1)/3): no código isso aciona o rótulo `# fora_da_faixa` e exige a flag `--allow-beyond-bft-threshold=true` (`ByzantineConfig.java:181-221`; `ByzantineSweep.java:129-146`; `MVPComparison.java:594-611`). No Nakamoto a coluna é só a contagem (sem faixa BFT).

| n | protocolo | universo u | limite BFT floor((u-1)/3) | f=0% | f=10% | f=20% | f=33% | f=40% | f=51% |
|---|---|---|---|---|---|---|---|---|---|
| 16 | pbft | 16 | 5 | 0 dentro | 2 dentro | 4 dentro | **6 fora** | **7 fora** | **9 fora** |
| 16 | hotstuff | 16 | 5 | 0 dentro | 2 dentro | 4 dentro | **6 fora** | **7 fora** | **9 fora** |
| 16 | tendermint | 16 | 5 | 0 dentro | 2 dentro | 4 dentro | **6 fora** | **7 fora** | **9 fora** |
| 16 | casperffg | 16 | 5 | 0 dentro | 2 dentro | 4 dentro | **6 fora** | **7 fora** | **9 fora** |
| 16 | parlia | 5 | 1 | 0 dentro | 1 dentro | 1 dentro | **2 fora** | **2 fora** | **3 fora** |
| 16 | nakamoto | 16 | n/a | 0 (n/a) | 2 (n/a) | 4 (n/a) | 6 (n/a) | 7 (n/a) | 9 (n/a) |
| 64 | pbft | 64 | 21 | 0 dentro | 7 dentro | 13 dentro | **22 fora** | **26 fora** | **33 fora** |
| 64 | hotstuff | 64 | 21 | 0 dentro | 7 dentro | 13 dentro | **22 fora** | **26 fora** | **33 fora** |
| 64 | tendermint | 64 | 21 | 0 dentro | 7 dentro | 13 dentro | **22 fora** | **26 fora** | **33 fora** |
| 64 | casperffg | 64 | 21 | 0 dentro | 7 dentro | 13 dentro | **22 fora** | **26 fora** | **33 fora** |
| 64 | parlia | 19 | 6 | 0 dentro | 2 dentro | 4 dentro | **7 fora** | **8 fora** | **10 fora** |
| 64 | nakamoto | 64 | n/a | 0 (n/a) | 7 (n/a) | 13 (n/a) | 22 (n/a) | 26 (n/a) | 33 (n/a) |
| 100 | pbft | 100 | 33 | 0 dentro | 10 dentro | 20 dentro | 33 dentro | **40 fora** | **51 fora** |
| 100 | hotstuff | 100 | 33 | 0 dentro | 10 dentro | 20 dentro | 33 dentro | **40 fora** | **51 fora** |
| 100 | tendermint | 100 | 33 | 0 dentro | 10 dentro | 20 dentro | 33 dentro | **40 fora** | **51 fora** |
| 100 | casperffg | 100 | 33 | 0 dentro | 10 dentro | 20 dentro | 33 dentro | **40 fora** | **51 fora** |
| 100 | parlia | 30 | 9 | 0 dentro | 3 dentro | 6 dentro | **10 fora** | **12 fora** | **16 fora** |
| 100 | nakamoto | 100 | n/a | 0 (n/a) | 10 (n/a) | 20 (n/a) | 33 (n/a) | 40 (n/a) | 51 (n/a) |

**Leituras diretas da tabela [C, do script]**

1. **Em n = 100 o PBFT, o HotStuff, o Tendermint e o CasperFFG têm f = 33 dentro da faixa e 40 e 51 fora**; em n = 16 e n = 64, **f = 33% já fica fora** (6 > 5; 22 > 21). O nível "33%" do YAML só é "no limite" em n = 100. A menor porcentagem inteira que excede a faixa é 32% (n = 16), 33% (n = 64) e 34% (n = 100) nos protocolos com universo n.
2. **O Parlia fica fora da faixa já em 33%** em todos os três n (2 > 1; 7 > 6; 10 > 9), e em n = 16 passa a 21% (f = 2 de 5 validadores). Em n = 16 o universo tem 5 validadores e limite 1: **f = 10% e 20% dão o mesmo f = 1**, e 33% e 40% dão o mesmo f = 2 (os níveis colapsam).
3. **Com universos pequenos os níveis percentuais colapsam e o `ceil` sobe o f real.** Ex.: Parlia n = 16, f = 10% → 1 de 5 = **20% do universo**; PBFT n = 16, f = 10% → 2 de 16 = 12,5%. A porcentagem pedida não é a efetiva. [C, do script: f/u]
4. **Sem a decisão C, o mesmo percentual sobre n levaria ao f absoluto idêntico em todos os protocolos**, que no Parlia seria f > u em n pequeno (por exemplo, f = 10 de 5 validadores em n = 16). A decisão C evita isso, ao custo das limitações abaixo.
5. **Parlia com n pequeno (fora da tabela principal):** n = 4 → 1 validador (limite 0); n = 7 → 2 (limite 0); n = 10 → 3 (limite 0); só em n ≥ 13 há limite ≥ 1 (saída completa no anexo). Isto é o achado da N1 de que Parlia em n = 4 tem 1 único validador, com quórum `votos > (V·2)/3` e, portanto, 1 voto finaliza (`n1-conversao-n/README.txt`, seção 1.3). Quando o limite é 0, **qualquer f ≥ 1 está fora da faixa**; o piloto (Q33 A) deve dizer se n < 13 entra no Parlia.

**Limitações que o pré-registro deve declarar**

L1. **A comparação entre protocolos é pelo mesmo percentual do universo de cada um, não pelo mesmo f absoluto, nem pelo mesmo percentual dos nós da rede.** Em n = 100 o PBFT recebe f = 10 e o Parlia f = 3 com "10%": são 10 contra 3 nós bizantinos, e 3% dos nós totais no Parlia. Diferenças de resultado entre os dois **misturam** efeito do protocolo e efeito do tamanho do universo. O pré-registro deve apresentar f e u por célula, não só o percentual.
L2. **Parlia: universo pequeno e níveis colapsados** (leitura 2, 3 e 5). Resultados de Parlia em f% distintos podem ser a mesma célula (mesmo f); a contagem de células independentes é menor que a do desenho fatorial. O número de sementes (YAML: 10, "PROVISÓRIO", `bateria2.yaml:17`) não compensa isso.
L3. **"Fora da faixa" é por protocolo**, e o limite `floor((u−1)/3)` é o do PBFT/HotStuff/Tendermint aplicado ao universo de cada um. Para o CasperFFG o quórum real **não** é 2/3 de todos os nós: o denominador S exclui os mineradores (dívida C1, `NetworkSizing.java:91-94`; `README.txt` seção 1.1), então o limite 1/3 de n **não é** o limiar do mecanismo de finalização do JABS; declarar como convenção de comparação. **NÃO VERIFICADO** o limiar efetivo do Casper em S = stakeholders (não li `CasperFFG`).
L4. **Nakamoto:** o percentual é de nós mineradores, mas o poder de hash é amostrado por minerador (`GlobalProofOfWorkNetwork.java:72-77`, `sampleHashPower`); a fração de nós **não** é a fração de poder de hash. O limiar de Nakamoto está `a definir com o humano` (comentário em `ByzantineSweep.java:557-561`); a tabela não marca faixa. O YAML inclui o nível `nakamoto` e o ADR-0000 o excluiu do plano (`index:16`); a decisão sobre mantê-lo é do humano.
L5. **Rótulo `# fora_da_faixa` não é uniforme no código.** No `ByzantineSweep` ele usa o **universo do tipo** (mineradores no POSA; todos os nós no POS) (`ByzantineSweep.java:129-146`), que é a decisão C. No `MVPComparison` o rótulo e o guarda usam `n` e só existem para HOTSTUFF (`MVPComparison.java:594-611`; `0015:910-917`): Tendermint, PBFT e o Parlia no `MVPComparison` não têm esse caminho de ataque, e o rótulo, se aplicado ali, usaria `n` em vez de `u`. Se a Bateria 2 usar o `MVPComparison` para algum protocolo além do HotStuff, o rótulo do Parlia estaria no universo errado. NÃO VERIFICADO qual dos dois motores a Bateria 2 usará (o YAML não diz); é decisão de implementação ligada às tarefas do loop 2.
L6. **O arredondamento é o teto (`ceil`) em inteiros** (Q34 A recomendada; ESCOLHIDO só no que a decisão C declara). O teto sobe o f efetivo (leitura 3); a tabela mostra o f efetivo, e o pré-registro deve declarar também f/u por célula.
L7. **O n é ilustrativo.** Trocar n muda a tabela inteira; refazer o script com o n do E2 quando Q33 A fechar. O script aceita editar `NS`.
L8. **A semântica `nodes` decompõe 30/70** sem fonte (`NetworkSizing.java:16-17`, "a definir com o humano"; B-44, `0015:288`); o universo do Parlia **herda** essa proporção. Mudar a proporção muda a tabela do Parlia.
L9. **Posições dos bizantinos:** o universo do sorteio no Parlia é só os mineradores (ids `[0, miners)`), no Casper todos os nós, no HotStuff os n; o sorteio é pelo gerador único (BYZ-UNICA, commit `99d50cc`). A seleção não é pareada entre protocolos (universos diferentes), só reproduzível por semente em cada um.

**O que isto NÃO decide:** n do E2, nível `nakamoto` no plano, o limiar de Nakamoto, a proporção 30/70, o motor da Bateria 2.

### Pendências desta fatia: repetição por script do achado de gás (Q35) e o que não confirmei

**Q35, achado dos ≈22%: repetido por script, CONFIRMADO.** `docs/adr/anexos/0015-q35-gas-reproducao.py` (biblioteca padrão), saída em `0015-q35-gas-reproducao.saida.txt`. sha256 do script `8eebdac5c134af947700335f9a47b00c1d665ab0bca44eade0572a39e94add87`; da saída `aa1ff07df04fadbc3fcb043af53d5750a95d51d739f10843c183e0e8d0485833`. As tabelas foram copiadas de `TransactionFactory.java:27-45` e a regra de sorteio de `RandomnessEngine.java:30-41`. Resultados [C, executado por script, 17/11/2026]:

| Grandeza | Conta à mão (Q35 itens 3 e 4) | Script |
|---|---|---|
| soma das 20 entradas da tabela de gás | ≈ 0,7997 | 0,799744 |
| soma das 19 testadas pelo laço | ≈ 0,7766 | 0,776623 |
| P(gás = 12 475 587) | ≈ 22,3% | **22,34%** (exato); Monte Carlo 22,2% a 22,4% em 3 sementes |
| gás médio por tx | "da ordem de 3 milhões" | **2 909 772** (exato) |
| tx por bloco pelo limite de gás | "da ordem de 4" | razão de médias 4,30; **simulado (Python, parada na primeira que estoura, como `EthereumMinerNode.java:54-56`): média ≈ 3,7, mediana 2, p10 = 1, p90 = 9** |
| P(tamanho = 296 985 B) | ≈ 0,8% | 0,83% (tamanho médio 2 873 B) |
| g máximo com g·L ≤ 12 500 000 | 1 250 000 / 125 000 / 25 000 | idem (L = 10 / 100 / 500) |

Duas observações que **corrigem ou refinam** o texto da Q35: (i) a "ordem de 4 tx por bloco" é a razão de médias; o que o laço produz é uma distribuição **muito assimétrica** (mediana 2; bloco com 1 tx em ≥ 10% dos casos; máximo na casa de dezenas), e duas tx de gás máximo nunca cabem juntas (2 × 12 475 587 > 12 500 000); (ii) o Monte Carlo usa o gerador do Python, não o `MersenneTwister` do JABS, portanto os 3,7 e a mediana valem só como ordem de grandeza, e **não** modelam a ordem de iteração do `HashSet` do `memPool` (a seleção real não é uma amostra independente; Q35 item 6). A condição pedida pelo humano ("repetir por script o achado dos 22% antes de aceitar") está **cumprida para o percentual**; o valor de g continua `a definir com o humano`. O menor bin (21 001) é ≤ 25 000, compatível com a trava para L = 500 (o que a Q35 já dizia).

**O que não consegui confirmar nesta fatia**

1. **Q36:** o hash `325f4e3`. `git log` no repositório atual não o encontra (39 commits, o mais antigo `964e0ab`, de 31/10; o `325f4e3` seria de 30/10) e o worktree `/tmp/opencode/base-325f4e3` não existe. É inferência minha que o histórico foi reescrito; NÃO VERIFICADO. Não verifiquei que o veredito `APROVADO` da N1 (`.jsonl:117`) vale para o código de hoje (a N1 é de 09/11; o `MVPComparison` mudou depois). Não li `nao_regressao.py` por inteiro.
2. **Variante PoS:** não li `BlockFinalizationLogger` (o logger extra do TREE, `MVPComparison.java:1219`): se ele afeta a saída é NÃO VERIFICADO. Não li `CasperFFG.java` (limiar efetivo de finalização). Não verifiquei por busca em todo o repositório de testes que MCO2 e TREE só diferem nos pontos listados. Não rodei nem compilei nada.
3. **Parlia/universo:** a tabela reproduz fórmulas lidas (`NetworkSizing`, `ByzantineConfig.bftLimit`), **não executa o Java**; a conferência por execução do dimensionamento já existe no `diagnostico-executado.txt` da N1 (63 linhas OK) para n = 4, 16, 64, 128, 256, 400, 800, **não** para n = 100 (que a tabela usa). `nodes(100) = 30` por fórmula: `(3·100+5)/10 = 30`. Não li a tabela de f feita para o `ByzantineConfig.forUniverse` quanto ao tratamento de `f > u`. Qual motor (`MVPComparison` ou `ByzantineSweep`) a Bateria 2 usará **não consta** no YAML; isso muda a limitação L5.
4. **Não tocei** em `src/`, nos outros ADRs nem em `.experimento/`. O `git status` mostra `docs/adr/0013-formacao-de-bloco-e-demanda.md` modificado: **não foi por mim** (modificação anterior, da fatia 1 ou de sessão anterior; não conferi o diff).
5. **Citações de linha:** conferi as de `ByzantineSweep.java`, `NetworkSizing.java`, `ByzantineConfig.java` e `MVPComparison.java` por leitura nesta sessão; as do ADR-0013 e do README da N1 vêm das leituras acima, sem reabrir cada trecho citado.

**Decisões que o humano ainda deve tomar nesta fatia** (nenhuma foi tomada por mim): Q36.1 (forma da prova; recomendação A), Q36.2 (veredito da N1; recomendação A, a ser confirmada contra o código atual), Q36.3 (baseline; recomendação A), Q-PoS (variante; recomendação A = MCO2 15,0 s), n do E2 e o nível `nakamoto` (limitações L4 e L7), motor da Bateria 2 (L5), valor de g (Q35).
