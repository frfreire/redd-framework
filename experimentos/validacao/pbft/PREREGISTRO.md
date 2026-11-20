# PRÉ-REGISTRO (RASCUNHO, etapa 1, revisão de 22/11/2026): V-ABC-pbft (só V-A; V-B e V-C fora, com motivo)

Data do rascunho original: 2026-11-21; revisado em 2026-11-22 depois da T6, da política de liderança e do pino do Tendermint. Tarefa: `V-ABC-pbft` (sucede a "Conferência do PBFT", seção 10 do pré-registro do Tendermint). Autor: `jabs-experimento` (**não aprova o próprio resultado**).
Impacto: **ALTO** (portão de validação): o loop 3 vai ao humano, sem arbiter.

**ESTADO: RASCUNHO. NÃO é o pré-registro congelado.** Falta a decisão do humano sobre os itens da seção 9 (inclusive se este V-ABC deve existir), os scripts do bloco `va`, o hash e o commit.
**Nenhum script de célula, de análise ou de hash foi escrito para o PBFT**; só serão escritos depois que o humano aceitar os critérios, no padrão de `experimentos/validacao/tendermint/scripts/`. **Nenhuma célula de portão foi rodada.**

## 0. Divulgação: o que eu já vi antes de fixar a regra (este rascunho NÃO é cego)

