# PRÉ-REGISTRO (RASCUNHO, etapa 1): V-ABC-pbft (só V-A; V-B e V-C fora, com motivo)

Data do rascunho: 2026-11-21. Tarefa: `V-ABC-pbft` (sucede a "Conferência do PBFT" da seção 10 do pré-registro do Tendermint). Autor: `jabs-experimento` (**não aprova o próprio resultado**).
Impacto: **ALTO** (portão de validação): o loop 3 vai ao humano, sem arbiter.

**ESTADO: RASCUNHO. NÃO é o pré-registro congelado.** Falta o pino (seção 2), a decisão do humano sobre os itens da seção 9 e o commit do arquivo.
**Nenhum script de célula, de análise ou de hash foi escrito para o PBFT**: eles só serão escritos depois que o humano aceitar os critérios, no padrão de `experimentos/validacao/tendermint/scripts/`.

## 0. Divulgação: o que eu já vi antes de fixar a regra (este rascunho NÃO é cego)

1. **Rodei sondas exploratórias do PBFT puro e vi números.** Semente 1, `PBFTLANScenario`, 20 a 60 s, n = 4, 7, 10, 13 e 16 (sondas em scratchpad, **fora do repositório**, sem CSV arquivado). O que vi, em **uma** semente, com 21 a 36 alturas por n:
   - bytes de consenso por altura (soma da rede): 8 986 (n = 4), 37 124 (7), 51 405 (10), 119 258 (13), 171 455 (16); pacotes por altura: 233,7; 931,8; 1 270,8; 2 922,7; 4 179,5. Expoente (OLS de ln em ln n, extremos): **≈ 2,1** nos dois.
   - bytes totais por altura: 29 MB (n = 4) a 141 MB (n = 16), expoente **≈ 1,1**; a carga do bloco domina.
   - **cerca de 7 a 10 vezes mais pacotes por altura do que a contagem de livro** (n−1)(2n+1) (27 em n = 4, razão 8,7; 90 em n = 7, 10,4; 189 em n = 10, 6,7; 495 em n = 16, 8,4). **Rastreado em 21/11/2026 (seção 0-bis): a redundância é a multiplicidade de blocos COMMITADOS por altura, não reenvio nem defeito do ledger.** As 3 cópias da carga do bloco por nó são as PRE_PREPAREs de vários proponentes na mesma altura.
   Logo a **classe esperada (O(n²) em pacotes e bytes de consenso; O(n) em bytes totais)** me é conhecida; ela **não é uma previsão cega**. As sementes do estudo serão **disjuntas** de 1 e de 7 (seção 6), mas o viés de expectativa existe e está declarado.
2. **Conheço os V-ABC do HotStuff e do Tendermint** (classe O(n²) em consenso para o Tendermint; O(n) em pacotes e O(n²) em bytes de consenso INDIVIDUAL para o HotStuff). Isso orienta a minha expectativa, **não o critério**.
3. **O PBFT puro só existe desde 21/11/2026** (task `PBFT-puro-mvpcomparison-ledger`, critic APROVADO na rodada 3). Nenhuma célula de portão foi rodada.

## 0-bis. Rastreio da redundância (feito em 21/11/2026, só leitura de código e sondas em cópia; **nenhum código do repositório foi alterado**)

