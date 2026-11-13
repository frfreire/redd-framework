# ADR-0009: Escopo de protocolos do estudo: plano complexidade × liderança (Opção C), alinhado ao ADR-0000

- **Estado:** Aceito (decisão humana de 2026-10-28), **com emenda de 2026-10-30 por decisão do humano**. A emenda troca os seis níveis do fator A por cinco e passa a faixa de n e os experimentos para o ADR-0000 de `.experimento/`. A decisão central (Opção C, plano complexidade × liderança) **continua valendo**. O texto de 2026-10-28 está preservado, sem alteração, na seção "Histórico (superado em 2026-10-30)".
- **Data:** 2026-10-28 (decisão original); 2026-10-30 (emenda)
- **Decisores:** humano. O ADR de origem lista "Caburlão, Kleber, <você>" (o `<você>` não está resolvido na fonte). Nenhum agente aceita este ADR; o `redd-dominio` só registra a decisão do humano. Nome(s) a confirmar pelo humano.
- **Trava:** tarefas D3a (HotStuff) e D3b (Tendermint) do loop 2; pré-condições P2, P4a e P4b de `.experimento/PROTOCOLO-EXPERIMENTAL.md`; o desenho de qualquer bateria (fator A).
- **Origem:** `.experimento/0003-escopo-protocolos.md` (lá o número é ADR-0003; renumerado para 0009 porque `docs/adr/0003-ajuste-correspondente-e-registro-externo.md` já existe e está Aceito). Fonte da emenda: **pedido do humano em 2026-10-30** ("Se o ADR-0009 está falando de temas fora do estudo previsto, atualize e deixe de acordo"), com o estudo previsto sendo o do `.experimento/ADR-0000-redesenho-escalas.md`, que o humano mandou seguir onde ele define algo (índice, seção 6.1). `.experimento/` é fonte oficial do desenho experimental e **não foi editado**; este ADR registra a decisão no repositório.
- **Referências:** ADR-0000 de `.experimento/` (faixa de n, E1, E2; **Status: Proposta** na fonte, aceite formal dos coautores é do humano), `docs/adr/0010-parametros-de-instrumento-do-estudo.md` (Proposto; valores `a definir com o humano`), `docs/adr/0011-setup-p2p-n-pequeno.md` (**Aceito**, 2026-10-30, opções A+D), `docs/adr/0006`, `0007`, `0004` e `0005`.

Marcas: **[L]** leitura de arquivo; **[C]** calculado agora; **[E]** executado. Nesta emenda nada foi executado.

## Emenda de 2026-10-30: o que muda e o que fica

| Item | Texto de 2026-10-28 | Situação em 2026-10-30 |
|---|---|---|
| Fator A | 6 níveis: pBFT, HotStuff, Tendermint, CasperFFG, Parlia, Nakamoto | **SUPERADO.** 5 níveis (ADR-0000, decisão 3): **pBFT, Tendermint, HotStuff** (BFT), com **PoSA e PoS como âncoras** de validade externa. **PoW/Nakamoto excluído** por decisão do humano |
| Opção C (plano complexidade × liderança) | adotada | **MANTIDA** |
| HotStuff com rotação como parâmetro; ordem D3a → D3b | decididas | **MANTIDAS** |
| Ablação (HotStuff com líder estável) | indefinida (índice 3(i)) | **MANTIDA como lote à parte** (decisão aceita em 2026-10-29, índice seção 5(i); tamanho do lote `a definir`) |
| DAGsper e TangleIOTA | fora do escopo | **MANTIDO**; código mantido e marcado (ver decisão 6) |
| Faixa de n | n ∈ {25, 50, 100, 200, 400} (YAML) | **SUPERADO.** ADR-0000: n ∈ {4, 16, 64, 128}; validação fora do ajuste em {256, 400, 800} |
| Contagem de células | Bateria 1 = 174, Bateria 2 = 36, piloto = 18, ~2.100 execuções com r = 10 | **SUPERADO**: ver "Contagem de células" |
| Estado do código | sem HotStuff nem Tendermint | **ENVELHECEU**: ver "Contexto atualizado" |

