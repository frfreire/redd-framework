# ADR-0007: PBFT valida o primário da view e sincroniza a view (débito técnico 4 do ADR-0006)

- **Estado:** Aceito (2026-10-26, Fabricio)
- **Data:** 2026-10-26
- **Decisores:** Fabricio (humano). Nenhum agente aceita este ADR.
- **Trava:** a tarefa T6 do loop 2 (primário e view do PBFT). Também trava qualquer resultado do ARQ/PBFT que envolva líder bizantino, falha de primário ou view change.
- **Origem:** o critic do loop 2 apontou o gap na T2 (critério 3, fidelidade ao protocolo). O arbiter o classificou `GAP_NAO_BLOQUEANTE` como débito pré-existente. Ver "Fechamento da tarefa 2" em `docs/adr/0006-pbft-trafego-medido.md`.

## Contexto (verificado em 2026-10-25, sem fonte externa consultada)

1. `PBFT.java` (`newIncomingVote`, caso `PRE_PREPARE`, ~l.181-185) aceita PRE_PREPARE de qualquer remetente. Só confere que o bloco conecta ao genesis. A baseline anterior à T2 fazia o mesmo (`/tmp/opencode/t2-base`, l.117-127).
2. A view (`currentViewNumber`) é local a cada nó e só incrementa quando o próprio nó finaliza um bloco. Nós com latências diferentes divergem de view.
3. `getCurrentPrimaryNumber()` retorna o primário da view local (view 0 → nó 0).
4. `PBFTLocalLANNetwork.java:~97` fixa o proponente em `Math.min(10, n-1)`, que não é o primário da view. Existe um segundo caminho de proposta em `PBFT.checkVotes` (propõe quem tem `nodeID == view % n`, depois de finalizar).
5. View change não existe (`// TODO` em `PBFT.java:52`).
6. Medido pelo builder em cópia fora do repositório (T2, rodada 3):
   - só validar remetente = primário: ARQ finaliza 0 blocos (n=4, 7, 10);
   - validando e alinhando o agendador ao primário do nó 0: finaliza 51, 82 e 60 blocos, mas 1, 1 e 7 PRE_PREPAREs do primário legítimo são rejeitados por receptores com view defasada.
7. Efeito sobre pesquisa: o ARQ/PBFT atual não modela falha de primário, primário bizantino nem view change. Hoje isso fica rotulado em todo resultado.

## Opções

1. **Não corrigir; manter como limitação rotulada.** O PBFT do projeto vira "PBFT sem view change". Prós: nenhum custo, nenhuma mudança de saída. Contras: o hook bizantino de líder (equivocação do primário) não é exercitado; comparar PBFT com HotStuff/Tendermint (que têm rotação) fica injusto.
2. **Validar o primário com view sincronizada por altura.** A view passa a ser função da altura (ou do número de sequência) e do contador de timeouts, igual em todos os nós corretos; PRE_PREPARE só é aceito do primário dessa view; o agendador de propostas (`PBFTLocalLANNetwork`) passa a propor pelo primário da view. Sem view change por timeout (o primário é determinístico: `(altura + trocas) % n`). Prós: fidelidade moderada, cobre rotação de líder; escopo médio. Contras: não tolera primário silencioso (sem timeout, a rede para).
3. **Opção 2 mais view change completo** (timeout, VIEW_CHANGE, NEW_VIEW com provas). Prós: fidelidade ao artigo; permite medir falha de primário. Contras: escopo grande, várias tarefas, novo tipo de mensagem e tráfego a contabilizar.

## Decisão

Pendente. Opinião do orquestrador, sem fonte normativa: opção 2 agora, em tarefa de um gap (validar remetente, view por altura, agendador alinhado), e opção 3 como tarefa seguinte só se algum experimento precisar de falha de primário. A decisão é do humano.

- Opção escolhida: a definir com o humano
- Regra de rotação do primário (por altura, por bloco finalizado, ou fixo até timeout): a definir com o humano
- Timeout de view change, se opção 3: a definir com o humano

## Registro do aceite

Aceito pelo humano em 2026-10-26 ("Aprovo o ADR-0007"), adotando a recomendação registrada na seção Decisão.

- Opção escolhida: **2** (validar o primário com view sincronizada por altura; sem view change por timeout). A opção 3 só abre por novo ADR, se algum experimento precisar de falha de primário.
- Regra de rotação: **por altura**, primário = `(altura + trocas) % n`, `trocas = 0` enquanto não houver view change. É a regra proposta pelo orquestrador, adotada com o aceite.
- Timeout de view change: não se aplica (opção 3 não escolhida).
- Limitação que permanece: primário silencioso ou bizantino para a rede; todo resultado ARQ/PBFT carrega o aviso "sem view change, não tolera falha de primário".

## Consequências

- Opção 1: nada muda; todo resultado ARQ/PBFT carrega o aviso "sem validação de primário nem view change".
- Opções 2 e 3: altera `PBFT.java`, `PBFTLocalLANNetwork.java` e testes; **muda intencionalmente a saída do ARQ** (nota de não regressão do critério 7): C_B e Tb anteriores a esta tarefa deixam de valer para comparação (já eram inválidos pelo ADR-0006; esta é mais uma versão).
- Opção 3: novo tipo de mensagem, tráfego de view change a medir (hook de `PeerBlockchainNode`), nova decomposição de C_B.
- Em qualquer opção: Fabric usa Raft; o resultado continua sendo uma alternativa de ordenação, não o Fabric atual (ADR-0004, REQ-10).

## Verificação

- Opções 2 e 3: PRE_PREPARE de não primário não aceita nem libera votos retidos (teste); o primário legítimo nunca é rejeitado por view defasada em condições sem falha (contador de rejeições = 0 em n=4, 7, 10, 25); ARQ finaliza com número de blocos da ordem do baseline (>0).
- `checar-determinismo.py --protocolo ARQ --validadores 7`: idêntico.
- `nao_regressao.py`: Casper e Nakamoto idênticos à baseline.
- Opção 3: teste com primário silencioso (crash) finalizando após view change.
- Humano registra a opção neste arquivo e muda o estado para Aceito.