1. **Contagem por tipo e por altura** (cópia de `HEAD` com um contador no hook de `PeerBlockchainNode`; n = 4, semente 1): na altura 3, **7 blocos distintos** circulam, e PRE_PREPARE = 21 = 7 × 3, PREPARE = 84 = 7 × 12, COMMIT = 84 = 7 × 12 (pacotes de rede). Ou seja, os pacotes por altura = **(nº de blocos na altura) × a contagem de livro por bloco** (27 em n = 4, sem autoentrega). Não há reenvio de votos: o `PBFTP2P` só faz o `broadcastMessage` direto (n pacotes, um deles para si).
2. **Vários blocos por altura são COMMITADOS, não só propostos** (reflexão sobre `committedBlocks`, n = 4, 30 s): cada nó commitou **127 a 130 blocos em 27 alturas**, com **até 10 commits na mesma altura** e **23 das 27 alturas com mais de 1 commit**; o número de view chegou a 127–130 (um incremento por bloco commitado, não por altura).
3. **Vários proponentes por altura:** os blocos de uma mesma altura vêm de 3 a 4 criadores distintos (n = 4) e 4 criadores distintos (n = 7). Hipótese da causa (**não provada**): cada nó decide "sou o primário" pela sua própria `currentViewNumber` (`PBFT.java:~590`, `getCurrentPrimaryNumber()`), que avança a cada commit local; como vários blocos da mesma altura são commitados, as views divergem entre nós e mais de um nó se julga primário. O modelo de primário do ADR-0007 (`(altura + trocas) % n`) **não** é o que o código executa.
4. **Consequência para o V-A:** a soma da rede **"por altura"** mistura ~5 a 10 decisões. Os bytes de consenso e os pacotes por altura crescem como (nº de decisões por altura) × O(n²). Nas sondas o nº de blocos por altura ficou entre **7 e 10** em n = 4, 7 e 10; **não determinei se cresce com n** (se crescer, o expoente medido por altura é maior que o da decisão).
5. **Isto é uma propriedade do PBFT puro do JABS, não do ledger** (o ledger fecha com os livros do PBFT e conta cada pacote uma vez). **Mas torna "por altura decidida" uma unidade enganosa para o PBFT**: o dado seria, na verdade, "por altura, com ~5 a 10 decisões concorrentes". Ver seção 9, item 1.

## 1. O que se quer validar e a granularidade

**Origem do requisito (a confirmar com o humano):** `.experimento/PROTOCOLO-EXPERIMENTAL.md` define P4a (HotStuff) e P4b (Tendermint); **não existe pré-condição P4 do PBFT** lá. O pedido vem da seção 10 do pré-registro do Tendermint ("bloco V-A do PBFT: O(n²) agregado; por nó O(n)"). Se o humano não quiser este V-ABC, o arquivo se descarta. **A ordem depende da granularidade**; declaro as duas:

| Granularidade | Medida | Fonte | Esperado |
|---|---|---|---|
| **AGREGADO DE REDE por altura decidida** (principal; decide) | `rede_msgs` (pacotes), `rede_consenso` (`CbRedeConsenso_avg_B`), `rede_total` (`CbRede_avg_B`) | `NetworkHeightLedger` do PBFT (soma sobre todos os nós receptores, sem corte local, nunca autoentrega); `mvp-results-cb.csv` | `rede_msgs` e `rede_consenso`: **O(n²)** (PREPARE e COMMIT todos-para-todos); `rede_total`: **O(n)** (a carga do bloco, enviada pelo primário, domina) |
| **POR NÓ** (só rótulo, **não coberto**) | pacotes e bytes por nó e por altura | **não existe** observador do PBFT: o `MVPComparison` exporta só a soma da rede | previsão por código: O(n) por nó (cada nó recebe de n−1 pares); **não verificada por dado** |

- **Tt NÃO é medido** (o PBFT puro não tem gerador de transações; sai `-1` no CSV agregado). Nenhum critério usa Tt.
- **A autoentrega (D2) não entra** na soma da rede (regra 6); fica em `mvp-results-autoentrega.csv`.
- **Cb do primeiro nó** (`CbPrimeiro_*`) **não decide** (artefato do ponto de medição; ADR-0015 D1).

## 2. Instrumento: commit, build, JVM