1. **Sondas exploratórias ANTES da T6 (21/11/2026)**, semente 1, n = 4 a 16: o PBFT puro de então commitava 5 a 10 blocos por altura (7 a 10× os pacotes de livro). **Isso foi explicado e corrigido pela T6** (ADR-0007 opção 2; critic APROVADO; PR #6): view = altura decidida, primário único por altura, sem a proposta periódica do genesis. **Esses números não valem mais e não entram em nada.**
2. **Sondas DEPOIS da T6 (21/11/2026)**, semente 1, `PBFTLANScenario`, 60 s, primário rotativo (T6), alturas 3 a hmax−3 (fora do repositório; sem CSV arquivado). O que vi, em **uma** semente:

   | n | alturas | pacotes/altura | bytes de consenso/altura |
   |---|---|---|---|
   | 4 | 116 | 27,0 | 1 038 |
   | 7 | 77 | 90,0 | 3 588 |
   | 10 | 66 | 189,0 | 7 650 |
   | 16 | 36 | 492,5 | 20 205 |

   Expoente (OLS de ln em ln n, 4 pontos): **≈ 2,1** nos dois. **Os pacotes batem EXATAMENTE com a contagem de livro `(n−1)(2n+1)` em n = 4, 7 e 10** (27, 90, 189); o teste `PBFTPrimarioPorAlturaTest` já exige isso para n = 4 e 7. Os bytes de consenso batem com `(n−1)·O + 2n(n−1)·(H+O)` com H = 32 B (hash), O = 10 B: 1 038, 3 588, 7 650. **Em n = 16 a sonda deu 492,5 contra 495 (−0,5 %) e 20 205 contra 20 310 (−0,5 %), e eu NÃO expliquei a diferença** (hipótese não testada: pacotes ainda em voo no fim da janela).
   Logo a **classe esperada (O(n²) em pacotes e bytes de consenso; O(n) em bytes totais) e a fórmula me são conhecidas; a previsão NÃO é cega.** As sementes do estudo serão disjuntas de 1 e de 7.
3. **Conheço os V-ABC do HotStuff e do Tendermint.** Isso orienta a minha expectativa, **não o critério**.
4. **O PBFT puro existe desde 21/11/2026** (`PBFT-puro-mvpcomparison-ledger`, critic APROVADO). A T6, a política de liderança (`--pbft-leader-policy`, critic: `GAP_NAO_BLOQUEANTE` aceito pelo humano) e o ledger estão em `main`.

## 1. O que se quer validar e a granularidade

**Origem do requisito (a confirmar com o humano):** `.experimento/PROTOCOLO-EXPERIMENTAL.md` define P4a (HotStuff) e P4b (Tendermint); **não existe pré-condição P4 do PBFT** lá. O pedido vem da seção 10 do pré-registro do Tendermint ("bloco V-A do PBFT: O(n²) agregado; por nó O(n)"). Se o humano não quiser este V-ABC, o arquivo se descarta. **A ordem depende da granularidade**; declaro as duas:

| Granularidade | Medida | Fonte | Esperado |
|---|---|---|---|
| **AGREGADO DE REDE por altura decidida** (principal; decide) | `rede_msgs` (pacotes), `rede_consenso` (`CbRedeConsenso_avg_B`), `rede_total` (`CbRede_avg_B`) | `NetworkHeightLedger` do PBFT (soma sobre todos os nós receptores, sem corte local, nunca autoentrega); `mvp-results-cb.csv` | `rede_msgs` e `rede_consenso`: **O(n²)**; `rede_total`: **O(n)** (a carga do bloco, enviada pelo primário, domina) |
| **POR NÓ** (só rótulo, **não coberto**) | pacotes e bytes por nó e por altura | **não existe** observador do PBFT: o `MVPComparison` exporta só a soma da rede | previsão por código: O(n) por nó; **não verificada por dado** |

- **Uma decisão por altura** (T6): "por altura decidida" agora é uma unidade correta (antes, ~5 a 10 decisões).
- **Tt NÃO é medido** (sem gerador de transações; `-1` no CSV agregado). Nenhum critério usa Tt.
- **A autoentrega (D2) não entra** na soma da rede (regra 6); fica em `mvp-results-autoentrega.csv`. **Cb do primeiro nó** não decide.

## 2. Instrumento: commit, build, JVM

- **Pino proposto: o MESMO do V-ABC-tendermint**, `COMMIT_PINADO = 2980d2695436ff4efea0138275aaf04a796ff6ff` (`src/` `326bf654091f16afa49323d2e88956fa4c12bf15`, `pom.xml` `32f934e332097d6bfed46830ff03132c36763670`): ele já contém o PBFT puro, a T6 e a política de liderança. **Se algo em `src/` mudar antes do pino, o pino e este V-A mudam.** A conferência do pino é a mesma de `preparar-tm.py` (a replicar para o PBFT).
- **Comando por célula (previsto):** `java -Xmx2g -XX:+UnlockExperimentalVMOptions -XX:hashCode=2 -cp <build pinado> jabs.example.MVPComparison --protocols=PBFT --validators=<n> --trials=1 --duration=<s> --seed=<semente − n·1000> --output=<dir> --csv-out=<dir>/mvp-results.csv --pbft-max-future-horizon=1000 --pbft-leader-policy=STABLE`. **`-XX:hashCode=2` obrigatório.** Teto 1000 **decidido** (ADR-0015); `STABLE` = modo do estudo (ADR-0010 PI-14).
- **Ambiente de execução:** o mesmo declarado no pré-registro do Tendermint (Windows 11, Java 21.0.8 Oracle, Maven 3.9.11; o V-ABC do HotStuff foi Linux, OpenJDK 25, Maven 3.8.7), com a equivalência medida em **uma** célula de HotStuff. **Para o PBFT essa equivalência não foi medida.**
- **Sem segunda JVM** (não há observador): a integridade só usa `rc`, `blocos > 0` e o CSV por bloco; **não há conferência cruzada entre duas JVMs**. Limite declarado.
- **Fixtures herdadas do código (não são parâmetros do estudo):** PREPARE/COMMIT = hash 32 B + overhead 10 B = **42 B**; PRE_PREPARE = tamanho do bloco + 10 B. **O tamanho do bloco NÃO é parâmetro**: é amostrado de `BITCOIN_BLOCK_SIZE_2020` (`TODO: Size of PBFT Blocks` no código); a contagem de transações (20 a 79) não altera o tamanho. **Consequência:** `rede_total` tem variância alta e **não decide**; pacotes e bytes de consenso não dependem do tamanho do bloco.

## 3. Critérios (a congelar após a decisão do humano)

### 3.1 Regime e carga
LAN simulada do JABS (`LAN100MNetworkStats`), malha completa, `PBFTLANScenario`, **líder estável** (nó 0 propõe toda altura; PI-14), sem ataque, sem view change, 1 trial por célula. **Com `ROTATING` a contagem de pacotes por altura é a mesma** (T6; o que muda é quem envia a proposta e, portanto, o tráfego por nó e por bloco); `ROTATING` **não é rodado** aqui.

### 3.2 V-A, agregado de rede (decide)
**Níveis:** n ∈ {4, 7, 10, 16, 31}; 5 sementes por n ⇒ **25 células**. n mínimo = 4 (o código recusa n < 4).
**Previsão pela fórmula (por altura decidida; H = 32, O = 10):** pacotes `(n−1)(2n+1)`: 27, 90, 189, 495, 1 890 (n = 4, 7, 10, 16, 31); bytes de consenso `(n−1)·O + 2n(n−1)·(H+O)`: 1 038, 3 588, 7 650, 20 310, 78 420 (conferir por script). `rede_total` não tem previsão (bloco amostrado).
**Critérios, por medida (DECIDEM só as duas do agregado):**
- **I (integridade, por célula):** rc = 0; `blocos > 0`; CSV agregado, `-cb.csv` e `-autoentrega.csv` presentes com 1 linha de dados; `Tt_avg = -1` (nunca zero); `SemAltura_B = 0` (honesto: nada além do teto); alturas de regime ≥ **mínimo (seção 9, item 2)**. Célula não íntegra **não é excluída**: o veredito vira INCONCLUSIVO (instrumento).
- **F (fidelidade à fórmula):** `|medido − fórmula| / fórmula ≤ tolerância` em toda célula íntegra, em `rede_msgs` e `rede_consenso`. **Tolerância PROPOSTA: 1 % (seção 9, item 3).** O Tendermint usou 0,1 %; **aqui a sonda deu −0,5 % em n = 16 sem explicação**, então 0,1 % poderia reprovar por um efeito de borda que eu não entendo.
- **E (classe):** `b` pela OLS de ln(métrica) em ln(n) sobre as médias por n: b < 0,5 O(1); 0,5 ≤ b < 1,5 O(n); 1,5 ≤ b < 2,5 O(n²); b ≥ 2,5 "> O(n²)".
- **A1 (decide):** `rede_msgs` com 1,5 ≤ b < 2,5 **e** F. **A2 (decide):** `rede_consenso` com 1,5 ≤ b < 2,5 **e** F. `rede_total` e tudo por nó: **só rótulo**.
- **S (`validar-complexidade.py`, REPORTADO, não decide):** `--esperado quadratico` para `rede_msgs` e `rede_consenso`.
- **Veredito mecânico:** INCONCLUSIVO (instrumento) = célula faltante, extra ou não íntegra, ou n sem célula íntegra. INCONSISTENTE = A1 ou A2 falha. CONSISTENTE = nenhum dos anteriores. **Não é aprovação**: o loop 3 (humano) decide.
- **Réplica:** reportam-se média, mínimo, máximo e desvio entre as 5 sementes; **sem IC com precisão fingida**.
- **O que NÃO se afirma:** o V-A mede a **classe** e a fidelidade à fórmula **do código**; **não** diz que o modelo reproduz o PBFT do artigo (sem view change, sem checkpoint, sem assinatura individual modelada, bloco amostrado de distribuição Bitcoin).

### 3.3 V-B (limiar bizantino): **FORA**
O PBFT puro não lê ataque no `MVPComparison`. Há EQUIVOCATION no PBFT, mas só pelo ARQ/`ByzantineSweep`, que não é o PBFT puro. **Mesmo com ataque, o `STABLE` sem view change trava se o nó 0 for bizantino** (resultado bimodal medido: n = 10, f = 3, sementes 1 a 10: as sementes 2, 6 e 7 dão 0 blocos; as outras 7, 51 a 61): abrir V-B exige tarefa própria **e** a decisão do PI-14 (o líder pode ser bizantino?), mais novo pré-registro.

### 3.4 V-C (confronto com publicado): **proposta de declarar INVIÁVEL (aguarda o humano)**
Não há fonte VERIFICADA de vazão/latência do PBFT em LAN comparável (sem Tt, bloco não parametrizável). **Eu não busquei fontes nesta rodada**: o argumento é por ausência de Tt e de bloco parametrizável, **não** por revisão de literatura.

## 4. Parâmetros
Só dois `--pbft-*`: **teto 1000 (decidido, ADR-0015)** e **política `STABLE` (modo do estudo, PI-14; a confirmar neste V-A)**. **Não parametrizáveis** hoje: tamanho do bloco, número de transações, tamanho de PREPARE/COMMIT, timeouts de view (não há view change). Isso é limite, não decisão.

## 5. V-B: não se aplica.

## 6. Sementes, replicações, regime
- `semente = semente_base + n·1000 + replicação`; `MVPComparison --seed = semente − n·1000`. **Base proposta: 1 120 345** (faixas 1 124 346 a 1 151 350). **A disjunção** de todas as sementes já usadas (HotStuff, Tendermint, K40, piloto) **precisa ser conferida por script**; **não foi conferida**. As sementes 1 e 7 das sondas **não entram**.
- **Réplicas:** 5. **Duração e mínimo de alturas: A DEFINIR (seção 9, item 2).** **Dado da sonda pós-T6 (60 s):** n = 4: 116 alturas; n = 7: 77; n = 10: 66; n = 16: 36 (≈ 0,6 altura/s); **n = 31 não foi medido**.
- **Transiente:** a janela de regime do código (alturas 2 a hmax−2) já exclui o arranque.
- **Determinismo (verificação extra):** 2 células rodadas **duas vezes** com a mesma semente (n = 7 e n = 16), saída idêntica (CSVs agregados e por bloco). **Não entram nas estatísticas.**

## 7. Custo e paralelismo
**Não medido.** Só há durações de parede das sondas (segundos a poucos minutos, n ≤ 16). **Não estimar** o custo de n = 31 a 300 s sem uma célula piloto que **não conte** como dado de portão.

## 8. O que depende do pino (e o que o invalida)
1. **Qualquer mudança em `src/` depois do pino** (em particular `PBFT.java`, `PBFTP2P`, `PeerBlockchainNode`, `NetworkHeightLedger`, `CbRedeMedida`, `PBFTLocalLANNetwork`) invalida este V-A.
2. A tabela de artefatos e o hash do PREREGISTRO só valem depois da decisão do humano e da gravação.

## 9. Decisões que dependem do humano
1. **Este V-ABC deve existir?** (origem do requisito, seção 1.) Se sim, a ordem sugerida é rodar o do Tendermint primeiro.
2. **Duração e mínimo de alturas** (seção 6). Dados: 0,6 altura/s em n = 16; n = 31 desconhecido. **Opções:** (a) 300 s e mínimo **50** alturas para todo n (n = 16 dá ≈ 180; n = 31 pode ficar abaixo, e então é INCONCLUSIVO por regra); (b) mínimo 100 (n grande tende a INCONCLUSIVO); (c) duração por n (mais custo). **Sugestão: (a).**
3. **Critério F e tolerância:** aceitar F com **1 %** (ou outra tolerância), sabendo que a sonda pós-T6 deu −0,5 % em n = 16 sem explicação; ou rodar um n = 16 piloto fora do portão para entender a diferença antes de fixar (custo: uma célula que não conta como dado).
4. **Política `STABLE` neste V-A** (sem ataque os contadores não dependem dela). **O PI-14 ("o líder pode ser bizantino?") não afeta este V-A**, só um futuro V-B.
5. **V-B e V-C:** confirmar "fora".
6. **Pino e commit** do pré-registro congelado: este agente **não commita**.

## 10. O que NÃO foi verificado (limites)
- **Nenhuma célula de portão foi simulada**; as previsões vêm de leitura de código e de sondas exploratórias de uma semente.
- **A diferença de −0,5 % em n = 16** (seção 0) não foi explicada.
- **Por nó:** sem observador; nenhuma previsão por nó foi medida.
- **Equivalência de ambiente (Windows/Java 21 × Linux/OpenJDK 25) para o PBFT:** não medida (só uma célula de HotStuff).
- **`-XX:hashCode=2` não altera o PBFT:** só há a observação do critic (saída idêntica sem a flag).
- **Custo** de qualquer célula com n ≥ 16; **taxa de alturas em n = 31**.
- **Distâncias do PBFT real:** sem view change e sem checkpoint; sem assinaturas individuais modeladas (42 B por voto); LAN simulada; **bloco amostrado de uma distribuição Bitcoin**.
- Independência entre sementes consecutivas (MersenneTwister por semente).

## 13. O que é pós-hoc (mistura proibida)
Resultado primário = **só as 25 células novas** do bloco `va`. **Pós-hoc (rotulado e fora do veredito):** qualquer fórmula escrita **depois** de ver dado do portão; outra tolerância; outro limiar de `b`; exclusão de célula; conclusão sobre n > 31; **qualquer comparação PBFT × Tendermint × HotStuff**; qualquer número publicado não VERIFICADO; as sondas da seção 0 (**não são dado do estudo**).

---
### Artefatos (SHA-256)
**PENDENTE.** Enquanto o humano não decidir a seção 9, este arquivo **não tem hash**. Artefatos previstos (ainda não escritos): `config/fatores-va.json`, `celulas-esperadas.json`, scripts de geração, execução e análise do bloco `va`.