## Contexto

1. **O argumento do plano continua o mesmo** (`.experimento/0003-escopo-protocolos.md`, Contexto). Entre pBFT e HotStuff mudam duas variáveis ao mesmo tempo: complexidade de mensagens (O(n²) → O(n)) e política de liderança (líder estável → rotação por bloco), além da agregação de assinaturas. Só com esses dois protocolos, a afirmação "a linearidade é o que faz o HotStuff escalar" não se sustenta.
2. **Plano complexidade × liderança** (inalterado; usa só os três protocolos BFT):

   | | Líder estável | Rotação de líder |
   |---|---|---|
   | **O(n²)** | pBFT | **Tendermint** |
   | **O(n)** | *(ablação do HotStuff)* | HotStuff |

   PoSA e PoS **não ocupam célula do plano**: são âncoras de validade externa (ADR-0000, decisão 2). O ADR-0000 **não diz** qual protocolo do JABS corresponde a cada um; o índice os lê como Parlia (PoSA) e CasperFFG (PoS), **a confirmar com o humano** (índice, seção 6.1).
3. **Por que o Nakamoto saiu.** O ADR-0000 o exclui ("viola R1, R2 e R4"). **R1 a R4 não estão definidos em nenhum arquivo do repositório** (busca feita no índice, seção 4 C-a): a exclusão é registrada como **decisão do humano e do ADR-0000**, não como conclusão verificável aqui. O humano confirmou a saída do Nakamoto em 2026-10-29 (registro de rodadas, linha 47, "Nakamoto sai do estudo") e no ADR-0011.
4. **O ADR-0004 e o ADR-0005 continuam valendo**: resultado BFT é alternativa de ordenação, não o Fabric (REQ-10), e não há limiar numérico fixado (comparação relativa). Fabric usa Raft; Raft não é tolerante a falha bizantina.
5. Em 2026-10-28 o humano cancelou a tarefa `T5-dagsper-cenario-executavel` ("DAGsper e Tangle fora do escopo de .experimento"); fonte: `.ia/rodadas/loop2-jabs-codigo.jsonl`, linha 25.

### Contexto atualizado (estado do código em 2026-10-30)

- **PBFT:** mede tráfego (ADR-0006, T1), quórum 2f+1 (T2). Validação do primário e view são tema do ADR-0007 (Aceito, opção 2), tarefa T6 **ainda não aberta** (nenhuma linha T6 em `.ia/rodadas/`).
- **HotStuff:** **[L/C]** existe código desde o commit `325f4e3`: `HotStuff.java` (471 linhas), `ReplicaCore`, `ReplicaState`, `SafetyRule`, `VoteAggregator`, `ViewLeaders`, mensagens de proposta e de QC, `HotStuffP2P`, ramo de tráfego medido em `PeerBlockchainNode`. Tarefas aprovadas pelo critic: D3a-1, D3a-2a-1, D3a-2a-2b, D3a-2a-2c-1; D3a-2a-2a e D3a-2a-2c-2a fechadas como `GAP_NAO_BLOQUEANTE` por decisão do humano (dívida de `Block.getParents()` mutável). **Não roda ponta a ponta**: faltam a troca de visão por timeout (2c-3) e o cenário com `case HOTSTUFF` no `MVPComparison` (2c-4); `MVPComparison` não tem `case` de HotStuff **[L]**. O portão V-A em O(n) **não foi executado**, portanto **P4a continua não atendida**.
- **Rotação de líder como parâmetro:** **[L]** o código já tem `HotStuffConsensusConfig.LeaderPolicy { ROTATING, STABLE }`, padrão `ROTATING`; `STABLE` é descrito no Javadoc como a ablação do `.experimento/0003`. A decisão 4 do texto original deixou "nome do parâmetro e valores aceitos: `a definir com o humano`". O nome `leaderPolicy` e os valores existem no código aprovado na D3a-1, **mas não há decisão do humano registrada que os ratifique para o estudo e para o Tendermint e o pBFT** (ADR-0010, PI-14, ainda pergunta). Segue `a definir com o humano`, agora com uma proposta concreta no código.
- **Tendermint:** nenhuma ocorrência em `src/main/java` **[L]** (busca por "tendermint", sem resultado). **P4b não atendida.**
- **Nakamoto, DAGsper, TangleIOTA:** código no repositório, sem marca "fora do escopo do estudo" (busca em `src/main/java` sem resultado). A marcação é tarefa de quem escreve em `src/` e **não foi feita**.
- **n pequeno:** o setup P2P (ADR-0011, Aceito, A+D) terminou nos casos de n = 4 e n = 7 para Casper e Parlia. O `n` da linha de comando ainda vira `(int)(0,3n)+(int)(0,7n)` nós em MCO2, TREE e AMBIFY **[L, `MVPComparison.java:321-322, 347-348, 375-376`]** e a **conversão de n é tarefa ainda não aberta** (registro, linhas 47 e 71). Até lá, n = 4 de PoSA/PoS **não é comparável** com n = 4 dos BFT.