- **`COMMIT_PINADO = a definir`.** Depende de mergear em `main`: PR #3 (`--n-semantics`), PR #5 (ataque uniforme) e o branch do PBFT puro (`pbft-puro-ledger`). O PBFT puro **ainda não está em `main`**.
- **Comando por célula (previsto):** `java -Xmx2g -XX:+UnlockExperimentalVMOptions -XX:hashCode=2 -cp <build pinado> jabs.example.MVPComparison --protocols=PBFT --validators=<n> --trials=1 --duration=<s> --seed=<semente − n·1000> --output=<dir> --csv-out=<dir>/mvp-results.csv --pbft-max-future-horizon=1000`. **`-XX:hashCode=2` obrigatório.** O teto 1000 é **decidido** (ADR-0015, PARIDADE).
- **Build a partir do git** (`git archive` do pino); a árvore de trabalho não é usada.
- **Sem segunda JVM** (não há observador): a integridade só pode usar `rc`, `blocos > 0` e o CSV por bloco; **não há conferência cruzada entre duas JVMs** como no Tendermint. Declarado como limite.
- **Fixtures sem fonte, herdadas do código (não são parâmetros do estudo):** tamanho de PREPARE/COMMIT = hash 32 B + overhead 10 B = **42 B**; PRE_PREPARE = tamanho do bloco + 10 B; sobrecarga `PBFT_VOTE_SIZE_OVERHEAD = 10`. **O tamanho do bloco NÃO é parâmetro**: é amostrado de `BITCOIN_BLOCK_SIZE_2020` (`BlockFactory.sampleBitcoinBlockSize`, com um `TODO: Size of PBFT Blocks` no código), e a contagem de transações (20 a 79) não altera o tamanho. **Consequência:** `rede_total` tem variância alta e **não decide**; os bytes de consenso e os pacotes não dependem do tamanho do bloco.

## 3. Critérios (a congelar após o pino)

### 3.1 Regime e carga
LAN simulada do JABS (`LAN100MNetworkStats`), malha completa, `PBFTLANScenario`, **primário `currentViewNumber mod n`** (o ADR-0007 define `(altura + trocas) % n` e o ADR-0009 registra a tensão com "líder estável"; **não resolvida aqui**), sem ataque, 1 trial por célula. **Sem fórmula analítica validada** para o PBFT: a contagem de livro (n−1)(2n+1) por altura **não bate** com a sonda (7 a 10×), então **não há critério de fidelidade à fórmula (F)**; qualquer fórmula escrita depois de ver o dado é **pós-hoc** (seção 13).

### 3.2 V-A, agregado de rede (decide)
**Níveis:** n ∈ {4, 7, 10, 16, 31} (mesmos do Tendermint); 5 sementes por n ⇒ **25 células**. **n mínimo = 4** (o código recusa n < 4).
**Critérios, por medida (DECIDEM só as duas do agregado):**
- **I (integridade, por célula):** rc = 0; **`blocos > 0`**; CSV agregado, `-cb.csv` e `-autoentrega.csv` presentes e com 1 linha de dados; `Tt_avg = -1` (nunca zero); `SemAltura_B = 0` (corrida honesta: nada além do teto); alturas de regime ≥ **mínimo a definir (seção 9, item 2)**. Célula não íntegra **não é excluída**: o veredito vira "INCONCLUSIVO (instrumento)".
- **E (classe):** `b` pela OLS de ln(métrica) em ln(n) sobre as médias por n. b < 0,5 O(1); 0,5 ≤ b < 1,5 O(n); 1,5 ≤ b < 2,5 O(n²); b ≥ 2,5 "> O(n²)".
- **A1 (decide):** `rede_msgs` com 1,5 ≤ b < 2,5. **A2 (decide):** `rede_consenso` com 1,5 ≤ b < 2,5. `rede_total` e tudo por nó: **só rótulo** (previsto O(n) para `rede_total`, **sem critério**).
- **S (`validar-complexidade.py`, REPORTADO, não decide):** `--esperado quadratico` para `rede_msgs` e `rede_consenso`.
- **Veredito mecânico:** **INCONCLUSIVO (instrumento)** = célula faltante, extra ou não íntegra, ou n sem célula íntegra. **INCONSISTENTE** = A1 ou A2 falha. **CONSISTENTE** = nenhum dos anteriores. **Não é aprovação**: o loop 3 (humano) decide.
- **Réplica:** diferente do Tendermint, o tráfego do PBFT **varia por altura** (a sonda mostrou de 108 a 189 pacotes na soma da rede nas seis primeiras alturas de n = 4): **não há "determinismo em bytes por altura"**. Reportam-se média, mínimo, máximo e desvio entre as 5 sementes; **sem IC com precisão fingida**.
- **O que NÃO se afirma:** o critério mede a **classe** de crescimento (b), **não** a constante. A redundância de 7 a 10× sobre o PBFT de livro **está explicada** (seção 0-bis: ~5 a 10 blocos commitados por altura), mas **não é validada como comportamento correto do PBFT**: se for defeito do modelo, a classe pode continuar O(n²) e o V-A passar **sobre um tráfego de múltiplas decisões por altura**. Ver seção 9, item 1.

