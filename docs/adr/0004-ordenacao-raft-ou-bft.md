# ADR-0004: Serviço de ordenação do produto: Raft ou BFT

- **Estado:** Aceito (2026-10-25, Fabricio)
- **Data:** 2026-10-25
- **Decisores:** Fabricio (humano). Nenhum agente aceita este ADR.
- **Trava:** como a `redd-ponte` e o loop 3 apresentam resultados de BFT do JABS (REQ-02, REQ-05, REQ-09, REQ-10); qualquer troca do ordenador em `rede-fabric/`.

## Contexto
O Fabric 2.5 usa Raft como serviço de ordenação, que tolera falha de parada (crash), não falha bizantina (`AGENTS.md`, seção Limites). O JABS simula protocolos bizantinos (PBFT, HotStuff, Tendermint, etc.). Um resultado BFT do simulador descreve uma alternativa de ordenação, não o comportamento do Fabric atual. O ADR-0006 trata a medição do tráfego do PBFT e não responde a esta pergunta.

Em um consórcio de 3 organizações com interesses distintos (proponente, verificador, mercado), o modelo de ameaça relevante pode incluir uma organização maliciosa, o que o Raft não cobre. Qual é o modelo de ameaça aceito é decisão do humano; não há fonte aqui.

## Opções
1. **Raft (estado atual).** Mantém o produto; resultados BFT do JABS servem de comparação teórica, com a distância documentada. Prós: nenhuma mudança. Contras: não tolera orderer malicioso.
2. **BFT no produto.** Migração para um ordenador bizantino (o Fabric 2.5 não traz um ordenador BFT estável por padrão; a opção e a versão precisam ser confirmadas contra a documentação do Fabric). Prós: tolera falha bizantina. Contras: mudança grande, risco de manutenção.
3. **Raft agora, BFT como linha de pesquisa.** O produto segue com Raft e o JABS mede o custo de uma alternativa BFT (Tb, C_B, Pdv), declarando a distância.

## Decisão
Pendente. Opinião do `redd-dominio`: opção 3, porque corresponde ao desenho do projeto (duas trilhas que se alimentam) e não força mudança de produto. O humano decide.

- Opção escolhida: a definir com o humano
- Modelo de ameaça (orderer malicioso é considerado?): a definir com o humano

## Registro do aceite

Aceito pelo humano em 2026-10-25 ("Aceito as ADR que estão pendentes"), adotando as recomendações do `redd-dominio` registradas na seção Decisão. 

- Opção escolhida: **3 (Raft no produto; BFT como linha de pesquisa no JABS)**.
- Modelo de ameaça: orderer malicioso **não** é considerado no produto. Todo resultado BFT carrega o aviso REQ-10 e o campo "relação com o Fabric".

## Consequências
- Opção 1 ou 3: REQ-10 vale: todo resultado BFT carrega o aviso "alternativa de ordenação, não o Fabric atual" e a ponte documenta a distância.
- Opção 2: a ponte passa a calibrar o simulador com medições do ordenador BFT escolhido; `rede-fabric/` e a política de endosso mudam.
- REQ-09 e REQ-05 são lidos em Raft ou BFT conforme a opção.

## Verificação
- Toda tabela de resultado do loop 3 contém o campo "relação com o Fabric" (Raft vs BFT).
- Humano registra a opção neste arquivo e muda o estado para Aceito.
