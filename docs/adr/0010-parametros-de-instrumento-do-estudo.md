# ADR-0010: Parâmetros de instrumento do estudo (todos a definir com o humano)

- **Estado:** Proposto
- **Data:** 2026-10-29
- **Decisores:** humano (e coautores, se o ADR-0000 de `.experimento/` for aceito). Nenhum agente aceita este ADR.
- **Trava:** a bateria principal em qualquer dos dois desenhos (PROTOCOLO de 28/10 ou ADR-0000 de 29/10); a pré-condição P8 (pré-registro congelado); as tarefas do loop 2 que dependem de valor numérico (gerador de carga, modelo de ataque, política de liderança do pBFT, tamanho de voto e de QC). Não trava as tarefas que valem nos dois desenhos (ver "Consequências").
- **Origem:** `.experimento/ADR-0000-redesenho-escalas.md`, seção "Parâmetros a fixar antes da bateria principal" (Proposta, 2026-10-29), mais as sugestões aceitas pelo humano na mesma data. O ADR-0000 de `.experimento/` **não** é o `docs/adr/0000-template.md`: a numeração colide (ver `especificacoes/protocolo-experimental-index.md`, seção 3(b) e seção 4).

## Contexto

Fatos verificados em 2026-10-29 (leitura; nada foi executado):

1. O ADR-0000 de `.experimento/` (Proposta) lista, em "Parâmetros a fixar antes da bateria principal": (i) λ_B e as faixas de N_op, κ, η, W, P do modelo de demanda, "com fontes"; (ii) valores de W_nó institucional e restrito; (iii) L_max, U_max e o limiar de MAPE; (iv) tamanho de mensagem de voto s_v e de transação s_tx; (v) estado das implementações de HotStuff e Tendermint. Cada item é, para este projeto, **lacuna normativa ou experimental**: nenhuma norma consultada nem arquivo do repositório fixa o valor. Pela regra 8 do `AGENTS.md`, ficam `a definir com o humano`.
2. Além dos itens do ADR-0000, as sugestões aceitas em 2026-10-29 e a leitura do código geram parâmetros que também não têm valor: codificação dos votantes de um QC e tamanhos de assinatura do HotStuff; parâmetros do MMPP-2; amplitude e período do perfil sazonal, se mantido; definição operacional do ataque; universo de sorteio dos bizantinos; política de liderança do pBFT.
3. O ADR-0005 (Aceito, opção 3) decidiu **não fixar limiar** das métricas de requisito REDD+ (Tb, Pdv, vazão, falha tolerada). Os limiares do ADR-0000 (L_max, U_max, λ_pico em V1 a V3, MAPE) são de natureza parecida e entram aqui como parâmetros a definir, sob a sugestão aceita sobre P7: limiar é parâmetro `a definir com o humano`, com faixa de sensibilidade congelada no pré-registro.
4. Estado do código relevante (para o item (v) do ADR-0000):
   - HotStuff: existem só dados e configuração (`jabs/consensus/config/HotStuffConsensusConfig.java`, `jabs/ledgerdata/hotstuff/*`), da tarefa `D3a-1-hotstuff-dados-config`, ainda em rodada 4 autorizada pelo humano (`.ia/rodadas/loop2-jabs-codigo.jsonl`, linhas 28 a 32). Não há classe do algoritmo, cenário nem hook de tráfego.
   - Tendermint: nenhuma ocorrência em `src/`.
   - PBFT: mede tráfego por pacote (`PBFT.java:147,158`; `PeerBlockchainNode.java:155-167`), quórum 2f+1, proponente fixo no agendador (`PBFTLocalLANNetwork.java:97`), sem view change (ADR-0006 e ADR-0007).
5. `HotStuffConsensusConfig` já **não tem padrão** para os parâmetros sem base normativa: `build()` falha se faltarem `viewTimeoutSeconds`, `batchSize`, os três tamanhos de assinatura e `voterEncoding`. Isso é coerente com este ADR; ver PI-15 e PI-16.
6. Python 3.12 sem pandas (`AGENTS.md`). Isso restringe o formato do raw (item PI-17) e a análise estatística (conflito C-h do índice).

7. **Decisões do humano em 2026-10-29** (detalhe no índice, seção 6): (i) precedência por cláusula: vale o ADR-0000 onde ele define algo; o que ele não define (R1 a R4, valores) fica como estava; o ADR-0000 segue **Proposta** na fonte e o aceite formal dos coautores é do humano; (ii) o humano **deu as fórmulas do modelo de demanda** (PI-00 a PI-06 abaixo), sem valores numéricos; (iii) o humano **propôs** a conversão para MMPP-2 (PI-10), ainda não aceita; (iv) o "pré-registro anterior" era a v1 do rascunho do humano e não há resultado arquivado no repositório; (v) n = 4 não roda em Parlia/Casper (PI-19); (vi) Θ, U, C_nó^max e L_tx p95 do ADR-0000 não existem no simulador e V1 a V3 colidem com V-A/B/C (PI-20).

**Padronização de símbolos (decisão do humano, 2026-10-29).** O ADR-0000 usa "W" para duas grandezas diferentes. Neste repositório: **W_janela** = duração da janela que antecede o prazo de conciliação (modelo de demanda); **W_nó** = enlace por nó (fator W do E1). O símbolo "W" sozinho **não é mais usado** em documentos do repositório. O texto de `.experimento/` não foi alterado. Observação adicional: o símbolo **P** (duração do período de compromisso) não tem relação com as pré-condições **P1 a P8** do PROTOCOLO.