### 3.3 V-B (limiar bizantino): **FORA**
O PBFT puro **não lê ataque** no `MVPComparison` (escopo mínimo escolhido em 21/11). Há EQUIVOCATION no PBFT, mas só pelo ARQ/`ByzantineSweep`, que **não é** o PBFT puro de n validadores. Abrir V-B exige tarefa de código própria (ataque no PBFT puro) e novo pré-registro.

### 3.4 V-C (confronto com publicado): **proposta de declarar INVIÁVEL (aguarda o humano)**
Não há fonte VERIFICADA de vazão/latência do PBFT em LAN comparável (n, tamanho de bloco amostrado de Bitcoin, sem Tt). **Eu não busquei fontes nesta rodada**: o argumento é por ausência de Tt e de bloco parametrizável, **não** por revisão de literatura.

## 4. Parâmetros
Nenhum `--pbft-*` além do teto. **Decidido:** `--pbft-max-future-horizon = 1000`. **Não parametrizáveis** hoje: tamanho do bloco, número de transações, tamanho de PREPARE/COMMIT, timeouts de view (não há liveness sob falha neste escopo). Isso é limite, não decisão.

## 5. V-B: não se aplica.

## 6. Sementes, replicações, regime
- `semente = semente_base + n·1000 + replicação`; `MVPComparison --seed = semente − n·1000`. **Base proposta: 1 120 345** (va-pbft; faixas 1 124 346 a 1 151 350). **A disjunção** de todas as sementes já usadas (incluindo as do HotStuff e do Tendermint) **precisa ser conferida** por script no pino; **não foi conferida** neste rascunho. As sementes 1 e 7 das sondas **não entram**.
- **Réplicas:** 5. **Duração e alturas mínimas: A DEFINIR** (item 2 da seção 9). **Dado da sonda:** n = 16 finalizou **21 alturas em 60 s** (cerca de 3 s por altura); n = 4, 35 alturas em 60 s. Para um mínimo de 100 alturas, n = 16 exigiria ≳ 300 s simulados e n = 31, mais, **com custo de CPU não medido**.
- **Transiente:** a janela de regime do código (alturas 2 a hmax−2) já exclui o arranque.
- **Determinismo (verificação extra):** 2 células executadas **duas vezes** com a mesma semente (n = 7 e n = 16), saída idêntica (CSVs agregados e por bloco). **Não entram nas estatísticas.** O critic da tarefa do PBFT puro observou saída idêntica com a mesma semente, inclusive sem `hashCode=2`; isso **não** substitui a verificação do pino.

## 7. Custo e paralelismo
**Não medido.** Só há as durações de parede das sondas curtas (segundos a poucos minutos por execução, n ≤ 16). **Não estimar** o custo de n = 31 a 300 s ou mais sem uma célula piloto que **não conte** como dado de portão.