## Opções

As três opções são as do ADR de origem, resumidas sem alteração. A emenda de 2026-10-30 **não reabre** a escolha entre elas.

1. **A, duas células (só HotStuff).** Caminho crítico mais curto. Complexidade e liderança ficam confundidas e a afirmação causal central fica indefensável.
2. **B, três células (HotStuff + ablação de liderança).** Resolve o confundimento a custo marginal quase zero. A célula O(n²)/rotativo fica vazia e HotStuff com líder estável é estudo de ablação, não protocolo implantado.
3. **C, quatro células (HotStuff + Tendermint + ablação).** Fecha o plano, com o Tendermint como protocolo real na célula que faltava. Dobra o item de maior risco de cronograma.

## Decisão

**Opção C.** Tendermint entra no escopo. (Decisão de 2026-10-28, mantida.)

1. **Fator A com 5 níveis** *(emendado em 2026-10-30; substitui "6 níveis")*: **pBFT, Tendermint, HotStuff** (BFT) e **PoSA e PoS** como âncoras de validade externa. **PoW/Nakamoto fora do estudo** (decisão do humano; ADR-0000, decisão 2).
2. **Plano complexidade × liderança** como na tabela do Contexto: pBFT, Tendermint e HotStuff ocupam três das quatro células, e a quarta é a ablação. *(Mantida.)*
3. **Ordem em D3:** D3a HotStuff primeiro (valida o portão V-A no sentido O(n) e destrava o contraste central); D3b Tendermint em seguida (V-A no sentido O(n²), mais verificação de *locking* (PoLC) e votos nil). *(Mantida.)*
4. **Rotação de líder como parâmetro de configuração do HotStuff desde o primeiro dia.** *(Mantida.)* Nome e valores: ver "Contexto atualizado"; ratificação formal `a definir com o humano`.
5. **Ablação: lote à parte** (HotStuff com líder estável). Não é sexto nível do fator A nem análise de sensibilidade (decisão do humano de 2026-10-29, índice seção 5(i)). Tamanho do lote (células e r): `a definir com o humano`. **O ADR-0000 não menciona a ablação**; por isso ela vale pela regra de precedência por cláusula (o que o ADR-0000 não define fica como estava).
6. **Fora do escopo: DAGsper e TangleIOTA** *(mantida)*, e agora também **Nakamoto/PoW**. Consequências:
   - nenhum resultado desses três entra em bateria do estudo;
   - o gap do critério 3 da T5 (quórum do DAGsper contra o artigo) e o débito técnico 7 do ADR-0006 deixam de importar para o estudo;
   - o código permanece (`DAGsper.java`, `TangleIOTA.java`, `NakamotoConsensus.java`, `BitcoinNode.java`, `DAGsperNetworkScenario`, `case DAGSPER`, `case OFFSET`/`EARTH` em `MVPComparison`/`ByzantineSweep`), **marcado como "fora do escopo do estudo"**. A marcação em `src/` é tarefa do `jabs-builder` e **não foi feita**; remover o código é decisão do humano e não está tomada aqui;
   - o Nakamoto ainda aparece como parte da evidência de não regressão (B1, P2P-1) e dos YAML; isso é higiene de código compartilhado, não escopo do estudo.