**Convenção desta lista.** Cada item tem: identificador `PI-xx` (estável, para o índice, os testes e o pré-registro citarem), o que é, onde o ADR-0000 ou outro arquivo o menciona, opções e trade-offs onde existirem, e **Valor: `a definir com o humano`**. As opções são formas de decidir, não valores propostos. Onde escrevo "fonte", é a fonte que o humano ou o coautor precisa trazer; **não indiquei nenhuma e não inventei nenhuma**.

## Opções

Três opções gerais de como fechar este ADR (valem para a lista inteira, e o humano pode decidir item a item):

1. **Fixar tudo antes do piloto.** Prós: pré-registro (P8) fica completo e o piloto roda com o instrumento final. Contras: valor sem fonte vira opinião; erro só aparece depois.
2. **Fixar em dois momentos.** Parâmetros de instrumento (PI-07, PI-08, PI-12 a PI-16) antes do piloto, e parâmetros de demanda e de limiar (PI-01 a PI-06, PI-09 a PI-11; PI-11 NÃO USADO desde 2026-10-29) antes do pré-registro, com o piloto servindo de insumo à faixa. Prós: acompanha o fluxo do PROTOCOLO (piloto, depois pré-registro). Contras: o piloto não valida o desenho de carga final.
3. **Declarar o item como faixa de sensibilidade, sem ponto central.** Prós: honesto quando não há fonte (alinha com o ADR-0005). Contras: multiplica células; o fatorial do ADR-0000 já tem 240 × r.

Opções específicas por item seguem na lista.

## Lista de parâmetros

### Modelo de demanda (ADR-0000, "λ_B e as faixas de N_op, κ, η, W, P, com fontes")

O ADR-0000 nomeia os símbolos mas **não define o modelo** que os relaciona (não há fórmula em `.experimento/`). Sem a fórmula, não é possível dizer qual símbolo é taxa, contagem ou fração. A primeira decisão do humano é, portanto, **o modelo de demanda em si**, antes dos valores. Não preenchi o significado de N_op, κ, η, W e P por dedução; os nomes abaixo são os do ADR-0000.

> **Atualização 2026-10-29: o humano forneceu o modelo.** O parágrafo acima descreve o estado anterior (histórico mantido). O ADR-0000 continua sem fórmula; a fórmula abaixo é **decisão do humano** registrada aqui, não cláusula do ADR-0000. **Os valores numéricos de N_op, κ, P, η e W_janela continuam `a definir com o humano`, com fonte `a definir`; nenhum valor foi inventado.**
>
> ```
> λ̄        = N_op · κ / P            (taxa média, tx/s se P em segundos)
> λ_pico   = η · N_op · κ / W_janela (taxa na janela que antecede o prazo de conciliação)
> λ_pico / λ̄ = η · P / W_janela
> ```
>
> | Símbolo | Significado (humano) |
> |---|---|
> | N_op | número de operadores e participantes ativos |
> | κ | transações de registro por participante em um período de compromisso (relatos, transferências, conciliações, aposentadorias) |
> | P | duração do período de compromisso |
> | η | fração dessas transações que cai na janela que antecede o prazo de conciliação |
> | W_janela | duração dessa janela |
> | λ_B | valor de λ̄ no cenário regulatório base |
>
> Os três níveis do fator L (λ_B, 10λ_B, 100λ_B) são cenários de **N_op·κ/P em três ordens de grandeza**. **η e W_janela NÃO entram em λ_B**: eles definem só a forma temporal (pico), não a média. Verificação dimensional: κ e N_op são contagens, P e W_janela são tempos; λ̄ e λ_pico saem em 1/tempo, e λ_pico/λ̄ = η·P/W_janela é adimensional. Se η = W_janela/P, então λ_pico = λ̄ (sem pico).

**PI-00. Modelo de demanda (fórmula que liga N_op, κ, η, W_janela, P a λ̄, λ_pico e λ_B).** **Fórmula definida pelo humano em 2026-10-29** (bloco acima). Fonte da fórmula: decisão do humano; o ADR-0000 pede "com fontes" para os **valores**, que seguem `a definir com o humano`. Fonte dos valores: `a definir`. Texto anterior (histórico): "`a definir com o humano`... qual é a definição de cada símbolo e a fórmula?" — respondido. Observação de domínio, hipótese minha e não do ADR-0000 nem do humano: `dominio-redd.md` REQ-03 e REQ-08 tratam o pico de submissão MRV e a emissão em lote, e o ADR-0002 (unidade do crédito) muda muito o número de transações; κ pode depender dessa decisão. Ainda aberto: se as quatro categorias de transação de κ (relatos, transferências, conciliações, aposentadorias) têm pesos ou tamanhos distintos (s_tx, PI-08); o modelo as conta como uma só taxa.

