EVIDENCIA DE NAO REGRESSAO DO CA-01 (ADR-0011, tarefa P2P-1-setup-n-pequeno, loop2, rodada 2)
==========================================================================================

Baseline : commit e703e3d47da726f5fbcf94657f574db617a59023 ("Experimento"), worktree limpo em
           /tmp/opencode/base-e703e3d, compilado com
             mvn -q -o clean compile
             mvn -q -o dependency:build-classpath -Dmdep.outputFile=target/cp.txt
Atual    : esta arvore (raiz do repo), compilada com mvn -o compile (target/classes + target/cp.txt).
Script   : scripts/jabs/nao_regressao.py (LOGICA DE COMPARACAO INTOCADA; nao e editavel pelo jabs-builder,
           escopo src/ e pom.xml). Compara CSV ignorando linhas '#' e colunas 'hash'.
Gerador  : src/test/java/jabs/network/p2p/EvidenciaRodar.java (driver) e EvidenciaHashes.java (sha256
           normalizado). hashes.py e rodar.sh neste diretorio sao so avisos de "substituido".

COMO REPRODUZIR (da raiz do repo, sem outro trabalho pesado na maquina; poucos minutos):
  mvn -q -o compile test-compile
  java -cp target/test-classes jabs.network.p2p.EvidenciaRodar /tmp/opencode/base-e703e3d /tmp/opencode/p2p1-r2/final todos
  python3 src/test/resources/nao-regressao/p2p-1/resumo.py
O driver aborta se a baseline nao estiver em e703e3d ou estiver suja. Reescreve comandos.txt, hashes.txt,
saida-final/. Linhas de comando exatas executadas: comandos.txt (uma por caso e semente), por exemplo:
  python3 scripts/jabs/nao_regressao.py --base /tmp/opencode/base-e703e3d --atual . --casos OFFSET:40:20000 \
      --semente 2 --timeout 900 --saida /tmp/opencode/p2p1-r2/final/termina/OFFSET_40_20000/s2

ARQUIVOS
  comandos.txt   comando exato de cada execucao (48 = 30 + 18)
  saida-final/   saida COMPLETA do script por caso e semente (+ "exit=N"); 48 arquivos
  hashes.txt     sha256 normalizado e n. de linhas de dados de CADA CSV de cada JVM (base-a, base-b, atual-a,
                 atual-b). Mesmo sha256 = IDENTICO para o script. Permite conferir sem rodar de novo.
  resumo.txt     tabela caso x semente (gerada por resumo.py a partir de saida-final/)
  saida/         PRIMEIRA bateria (12 JVMs por vez, 3 casos/semente). Mantida como evidencia da fragilidade a
                 carga, NAO como prova (ver "Por que o INCONCLUSIVO" abaixo).

RESULTADO (resumo.txt)
  (iii-a) TERMINAM na baseline: 3 sementes (1,2,3) x 10 casos = 30 execucoes, + OFFSET:20 semente 3 (que
          terminou na baseline por acaso da semente) = 31. Em TODAS: base x base IDENTICO, atual x atual
          IDENTICO, base x atual IDENTICO (31/31).
            Casper   MCO2:10:60 (fronteira), MCO2:20:300, MCO2:50:600 (folgado)
            Parlia   AMBIFY:10:60 (fronteira), AMBIFY:12:60, AMBIFY:30:300 (folgado)
            Nakamoto OFFSET:30:20000 (fronteira), OFFSET:35:20000 (intermediario), OFFSET:40:20000
            ARQ      ARQ:7:60 (nao usa AbstractBlockchainP2PConnections; controle)
  (iii-b) TRAVAM na baseline (TIMEOUT 30 s em base-a e base-b, laco infinito de connectToNetwork) e a saida
          do ATUAL: MCO2:4, MCO2:7, AMBIFY:4, AMBIFY:7, OFFSET:10 (3 sementes cada) e OFFSET:20 (sementes 1
          e 2). O atual termina com rc=0 em ~0-2 s e escreve CSV com dados (ex.: OFFSET:20 s1: 13 linhas
          em offset-20v-t0.csv). O script devolve exit=2 (INCONCLUSIVO) nesses casos DE PROPOSITO: nao ha
          baseline para comparar. Os CSV da baseline que travou tem so cabecalho (0 linhas), ver hashes.txt.
  Atencao: a classe depende da SEMENTE. OFFSET:20 semente 3 terminou na baseline (exit=0, IDENTICO).
  A classe da tabela e medida (base-a e base-b rc=0), nao presumida.

