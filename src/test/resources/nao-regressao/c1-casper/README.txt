EVIDENCIA DE NAO REGRESSAO E DE MUDANCA INTENCIONAL - C1-casper-quorum-correcao (loop2-jabs-codigo, rodada 1)
==============================================================================================================

Mudanca: CasperFFG deixa de usar  votos > ((S/3)*2)+1  e passa a usar  votos >= ceil(2S/3) = (2S+2)/3
(CasperFFG.supermajorityThreshold, metodo estatico puro; mesmo limiar na justificacao e na finalizacao).
Peso unitario por validador; stake desigual nao modelado (a definir com o humano). S continua sendo
numOfStakeholders (denominador NAO foi alterado).

Baseline : commit e703e3d47da726f5fbcf94657f574db617a59023, worktree limpo (conferido pelo driver:
           git log -1 + git status --porcelain) em /tmp/opencode/base-e703e3d, compilado
           (mvn -o -q clean compile; mvn -o -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt).
Atual    : esta arvore, mvn -o compile (target/classes + target/cp.txt).
Script   : scripts/jabs/nao_regressao.py (INTOCADO). Um caso e uma semente por vez (4 JVMs), --timeout 900,
           nenhum mvn em paralelo durante a bateria.
Driver   : src/test/java/jabs/network/p2p/EvidenciaCasperRodar.java  (molde: EvidenciaRodar do P2P-1)
Resumo   : src/test/java/jabs/network/p2p/EvidenciaCasperResumo.java (tabela Tb/Cb/blocos por caso e semente)
Hashes   : EvidenciaHashes (sha256 normalizado do CSV, sem '#' e sem colunas 'hash').
Hashes de arquivos-fonte (CasperFFG.java etc.): NAO calculados (sha256sum nao permitido neste ambiente);
a versao e identificada pelo estado da arvore sobre e703e3d.

COMO REPRODUZIR (raiz do repo)
  mvn -o -q compile test-compile
  java -cp target/test-classes jabs.network.p2p.EvidenciaCasperRodar /tmp/opencode/base-e703e3d /tmp/opencode/c1-casper/final2
  java -cp target/test-classes jabs.network.p2p.EvidenciaCasperResumo /tmp/opencode/c1-casper/final2
