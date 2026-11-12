PBFT-prepare-equivocation (loop 2, rodada 1 de 3): evidencia de nao regressao (criterio 7)
==========================================================================================

Contexto: ADR-0015 B-35 / Q28 (registro de 09/11/2026) e lacuna L3 da auditoria. O EQUIVOCATION do PBFT cobria PRE_PREPARE
(primario) e COMMIT, mas nao o PREPARE; o do Tendermint cobre proposta + prevote + precommit. A decisao do humano e so
"PBFT passa a equivocar tambem o PREPARE"; o COMMIT ja equivocava e NAO foi tocado. SILENT/WITHHOLD/DOUBLE_SIGN NAO foram
ampliados ao PREPARE. Mudanca de saida SOB ataque autorizada (a declarar no ADR-0006); SEM ataque a saida tem de ser identica.

Baseline: commit 423ffbe (HEAD no inicio da tarefa, arvore limpa), binario em /tmp/opencode/pbft-prep/base (git archive HEAD +
mvn -o clean compile; EFEMERO: o repositorio guarda as SAIDAS, o commit e os comandos). Java 25 (pom mira 17; nao testei em 17).
Nada commitado. Estado da arvore: ver ESTADO-ARVORE.txt (git diff 423ffbe -- src scripts | sha256sum + nao rastreados).

1. O que mudou
--------------
  src/main/java/jabs/consensus/algorithm/PBFT.java   (unico arquivo de producao)
    - isPrepareEquivocator(): no bizantino E ataque "EQUIVOCATION" (comparacao por nome, sem tocar o enum AttackType).
    - broadcastPrepareEquivocation(block): PREPARE do bloco real A aos vizinhos [0, half), PREPARE de um bloco conflitante B
      aos demais; half = max(1, n/2), a MESMA particao, a MESMA pre-condicao (requireEquivocationSplit, fase "PREPARE") e o
      MESMO envio (addToUpLinkQueue, um pacote por vizinho) do PRE_PREPARE/COMMIT equivocados. Sem catch.
    - alternativeFor(A): B com mesmo pai, altura e tamanho de A, hash/identidade distintos, sem transacoes, criador = o
      bizantino; NAO consome aleatoriedade; um B por A (cache por identidade), liberado quando A finaliza.
    - gancho em newIncomingVote(PRE_PREPARE): onde o no emitia o PREPARE honesto, o bizantino-equivocador emite o equivocado.
  src/test/java/.../PBFTEquivocationTest.java: 2 testes ajustados (redeComUmUnicoVizinho..., metadeSemNoHonesto...): a mesma
    falha alto agora acontece ao aceitar o PRE_PREPARE (o PREPARE equivoca antes do COMMIT); a mensagem e a pre-condicao sao as mesmas.
  src/test/java/.../PBFTPrepareEquivocationTest.java (NOVO, 12 testes) e PBFTPrepareEquivocationNaoRegressaoTest.java (NOVO, 2 testes).
  scripts/jabs/mutar-pbft-prepare-equivocation.py (NOVO): 9 mutantes; todos DETECTADOS.

2. Comandos (cwd = raiz; B=/tmp/opencode/pbft-prep/base; O=/tmp/opencode/pbft-prep/nr)
-----------------------------------------------------------------------------------
  Sem ataque (4 JVM em paralelo no maximo; cada caso roda base-a, base-b, atual-a, atual-b):
    python3 scripts/jabs/nao_regressao.py --base $B --atual . --semente S --timeout 900 --paralelo 4 --saida $O/sS/geral \
        --casos ARQ:4:60,ARQ:7:60,ARQ:10:60,ARQ:13:60                                          (S = 1 e 2)
  Sob ataque (ByzantineSweep VOTING = PBFT/ARQ com ataque por percentual):
    python3 scripts/jabs/nao_regressao_sweep.py --base $B --atual . --saida $O/sS/sweep-voting --tipo VOTING --validators 10 \
        --max-byz 3 --trials 2 --duration 60 --attack EQUIVOCATION --semente S                 (S = 1 e 2)
  Assinatura por evento (hash dos BlockFinalizationEvent): jabs.consensus.algorithm.PBFTSignatureMain (classe de teste), antes
    (sig-base.txt) e depois (sig-atual.txt); 40 celulas = sementes {1,42} x n {4,7,10,13} x {none, EQUIVOCATION, DOUBLE_SIGN, WITHHOLD, SILENT}.
  Testes: mvn -o test ; mutacao: python3 scripts/jabs/mutar-pbft-prepare-equivocation.py

