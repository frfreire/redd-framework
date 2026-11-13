# ADR-0001: Reserva (buffer) e reversão de créditos emitidos

- **Estado:** Aceito (2026-10-25, Fabricio)
- **Data:** 2026-10-25
- **Decisores:** Fabricio (humano). Nenhum agente aceita este ADR.
- **Trava:** implementação de reversão no chaincode (INV-16); qualquer métrica que dependa de reversão (REQ-02, REQ-06). Emissão, transferência e aposentadoria não ficam travadas: o contrato simplesmente não implementa reversão enquanto este ADR não for aceito.

## Contexto
REDD+ tem risco de não permanência: floresta protegida pode queimar depois de o crédito ser emitido. Os programas tratam isso com reserva (buffer) de não permanência e regras de substituição ou cancelamento (ver mapeamento na seção 4 de `especificacoes/dominio-redd.md`; cláusulas não consultadas, a confirmar). O ledger atual não tem conceito de reserva nem de reversão (`chaincode/src/main/java/br/com/redd/chaincode/model/StatusCredito.java`, a confirmar na leitura do builder).

Fato: percentual de buffer, critério de reversão e quem comprova a reversão não estão fixados por nenhuma fonte neste repositório.

## Opções
1. **Fora do ledger.** O registro/programa externo administra o buffer; o ledger só guarda o resultado verificado. Prós: menor escopo, sem inventar regra. Contras: o ledger não detecta crédito emitido sobre área revertida.
2. **Reserva como entidade no ledger.** Cada emissão separa uma fração para uma conta de reserva; reversão consome reserva. Prós: rastreável. Contras: exige percentual (norma ou humano), nova invariante de conservação, mais transações.
3. **Estado REVERTIDO por crédito, sem reserva.** Verificador registra reversão que marca créditos afetados. Prós: simples. Contras: o problema de quem arca com o prejuízo (comprador, que já aposentou) fica aberto.

## Decisão
Pendente. Opinião do `redd-dominio`, sem fonte normativa: começar pela opção 1 e deixar INV-16 como limitação declarada, porque as opções 2 e 3 exigem valor que não se inventa. A decisão é do humano.

- Opção escolhida: a definir com o humano
- Percentual de buffer: a definir com o humano
- Tratamento de crédito já aposentado cuja área reverteu: a definir com o humano

## Registro do aceite

Aceito pelo humano em 2026-10-25 ("Aceito as ADR que estão pendentes"), adotando as recomendações do `redd-dominio` registradas na seção Decisão. Valores não fornecidos pelo humano continuam sem inventar.

- Opção escolhida: **1 (fora do ledger)**. INV-16 fica como limitação declarada em toda apresentação de resultado; o contrato não implementa reversão.
- Percentual de buffer: não se aplica (fora do ledger).
- Crédito já aposentado cuja área reverteu: fora do escopo do ledger.

## Consequências
- Opção 1: nenhuma mudança no chaincode; a limitação é registrada em toda apresentação de resultado.
- Opções 2 e 3: novo estado/entidade, novos eventos (INV-07), novo conjunto de testes de conservação (INV-15) e provável aumento de transações por emissão (afeta REQ-08).

## Verificação
- INV-16: lint de `delState` sobre créditos emitidos; se opção 2 ou 3, testes de reversão e conservação com reserva.
- Humano registra a opção neste arquivo e muda o estado para Aceito.