7. **Faixa de n e experimentos vêm do ADR-0000** *(novo)*: n ∈ {4, 16, 64, 128}; validação fora do ajuste em n ∈ {256, 400, 800}; E1 e E2 como abaixo. O ADR-0000 está **Proposta** na fonte; o humano mandou segui-lo onde ele define algo, e o aceite formal dos coautores continua sendo do humano.

## Contagem de células (substitui as contas de 2026-10-28)

- **E1 (ADR-0000, decisão 3), φ = 0:** A (5: pBFT, Tendermint, HotStuff, PoSA, PoS) × N (4) × L (3: λ_B, 10λ_B, 100λ_B) × R (2: Poisson, MMPP-2 de mesma média) × W_nó (2: institucional, restrito) = 5×4×3×2×2 = **240 configurações × r** replicações **[C]**. r vem de um piloto; **não há valor de r aqui** (`a definir`). Com r provisório de 10 do PROTOCOLO, seriam 2.400 execuções (conta, não decisão).
- **E2 (ADR-0000, decisão 4):** φ ∈ {0; 0,10; 0,30} × regime, "para n no centro da faixa". O ADR-0000 **não define**: quais protocolos, qual n (4 a 128 em escala log não tem um centro único declarado), L e W_nó de E2. Número de células: **`a definir com o humano`**. Não o calculei.
- **Validação (n = 256, 400, 800):** fora do ajuste; **o ADR-0000 não diz com quais protocolos nem com quais L, R, W_nó**. Número de células: `a definir com o humano`. Custo de n = 800: `NAO_MEDIDO`.
- **Ablação (lote à parte):** `a definir com o humano`.
- **Piloto:** o ADR-0000 não define piloto novo. O `piloto.yaml` usa n ∈ {25, 100, 400}, fora da faixa do ADR-0000. Contagem e desenho do piloto: `a definir com o humano`.

### Superado pelo desenho de 2026-10-29 (ADR-0000), para não deixar o número vivo em outro lugar

| O que diz `.experimento/PROTOCOLO-EXPERIMENTAL.md` / YAML / texto original deste ADR (28/10) | Situação |
|---|---|
| Fator A com 6 níveis incluindo `nakamoto` (`bateria1.yaml:13`, `bateria2.yaml:15`) | **superado** (5 níveis; sem PoW) |
| n ∈ {25, 50, 100, 200, 400}; Nakamoto excluído em n = 400 | **superado** (n ∈ {4, 16, 64, 128}; validação 256, 400, 800) |
| Bateria 1 = 6×5×2×3 − 6 = **174** células | **superado** (E1 = **240** × r) |
| Bateria 2 = 6×6 = **36** células, f ∈ {0, 10, 20, 33, 40, 51} %, n = 100 | **superado** (E2 = φ ∈ {0; 0,10; 0,30} × regime). Aviso aberto: φ máximo 0,30 < 1/3, nenhuma célula do E2 atinge a ruptura BFT que o portão V-B pede (índice, C-f); decisão do humano |
| Piloto = 18 células; "12 células × 10 sementes" | **não definido pelo ADR-0000**; n do `piloto.yaml` está fora da faixa nova (superado quanto ao n); contagem `a definir` |
| ~2.100 execuções com r = 10 (174 + 36) × r | **superado** |
| Fator E (mix público/privado 100-0, 70-30, 50-50) | **superado** (o ADR-0000 não tem fator E; fatores L e W_nó entram) |
| Perfil `estacionario`/`sazonal` | **superado**: regime Poisson × MMPP-2; o perfil sazonal está NÃO USADO (ADR-0010, PI-11) |

