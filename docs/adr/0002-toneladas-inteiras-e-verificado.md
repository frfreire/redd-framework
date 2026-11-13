# ADR-0002: Unidade inteira de toneladas, estimado vs verificado e granularidade do crédito

- **Estado:** Aceito (2026-10-25, Fabricio)
- **Data:** 2026-10-25
- **Decisores:** Fabricio (humano). Nenhum agente aceita este ADR.
- **Trava:** loop 1 (chaincode) nas tarefas que mexem em `RelatorioMRV`, `CreditoCarbono`, `emitirCredito` e `verificarRelatorioMRV` (INV-03, INV-11, INV-12); REQ-08.

## Contexto (verificado em 2026-10-25)
1. Toneladas são `double` no código: `model/RelatorioMRV.java:31`, `model/CreditoCarbono.java:26`, `RedContract.java:116` e `:229`. Soma e igualdade de ponto flutuante não são confiáveis, e INV-03 depende de `<=` exato.
2. A emissão compara com a quantidade **estimada**: `RedContract.java:254` usa `relatorio.getToneladasCO2eEstimadas()`. O relatório não tem campo de toneladas verificadas (lacuna já listada no esqueleto original da especificação).
3. O crédito tem um campo de quantidade: modelo "1 crédito = N toneladas". A skill do builder admite `BigDecimal` ou inteiros em milésimos (`.agents/skills/fabric-chaincode-builder/SKILL.md`, ponto 5). A especificação (INV-11) decide por inteiros; falta a unidade.
4. Valor inicial: não há fonte neste repositório para a unidade, nem para a relação permitida entre verificado e estimado.

## Opções
**Unidade do inteiro:**
- A. 1 = 1 tCO2e. Simples, alinhado à unidade de crédito de mercado (1 crédito = 1 tCO2e). Perde fração; arredondamento vira regra (a definir, direção).
- B. 1 = 1 kgCO2e (milésimos de tonelada). Preserva fração; números maiores; cuidado com overflow (`Math.addExact`).

**Granularidade do crédito:**
- C. Um registro de crédito por lote com quantidade inteira N, divisível na transferência/aposentadoria parcial. Poucas transações; exige regra de divisão e identificação das frações (série).
- D. Um registro por tonelada. Cada série é única e a aposentadoria é trivial; custo de transações cresce com o volume (afeta REQ-08).
- E. Um registro por lote indivisível. Mais simples; só transfere/aposenta tudo (INV-14 fica como está).

**Estimado vs verificado:**
- F. Verificado pode ser maior, menor ou igual ao estimado; emissão usa só o verificado.
- G. Verificado limitado ao estimado (`verificado <= estimado`).

## Decisão
Pendente. Opinião do `redd-dominio` sem fonte normativa: A ou B conforme o humano, E para começar, e F (impõe só `verificado > 0`), porque G é regra que nenhuma fonte fixou. O humano decide.

- Unidade: kgCO2e (B)
- Granularidade: E (lote indivisível)
- Relação verificado/estimado: F
- Regra de arredondamento: R1 (ver Registro do aceite)

## Registro do aceite

Aceito pelo humano em 2026-10-25 ("Aceito as ADR que estão pendentes"), adotando as recomendações do `redd-dominio` registradas na seção Decisão. 

- Granularidade: **E (lote indivisível)**.
- Relação verificado/estimado: **F** (só `verificado > 0`; emissão usa só o verificado; `Math.addExact`).
- Tipo: `long` inteiro, nunca `double`/`float` (INV-11).
- Unidade: **B (1 = 1 kgCO2e)**, definida pelo humano em 2026-10-25. Inteiro `long`, com `Math.addExact`. Interfaces externas que falem em tCO2e convertem na borda (1 tCO2e = 1000 unidades).
- Regra de arredondamento: **R1 (rejeitar)**, definida pelo humano em 2026-10-25. O contrato rejeita entrada que não seja inteira em kgCO2e; não há arredondamento no ledger. Conversão de tCO2e decimal fica na borda (API/cliente), fora do chaincode, e se existir deve truncar (R2) para nunca exceder o verificado.

## Opções de arredondamento (unidade kgCO2e)

Só se aplica onde um valor não inteiro em kg entra no sistema (ex.: tCO2e decimal vindo do relatório ou da API).

- R1. Rejeitar. A entrada tem de ser inteira em kg; o chamador converte. Sem perda silenciosa.
- R2. Para baixo (truncar). Nunca emite mais do que o verificado.
- R3. Para o inteiro mais próximo (meio para cima). Erro mínimo, pode exceder o verificado em até 0,5 kg.
- R4. Para baixo na emissão e para cima na aposentadoria. Conservador nos dois sentidos, mas quebra a conservação INV-15.
- R5. Arredondar para tonelada cheia (múltiplo de 1000 kg), resto fica como saldo não emitido. Alinha com crédito de mercado de 1 tCO2e; contraria a razão de usar kg.

## Consequências
- Qualquer opção: troca `double` por `long` (ou `BigInteger`) em modelos, argumentos e eventos; migração de estado existente não é necessária se a rede ainda não foi implantada (a confirmar pelo orquestrador).
- Acrescenta `toneladasVerificadas` e `toneladasEmitidas` ao relatório; emissão passa a usar o verificado.
- Opção D multiplica transações por emissão; REQ-08 precisa de carga medida pela ponte.
- Opção C exige nova invariante de série de fração.

## Verificação
- INV-11: grep sem `double`/`float` em campos de toneladas; teste de soma exata; teste de estouro.
- INV-12: relatório estimado 100 / verificado 60, emitir 61 falha, 60 passa.
- INV-03: emissão de parcelas que somam o verificado passa; 1 unidade extra falha.
- Humano registra as opções neste arquivo e muda o estado para Aceito.