3. Resultado
------------
  3.1 SEM ataque: ARQ n=4,7,10,13 x sementes 1 e 2 = 8 casos, base-a x atual-a IDENTICO em todos (e a x b IDENTICO nos dois lados).
      CSV iguais byte a byte (TABELA.txt, coluna byte_a_byte; inclui a coluna de hash). f=0 do sweep (sem bizantino) tambem IGUAL
      byte a byte nas duas sementes (4 trials).
  3.2 Assinatura por evento: 34 de 40 celulas IGUAIS (todas as sem ataque, DOUBLE_SIGN, WITHHOLD, SILENT, e EQUIVOCATION n=4,
      que nao finaliza nada nem antes nem depois: 0 eventos). 6 celulas DIFEREM: EQUIVOCATION n=7,10,13 x sementes 1 e 42
      (mesmo numero de eventos: 259/370/598; muda o hash, isto e, tempos e bytes). Isto e a mudanca INTENCIONAL.
  3.3 SOB ataque (sweep VOTING n=10, f=0..3, 2 trials, 60 s): base x atual DIVERGENTE (esperado), a x b IDENTICO nos dois lados
      (o atual e deterministico). Os CSV por trial de f=1,2,3 diferem; os de f=0 sao identicos.
      Medias por f (base -> atual), semente 1: avgBlockCount 31->27 (f=1), 8->8, 7->7; avgFinalizationTime 1,0637->1,0576 (f=1),
      0,8960->0,9732 (f=2), 1,1198->1,2158 (f=3). Semente 2: avgBlockCount 24->25 (f=1), 9->9, 6->6; avgFinalizationTime
      1,0860->1,1260, 0,9298->0,9987, 1,1122->1,0330. Com 2 trials por f e sem ANOVA, NAO ha conclusao de direcao: so ha a prova
      de que mudou e de que e reproduzivel.
  3.4 Granularidade (herdada, nao alterada): TrafficUntilFinalization e C_B por NO que recebe, total (inclui carga do bloco),
      so pacotes que passaram pela rede simulada; a coluna de consenso e a do ADR-0006. Cada PREPARE equivocado vira UM pacote por
      vizinho (n pacotes, como o honesto): o trafego ENVIADO nao muda; o RECEBIDO muda porque metade dos PREPAREs nomeia B e o
      no que recebe contabiliza os bytes sob o bloco B (que nunca finaliza).
  3.5 Testes: mvn -o test = 714 testes, 0 falhas (antes 700 do HEAD + 12 + 2). Lint: 13 bloqueantes / 41 avisos, igual a linha de base.

4. Limites
----------
  - O binario da baseline fica em /tmp (efemero). Sementes 1 e 2 (nao_regressao/sweep) e 1 e 42 (assinatura).
  - A cobertura sem ataque e do ARQ puro (MVPComparison) com n<=13 e 60 s; os demais protocolos nao foram tocados e nao foram
    re-rodados (o diff so altera PBFT.java; ver ESTADO-ARVORE.txt).
  - Em n=4 com f=2 o EQUIVOCATION nao finaliza nada (antes e depois): celula sem poder de discriminacao.
  - O sweep nao isola o efeito do PREPARE do efeito de mudar o conjunto bizantino: aqui a comparacao e do MESMO conjunto, mudando so o codigo.
  - Particao (B-34) e coordenacao (B-36) continuam provisorias; o B do PREPARE e fabricado por cada bizantino por conta propria
    e NAO coincide com os B do PRE_PREPARE/COMMIT (que sao amostrados): sem coordenacao.

5. Rodada 2 (criterio 3: retencao de PREPAREs do bloco conflitante B)
---------------------------------------------------------------------
Gap: os PREPAREs de B (nunca recebe PRE_PREPARE) iam para pendingPrepares sem caminho de remocao.
Correcao (menor, so o que o ataque introduz):
  - NOVO src/main/java/jabs/ledgerdata/pbft/PBFTConflictingBlock.java (extends PBFTBlockWithTx): marca o B fabricado por
    alternativeFor. Nenhum bloco honesto e desta classe.
  - PBFT.newIncomingVote(PREPARE): PREPARE de PBFTConflictingBlock sem PRE_PREPARE aceito NAO e retido (nunca poderia ser contado).
  - PBFT.addBlockTraffic/addConsensusMessageTraffic: bloco conflitante nao abre entrada em blockTraffic/consensusTraffic (mesma
    retencao eterna, pois B nunca finaliza). O trafego RECEBIDO dos PREPAREs de B segue sendo medido pelo no na recepcao? NAO:
    deixa de ser somado, porque nunca seria lido (B nunca finaliza). Nao altera nenhum BlockFinalizationEvent (ver 5.2).