**PI-01. λ_B (demanda de base).** Valor: `a definir com o humano`. **Definição (humano, 2026-10-29):** λ_B é o valor de λ̄ = N_op·κ/P no cenário regulatório base; com P em segundos a unidade é tx/s. η e W_janela **não** entram em λ_B. O fator L do ADR-0000 usa múltiplos de λ_B (λ_B, 10λ_B, 100λ_B), então λ_B é a âncora de toda a escala de demanda; os três níveis são N_op·κ/P em três ordens de grandeza. Opções: (a) derivar de dado de registro real (fonte: `a definir`); (b) derivar das transações do contrato medidas na aplicação Fabric (a ponte); a medição **ainda não foi feita** (`NAO_MEDIDO`); (c) tratar como parâmetro de sensibilidade com faixa. Trade-off: (a) e (b) têm ancoragem mas dependem de dado que não existe aqui; (c) não depende de dado, mas deixa o resultado condicional.

**PI-02. N_op** (número de operadores e participantes ativos). Faixa: `a definir com o humano`; fonte: `a definir`. Entra em λ̄ e λ_pico.

**PI-03. κ** (transações de registro por participante por período de compromisso). Faixa: `a definir com o humano`; fonte: `a definir`.

**PI-04. η** (fração das transações do período que cai na janela que antecede o prazo de conciliação). Faixa: `a definir com o humano`; fonte: `a definir`. Não entra em λ̄ nem em λ_B; entra em λ_pico. Restrição de validade do MMPP-2: η > W_janela/P (PI-10).

**PI-05. W_janela (duração da janela que antecede o prazo de conciliação; modelo de demanda).** Faixa: `a definir com o humano`; fonte: `a definir`. Não entra em λ̄ nem em λ_B. **Ambiguidade de nome FECHADA (decisão do humano, 2026-10-29):** o "W" do modelo de demanda é a **duração da janela** (W_janela); o fator "W Enlace por nó" do E1 é **W_nó** (PI-07). São duas grandezas; símbolo "W" sozinho não é mais usado no repositório. Texto anterior (histórico): "Pergunta ao humano: são a mesma coisa? Enquanto não houver resposta, este ADR trata como dois itens (PI-05 e PI-07)" — respondido: são coisas diferentes, e a separação em dois itens estava certa.

**PI-06. P** (duração do período de compromisso; em segundos para λ̄ sair em tx/s). Faixa: `a definir com o humano`; fonte: `a definir`.

**PI-09a. λ_pico.** Usado em V1 (Θ ≥ λ_pico). Não é definido no ADR-0000. **Definição (humano, 2026-10-29):** λ_pico = η·N_op·κ/W_janela, e λ_pico/λ̄ = η·P/W_janela. Isto fecha a opção (a) abaixo; resta `a definir` o valor, que depende de PI-02 a PI-06. Opções anteriores (histórico): (a) derivar do modelo de demanda (PI-00) como o máximo do regime; (b) tomar do MMPP-2 (PI-10) como a taxa do estado de rajada; (c) fixar por fator L. Trade-off: (a) e (b) dependem de decisões ainda abertas; (c) é simples mas pode não representar o pico. Nota: sob o MMPP-2 proposto (PI-10), λ_pico coincide com λ2 (taxa do estado de rajada), então (a) e (b) dão o mesmo número; isso vale só se a proposta for aceita. Valor: `a definir com o humano`.

### Enlace por nó (ADR-0000, "Valores de W_nó")

**PI-07. W_nó institucional e W_nó restrito.** Valores: `a definir com o humano` (unidade e, se aplicável, latência e perda: `a definir`). Fonte: `a definir`. Fato do código que condiciona a decisão: hoje só o PBFT/ARQ usa banda constante (100 Mbps, `LAN100MNetworkStats.java:10`); Casper e Parlia usam distribuição por 6 regiões e o Nakamoto, 86 países (auditoria B.2). O fator W só é comparável entre protocolos se a banda por nó virar parâmetro uniforme: tarefa do loop 2, não aberta. O PROTOCOLO de 28/10 fixa `largura_banda_mbps: 100` como parâmetro fixo, não como fator.

### Tamanhos de mensagem (ADR-0000, "s_v e s_tx")

**PI-08. s_v (voto) e s_tx (transação).** Valores: `a definir com o humano`. Fontes possíveis a trazer: medição da aplicação Fabric (ponte; **`NAO_MEDIDO`**) ou especificação do esquema de assinatura adotado. Estado atual no código, sem valor decidido:
- Voto do pBFT: hash + 10 B de cabeçalho (`PBFTBlockVote.PBFT_VOTE_SIZE_OVERHEAD = 10`), sem assinatura; 42 B no teste de tráfego (auditoria B.2).
- Voto do HotStuff: hash + 10 B + tamanho de assinatura configurável (`HotStuffVote.computeSize`), sem padrão.
- Transação: os YAML de 28/10 trazem `tamanho_tx_bytes: 512`, mas o código amostra de distribuições herdadas do Bitcoin e do Ethereum e não usa 512 B (auditoria B.2, L8). O 512 do YAML é valor de spec, não medição.

Trade-off: s_v igual entre protocolos dá comparação justa, mas ignora que o pBFT atual não carrega assinatura e o HotStuff carrega; unificar muda a saída do pBFT (nota de não regressão, critério 7 da bar do loop 2).

### Limiares de viabilidade e de análise (ADR-0000, "L_max, U_max e o limiar de MAPE")