Os arquivos de `.experimento/` **não foram alterados**. A correção do texto deles (PROTOCOLO §1 e §4: 12, 144, 30, 120 e agora também 174, 36, 18) depende de autorização humana explícita (índice, pergunta 8). Enquanto isso, **este ADR e o índice dizem que o número vigente é o do ADR-0000**.

## Consequências

- **D3 continua dois entregáveis do loop 2**, cada um com seu portão: D3a (V-A em O(n)) e D3b (V-A em O(n²), *locking* e votos nil). Sem *locking* e votos nil o que se implementou não é Tendermint (critério de fidelidade, bar do loop 2, critério 3).
- **Risco a monitorar:** o Tendermint é o item que escorrega. Se escorregar, a limitação a declarar passa a ser o confundimento complexidade × liderança, não "cobertura da família".
- **Tensão com o ADR-0007 e com PI-14, não resolvida aqui.** O ADR-0007 (opção 2) define o primário do PBFT como `(altura + trocas) % n`, isto é, rotação por altura; o humano registrou em 2026-10-29 (B8, índice 6.8) que **o pBFT mantém a opção 2, sem view change**. Já o ADR-0010 PI-14 diz que o estudo usa **líder estável** para o pBFT, com o rotativo como modo alternativo. O plano acima trata o pBFT como **líder estável**. As duas decisões do humano não são compatíveis entre si: se o pBFT usar a opção 2 do ADR-0007, ele sai da célula "líder estável". Vai ao humano.
- **A ablação não é um nível do fator A** (decisão 5).
- **PoSA e PoS são âncoras, não células do plano.** O que sustenta a afirmação causal (complexidade × liderança) são os três BFT; as âncoras servem à validade externa. Correspondência com Parlia e CasperFFG: `a confirmar`. Comparar C_b de PoS/PoSA com o dos BFT exige medição por pacote também de votos (índice, C-i e (u)), que **não está confirmada**.
- **Conflito de capacidade n = 4 (PoSA/PoS):** resolvido do lado do P2P (ADR-0011, Aceito), **não** do lado do `n` (conversão de n no `MVPComparison`, tarefa por abrir). Ver "Contexto atualizado".
- **REQ-10 e ADR-0004** valem para HotStuff e Tendermint: resultado BFT carrega o aviso "alternativa de ordenação, não o Fabric atual" e a relação com o Fabric.
- **ADR-0005** vale: sem limiar fixado, a comparação entre os protocolos é relativa. Este ADR não fixa prazo, limiar nem valor normativo. Os parâmetros do ADR-0000 (λ_B, N_op, κ, η, W_janela, P, W_nó, L_max, U_max, MAPE, s_v, s_tx) estão no ADR-0010, todos `a definir com o humano`.
- **ADR-0000 não tem número no repositório** (colide com `docs/adr/0000-template.md`). Este ADR o cita pelo caminho de `.experimento/`.

## Equivalência de numeração

| Numeração em `.experimento/` | Assunto | No repositório | Situação |
|---|---|---|---|
| ADR-0000 | redesenho das escalas (faixa de n, E1, E2, protocolos) | **sem número**: `docs/adr/0000-template.md` é o template. Citado por caminho (`.experimento/ADR-0000-redesenho-escalas.md`) | **Proposta** na fonte; humano mandou segui-lo onde define algo; número no repositório `a definir com o humano` |
| ADR-0001 | medição unificada de tráfego | `docs/adr/0006-pbft-trafego-medido.md` | Aceito (2026-10-25). `docs/adr/0001` é buffer e reversão, outro assunto |
| ADR-0002 | pré-registro | **não existe** | `pre-registro/` também não existe (P8). `docs/adr/0002` é toneladas inteiras, outro assunto |
| ADR-0003 | escopo de protocolos | **este ADR**, `docs/adr/0009-escopo-de-protocolos-do-estudo.md` | Aceito, emenda de 2026-10-30. `docs/adr/0003` é ajuste correspondente, outro assunto |
| ADR-0004 | não consta em `.experimento/` | sem equivalente identificado; `docs/adr/0004` (Raft ou BFT) é outro assunto | conteúdo desconhecido; não assumir |
| "ADR-0005, escolhas estatísticas" (`PROTOCOLO` §4 Etapa B item 8) | escolhas estatísticas | **não existe**; `docs/adr/0005` é prazos e limiares | o conteúdo seria a decisão 8 do ADR-0000; onde registrar, `a definir com o humano` |
| (sem número em `.experimento/`) | parâmetros de instrumento exigidos pelo ADR-0000 | `docs/adr/0010-parametros-de-instrumento-do-estudo.md` | Proposto |
| (sem número em `.experimento/`) | setup P2P em n pequeno (n = 4 do ADR-0000) | `docs/adr/0011-setup-p2p-n-pequeno.md` | **Aceito** (2026-10-30, A+D) |

