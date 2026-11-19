# ADR-0000 — Redesenho experimental: separação das escalas de governança e de mercado

- **Status:** Proposta (bloqueia a bateria principal até aceite dos coautores)
- **Data:** 2026-10-29
- **Depende de:** ADR-0001 (medição unificada de tráfego), ADR-0002 (pré-registro), ADR-0003 (escopo de protocolos)
- **Substitui parcialmente:** fatores e níveis do pré-registro anterior (protocolo × regime × n ∈ {12, 36, 60} × φ)

## Contexto

1. Um revisor de trabalho anterior da Arq-REDD+ questionou se o pBFT continuaria viável em escala nacional, já que o tempo e o tráfego de finalização cresciam aproximadamente de forma linear até 400 nós.
2. O crescimento linear do tráfego do pBFT é um artefato. Em `PBFT.java` (linhas 122–135), o tráfego por bloco é `numAllParticipants * 2 * 1024` bytes mais ruído gaussiano. Isso corresponde ao tráfego de **um** nó. O tráfego total do caso normal do pBFT é ≈ 2n² mensagens por bloco (limite inferior Ω(n²) de Dolev e Reischuk, 1985). Em n = 400 a subestimação é de ordem n (≈ 400×).
3. O desenho anterior dimensionava validadores pela área (1 nó/1.000 ha). Em escala nacional isso leva a n ≈ 10⁵, que é inviável para qualquer BFT plano e não corresponde a um registro regulado (Lei 15.042/2024: registro central sob um órgão gestor e entidades credenciadas).
4. O artigo não pode ser mais uma comparação de algoritmos: a escolha dos protocolos e dos fatores precisa decorrer do domínio.

## Decisão

1. **Pergunta de pesquisa:** sob quais combinações de governança (n), demanda (λ) e capacidade de enlace cada família de consenso permanece viável, e qual restrição se esgota primeiro?
2. **Protocolos (seleção por requisitos R1–R4):** pBFT, Tendermint, HotStuff (BFT), com PoSA e PoS como âncoras de validade externa. **PoW excluído** (viola R1, R2 e R4).
3. **Experimento E1 (escalabilidade), fatorial completo, φ = 0:**

   | Fator | Níveis |
   |---|---|
   | A Protocolo | pBFT, Tendermint, HotStuff, PoSA, PoS |
   | N Validadores | 4, 16, 64, 128 (faixa de governança, log) |
   | L Demanda média | λ_B, 10λ_B, 100λ_B |
   | R Regime | Poisson, MMPP-2 (mesma média) |
   | W Enlace por nó | institucional, restrito |

   Total: 240 configurações × r replicações.
4. **Experimento E2 (robustez):** φ ∈ {0; 0,10; 0,30} × regime, para n no centro da faixa.
5. **Pontos de validação (fora do ajuste):** n ∈ {256, 400, 800}. Não entram em nenhum ajuste.
6. **Métricas:** T_b, C_b (total, medido por pacote), C_nó^max, vazão Θ, latência p95 L_tx, ocupação do enlace U.
7. **Critérios de viabilidade (pré-registrados):** V1 Θ ≥ λ_pico; V2 L_tx ≤ L_max; V3 U ≤ U_max.
8. **Análise:** ANOVA de efeitos fixos sobre Box–Cox, mais HC3, ART, Games–Howell, Holm e ω². Metamodelo log-log com validação fora da faixa (cobertura do IP 95% e MAPE < 10%). Fronteira n_max(λ) por inversão do metamodelo, confirmada por simulação.

## Parâmetros a fixar antes da bateria principal

- λ_B e as faixas de N_op, κ, η, W, P (modelo de demanda), com fontes.
- Valores de W_nó (institucional e restrito).
- L_max, U_max e o limiar de MAPE.
- Tamanho de mensagem de voto s_v e de transação s_tx no simulador.
- Estado das implementações de HotStuff e Tendermint no JABS.

## Consequências

- **A ADR-0001 passa a ser pré-requisito bloqueante:** sem medição por pacote em todos os protocolos, E1 não é válido.
- Os resultados já gerados com o desenho anterior passam a valer como **piloto**: estimam a variância para calcular r e validam o simulador.
- O artigo explicita a correção do resultado anterior (crescimento linear como artefato da estimativa), em vez de omiti-la.
- Arquiteturas hierárquicas aparecem como alternativa na discussão, sem simulação neste artigo.