**PI-09. L_max, U_max e limiar de MAPE.** Valores: `a definir com o humano`. Fontes: `a definir`. Observações:
- O ADR-0000 escreve "MAPE < 10%" na decisão 8 e lista "o limiar de MAPE" como parâmetro a fixar. **A decisão e a lista divergem dentro do próprio ADR-0000.** Este ADR não adota os 10%; trata-os como valor proposto no ADR-0000 a confirmar.
- V1 a V3 são vereditos binários com limiar. O ADR-0005 (Aceito, opção 3) decidiu não fixar limiar das métricas de requisito. Opções: (a) tratar V1 a V3 como **descritivos** (rótulo "descritivo", sem fonte normativa), consistente com o ADR-0005; (b) reabrir o ADR-0005 para limiares normativos com fonte; (c) congelar faixa de sensibilidade no pré-registro (sugestão aceita sobre P7). Trade-off: (a) e (c) preservam o ADR-0005; (b) responde ao requisito, mas depende de fonte que não temos.
- **Colisão de nome:** V1 a V3 (critérios de viabilidade) × V-A, V-B, V-C (portões de qualidade, PROTOCOLO §6). Renomear um dos dois: `a definir com o humano` (não renomeei).

### Perfis de carga

**PI-10. Parâmetros do MMPP-2** (se o ADR-0000 for aceito): taxa do estado 1, taxa do estado 2 e matriz de transição (taxas de saída de cada estado). Valores: `a definir com o humano`. Restrição do próprio ADR-0000: "mesma média" que o regime Poisson; logo a média estacionária do MMPP-2 deve igualar a do Poisson. O ADR-0000 não diz como medir a burstiness; a ponte já exige o **índice de dispersão** do número de transações por janela (auditoria C.2, item 7), janela `a definir`. Trade-off: mais parâmetros livres (quatro, com a restrição de média, três graus de liberdade) contra o perfil sazonal (A e T), que tem dois. Fonte: `a definir`.

**PI-10 — PROPOSTA do humano (2026-10-29, "sugiro acrescentar"): conversão do modelo de demanda para MMPP-2. Registrada como proposta a entrar no pré-registro, NÃO como aceita.** Estado 1 = base (fora da janela), estado 2 = alto (na janela). σ1 = taxa de saída do estado 1; σ2 = taxa de saída do estado 2.

```
1/σ2 = W_janela                 (duração média no estado alto)
1/σ1 = P − W_janela             (duração média no estado base)
π_alto = σ1/(σ1+σ2) = W_janela / P
λ2 = η · N_op · κ / W_janela    (= λ_pico)
λ1 = (1 − η) · N_op · κ / (P − W_janela)
```

*Conferência de álgebra (feita pelo redd-dominio, 2026-10-29):*
- π_alto = σ1/(σ1+σ2) = [1/(P−W)] / [1/(P−W) + 1/W] = W/P. **Confere.**
- (1−π)·λ1 + π·λ2 = ((P−W)/P)·(1−η)·Nκ/(P−W) + (W/P)·η·Nκ/W = (1−η)Nκ/P + ηNκ/P = **Nκ/P = λ̄**. **Confere exatamente**, para quaisquer η, W_janela, P válidos. Logo a referência Poisson usa a mesma λ̄ e a restrição "mesma média" do ADR-0000 é satisfeita por construção.
- Contagem por ciclo: λ2·W_janela = η·Nκ e λ1·(P−W_janela) = (1−η)·Nκ; soma = Nκ por ciclo médio de duração (P−W_janela)+W_janela = P. **Confere.**
- λ2/λ̄ = η·P/W_janela = λ_pico/λ̄. **Confere** com a fórmula do humano.
- Conferência numérica com aritmética racional exata (Python `fractions`, três conjuntos de valores **ilustrativos descartados, não são parâmetros do estudo**) e o caso η = W/P: igualdades exatas. Nenhum desses valores deve ser lido como proposta.

*Restrição de validade (para o pré-registro):* **0 < W_janela < P** e **W_janela/P < η < 1** (η < 1 mantém λ1 > 0; η > W_janela/P garante λ2 > λ1, isto é, estado "alto" realmente alto). Com **η = W_janela/P**, λ1 = λ2 = λ̄ e o MMPP-2 **degenera em Poisson**. Com η < W_janela/P o estado "alto" seria o mais lento; não é o regime pretendido.

*Limite do modelo (a registrar):* as durações dos estados de um MMPP-2 são **exponenciais**. Logo 1/σ2 = W_janela e 1/σ1 = P − W_janela valem **em média**. "Uma janela por período de compromisso" vale **em média**, não por período: um período individual pode ter zero, uma ou várias janelas, e janelas de duração muito diferente de W_janela (o coeficiente de variação de uma exponencial é 1). Um prazo de conciliação real é determinístico e periódico; o MMPP-2 não o reproduz. Se a periodicidade exata importar, o perfil sazonal determinístico (PI-11) é a alternativa. A escolha é do humano.

*Efeito nos parâmetros livres:* com a proposta, o MMPP-2 deixa de ter parâmetros livres próprios; é função de (N_op, κ, P, η, W_janela). Isto substitui, se aceito, a contagem de "três graus de liberdade" acima por **dois parâmetros de forma (η, W_janela) além da escala N_op·κ/P**. Os valores seguem `a definir com o humano`. Índice de dispersão: `a definir` (janela de contagem), como já exigido pela ponte; **não calculei** a dispersão teórica do MMPP-2 aqui.

