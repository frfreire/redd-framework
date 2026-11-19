# ADR-0003: Escopo de protocolos novos — células do plano complexidade × liderança

- **Estado:** Aceito (escopo com Tendermint) — 2026-10-28
- **Data:** 2026-08-28
- **Trava as demandas:** D3
- **Decisores:** Caburlão, Kleber, <você>

## Contexto

**A pergunta deste ADR foi reformulada em 2026-10-28.** Antes era "um protocolo
novo ou dois?", enquadrada como cobertura da família de votação. O enquadramento
correto é de desenho experimental: quantas células do plano
complexidade × política de liderança o estudo precisa para **atribuir** o efeito
em vez de confundi-lo?

Entre pBFT e HotStuff mudam duas variáveis ao mesmo tempo: a complexidade de
mensagens (O(n²) → O(n)) e a política de liderança (líder estável → rotação por
bloco), além da agregação de assinaturas. Com apenas esses dois protocolos, a
afirmação central pretendida pelo artigo — "a linearidade é o que faz o HotStuff
escalar" — não se sustenta: a diferença observada pode vir da rotação.

| | Líder estável | Rotação de líder |
|---|---|---|
| **O(n²)** | pBFT | **Tendermint** |
| **O(n)** | *(ablação do HotStuff)* | HotStuff |

Tendermint ocupa a célula O(n²)/rotativo e é o que permite decompor o efeito.
É também um protocolo real e reconhecível (base do Cosmos), não um artefato
de laboratório.

A implementação de protocolo é o item de maior risco de cronograma do projeto,
e a disponibilidade da Caburlão após a defesa pode ser limitada.

## Opções consideradas

### Opção A — duas células (só HotStuff)
- **Prós:** caminho crítico mais curto; risco de prazo menor.
- **Contras:** complexidade e liderança permanecem confundidas; a afirmação
  causal central do artigo fica indefensável e vira limitação declarada.

### Opção B — três células (HotStuff + ablação de liderança)
- **Prós:** resolve o confundimento por custo marginal quase zero (a rotação
  vira parâmetro na própria implementação do HotStuff); mesma base de código,
  então a única variável que muda é a política.
- **Contras:** a célula O(n²)/rotativo fica vazia; HotStuff com líder estável é
  um estudo de ablação, não um protocolo que alguém implanta.

### Opção C — quatro células (HotStuff + Tendermint + ablação) — ADOTADA
- **Prós:** fecha o plano; Tendermint é protocolo real na célula que falta;
  permite atribuição causal limpa entre complexidade e liderança.
- **Contras:** dobra o item de maior risco de cronograma; se o Tendermint
  atrasar, arrasta D2, D5 e D6.

## Decisão

**Opção C — Tendermint entra no escopo.** O fator A passa a seis níveis
(pBFT, HotStuff, Tendermint, CasperFFG, Parlia, Nakamoto).

Ordem de implementação em D3: **HotStuff primeiro** (D3a), porque valida o
portão V-A no sentido O(n) e destrava o contraste central; **Tendermint em
seguida** (D3b), apoiado pela skill `jabs-tendermint`.

Decisão dependente, a tomar **antes** do início de D3a: se a rotação de líder
entrar como parâmetro de configuração do HotStuff desde o primeiro dia, a
quarta célula (ablação) custa quase nada. Implementar com rotação fixa e
parametrizar depois vira refatoração.

## Consequências

Fator A com 6 níveis. Bateria 1 passa de 144 para **174 células**; Bateria 2, de
30 para **36**; piloto, de 12 para **18**. Com r = 10, o total vai a ~2.100
execuções nas baterias.

D3 passa a ter dois sub-entregáveis com portões distintos: D3a (HotStuff, V-A
no sentido O(n)) e D3b (Tendermint, V-A no sentido O(n²), mais verificação de
*locking* e votos nil).

Risco a monitorar: o Tendermint é o item que escorrega se o prazo apertar. Se
escorregar, a limitação a declarar deixa de ser "cobertura da família" e passa
a ser o confundimento complexidade × liderança — mais honesto e mais específico.

## Verificação

- HotStuff passa no V-A com ordem O(n); Tendermint, com O(n²).
- Ambos usam `addBlockTraffic(packet.getSize())` — nunca fórmula fechada
  (ADR-0001).
- Ambos respeitam os mesmos tipos de ataque de `ByzantineConfig`, para que o
  modelo de ataque seja idêntico entre protocolos na Bateria 2.
- Tendermint: *locking* (PoLC) e votos nil implementados e verificados — sem
  eles o que foi implementado não é Tendermint.