## Verificação

- Fator A do estudo tem **cinco** níveis (pBFT, Tendermint, HotStuff, PoSA, PoS). **Os YAML de `.experimento/` ainda listam seis** (`bateria1.yaml:13`, `bateria2.yaml:15`): estão superados e não foram corrigidos (sem autorização para editar `.experimento/`); `piloto.yaml` usa o subconjunto pbft, hotstuff, tendermint.
- D3a: `grep -rn "addBlockTraffic" src/main/java` mostra HotStuff no hook de `PeerBlockchainNode`; `scripts/jabs/validar-complexidade.py ... --esperado linear` consistente; `scripts/jabs/checar-determinismo.py` passa; critic do loop 2 aprova e `registrar-rodada.py --verificar-gate loop2-jabs-codigo` sai com 0. **Nada disto foi executado nesta emenda.** O V-A só pode rodar depois do cenário (2c-4).
- D3b: o mesmo com `--esperado quadratico`, mais teste de *locking* (PoLC) e de votos nil.
- Rotação: teste que roda o HotStuff com rotação ligada e desligada na mesma semente e mostra a diferença só na política de liderança.
- Ambos respeitam os hooks de `ByzantineConfig` (critério 4 da bar do loop 2: modelo de falha uniforme entre protocolos).
- Nenhum resultado de DAGsper, TangleIOTA ou Nakamoto em `experimentos/` como resultado do estudo.
- Nenhuma contagem de células de 28/10 (174, 36, 18, 2.100) citada como vigente em documento do repositório.

## Perguntas abertas ao humano

1. Aceite formal do ADR-0000 pelos coautores e seu número no repositório (hoje colide com o template).
2. Correspondência PoSA/PoS com Parlia/CasperFFG (o ADR-0000 não diz).
3. E2: protocolos, n "no centro da faixa", L e W_nó; número de células. Validação 256/400/800: protocolos e demais fatores.
4. Tamanho do lote da ablação (células e r) e do piloto.
5. Política de liderança do pBFT: líder estável (ADR-0010 PI-14) ou opção 2 do ADR-0007 (B8)? Ratificar o nome `leaderPolicy`.
6. Autorização para corrigir os números de células em `.experimento/` (PROTOCOLO §1 e §4, YAML).
7. Marcar `DAGsper`, `TangleIOTA` e Nakamoto como fora do escopo no código (tarefa do `jabs-builder`): confirmar.
8. Tarefa de conversão de n do `MVPComparison`: abrir quando e com que regra para o significado de n.
9. Definição de R1 a R4 (o ADR-0000 os cita para excluir o PoW; não estão no repositório).

---

## Histórico (superado em 2026-10-30)

> **Texto original deste ADR, de 2026-10-28, preservado sem alteração de conteúdo.** Só os níveis dos títulos foram rebaixados para não competir com a estrutura acima. Vale o que está **acima** desta linha. Partes superadas: o título ("seis níveis do fator A"), o Estado, a decisão 1 (seis níveis incluindo Nakamoto), as contas de Volumes (174, 36, 18, ~2.100) e a Verificação de "exatamente seis níveis". Partes mantidas: Opção C, a tabela complexidade × liderança, a decisão 2 a 5 (com as emendas acima), a tensão com o ADR-0007 e a ausência de limiar.