**PI-11 — NÃO USADO — decisão do humano de 2026-10-29.** O perfil sazonal (amplitude A, período T, forma da função e nome `sazonal`/`sazonal-rajada`) e a razão pico/média ρ_p como fator de experimento saem do desenho (B3: remover). O E2 é o do ADR-0000: robustez, φ ∈ {0; 0,10; 0,30} × regime (Poisson × MMPP-2). Nenhum valor de A ou T será fixado. ρ_p = ηP/Δ continua definição do MMPP-2 e η, Δ, P continuam parâmetros (PI-04, PI-05, PI-06, PI-10). Texto original abaixo preservado como histórico.

*Histórico (texto original de PI-11):*

**PI-11. Perfil sazonal, se mantido (desenho de 28/10): amplitude A e período T, forma da função, nome.** Valores: `a definir com o humano`. Nome: `sazonal` = `sazonal-rajada` (sugestão aceita; sinônimos). Condição já registrada no índice (P6): taxa média igual à do estacionário, por constante de normalização conferida por contagem de transações geradas por semente. **A substituição do fator pelo regime Poisson × MMPP-2 é proposta do ADR-0000, não decisão.** Fonte do formato: a medição da aplicação (`NAO_MEDIDO`).

### Ataque bizantino e universo de sorteio

**PI-12. Definição operacional do ataque único (aceita como direção; ver abaixo o que continua aberto).** Texto aceito: *um nó bizantino emite duas mensagens conflitantes, cada uma a um subconjunto disjunto de nós honestos.* Tradução oficial `equivocacao` para `EQUIVOCATION`. Ataque desconhecido: **falha alta** (erro explícito, não comportamento honesto silencioso). Fato do código: hoje o construtor `ByzantineConfig(String)` aceita qualquer texto e os algoritmos caem no ramo honesto sem erro (auditoria A.2, L1). Continuam `a definir com o humano`:
- os subconjuntos disjuntos: tamanho de cada um (metade e metade, como o PBFT atual faz com `half = size/2` dos vizinhos locais, ou outra partição) e como são escolhidos;
- se vale para todo tipo de mensagem do protocolo (proposta, voto de cada fase) ou só para a fase que o protocolo define como crítica;
- o que a definição significa em PoW (Nakamoto), se o Nakamoto permanecer (conflito C-a do índice). Hoje `EQUIVOCATION` em `BitcoinNode.java:58-61` é tratado como retenção;
- coordenação entre nós bizantinos (L5 da auditoria: não existe no código).

**PI-13. Universo de sorteio dos nós bizantinos (aceito: unificar para pareamento por semente).** Hoje o universo é diferente por protocolo: todos os nós no ARQ/Parlia, stakeholders no Casper, mineradores no Nakamoto (auditoria A.3, L4). Valor a decidir: qual universo (todos os nós, ou só os validadores/votantes) e qual gerador (RNG dedicado, aninhado entre valores de f). `a definir com o humano`. Trade-off: "só validadores" torna φ comparável entre protocolos; "todos os nós" mantém o caminho atual mas deixa o mesmo φ valer coisas diferentes. Depende do significado de n (L10).

### Política de liderança do pBFT

**PI-14. Política de liderança do pBFT (aceito como direção; exige ADR complementar aceito pelo humano).** Direção aceita: parametrizar a política; o estudo usa **líder estável** (troca só por falha); o **primário rotativo do ADR-0007** (`(altura + trocas) % n`, opção 2 aceita em 2026-10-26) vira **modo alternativo**; ablação = **lote à parte** com HotStuff de líder estável (`HotStuffConsensusConfig.LeaderPolicy.STABLE` já existe como valor). Pontos abertos, todos `a definir com o humano`:
- nome do parâmetro e valores aceitos (o ADR-0009, decisão 4, deixou o nome em aberto para o HotStuff também; manter o mesmo nome nos dois protocolos?);
- **"troca só por falha" pressupõe view change por falha.** O ADR-0007 escolheu a opção 2 (sem view change por timeout, sem tolerar primário silencioso) e disse que a opção 3 "só abre por novo ADR, se algum experimento precisar de falha de primário". O estudo de robustez bizantina precisa. O ADR complementar deve dizer se abre a opção 3 do ADR-0007 e com que timeout de view change (valor: `a definir`);
- qual nó é o líder estável inicial (hoje `min(10, n-1)` no agendador e nó 0 pela view local: divergência registrada no ADR-0007 contexto 4);
- como o líder estável interage com a seleção aleatória de bizantinos: o líder pode cair entre os bizantinos (índice (h)); política de sorteio com ou sem o líder: `a definir`;
- tamanho do lote da ablação (células e r): `a definir`.

Este ADR **não** é o ADR complementar; apenas registra que ele é necessário. Mudança de saída do pBFT/ARQ: nota de não regressão (critério 7) quando for implementada.

### HotStuff: votantes de um QC e assinaturas

**PI-15. Codificação dos votantes de um QC.** Estado atual: decisão humana da rodada 4 da tarefa D3a-1: **bitmap de `ceil(n/8)` B**, igual nos modos INDIVIDUAL e AGGREGATED, de modo que a vantagem do agregado fique somente na assinatura (`HotStuffConsensusConfig.voterIdentifiersSize`; enum `VoterEncoding` com só `BITMAP`). Registro aqui porque o código de `HotStuffConsensusConfig` trata essa escolha como suposição a definir com o humano. Opções para o estudo: (a) manter o bitmap; (b) lista de identificadores (tamanho cresce com o nº de votantes e favorece o agregado menos); (c) sem identificadores, só para ablação. Valor final para o pré-registro: `a definir com o humano` (confirmar o bitmap ou substituí-lo). Observação: o bitmap pressupõe conjunto fixo e conhecido de n validadores, coerente com registro permissionado, mas isso é leitura minha.