POR QUE O OFFSET:40:20000 SAIU INCONCLUSIVO PARA O CRITIC (investigado)
  1. Causa direta, com evidencia: /tmp/opencode/critic-p2p/OFFSET-40v-20000s-atual-{a,b}.log terminam em
       NoClassDefFoundError: jabs/simulator/event/BlockConfirmationEvent  (NakamotoConsensus.updateChain)
     SO no lado 'atual' (a baseline do mesmo caso rodou normalmente, 5 s). O .class existe hoje em
     target/classes, com mtime 17:59:43, DEPOIS do log do erro (17:59:41): target/classes do repo estava
     sendo recompilado/apagado (mvn clean compile / mvn test) enquanto o script rodava com --atual . .
     Nao e timeout nem falta de cp.txt (as outras execucoes atuais do mesmo lote, ex. MCO2:10, passaram).
     OFFSET:40 e o caso mais lento e carrega mais classes tarde, por isso foi o que pegou a janela.
  2. Fragilidade independente, MEDIDA nesta rodada: nao_regressao.py dispara TODAS as JVMs de TODOS os casos de
     uma vez (4 x numero_de_casos). Com 12 JVMs (saida/) e com 40 JVMs, o Nakamoto/OFFSET diverge ate entre
     base-a e base-b (a BASELINE contra ela mesma), p. ex. OFFSET:30 semente 1 com 40 JVMs
     (Tb_avg 11012.475274 vs 10962.520674); com 12 JVMs o Parlia AMBIFY:30 semente 3 tambem divergiu em
     base x base (saida/termina-parlia-s3.txt). Com 4 JVMs (um caso e uma semente por vez) deu IDENTICO em
     todos os casos de saida-final/. Logo: Nakamoto (e, uma vez, Parlia) nesta base sao sensiveis a carga da
     maquina; a divergencia base x base e PRE-EXISTENTE (nao vem do P2P) e so aparece com disputa de CPU.
     NAO identifiquei a linha de codigo responsavel (candidatas, nao confirmadas: thread csv-flusher do
     AbstractCSVLogger; DoubleSpendTracker usa System.currentTimeMillis). Isso e dado, nao explicacao.
     Como o atual tambem varia sob carga, uma bateria com muitas JVMs pode dar DIVERGENTE falso para
     base x atual: por isso a prova usa 4 JVMs por vez.
  3. Pre-requisitos que o script nao verifica: target/cp.txt e target/classes de --base e --atual atualizados.

COMANDO E CONDICOES QUE FUNCIONAM (script nao editado)
  - NAO rodar mvn (compile/test/clean) em outro terminal durante a bateria.
  - Rodar 1 caso e 1 semente por vez (--casos CASO --semente N), --timeout 900 para casos que terminam e
    --timeout 30 para os que travam na baseline (30 s basta: os que terminam levam < 1 s).
  - --saida num diretorio vazio por execucao.
  - Antes: mvn -o compile no repo e confirmar que nenhum fonte e mais novo que target/classes.
  - O EvidenciaRodar faz isso (e ainda confere o commit e a limpeza da baseline).

LIMITES (o que esta evidencia NAO prova)
  - Cobre os casos e sementes listados; nao e prova para todo n nem toda semente. A comparacao e sobre os CSV
    de MVPComparison (granularidade: agregado de rede, 1 trial por JVM), sem colunas de hash.
  - "IDENTICO" vale com 4 JVMs simultaneas. Sob carga o Nakamoto nao e deterministico nem na baseline.
  - Nao ha comparacao base x atual para os casos que travam na baseline (por definicao).
  - A coluna TrafficConsensusUntilFinalization (T1, ADR-0006) e removida da comparacao pelo script quando
    vale -1 em todas as linhas (extra_ok); nao foi disparada nesta evidencia (nenhuma nota gerada).