Alternativa tentada e descartada (medida): liberar B quando A finaliza (por altura, e por pai/irmao). (a) Poda por altura <=
ultima finalizada MUDOU a saida SEM ataque (descarta voto de bloco honesto ainda nao aceito de altura menor): 762 eventos contra
1365 em none seed=1 n=4. (b) Por irmao, so para conflitantes: nao muda a saida, mas e INSUFICIENTE: com 2 bizantinos equivocando o
PRE_PREPARE, o A cujo PREPARE foi equivocado vive em ramo que o no nunca finaliza; medido n=7 sementes 1, 60 s: 24 B retidos
por no honesto (sem a correcao final) contra 0 agora.
5.1 Resultados: a SAIDA (40 celulas de PBFTSignatureMain) e IDENTICA a da rodada 1 (sig-atual.txt): 34 celulas iguais a 423ffbe
(sem ataque, DOUBLE_SIGN, WITHHOLD, SILENT, EQUIVOCATION n=4) e as 6 de EQUIVOCATION n=7,10,13 iguais as da rodada 1 (diferentes de
423ffbe, mudanca intencional ja declarada). Logo sig-atual.txt, as tabelas e os CSV s1/s2 desta pasta NAO foram regerados:
a correcao nao altera nenhum BlockFinalizationEvent (so deixa de reter/somar o que nunca e lido). PBFTPrepareEquivocationNaoRegressaoTest
continua passando sem editar as constantes.
5.2 Por que a saida nao muda: o trafego de B era acumulado em blockTraffic[B], lido apenas ao finalizar B (nunca); o voto retido
em pendingPrepares[B] so seria reavaliado se B recebesse PRE_PREPARE (nunca). Ambos eram memoria morta.
5.3 Limite residual (honesto; nao e teto geral, que pertence a PARIDADE-teto-retencao-pbft-tendermint):
  - PREPAREs de blocos NAO conflitantes que nunca recebem PRE_PREPARE (preexistente, nao introduzido aqui) seguem retidos; idem
    pendingCommits (COMMIT equivocado amostrado) e alreadySeenVotes.
  - prepareAlternatives (A -> B, so no no bizantino): a entrada de um A que nunca finaliza naquele no fica ate o fim (uma por A
    aceito, sem votos). A medicao n=7 s1: 12 entradas em cada bizantino.
  - O B continua sendo fabricado por cada bizantino sem coordenacao (ja listado em 4).
5.4 Testes: mvn -o test = 719 testes, 0 falhas. Mutacao: 12 mutantes (M10 PREPARE do conflitante volta a ser retido, M11 trafego
volta a abrir entrada, M12 conflitante sem a marca), todos DETECTADOS. Lint 13/41 igual a baseline.
5.5 Discordancia de gravidade: ver relatorio da rodada (o mecanismo de retencao por bloco sem PRE_PREPARE preexiste; o que e novo
e o B fabricado, e isso foi corrigido).
(A decisao da rodada 2 de "deixar de somar" o trafego de B foi REVISTA na rodada 3: ver secao 6. Os bytes de B voltam a ser medidos.)

6. Rodada 3 (criterio 1: trafego medido, completo; e retencao limitada)
-----------------------------------------------------------------------
Gap: a rodada 2 parou de somar os bytes dos PREPAREs que nomeiam B (PBFTConflictingBlock) em blockTraffic/consensusTraffic; esses
pacotes atravessam a rede (um por vizinho, mesma contagem de antes) mas sumiam da contabilidade de recebimento por no.
Correcao (PBFT.java; nenhum outro arquivo de producao):
  - 6 contadores long por no (tamanho constante, O(1) em B), um LIVRO DE RECEBIMENTO: todo byte de voto PBFT entregue ao no cai em
    exatamente uma categoria, para C_B_total e para C_B_consenso:
      atribuido      bloco que ainda pode finalizar (mapas por bloco; unico que chega ao BlockFinalizationEvent)
      posterior      chegou depois da finalizacao local do bloco (antes: descartado sem rastro; agora contado)
      conflitante    voto que nomeia PBFTConflictingBlock (antes da rodada 3: descartado)
    bytes entregues = atribuido + posterior + conflitante.  Getters: getConflictingBlockTraffic, getConflictingBlockConsensusTraffic,
    getAfterFinalizationTraffic, getAttributedTraffic, getAccountedVoteBytes, getAccountedConsensusBytes, getOpenTrafficEntryCount.
  - ESCOLHA (justificada): agregado proprio, NAO somado ao C_B de nenhum bloco. (a) B nunca finaliza, nenhum evento o leria;
    (b) somar ao A irmao seria atribuicao por palpite (mesma regra do voto nil no Tendermint/HotStuff: fora de qualquer bloco) e
    mudaria TrafficUntilFinalization sob ataque sem lastro; (c) mudar colunas/cabecalho do CSV quebraria a saida identica a 423ffbe.
    Os bytes de B ficam MEDIDOS e auditaveis no contador, mas fora do C_B por bloco. Efeito sobre o C_B reportado: nenhum
    (a saida de PBFTSignatureMain e identica a da rodada 2: 34 celulas == 423ffbe; 6 de EQUIVOCATION == rodada 1/2; sig-atual.txt,
    tabelas e CSV desta pasta continuam validos e NAO foram regerados). O que muda: a soma do trafego recebido por no agora fecha
    com o enlace, e a diferenca (bytes de B) aparece em vez de sumir.
  - retencao: nenhuma entrada em blockTraffic/consensusTraffic/pendingPrepares por B (getOpenTrafficEntryCount nao cresce com B).