### Texto original (título): ADR-0009: Escopo de protocolos do estudo: plano complexidade × liderança (Opção C), seis níveis do fator A

- **Estado:** Aceito (decisão humana de 2026-10-28; o ADR de origem já está Aceito)
- **Data:** 2026-10-28
- **Decisores:** humano. O ADR de origem lista "Caburlão, Kleber, <você>" (o `<você>` não está resolvido na fonte). Nenhum agente aceita este ADR. Nome(s) a confirmar pelo humano.
- **Trava:** tarefas D3a (HotStuff) e D3b (Tendermint) do loop 2; pré-condições P2, P4a e P4b de `.experimento/PROTOCOLO-EXPERIMENTAL.md`; o desenho de qualquer bateria (fator A).
- **Origem:** `.experimento/0003-escopo-protocolos.md`. Lá o número é ADR-0003. **Renumerado para 0009** porque `docs/adr/0003-ajuste-correspondente-e-registro-externo.md` já existe e está Aceito. `.experimento/` é a fonte oficial do desenho experimental, por decisão humana; este ADR **registra** a decisão no repositório e não reescreve o ADR de origem.

#### Contexto

Fatos verificados em 2026-10-28:

1. Entre pBFT e HotStuff mudam duas variáveis ao mesmo tempo: complexidade de mensagens (O(n²) → O(n)) e política de liderança (líder estável → rotação por bloco), além da agregação de assinaturas. Só com esses dois, a afirmação "a linearidade é o que faz o HotStuff escalar" não se sustenta (`.experimento/0003-escopo-protocolos.md`, Contexto).
2. Plano complexidade × liderança, conforme a fonte:

   | | Líder estável | Rotação de líder |
   |---|---|---|
   | **O(n²)** | pBFT | **Tendermint** |
   | **O(n)** | *(ablação do HotStuff)* | HotStuff |

3. Estado do código (`src/main/java/jabs`): não há HotStuff nem Tendermint. O PBFT mede tráfego (ADR-0006, T1) e tem quórum 2f+1 (T2). A validação do primário e a view do PBFT são tema do ADR-0007 (Aceito, opção 2), com a tarefa T6 ainda não aberta.
4. O ADR-0004 e o ADR-0005 já valem: resultado BFT é alternativa de ordenação, não o Fabric (REQ-10), e não há limiar numérico fixado (comparação relativa).
5. Em 2026-10-28 o humano cancelou a tarefa `T5-dagsper-cenario-executavel` do loop 2 ("DAGsper e Tangle fora do escopo de .experimento"). Fonte: `.ia/rodadas/loop2-jabs-codigo.jsonl`, última linha, veredito `DECISAO_HUMANA_ACEITA`.

#### Opções

As três opções são as do ADR de origem, resumidas aqui sem alteração.

1. **A, duas células (só HotStuff).** Caminho crítico mais curto. Complexidade e liderança ficam confundidas e a afirmação causal central fica indefensável.
2. **B, três células (HotStuff + ablação de liderança).** Resolve o confundimento a custo marginal quase zero. A célula O(n²)/rotativo fica vazia e HotStuff com líder estável é estudo de ablação, não protocolo implantado.
3. **C, quatro células (HotStuff + Tendermint + ablação).** Fecha o plano, com o Tendermint como protocolo real na célula que faltava. Dobra o item de maior risco de cronograma.

#### Decisão

**Opção C.** Tendermint entra no escopo.