**PI-16. Tamanhos de assinatura (individual, agregada, parcela agregada).** Os valores que o código cita (64 B individual e 96 B agregada, como exemplos de ECDSA/Ed25519 e BLS12-381 G2) são **suposições do builder**, não valores decididos. Valores: `a definir com o humano`. Fonte: o esquema de assinatura efetivamente adotado pelo Fabric na aplicação (`NAO_MEDIDO` aqui). Pergunta a levar ao humano: o estudo modela o Fabric (assinatura da identidade X.509 da organização) ou o esquema de uma implementação de HotStuff/Tendermint? A resposta altera os três tamanhos. O PROTOCOLO de 28/10 fixa `esquema_assinatura: individual` como parâmetro fixo, com "agregada" como sensibilidade; o ADR-0000 não menciona o esquema.

> **Nota 09/11/2026 (tarefa D3b-tendermint-assinatura-proponente): modelo de assinatura do Tendermint.** Voto e proposta são assinados e modelados com **parâmetros separados, ambos obrigatórios e sem valor padrão**: `signatureSizeBytes` (voto; flag `--tm-signature-size`) e `proposerSignatureSizeBytes` (proposta; flag `--tm-proposer-signature-size`). Ambos exigem valor > 0; valores: `a definir com o humano`. Tamanho do voto = hash do bloco (32 B) + `messageOverheadBytes` + `signatureSizeBytes`. Tamanho da proposta = hash + `messageOverheadBytes` + `proposerSignatureSizeBytes` + payload do bloco; o payload é carga do bloco, não consenso. Campos separados porque o esquema de assinatura pode diferir entre proposta e voto, e reusar o valor do voto seria um padrão implícito. A proposta **não** modela a justificação do `validRound` (POL, 2f+1 prevotes): leva só o inteiro, o que pode subdimensionar propostas com `validRound >= 0` (decisão do humano pendente). `TENDERMINT_BLOCK_HASH_SIZE = 32` segue constante do formato do id. **Assimetria:** pBFT e HotStuff usam outro modelo de assinatura; o cálculo do Tendermint não é unificado com eles, e a comparação só vale com os esquemas e valores declarados. Também: `DEFAULT_MESSAGE_OVERHEAD_BYTES = 10` do `HotStuffConsensusConfig` continua como padrão silencioso (decisão do humano pendente). **[Superado em 22/11/2026: a constante `DEFAULT_MESSAGE_OVERHEAD_BYTES` e os padrões de `leaderPolicy` e `signatureMode` foram removidos do `HotStuffConsensusConfig.Builder` (tarefa `HS-campos-obrigatorios`, ADR-0015 Q3-A, Q8-B, Q10-B); os três são obrigatórios e sem padrão.]**

### Formato de dados

**PI-17. Formato do raw.** Direção aceita: **CSV/JSONL** no raw, sem pandas; **Parquet opcional**. Scripts do protocolo em `experimentos/scripts/` (escritos por `jabs-experimento`). Em aberto, `a definir com o humano`: se existe dependência externa autorizada para análise (numpy, scipy, statsmodels), pois Box-Cox, HC3, ART e Games-Howell do ADR-0000 pedem mais que a biblioteca padrão; quem revisa scripts de análise (fora do escopo do `loop2-jabs-codigo`, índice 3(d)).

### Conflitos de capacidade do simulador (registrados em 2026-10-29)

**PI-19. n = 4 para PoSA e PoS (conflito de capacidade do simulador).** O ADR-0000 pede n ∈ {4, 16, 64, 128} para **todos** os protocolos. Parlia e Casper do JABS exigem n ≥ 10, senão o setup da rede não termina (`AGENTS.md`; ADR-0006). Impacto: 2 protocolos × 3 L × 2 R × 2 W_nó = **24 das 240 células** (índice, C-b). **Não é parâmetro a escolher; é conflito a resolver por tarefa do `jabs-builder` (loop 2), ainda não aberta.** Opções (vão ao humano; **não escolhi**):
1. **Corrigir o setup P2P compartilhado** para aceitar n < 10. Prós: o fatorial fica completo. Contras: o setup é compartilhado e **mudaria a saída de Casper, Parlia e Nakamoto**; exige ADR e nota de **não regressão** (critério 7 da bar do loop 2) e comparação de saídas antes/depois.
2. **Declarar n ≥ 10 como limitação** e usar **n = 16 como menor n para PoSA e PoS**. Prós: não toca o setup. Contras: o fatorial fica desbalanceado (n = 4 só para os três BFT); a análise de efeito de n com 4 níveis perde um nível nesses dois protocolos; 24 células deixam de existir (ou ficam `NAO_APLICAVEL`) e isso precisa estar no pré-registro.
Ressalva já registrada no índice: o limite é sobre o total de nós da rede; o que "n" significa (validadores × nós totais) segue aberto (auditoria L10). Valor/decisão: `a definir com o humano`.