6.1 Verificacao (PBFTPrepareEquivocationTest, 21 testes):
  - sobEquivocationBytesEntreguesPelaRedeSaoIguaisAosContabilizadosPorNo: LinkLoadCounters.recvBytes (contado em PacketReceivingProcess,
    independente do PBFT) == getAccountedVoteBytes por no, TODOS os nos; n=7 {1,2} semente 1 e n=10 {1,4,7} semente 42, 30 s.
  - semAtaqueOLivroTambemFechaESemTrafegoConflitante: idem sem ataque e sob DOUBLE_SIGN, com trafego conflitante == 0.
  - memoriaDeRetencaoEO1EmBMesmoComMilConflitantes: 1000 blocos B distintos -> 0 entradas novas, 0 retidos, 1000*42 B no agregado.
  - trafegoDoConflitanteNaoAbreEntradaPorBloco...: 3 PREPAREs de B = 126 B no agregado; mapas por bloco vazios; o real segue por bloco.
  - trafegoPosteriorAFinalizacaoTambemFechaOLivro: pacote pos-commit fecha o livro (antes descartado).
  Medido (30 s): n=7 s1 EQUIVOCATION entregue=185.066.193 B, B=7.392 B (176 PREPAREs de 42 B); n=10 s42: entregue=267.815.307 B,
  B=13.860 B (330 PREPAREs). Fracao < 0,01% do volume (dominado pelo PRE_PREPARE com bloco): pequeno, mas erro de contabilidade;
  agora fechado por igualdade exata, nao por tolerancia.
6.2 Mutacao: 17 mutantes, todos DETECTADOS (novos: M13/M14 bytes de B descartados de novo em total/consenso; M15 pos-finalizacao
  descartado; M16 bytes de B em dobro; M17 consensusTraffic abre entrada por B; M11 reescrito: abre entrada por B alem de contar).
6.3 Testes: mvn -o test = 723 testes, 0 falhas (719 + 4). Lint 13/41 igual a baseline. Estado da arvore: ESTADO-ARVORE.txt
  (regerar com python3 scripts/jabs/estado-arvore.py).
6.4 Limites: o livro cobre voto PBFT (PRE_PREPARE/PREPARE/COMMIT) entregue ao no; DataMessage de bloco e outras mensagens nao sao
  contadas pelo PBFT (como antes). A igualdade com o enlace vale nos cenarios PBFTLocalLANNetwork testados, onde todo pacote e voto.
  O B continua fabricado sem coordenacao. Rodadas esgotadas (3/3): outra so com decisao humana.

7. Rodada 4 EXTRA (autorizada pelo humano; criterio 8: retencao de B por A sem limite)
------------------------------------------------------------------------------------
Gap: prepareAlternatives (mapa A -> B, so no no bizantino) so era podado quando A finalizava neste no; uma sequencia de A que
nunca finalizam fazia o mapa crescer sem limite, e os testes so cobriam a liberacao no caso "A finaliza".
Escolha (a menor mudanca correta): ELIMINAR a retencao, nao limita-la. B passa a ser FUNCAO PURA de (A, no bizantino):
  - PBFTConflictingBlock(real, creator): mesmo pai, altura, tamanho E instante de criacao de A (antes: relogio da simulacao
    no momento da fabricacao, campo que nenhuma metrica le), criador = o bizantino; guarda a referencia a A (getConflictsWith).
    equals por valor (mesmo A por identidade E mesmo criador); hashCode deterministico (altura, tamanho, id do criador;
    NAO identityHashCode). Hash/identidade de objeto continuam distintos dos de A (Block nao define equals; o Hash do Data e por
    objeto e nenhum caminho do PBFT indexa bloco conflitante por ele).
  - PBFT.alternativeFor(A) cria o objeto a cada broadcast e NAO o guarda; removidos o campo prepareAlternatives, o put, o remove na
    finalizacao e o getter getPrepareAlternativeCount. Retencao A -> B = 0, para qualquer numero de A finalizados ou nao;
    nao ha teto nem politica de descarte para declarar.
  Por que nao o teto: um teto fixo exigiria politica de descarte (qual B esquecer) e um B esquecido seria recriado diferente
  (identidade nova) para o mesmo A, quebrando "cada metade ve um mundo consistente"; a funcao pura da isso sem estado.
  Dentro de UM broadcast as duas metades continuam recebendo UM bloco cada (o mesmo objeto). Entre rounds (2 PRE_PREPARE do mesmo
  A) os B sao objetos distintos mas IGUAIS por valor; o receptor nunca os distingue (PREPARE de conflitante vai ao agregado, sem
  mapa por bloco, regra da rodada 3).