1. **Fator A com 6 níveis:** pBFT, HotStuff, Tendermint, CasperFFG, Parlia, Nakamoto.
2. **Plano complexidade × liderança** como na tabela do Contexto: pBFT, Tendermint e HotStuff ocupam três das quatro células, e a quarta é a ablação.
3. **Ordem em D3:** D3a HotStuff primeiro (valida o portão V-A no sentido O(n) e destrava o contraste central); D3b Tendermint em seguida (V-A no sentido O(n²), mais verificação de *locking* (PoLC) e votos nil).
4. **Rotação de líder como parâmetro de configuração do HotStuff desde o primeiro dia**, antes de D3a começar. Implementar com rotação fixa e parametrizar depois é refatoração. Nome do parâmetro e valores aceitos: `a definir com o humano` (decide-se na tarefa do loop 2, com o critic checando o critério 3 da bar).
5. **Fora do escopo: DAGsper e TangleIOTA.** A T5 foi cancelada pelo humano em 2026-10-28. Consequências registradas aqui:
   - nenhum resultado de DAGsper ou TangleIOTA entra em bateria deste estudo;
   - o gap do critério 3 da T5 (quórum do DAGsper contra o artigo) e o débito técnico 7 do ADR-0006 deixam de importar para este estudo;
   - o código já alterado permanece no repositório (`DAGsper.java`, `TangleIOTA.java`, `DAGsperNetworkScenario`, `case DAGSPER` em `MVPComparison`/`ByzantineSweep`). Remover ou manter é decisão do humano e não está tomada aqui.

#### Consequências

- **Volumes** (conferidos pelos YAML de `.experimento/`; contas na seção 2 do índice):
  - Bateria 1: 6×5×2×3 = 180, menos 6 (Nakamoto, n=400) = **174** células;
  - Bateria 2: 6×6 = **36** células;
  - piloto: `piloto.yaml` gera 3×3×2 = **18** células.
  Com r = 10, ~2.100 execuções nas baterias. O r definitivo vem do piloto e entra no pré-registro. Não há valor de r aqui.
- **D3 vira dois entregáveis do loop 2**, cada um com seu portão: D3a (V-A em O(n)) e D3b (V-A em O(n²), *locking* e votos nil). Sem *locking* e votos nil o que se implementou não é Tendermint (critério de fidelidade, bar do loop 2, critério 3).
- **Risco a monitorar:** o Tendermint é o item que escorrega. Se escorregar, a limitação a declarar passa a ser o confundimento complexidade × liderança, não "cobertura da família".
- **Tensão com o ADR-0007, não resolvida aqui:** o ADR-0007 (opção 2) define o primário do PBFT como `(altura + trocas) % n`, isto é, com rotação por altura. O plano acima trata o pBFT como **líder estável**. Se a T6 for implementada como está no ADR-0007, o pBFT sai da célula "líder estável". Isto vai ao humano (ver índice, inconsistência (h)).
- **A ablação não é um nível do fator A.** Os YAML trazem seis valores de `protocolo` e nenhum "hotstuff com líder estável". Se a ablação entra no estudo como célula própria, falta decidir como (sétimo nível, lote separado ou análise de sensibilidade): `a definir com o humano` (índice, inconsistência (i)).
- **REQ-10 e ADR-0004** valem para HotStuff e Tendermint: resultado BFT carrega o aviso "alternativa de ordenação, não o Fabric atual" e a relação com o Fabric.
- **ADR-0005** vale: sem limiar fixado, a comparação entre os seis protocolos é relativa. Este ADR não fixa prazo, limiar nem valor normativo.

#### Verificação

- Fator A tem exatamente seis níveis nos YAML de `.experimento/` (`bateria1.yaml`, `bateria2.yaml`); `piloto.yaml` usa o subconjunto pbft, hotstuff, tendermint.
- D3a: `grep -rn "addBlockTraffic" src/main/java` mostra HotStuff no hook de `PeerBlockchainNode`; `scripts/jabs/validar-complexidade.py ... --esperado linear` consistente; `scripts/jabs/checar-determinismo.py` passa; critic do loop 2 aprova e `registrar-rodada.py --verificar-gate loop2-jabs-codigo` sai com 0.
- D3b: o mesmo com `--esperado quadratico`, mais teste de *locking* (PoLC) e de votos nil.
- Rotação: teste que roda o HotStuff com rotação ligada e desligada na mesma semente e mostra a diferença só na política de liderança.
- Ambos respeitam os hooks de `ByzantineConfig` (critério 4 da bar do loop 2: modelo de falha uniforme entre protocolos).
- Nenhum resultado de DAGsper ou TangleIOTA em `experimentos/`.
