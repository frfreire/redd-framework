# ADR-0005: Prazos, limiares das métricas e regras de borda de período

- **Estado:** Aceito (2026-10-25, Fabricio)
- **Data:** 2026-10-25
- **Decisores:** Fabricio (humano). Nenhum agente aceita este ADR.
- **Trava:** qualquer afirmação "o protocolo X atende o requisito" (loop 3); REQ-01..REQ-09 ficam sem veredito numérico até aceite; convenção de borda em INV-04.

## Contexto
Os requisitos REQ-01..REQ-09 de `especificacoes/dominio-redd.md` ligam operações a métricas do simulador (Tb, Pdv, C_B, vazão), mas nenhuma norma consultada fixa o valor. Os mapeamentos para VCS/Verra, ART-TREES, CORSIA e SBCE não trazem prazo de finalização de bloco: prazos regulatórios existem para relato e verificação (ordem de meses), não para latência de consenso (segundos). Não confundir os dois.

Há ainda três regras de INV-04 que o texto da especificação deixa abertas:
- intervalo de monitoramento fechado ou semiaberto (borda comum conta como sobreposição?);
- relatório REJEITADO continua ocupando o período?
- janela máxima entre submissão e verificação.

## Opções
1. **Humano fixa os números** com a fonte (norma, contrato, política da organização), registrados neste ADR. Prós: critérios verificáveis. Contras: depende de decisão externa.
2. **Limiares derivados do próprio experimento.** Mede-se o comportamento e o humano escolhe o limiar olhando a curva (ex.: o ponto em que Tb degrada). Prós: ancora no dado. Contras: o limiar passa a ser descritivo, não normativo; precisa rotular como tal.
3. **Não fixar limiar.** Resultados são relatados como comparação relativa entre protocolos, sem veredito "atende/não atende". Prós: honesto sem fonte. Contras: não responde ao requisito.

Para os períodos:
- P1. Intervalo fechado `[início, fim]`; borda igual é sobreposição (comportamento conservador hoje descrito).
- P2. Semiaberto `[início, fim)`; períodos adjacentes passam.
- Q1. REJEITADO libera o período. Q2. REJEITADO continua ocupando.

## Decisão
Pendente.

- Tb (REQ-01, 03, 04, 06): a definir com o humano
- Pdv (REQ-02, 05, 06): a definir com o humano
- Vazão sustentada (REQ-08): a definir com o humano
- Falha tolerada (REQ-09): a definir com o humano
- Janela entre submissão e verificação: a definir com o humano
- Borda de período (P1/P2): a definir com o humano; até lá, P1
- REJEITADO e período (Q1/Q2): a definir com o humano; até lá, Q2 (ocupa), por ser mais restritivo

Opinião do `redd-dominio`: opção 3 para os resultados enquanto não houver número, e opção 1 para o que o humano quiser declarar como requisito.

## Registro do aceite

Aceito pelo humano em 2026-10-25 ("Aceito as ADR que estão pendentes"), adotando as recomendações do `redd-dominio` registradas na seção Decisão. 

- Limiares (Tb, Pdv, vazão, falha tolerada, janela de verificação): **opção 3, não fixar**. Resultados são comparação relativa entre protocolos, sem veredito "atende/não atende"; qualquer limiar descritivo vem rotulado como tal.
- Borda de período: **P1** (fechado; borda igual é sobreposição).
- REJEITADO e período: **Q2** (continua ocupando).

## Consequências
- Sem números, o loop 3 não pode concluir "atende o requisito"; só pode relatar comparações, com semente, versão do simulador e granularidade.
- A convenção de borda e o tratamento de REJEITADO mudam o código de `submeterRelatorioMRV` e seus testes de INV-04.

## Verificação
- Cada REQ com limiar passa a conter valor e fonte; `grep "a definir" especificacoes/dominio-redd.md` mostra o que resta.
- Casos de teste de INV-04 cobrem a convenção escolhida.
- Humano registra as decisões neste arquivo e muda o estado para Aceito.