## 8. O que depende do pino (e o que o invalida)
1. O PBFT puro e o ledger precisam estar em `main` (hoje só no branch `pbft-puro-ledger`).
2. **Qualquer mudança em `PBFT.java`, `PBFTP2P`, `PeerBlockchainNode` (hook de tráfego), `NetworkHeightLedger` ou `CbRedeMedida` depois do pino invalida este V-A.**
3. Se o PBFT puro for **corrigido** para uma decisão por altura (seção 9, item 1), **o V-A repete** com o novo pino; um resultado sobre o pino atual **não vale** para o corrigido.
4. A tabela de artefatos e o hash do PREREGISTRO só valem depois do pino.

## 9. Decisões que dependem do humano
1. **Como tratar o fato de o PBFT puro commitar ~5 a 10 blocos por altura (seção 0-bis)?** Opções: **(A, recomendada)** tarefa de código do loop 2 para dar ao PBFT puro **uma decisão por altura** (primário único por view, conforme o ADR-0007), com não regressão sem ataque **que vai DIFERIR** (muda o tráfego do PBFT e do ARQ: declarar no ADR-0006), e só então pinar; **(B)** manter o modelo e definir a unidade do V-A como "por altura, com ~5 a 10 decisões", rotulando-a (a classe pode sair O(n²) ou maior, e **não** é o PBFT de livro); **(C)** medir por bloco commitado em vez de por altura (exige o ledger chaveado por bloco). **Eu recomendo A**: B e C validam um instrumento cujo comportamento difere do protocolo que o estudo diz comparar.
2. **Duração e mínimo de alturas** (seção 6): fixar o mínimo (p.ex. 30, 50 ou 100) **antes dos dados**, ou aceitar INCONCLUSIVO em n grande; e a duração por n.
3. **Critério E sem F:** aceitar que o V-A do PBFT decida **só pela classe** (b em [1,5; 2,5)), sem fidelidade à fórmula?
4. **V-B e V-C:** confirmar "fora" (V-B exige ataque no PBFT puro; V-C proposto inviável).
5. **Valores `b` esperados:** a sonda deu ≈ 2,1; **aceito** declarar a previsão `b ∈ [1,9; 2,3]` para `rede_msgs` e `rede_consenso` e `rede_total` em O(n) como rótulo?
6. **Pino e commit** do pré-registro: este agente **não commita** o pré-registro congelado.

## 10. O que NÃO foi verificado (limites)
- **Nenhuma célula de portão foi simulada**; as previsões vêm de leitura de código e de sondas exploratórias de uma semente.
- **Causa dos vários proponentes por altura:** a hipótese da seção 0-bis (views divergentes) **não foi provada**; o que está medido é o efeito (blocos e commits por altura).
- **Por nó:** sem observador; **nenhuma previsão por nó foi medida**.
- **Que `-XX:hashCode=2` não altera o PBFT:** não medido por mim; só há a observação do critic (saída idêntica sem a flag), **não** uma verificação formal.
- **Custo** de qualquer célula com n ≥ 16.
- **Distâncias do PBFT real:** sem view change e sem checkpoint no escopo; sem assinaturas individuais modeladas (42 B por voto, sem MAC/assinatura); LAN simulada; **bloco amostrado de uma distribuição Bitcoin**, não parametrizado.
- Independência entre sementes consecutivas (MersenneTwister por semente).

## 13. O que é pós-hoc (mistura proibida)
Resultado primário = **só as 25 células novas** do bloco `va`. **Pós-hoc (rotulado e fora do veredito):** qualquer fórmula analítica escrita **depois** de ver dado; outro limiar de `b`; exclusão de célula; conclusão sobre n > 31; **qualquer comparação PBFT × Tendermint × HotStuff**; qualquer número publicado não VERIFICADO; as sondas da seção 0 (**não são dado do estudo**).

---
### Artefatos (SHA-256)
**PENDENTE.** Enquanto `COMMIT_PINADO = a definir`, este arquivo **não tem hash**. Artefatos previstos (ainda não escritos): `config/fatores-va.json`, `celulas-esperadas.json`, scripts de geração, execução e análise do bloco `va`.