**Acréscimo 2026-10-29 (ADR-0011).** O humano determinou **corrigir o setup P2P** (opção 1) e **rejeitou** a opção 2 (manter só a limitação). A opção de implementação (A, B ou D) está em `docs/adr/0011-setup-p2p-n-pequeno.md` (Proposto), com a tarefa `P2P-1-setup-n-pequeno` no `loop2-jabs-codigo` e a tabela de n mínimo por protocolo (estimada por leitura, a confirmar por teste de término). Dois pontos novos de lá que afetam este item: (i) o limite não é "n ≥ 10" fixo: depende do grau-alvo e da ordem de conexão (Nakamoto exige no mínimo n ≥ 17 por contagem de pares); (ii) por leitura, o `n` da linha de comando vira `(int)(0,3n) + (int)(0,7n)` nós em Casper e Parlia, ou seja, **3 nós em n = 4**, o que liga este item à L10 e não se resolve só no P2P.

**Acréscimo 2026-10-30 (ADR-0011 ACEITO).** O texto de 2026-10-29 acima descreve o estado anterior e fica como histórico. O humano decidiu a opção **A+D** (limitar o alvo e terminar quando não há candidato elegível, sem alterar o sorteio onde a rede já terminava; falha alta só onde A não resolve) e a tarefa `P2P-1-setup-n-pequeno` foi **APROVADA pelo critic na rodada 2** (180 testes, número do registro), com baseline no commit `e703e3d`. Efeito sobre este item: (i) a premissa "Parlia e Casper exigem n ≥ 10" **deixou de valer para o setup P2P**; as 24 células de n = 4 deixam de ser bloqueadas pelo P2P (`MCO2:4` e `AMBIFY:4` terminam, 3 sementes), mas (ii) o `n` da linha de comando continua virando 3 nós (1 validador no Parlia) em n = 4: **a conversão de n do `MVPComparison` é tarefa separada, ainda não aberta**, e o significado de n (L10) segue aberto; (iii) em n pequeno a topologia vira malha completa (n ≤ d+1), a declarar no pré-registro; (iv) a semântica de finalização com 2 stakeholders **não foi verificada**; (v) o Nakamoto saiu do estudo (decisão do humano), então o n = 16 em `BitcoinCoreP2P` é só higiene. Não confirmado: `OFFSET` n = 4 e n = 16 (nunca executados), CA-03 com `-XX:hashCode`, CA-02 pelo `MVPComparison`. Detalhe e mapa CA-01 a CA-07 em `docs/adr/0011-setup-p2p-n-pequeno.md` (Aceito). A opção escolhida em PI-19 é, portanto, a opção 1; a opção 2 foi rejeitada.

**PI-20. Métricas do ADR-0000 sem implementação e critérios V1 a V3.**
- **Não existem no simulador** (busca em `src/main/java/jabs`, índice C-g): **Θ** (vazão), **U** (ocupação do enlace), **C_nó^max** (máximo por nó de bytes), e **L_tx p95 medido sem o fallback** que mistura duas definições de Tt (existe `getPercentileConfirmationLatency(95)`, com ressalva). **O simulador precisará ser estendido: tarefa do `jabs-builder` (loop 2), não aberta por mim.** Sem elas, V1 a V3 não são computáveis.
- **V1 (Θ ≥ λ_pico), V2 (L_tx ≤ L_max), V3 (U ≤ U_max)** dependem de **L_max, U_max** (PI-09) e de **λ_pico** (PI-09a, agora definido pela fórmula do humano; o valor segue `a definir`). Sem esses valores os critérios não podem ser pré-registrados.
- **Colisão de nome:** V1 a V3 (viabilidade) × **V-A, V-B, V-C** (portões de qualidade, PROTOCOLO §6). **Sugestão (a confirmar pelo humano; não renomeei nada em `.experimento/`): renomear os critérios de viabilidade para CV1, CV2 e CV3 no pré-registro.** Até a confirmação, este repositório escreve "V1 (viabilidade)" e "V-A (portão)" sempre qualificados.

### Não é parâmetro, mas é pré-requisito

**PI-18. Estado das implementações de HotStuff e Tendermint (item (v) do ADR-0000).** Não é valor a escolher, é fato a declarar. Estado verificado em 2026-10-29: ver Contexto, item 4. O ADR-0000 pede o estado dessas implementações *antes da bateria*: HotStuff só tem dados e configuração; Tendermint, nada. Parlia e Casper exigem n ≥ 10 (AGENTS.md; ADR-0006), o que afeta n = 4 do ADR-0000 (índice, C-b). *[Acréscimo 2026-10-30: a afirmação "exigem n ≥ 10" descreve o estado anterior ao ADR-0011 (Aceito, A+D); o DAGsper continua com `MIN_NODES = 10`.]* Código de **DAGsper e TangleIOTA fica no repositório, marcado "fora do escopo do estudo"** (sugestão aceita; a marcação em si é tarefa de quem escreve em `src/`, não feita aqui).

## Decisão