7.1 Saida: SEM ataque identica a 423ffbe (nenhum caminho honesto foi tocado: PBFTConflictingBlock so e instanciado por
  alternativeFor, e o remove saiu junto com o campo). SOB EQUIVOCATION a saida NAO mudou em relacao a rodada 3: PBFTSignatureMain
  (40 celulas) reexecutado, linha a linha IGUAL a sig-atual.txt (34 celulas == 423ffbe; as 6 de EQUIVOCATION n=7,10,13 x sementes
  1 e 42 == rodada 1-3, ja declaradas como mudanca intencional vs 423ffbe) e PBFTPrepareEquivocationNaoRegressaoTest passa sem
  editar as constantes. Por isso sig-atual.txt, sig-base.txt, TABELA.txt, saida-s*-*.txt, s1/ e s2/ NAO foram regerados (continuam
  validos); ESTADO-ARVORE.txt FOI regerado. Livro de trafego (entregue = atribuido + posterior + conflitante) segue fechando por
  igualdade exata, nos 4 cenarios do teste (n=7 s1 EQUIVOCATION: 185.066.193 B entregues, 7.392 B conflitantes; n=10 s42:
  267.815.307 / 13.860; iguais a rodada 3).
7.2 Testes (PBFTPrepareEquivocationTest: 21 -> 23): REMOVIDOS mesmoBlocoConflitanteEmPreparesRepetidosEPoda e
  alternativaELiberadaQuandoABlocoFinaliza (testavam o mapa); NOVOS mesmoBlocoConflitanteEmPreparesRepetidosEIgualPorValorSemSerGuardado,
  blocoConflitanteEFuncaoPuraDeAEDoCriador, nadaFicaRetidoQuandoABlocoFinaliza e
  muitosABlocosQueNuncaFinalizamNaoAcumulamBlocosConflitantes (ACUMULACAO: 2000 A aceitos, nenhum finaliza, cada um com PREPARE
  equivocado; varre por reflexao TODOS os campos de instancia do PBFT, mapas/colecoes/votos ate 3 niveis, e exige 0
  PBFTConflictingBlock retido; confere que 2000*(n-half) pacotes de B foram de fato enviados, isto e, o ataque foi aplicado).
  mvn -o test = 727 testes, 0 falhas (723 na rodada 3; desta pasta so entram +2 liquidos, 21 -> 23; os outros +2 vieram de
  testes de fora desta tarefa no working tree, nao investigados). Lint 13 bloqueantes / 41 avisos.
7.3 Mutacao: 20 mutantes, TODOS DETECTADOS. Reescritos: M7 (equals so por identidade), M8 (retencao A -> B reintroduzida SEM poda),
  M9, M12. Novos: M18 (hashCode por identidade), M19 (criador fora do equals), M20 (retencao A -> B COM poda ao finalizar =
  a versao da rodada 3: reprova o teste de acumulacao, que e o que o gap pedia). O script agora muta PBFT.java e
  PBFTConflictingBlock.java e restaura os dois (sha256 conferido).
7.4 Limites que restam (nao sao desta tarefa; ver PARIDADE-teto-retencao-pbft-tendermint): pendingPrepares de blocos comuns sem
  PRE_PREPARE, pendingCommits (COMMIT equivocado amostrado), alreadySeenVotes (ja tem teto de 50000 no PeerBlockchainNode) e os
  mapas blockTraffic/consensusTraffic de blocos comuns que nunca finalizam. B continua fabricado sem coordenacao entre bizantinos
  (dois bizantinos geram dois B distintos para o mesmo A: o equals inclui o criador de proposito).
  Java 25 (pom mira 17; nao testei em 17). Nada commitado.