Reescreve comandos.txt, hashes.txt e saida-final/*.txt (os .txt antigos sao apagados pelo driver).

ARQUIVOS
  comandos.txt        comando exato de cada execucao (36 = 12 identico + 9 pedido + 15 suplementar)
  saida-final/        saida completa do script por caso e semente, com "exit=N"
  hashes.txt          sha256 normalizado e n. de linhas de cada CSV de cada JVM (base-a, base-b, atual-a, atual-b)

RESULTADO 1 - protocolos que NAO usam CasperFFG: base x atual IDENTICO (12 de 12)
  ARQ:7:60, AMBIFY:10:60, AMBIFY:12:60, OFFSET:40:20000, sementes 1, 2, 3: base x base, atual x atual e
  base x atual IDENTICO, todos exit=0.
  Confirmacao por codigo: CasperFFG so e instanciado em EthereumCasperNode e EthereumCasperMiner
  (grep "new CasperFFG"), que so sao criados por CasperFFGGlobalBlockchainNetwork, que so e usado por
  CasperEthereumNetworkScenario (MVPComparison: MCO2 e TREE; ByzantineSweep: POS; Main: Casper). PBFT/ARQ,
  Parlia, Nakamoto/Bitcoin e HotStuff nao tocam essa classe. Outros pontos com "/3" (Parlia 2S/3 estrito,
  DAGsper, PBFT, SimulationMetrics) NAO foram alterados: fora do escopo desta rodada.

RESULTADO 2 - os 3 casos MCO2 pedidos NAO divergem (achado; a expectativa da tarefa nao se confirmou)
  MCO2:10:60, MCO2:20:300, MCO2:50:600, sementes 1, 2, 3: base x atual IDENTICO (exit=0), 9 de 9. Motivo medido:
    * MCO2:10:60 e MCO2:20:300: NENHUM bloco e finalizado nas duas versoes (0 linhas, Tb=Cb=0). O checkpoint
      e de 14 blocos a 15 s (~210 s de media): 60 s e 300 s a n=20 nao chegam a finalizar. Nao ha o que comparar.
    * MCO2:50:600: S=(int)(0,7*50)=35, S%3==2. Nesse residuo o limiar antigo e o novo COINCIDEM:
      antigo (35/3)*2+1+1 = 24; novo (2*35+2)/3 = 24. A mudanca nao altera esse caso.
  Isto NAO e prova de que a correcao nao tem efeito: so que esses tres casos nao a exercitam.

RESULTADO 3 - casos SUPLEMENTARES onde o limiar muda e ha finalizacao (600 s)
  Escolhidos por S%3 em {0,1} (limiar muda) e duracao >= checkpoint. n=20 a 600 s e controle (S%3==2).
  limiar (votos) antigo -> novo:  n=10 (S=7) 6 -> 5 | n=30 (S=21) 16 -> 14 | n=40 (S=28) 20 -> 19 |
  n=100 (S=70) 48 -> 47 | n=20 (S=14) 10 -> 10 (controle).
  Tb, Cb e blocos finalizados por caso (colunas "blocos" = linha "Blocks: G generated, F finalized" do log):
    caso        sem  Tb base    Tb atual   Cb base   Cb atual  blocos b/a  base x atual  atual x atual
    n=10 600     1   338.846169 338.713469 0.125728  0.083819  14/14       DIVERGENTE    IDENTICO
    n=10 600     2   340.996569 340.973477 0.006669  0.006669  14/14       DIVERGENTE    IDENTICO
    n=10 600     3   227.191737 227.082037 0.033712  0.035882  28/28       DIVERGENTE    IDENTICO
    n=30 600     1   295.836160 295.783945 0.002932  0.002932  14/14       DIVERGENTE    IDENTICO
    n=30 600     2   475.325749 475.243804 0.008452  0.004226  14/14       DIVERGENTE    IDENTICO
    n=30 600     3   304.790397 304.710686 0.058163  0.069795  28/28       DIVERGENTE    IDENTICO
    n=40 600     1   276.105065 276.102691 0.001464  0.001464  14/14       DIVERGENTE    IDENTICO
    n=40 600     2   307.014775 306.983003 0.007299  0.002896  14/14       DIVERGENTE    IDENTICO
    n=40 600     3   358.525728 358.524914 0.007478  0.007478  14/14       DIVERGENTE    IDENTICO
    n=100 600    1   351.022657 351.022172 0.007386  0.007386  14/14       DIVERGENTE    IDENTICO
    n=100 600    2   368.992669 368.989425 0.003011  0.003011  14/14       DIVERGENTE    IDENTICO
    n=100 600    3   367.919683 367.918923 0.021807  0.021807  14/14       DIVERGENTE    IDENTICO
    n=20 600     1   252.776398 252.776398 0.066673  0.066673  28/28       IDENTICO      IDENTICO
    n=20 600     2   349.319215 349.319215 0.005918  0.005918  14/14       IDENTICO      IDENTICO
    n=20 600     3   364.629189 364.629189 0.009551  0.009551  14/14       IDENTICO      IDENTICO
  Leitura: Tb ATUAL <= Tb BASE em todas as 12 linhas que divergem, por 0,0008 s a 0,13 s. Hipotese (NAO
  verificada por instrumentacao): o novo limiar exige menos votos, entao o quorum chega antes
  (S%3==1: um voto a menos; S%3==0: dois; S%3==2: igual, sem efeito). Aqui: S=7, 28, 70 -> um; S=21 -> dois.
  Cb muda em 5 das 12 linhas e nos DOIS sentidos (0.125728 -> 0.083819, mas 0.033712 -> 0.035882): Cb e a
  quantidade de bytes recebidos pelo PRIMEIRO no que finaliza ate o instante da finalizacao (muda o no e o
  instante), nao um custo por voto. Nao ha monotonia a explicar.
  Blocos finalizados iguais base x atual em todas as linhas (14 ou 28, multiplos do checkpointSpace=14);
  a causa do agrupamento em 14 nao foi investigada aqui.
  atual x atual IDENTICO em todos (determinismo preservado). base x base IDENTICO em todos.
  Casos suplementares exit=1 (DIVERGENTE) sao o esperado; os controles n=20 exit=0.

LIMITES (o que esta evidencia NAO prova)
  * Tb muda por frações de segundo porque o primeiro no a finalizar um link ja recebe muitos votos perto do
    limiar; o efeito de 1 a 2 votos a menos e pequeno em rede de ~10-100 nos. NAO e estimativa de impacto para
    S grande, stake desigual ou com falhas bizantinas: ByzantineSweep/POS NAO foi rodado aqui.
  * 1 trial por JVM, sementes 1-3, granularidade: agregado de rede, primeiro no a finalizar por altura.
  * Os numeros de Casper anteriores a esta correcao NAO sao comparaveis com os posteriores.
  * Dependencia de carga conhecida do P2P-1 (Nakamoto sob muitas JVMs): aqui 4 JVMs por vez, sem mvn em paralelo.

(a) POPULACAO QUE VOTA x DENOMINADOR - reportado, NAO alterado (ver tambem a resposta da tarefa)
  Diagnostico: src/test/java/jabs/consensus/algorithm/CasperPopulacaoMain.java (nao e teste).
    java -cp "target/classes:target/test-classes:$(cat target/cp.txt)" jabs.consensus.algorithm.CasperPopulacaoMain 10:600:1 30:600:1
  GlobalProofOfWorkNetwork.populateNetwork cria numMiners + numNonMiners = (int)(0,3n) + (int)(0,7n) nos;
  TODOS sao EthereumCasperMiner/EthereumCasperNode com CasperFFG, e todos votam e contam. O denominador
  configurado e so S = (int)(0,7n) (CasperEthereumNetworkScenario passa this.numOfStakeholders). Medido por
  reflexao no mapa de votos: n=10/600 s: 10 votantes distintos (3 mineradores + 7 stakeholders); n=30/600 s: 30
  (9 + 21). Nunca 7 nem 21.
    n    miners  S    votantes   limiar novo   limiar novo/votantes   2/3 dos votantes reais
    10   3       7    10         5             0,500                  7
    50   15      35   50         24            0,480                  34
  Efeito: o limiar efetivo e < 2/3 da populacao que realmente vota (50% em n=10; 48% em n=50), porque
  ceil(2S/3) e calculado sobre S < n. Antigo: 60% em n=10 (6/10) e 48% em n=50 (24/50).
  Para o n=10 e n=50 a diferenca em votos entre "2/3 de S" e "2/3 de n" e 5 vs 7 e 24 vs 34.
  NAO corrigido (instrucao: sem mudar denominador). Registrar como debito / decisao para o humano.