**Pendente.** Nenhum **valor numérico**, fonte ou limiar foi escolhido neste documento. O que mudou em 2026-10-29:
- **Definido pelo humano (estrutura, não valor):** a fórmula do modelo de demanda (PI-00), o significado de N_op, κ, P, η, W_janela, λ_B e λ_pico, e a padronização W_janela × W_nó (PI-05).
- **Proposto pelo humano, não aceito:** conversão para MMPP-2 (PI-10).
- **Registrado como conflito/pendência de implementação:** PI-19 (n = 4) e PI-20 (métricas ausentes, V1 a V3 × V-A/B/C).
- Os **valores** de PI-01 a PI-09 e dos parâmetros de forma continuam `a definir com o humano`, com fonte `a definir`.
As direções já aceitas pelo humano em 2026-10-29 (PI-12, PI-13, PI-14, PI-15 enquanto bitmap, PI-17) estão registradas como direção, **não** como valor final.

- Modelo de demanda: **fórmula definida**; valores de N_op, κ, P, η, W_janela (PI-02 a PI-06) e λ_B (PI-01): `a definir com o humano`
- λ_pico (PI-09a): fórmula definida; valor `a definir com o humano`
- W_nó (PI-07): `a definir com o humano`
- s_v e s_tx (PI-08): `a definir com o humano`
- L_max, U_max, MAPE (PI-09): `a definir com o humano`
- MMPP-2 (PI-10): conversão **proposta** (não aceita), valores `a definir com o humano`; sazonal (PI-11): **NÃO USADO** (decisão do humano, 2026-10-29); texto anterior: `a definir com o humano`
- n = 4 em PoSA/PoS (PI-19) e métricas ausentes (PI-20): decisão do humano sobre as opções. *Acréscimo 2026-10-30:* PI-19 teve a opção 1 decidida e implementada (ADR-0011 Aceito, A+D); falta a tarefa de conversão de n do `MVPComparison`. PI-20 segue aberto.
- Ataque (PI-12), universo de sorteio (PI-13): `a definir com o humano`
- Política do pBFT e ADR complementar (PI-14): `a definir com o humano`
- Votantes do QC e assinaturas (PI-15, PI-16): `a definir com o humano`
- Formato e dependências estatísticas (PI-17): `a definir com o humano`

Opinião do `redd-dominio`, sem fonte normativa: opção 2 geral (dois momentos) e opção 3 (faixa de sensibilidade) para qualquer item sem fonte, em coerência com o ADR-0005. É opinião; a decisão é do humano.

## Consequências

- **Enquanto Proposto:** nenhuma bateria principal; nenhum número de simulação que dependa de PI-xx pode ser tratado como resultado, só como piloto ou smoke test, com semente, versão do simulador e granularidade declaradas (regra 6 do projeto).
- **O que NÃO depende deste ADR (vale nos dois desenhos, pode seguir):** HotStuff e Tendermint no escopo (ADR-0009, decisões 2 a 4); medição de tráfego por pacote (ADR-0006); falha alta para ataque desconhecido; tradução `equivocacao` para `EQUIVOCATION`; geradores de carga parametrizáveis e determinísticos por semente (os parâmetros ficam como entrada, sem padrão); `HotStuffConsensusConfig` sem padrões nos itens sem base; scripts em `experimentos/scripts/`.
- **O que depende:** tudo o que precisa de valor (PI-01 a PI-10, PI-15, PI-16; PI-11 NÃO USADO desde 2026-10-29) e a escolha entre as opções de PI-19 e a escolha entre os dois desenhos (índice, C-k).
- **P7:** a redação proposta (índice, seção 2) mantém o ADR-0005 e usa PI-09 e PI-00 a PI-06 como parâmetros; a faixa de sensibilidade de cada um é congelada no pré-registro.
- **Muda o simulador (tarefas do loop 2, nenhuma aberta por mim):** banda por nó uniforme (PI-07); tamanho de voto uniforme (PI-08, com nota de não regressão do pBFT); gerador de carga com MMPP-2 (se a proposta de PI-10 for aceita) e taxa igual entre protocolos; validação de ataque na construção; universo de sorteio unificado (PI-13); política de liderança do pBFT e, se o humano abrir a opção 3 do ADR-0007, view change (PI-14); registro de Θ, U e C_nó^max, que não existem (índice, C-g; PI-20); suporte a n < 10 em Parlia/Casper, se o humano escolher a opção 1 de PI-19 (com ADR e não regressão). *[Acréscimo 2026-10-30: feito pela tarefa `P2P-1-setup-n-pequeno`, ADR-0011 Aceito; resta a conversão de n do `MVPComparison`, por abrir.]*
- **Muda o chaincode:** nada.

## Verificação

- `grep -n "a definir com o humano" docs/adr/0010-parametros-de-instrumento-do-estudo.md` lista o que resta; cada PI-xx some da lista quando ganhar valor e fonte registrados.
- Pré-registro (P8): se a proposta de PI-10 for aceita, a restrição W_janela < P e η > W_janela/P é verificada para cada célula MMPP-2, e a média amostral de cada semente é conferida contra λ̄ (Poisson e MMPP-2 com a mesma λ̄).
- Pré-registro (P8): todo PI-xx tem valor ou `a definir`, rótulo (normativo com fonte | descritivo) e faixa de sensibilidade.
- Nenhum arquivo de `experimentos/` contém resultado que dependa de PI-xx sem declarar o valor usado e a versão deste ADR.
- `python3 scripts/enxame/validar-config.py` sem falha.
- O humano registra as decisões neste arquivo e muda o estado para Aceito. Se o ADR-0000 de `.experimento/` for aceito, o humano indica o número dele no repositório (hoje colide com o template) e este ADR passa a citá-lo por esse número.
