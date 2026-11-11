HS-CB-fase2, PARTE B (rodada 1, continuacao): evidencia de nao regressao (criterio 7), B1 (determinismo sob carga), B2 (trava de fixture), B3 (comparador)
=========================================================================================================================================

Base: 8c680840925899e6fb3cbcf790b80a89e0ad3356 (binario congelado em /tmp/opencode/base-8c68084, BASELINE.txt: arvore_limpa=sim).
Arvore atual: Parte A + Parte B, NADA COMMITADO (ver ESTADO-ARVORE.txt). Java 25 executou tudo; o pom mira 17 (nao testei em 17).
Maquina: 28 CPUs logicas, 32 GB. Eu sou o builder: nao me autoaprovo. Nada aqui e resultado de pesquisa; --tm-*/--hs-* sao FIXTURE.
Nao toquei em docs/adr, PBFT.java, ByzantineConfig, output/, data/, docs/_build/.

1. B1: O ACHADO DE DETERMINISMO (baseline 8c68084, SEM a mudanca da Parte A)
-----------------------------------------------------------------------------
1.1 Resposta curta
  REPRODUZIDO e CAUSA PROVADA POR INTERVENCAO. NAO e identityHashCode, NAO e tempo de parede da simulacao. E uma CORRIDA DE
  ESCRITA no logger de CSV: src/main/java/jabs/log/AbstractCSVLogger.java (arquivo NAO modificado pela Parte A nem pela B).
  A simulacao em si e deterministica; o que varia sob carga e o ARQUIVO CSV POR BLOCO (tendermint-4v-t0.csv, hotstuff-4v-t0.csv):
  ordem das linhas e, as vezes, bytes entrelacados. O mvp-results.csv (agregado) nunca divergiu nessas execucoes: ele sai de
  SimulationMetrics em memoria, nao do CSV por bloco.

1.2 Mecanismo (arquivo:linha, texto da baseline = o da arvore atual)
  AbstractCSVLogger.java:134-140  bufferRow(): a thread principal enfileira a linha e, com >= 128 na fila, chama flushBuffer().
  AbstractCSVLogger.java:145-153  thread daemon "csv-flusher": chama flushBuffer() a cada 250 ms (tempo de PAREDE).
  AbstractCSVLogger.java:171-186  flushBuffer(): faz poll de ate 128 linhas e escreve com loggerCSV.writeRow(); NAO e synchronized.
  Duas threads fazem poll da MESMA fila e escrevem no MESMO CsvWriter (nao e thread-safe):
    - dois lotes retirados em ordem mas escritos em ordem trocada  -> linhas fora de ordem (classe ORDEM: mesmo multiconjunto de
      linhas, so a ordem muda; a coluna Time desce no ponto da troca);
    - duas escritas simultaneas no CsvWriter                       -> bytes entrelacados, colunas fora do lugar, contagem de
      linhas diferente (classe CONTEUDO: 365 x 346 linhas do achado original; a amostra arquivada tem 46 linhas com numero
      de colunas diferente do cabecalho).
  Por que a carga importa: a thread de 250 ms so cai no meio de um flush da principal quando a CPU esta disputada.
  Por que ARQ nao aparece (INFERENCIA coerente com os dados, nao medida a parte): o CSV do ARQ tem ~28 linhas (< 128), entao
  so a thread de 250 ms faz poll; Tendermint (347 linhas) e HotStuff (193) passam de 128 e ganham o segundo "poller".

1.3 Prova por intervencao (variantes compiladas FORA da arvore por scripts/jabs/diag_variante_logger.py; nada entrou em src/)
  - "sincronizado": so `private void flushBuffer()` -> `private synchronized void flushBuffer()`. identityHashCode, tempo e
    memoria iguais; a corrida some: 0 divergencias em 2100 execucoes onde a baseline deu 7/2220 (0,32 %). Chance de 0/2100 com
    taxa de 0,32 % ~ exp(-6,6) ~ 0,14 %.
  - "sem-flusher" (a thread de 250 ms vira no-op): 0/600 a 48 JVMs.
  - hashCode=2 NAO e o que explica: 0/600 a 48 JVMs com -XX:hashCode=2, mas a baseline a 48 JVMs deu 4/900 (0,44 %): P(0/600)
    ~ 7 % so por sorte; INCONCLUSIVO como prova de qualquer coisa, e as variantes acima ja provam a causa sem mexer no hash.

1.4 Identity hash nos protocolos: grep no Tendermint.java e no HotStuff.java: blockTraffic, consensusTraffic e locallyFinalized sao
    HashMap/HashSet chaveados por bloco (Tendermint.java:105-107, HotStuff.java:207-210), mas SO com get/put/contains/remove: nenhum
    laco itera sobre eles (o unico keySet() e removeIf em Tendermint.java:369, em outro mapa, e remocao nao decide comportamento).
    Nao ha System.currentTimeMillis/nanoTime nos dois. Tempo de parede no caminho de simulacao: AbstractScenario.java:119-149
    (nanoTime so para mensagem de progresso) e DoubleSpendTracker.java:235 (currentTimeMillis num campo timestamp): nenhum
    decide evento. A dependencia de identityHashCode (ADR-0012 RM-08) continua valendo para Parlia/Casper/Nakamoto/MCO2/AMBIFY/
    OFFSET/EARTH (README de 2d); NAO e a causa deste achado.

1.5 Tabela (TENDERMINT n=4 20 s semente 1; HOTSTUFF n=4 30 s semente 1; ARQ n=10 60 s semente 1; cada execucao e 1 JVM com o
    MESMO comando; comparacao com a execucao e000 do mesmo nivel, criterio do projeto: ignora colunas 'hash' e linhas '#').
    Fixture dos flags = a do README de 2d (tm-batch-size=10, hs-batch-size=20), como o pedido mandou; nao e a canonica.

  protocolo / variante          | JVM simultaneas | execucoes | divergentes | tipo
  TENDERMINT baseline           |   1             |   100     |   0         |
  TENDERMINT baseline           |   4             |   100     |   0         |
  TENDERMINT baseline           |  12             |   236     |   0         | (36 + 200)
  TENDERMINT baseline           |  24             |   120     |   1         | ORDEM
  TENDERMINT baseline           |  48             |   900     |   4         | 2 ORDEM + 2 CONTEUDO (300 + 600)
  TENDERMINT baseline           | 120             |  1200     |   2         | ORDEM
  TENDERMINT baseline hashCode=2|  48             |   600     |   0         |
  TENDERMINT var. sincronizado  |  48             |   900     |   0         | (300 + 600)
  TENDERMINT var. sincronizado  | 120             |  1200     |   0         |
  TENDERMINT var. sem-flusher   |  48             |   600     |   0         |
  TENDERMINT var. flusher 1 ms  |  12             |   200     |   0         |
  TENDERMINT var. 1 ms + sincr. |  12             |   200     |   0         |
  HOTSTUFF   baseline           |   1             |    40     |   0         |
  HOTSTUFF   baseline           |   4             |   100     |   0         |
  HOTSTUFF   baseline           |  12             |   200     |   1         | CONTEUDO (192 x 216 linhas; 77 linhas com colunas erradas)
  HOTSTUFF   baseline           |  48             |   600     |   0         |
  ARQ        baseline           |   1             |    40     |   0         | controle
  ARQ        baseline           |   4             |   100     |   0         | controle
  ARQ        baseline           |  12             |   200     |   0         | controle
  ARQ        baseline           |  48             |   600     |   0         | controle
  Total: 8 236 execucoes (TENDERMINT 6 356 + HOTSTUFF 940 + ARQ 940; somados dos itens acima). Os niveis de 24 e 120 JVMs foram
  acrescentados para ganhar poder estatistico (a 12 JVMs a baseline deu 0/236, entao as duas linhas de 12 JVMs com variante
  "flusher 1 ms" NAO informam nada: nao ha divergencia de baseline a 12 para a variante remover; valem so como "nao piorou").

1.6 Leitura honesta
  - No achado original (12 JVMs, 365 x 346): a assinatura bate com CONTEUDO (colunas ilegiveis na 1a rodada: 15 linhas com Time
    ilegivel e 22 quedas de Time no CSV arquivado em /tmp/opencode/pa/nr/s1). A reproducao em 12 JVMs deu 0/236 TENDERMINT e
    1/200 HOTSTUFF: a taxa a 12 e baixa (limite superior de 95 % ~1,3 % para TENDERMINT), e a 24+ JVMs fica em ~0,3-0,4 %.
    "4 JVMs deu IDENTICO" da 1a rodada e compativel com a taxa baixa: 0/100 a 4 JVMs tambem aqui; isso NAO prova ausencia.
  - HotStuff tambem sofre (1 CONTEUDO em 800 execucoes baseline com >= 12 JVMs). ARQ: 0/800 com >= 12 JVMs (0/940 no total),
    coerente com a inferencia de 1.2.
  - Afeta resultado de pesquisa? Afeta qualquer analise que LEIA o CSV por bloco (tendermint-*/hotstuff-*-t<trial>.csv):
    ordem trocada muda o que "primeira linha"/janela sequencial significam; bytes entrelacados corrompem media/contagem. NAO afeta
    o mvp-results.csv nem o mvp-results-cb.csv desta tarefa (ambos saem de memoria; nao testei o -cb sob 48+ JVMs, so que
    atual a x atual b deu IDENTICO nas execucoes de B4). Qualquer bateria de pesquisa que rode muitas JVMs em paralelo
    deve conferir linhas x cabecalho antes de confiar no CSV por bloco, ou rodar com poucas JVMs.
  - Nao testei -XX:hashCode=2 em HOTSTUFF/ARQ nem as variantes em HOTSTUFF/ARQ. Nao testei Java 17.

1.7 NAO CORRIGI (instrucao do pedido). Recomendacao para o orquestrador/humano abrir tarefa propria COM critic (e codigo em
    src/main/java/jabs que gera dado de pesquisa): `synchronized` em AbstractCSVLogger.flushBuffer (uma palavra, linha 171),
    ou ordenar o descarte final. A variante "sincronizado" acima e a evidencia. Decisao: a definir com o humano.
    Consequencia aplicada AGORA, so em scripts/: nao_regressao.py limita o paralelismo a 4 JVMs (ver 4).

1.8 Reproducao
  Scripts (scripts/jabs/): diag_determinismo_carga.py (roda N execucoes com P JVMs, compara com e000, classifica ORDEM/CONTEUDO, conta
  linhas com colunas erradas; modos --comparar A B e --ordem CSV), diag_variante_logger.py (compila a variante fora da arvore),
  arquivar_b1.py (gera b1/AMOSTRAS.txt). Autotestes: --selftest em cada um. Exemplo (a 48 JVMs, o nivel que mais divergiu):
    python3 scripts/jabs/diag_determinismo_carga.py --raiz /tmp/opencode/base-8c68084 --proto TENDERMINT --n 4 --dur 20 --semente 1 \
      --execucoes 600 --paralelo 48 --saida /tmp/opencode/b1/TM-p48-ctrl --extra=--tm-propose-timeout=1.0 --extra=--tm-propose-delta=0.5 \
      --extra=--tm-prevote-timeout=1.0 --extra=--tm-prevote-delta=0.5 --extra=--tm-precommit-timeout=1.0 --extra=--tm-precommit-delta=0.5 \
      --extra=--tm-batch-size=10 --extra=--tm-tx-size=512 --extra=--tm-signature-size=64 --extra=--tm-proposer-signature-size=80 \
      --extra=--tm-message-overhead=10
  Variante: `diag_variante_logger.py --raiz /tmp/opencode/base-8c68084 --variante sincronizado --saida /tmp/opencode/b1/var-sinc` e
  `--cp-prefixo /tmp/opencode/b1/var-sinc/classes` no comando acima. hashCode=2: `--jvm-flag=-XX:+UnlockExperimentalVMOptions
  --jvm-flag=-XX:hashCode=2`. HOTSTUFF: as 11 flags --hs-* (README de 2d), --dur 30. ARQ: sem --extra, --n 10 --dur 60.
  LIMITE: a saida de console de cada comando nao foi gravada em arquivo (so as linhas RESUMO transcritas acima); as pastas de
  execucao ficam em /tmp/opencode/b1 (efemeras). Arquivei as amostras divergentes e suas referencias (b1/AMOSTRAS.txt + CSV, <200 KB).

2. B2: TRAVA DE FIXTURE (decisao D4)
------------------------------------
  Codigo: MVPComparison.fixtureGuard / parseAllowFixtureMismatch / joinLines (src/main/java/jabs/example/MVPComparison.java).
  - HOTSTUFF e TENDERMINT na MESMA execucao com lote (--*-batch-size) ou tx-size (--*-tx-size) diferentes: IllegalArgumentException,
    ANTES de criar a pasta de saida; mensagem em portugues com "a definir com o humano" e a flag --allow-fixture-mismatch.
  - --allow-fixture-mismatch=true segue, imprime "AVISO: fixture-mismatch AUTORIZADO ..." no stdout e escreve uma linha '#' no
    cabecalho de mvp-results.csv E de mvp-results-cb.csv. Valor diferente de true/false (inclusive "TRUE") falha alto.
  - Um so dos dois protocolos em --protocols: nada muda (nenhuma linha, nada alocado); valores iguais: idem.
  - Fixture canonica UNICA: src/test/resources/fixture/fixture-canonica.properties (batch-size=20, tx-size=512; ROTULADA como
    fixture de teste, nao calibracao). Lida pelos testes (jabs.example.FixtureCanonica) e pelos scripts (scripts/jabs/
    fixture_canonica.py; nr_hscb_fase2.py a usa). Por que ali: e onde moram fixtures/evidencias, e texto puro (Java e Python
    leem sem dependencia), e NAO fica em src/main: o codigo de producao continua sem valor padrao de lote/tx-size (a trava so
    COMPARA, nao escolhe).
  - Errata (arquivo novo, README historico intocado): ../2d-hotstuff-equivocation/ERRATA-fixture-lote.txt.
  - Testes: MVPComparisonFixtureTravaTest, 12 testes (a trava dispara por lote e por tx-size; override passa e deixa rastro no
    stdout e nos dois CSV, antes do cabecalho de colunas; so um protocolo nao e afetado; valores iguais nao deixam rastro;
    flag invalida falha; a flag e conhecida).
  - Mutacoes (cada uma revertida; grep MUTACAO limpo): (a) `if (!allow)` -> `if (!allow && false)`: 3 testes FALHAM (lote, tx-size,
    execucao completa sem override); (b) o override devolve lista vazia em vez da linha '#': o teste do rastro FALHA
    (mvp-results.csv ==> expected 1 but was 0).

3. B3: COMPARADOR (scripts/jabs/nao_regressao.py)
--------------------------------------------------
  - ARQUIVOS_ADITIVOS = ("mvp-results-cb.csv",): lista EXPLICITA; so o nome exato e so na raiz da pasta de saida (nao regex, nao
    subpasta, nao sufixo parecido). O arquivo sai da comparacao da LISTA de arquivos; o resto (cabecalho/valores de mvp-results.csv e
    dos CSV por bloco) continua comparado.
  - Aditivo atual a x atual b (sem base): comparar_aditivo; exige que exista para HOTSTUFF/TENDERMINT e proibe nos demais; DIVERGENTE
    muda o codigo de saida (1). Impresso como "aditivo mvp-results-cb.csv atual a x b (sem base): ...".
  - Selftest (sem JVM): aditivo ignorado; arquivo desconhecido extra diverge (outro.csv, mvp-results-cb2.csv, x-mvp-results-cb.csv e
    mvp-results-cb.csv em subpasta); coluna alterada em mvp-results.csv diverge mesmo com o aditivo; baseline que ja tivesse o
    aditivo continua IDENTICO; aditivo: igual / valor diferente / so um lado / ausente esperado / presente nao esperado / '#' ignorado.
  - Mutacoes (revertidas): (a) ARQUIVOS_ADITIVOS vazio: selftest FALHA ("arquivos diferem"); (b) filtro amplo `endswith("-cb.csv")`:
    selftest FALHA em x-mvp-results-cb.csv (IDENTICO, deveria divergir).
  - --paralelo (padrao e MAXIMO 4): ver 4.
  - arquivar-nao-regressao.py: importa ARQUIVOS_ADITIVOS do comparador (fonte unica) e tabula o aditivo como "SEM_BASE".

4. B4: NAO REGRESSAO COMPLETA (sementes 1 e 2)
----------------------------------------------
  PARALELISMO: no maximo 4 JVMs simultaneas (nao_regressao.py --paralelo, padrao 4, recusa > 4). Motivo: B1. Com 4 nao houve
  divergencia em 100 execucoes por protocolo; isso NAO e prova de ausencia (ver 1.6), so a escolha mais prudente que o dado permite.
  Comandos (cwd = raiz do repositorio; antes: mvn -q -o compile). O orquestrador nr_hscb_fase2.py imprime cada comando exato com
  "$ " e grava a saida em <raiz>/saida-s<S>-<grupo>.txt:
    python3 scripts/jabs/nr_hscb_fase2.py --base /tmp/opencode/base-8c68084 --atual . --raiz /tmp/opencode/nr-hscb/final --semente 1
    python3 scripts/jabs/nr_hscb_fase2.py --base /tmp/opencode/base-8c68084 --atual . --raiz /tmp/opencode/nr-hscb/final --semente 2
    python3 scripts/jabs/arquivar-nao-regressao.py --raiz /tmp/opencode/nr-hscb/final --destino src/test/resources/nao-regressao/hs-cb-fase2 \
        --base-commit 8c68084 --grupos geral,tree,hotstuff,offset-hash2 --sementes s1,s2
  Grupos (o primeiro bloco de cada saida-sS-<grupo>.txt traz a linha de comando completa):
    geral        ARQ:10:60,AMBIFY:10:60,MCO2:50:600,OFFSET:40:20000,EARTH:40:20000,DAGSPER:10:600,TENDERMINT:4:20,TENDERMINT:7:20
                 --baseline-tem TENDERMINT + 11 flags --tm-* (batch 20, tx 512 = canonica); SEM flag de hash (AMBIFY e MCO2 inclusive)
    tree         TREE:50:6000 com -XX:+UnlockExperimentalVMOptions -XX:hashCode=2
    hotstuff     HOTSTUFF:4:30,HOTSTUFF:7:30 SEM ataque, --baseline-tem HOTSTUFF + 11 flags --hs-* (batch 20, tx 512 = canonica)
    offset-hash2 OFFSET:40:20000 com -XX:hashCode=2
  DIFERENCA para 2d: la TENDERMINT usava batch 10 e HOTSTUFF batch 20 (ver a errata); aqui os dois usam o par canonico. Os valores
  dos DEMAIS flags --tm-*/--hs-* sao os de 2d. EARTH/OFFSET/AMBIFY/MCO2/DAGSPER/ARQ: parametros de 2d.
  Resultado: TUDO IDENTICO, nenhum DIVERGENTE, nenhum INCONCLUSIVO (base a x b, atual a x b, base a x atual a) em todos os casos
  das duas sementes (12 casos por semente: 8 do grupo geral, 1 tree, 2 hotstuff, 1 offset-hash2; mais o aditivo nos 4 de TM/HS).
  caso (n, duracao s)               | semente | base a x b | atual a x b | base a x atual a | linhas de dados
  ARQ/PBFT (10, 60)                 | 1       | IDENTICO   | IDENTICO    | IDENTICO         | 28
  ARQ/PBFT (10, 60)                 | 2       | IDENTICO   | IDENTICO    | IDENTICO         | 34
  AMBIFY (10, 60)                   | 1 / 2   | IDENTICO   | IDENTICO    | IDENTICO         | 10 / 6
  MCO2 (50, 600)                    | 1 / 2   | IDENTICO   | IDENTICO    | IDENTICO         | 15 / 15
  OFFSET (40, 20000) sem hash       | 1 / 2   | IDENTICO   | IDENTICO    | IDENTICO         | 24 / 16   (semente 2: estavel desta vez; em 2d foi instavel, Limite 5 de 2d)
  OFFSET (40, 20000) hashCode=2     | 1 / 2   | IDENTICO   | IDENTICO    | IDENTICO         | 26 / 17
  EARTH (40, 20000)                 | 1 / 2   | IDENTICO   | IDENTICO    | IDENTICO         | 39 / 35
  DAGSPER (10, 600)                 | 1 / 2   | IDENTICO   | IDENTICO    | IDENTICO         | 8 / 19
  TREE (50, 6000) hashCode=2        | 1 / 2   | IDENTICO   | IDENTICO    | IDENTICO         | 3515 / 3571
  TENDERMINT (4, 20; fixture)       | 1 / 2   | IDENTICO   | IDENTICO    | IDENTICO         | 339 / 339
  TENDERMINT (7, 20; fixture)       | 1 / 2   | IDENTICO   | IDENTICO    | IDENTICO         | 319 / 319
  HOTSTUFF (4, 30; fixture, sem ataque) | 1 / 2 | IDENTICO | IDENTICO    | IDENTICO         | 193 / 193
  HOTSTUFF (7, 30; fixture, sem ataque) | 1 / 2 | IDENTICO | IDENTICO    | IDENTICO         | 186 / 185
  aditivo mvp-results-cb.csv, atual a x b (sem base): IDENTICO nos 4 casos TENDERMINT/HOTSTUFF de cada semente (8 no total, 1 linha
  de dados cada, pois o arquivo tem uma linha por (protocolo, n)); nos demais protocolos o arquivo nao existe e o script nao o imprime.
  TABELA.txt: 58 CSV. Byte a byte (inclui coluna de hash e linha '#') base-a e atual-a ficam IGUAL nos legados e DIFEREM em
  TENDERMINT/HOTSTUFF: o MOTIVO e conhecido e esperado: (i) a coluna BlockHashCode (hash de objeto, ignorada pelo criterio do
  projeto) e (ii) a linha '#' do EnhancedBlockFinalizationLogger, que a Parte A ampliou ("Cb granularity: ..."). Conferido por
  diag_determinismo_carga.py --comparar (HOTSTUFF n=7 s2: cabecalho igual, 184 x 184 linhas, IDENTICO fora de hash e '#').
  Isto e diferente de 2d (la todos os 77 deram IGUAL): a diferenca nasce da mudanca aditiva INTENCIONAL, nao de regressao.
  Nao copiados (> 200 KB, so sha256 + linhas na TABELA): TREE s1 (260269 B) e TREE s2 (267138 B).
  Quatro casos a mais que o pedido literal NAO foram rodados; o pedido cita ARQ, TENDERMINT, HOTSTUFF sem ataque, AMBIFY, MCO2,
  OFFSET, EARTH, DAGSPER, TREE: todos cobertos. HOTSTUFF com ataque NAO tem baseline (8c68084 nao o implementa em MVPComparison com
  esse comportamento; a baseline recusa) e NAO foi reexecutado aqui.

5. B5: TESTES E LINT
--------------------
  mvn -q -o test : rc=0; 64 classes, 675 testes, 0 falhas, 0 erros, 0 ignorados (contagem dos relatorios surefire).
  lint-jabs.py src/main/java : 255 arquivos, 14 bloqueantes, 41 avisos (= os totais de 8c68084: 14 e 41; os 2 arquivos a mais sao
  CbRedeMedida e NetworkHeightLedger, sem achado). Os 9 JB-009 que o lint mostra em EnhancedBlockFinalizationLogger.java (linhas
  227-312) sao catch vazios ANTIGOS; a Parte A so acrescentou a linha 143. NAO consegui provar por `git diff 8c68084` (o ambiente
  recusou git diff/show com argumento), entao a afirmacao se apoia nos totais iguais e na leitura do arquivo: a decidir pelo critic.
  Autotestes: nao_regressao.py, nr_hscb_fase2.py, diag_determinismo_carga.py, arquivar_b1.py, checar-determinismo.py: SELFTEST OK.

6. LIMITES DECLARADOS
---------------------
  1. Java 25 em tudo; pom mira 17; nao testei em 17.
  2. 2 sementes, 1 trial por caso; n=4 e 7 (e os n de 2d para os legados). Nao cobre outros n, --n-semantics=nodes, --enable-tx=false,
     --load-record, ByzantineSweep, HybridArchitectureValidation, nem HOTSTUFF sob ataque.
  3. B1 mede UMA maquina (28 CPUs). A taxa de divergencia depende da maquina e da carga; os numeros nao se transferem.
     A causa esta provada para TENDERMINT n=4; para HOTSTUFF e coerente (1 CONTEUDO) mas as variantes nao foram rodadas la.
  4. Com <= 4 JVMs a taxa e baixa mas NAO e zero provada; um DIVERGENTE esporadico em base a x b ou atual a x b nao deve ser
     interpretado como regressao sem olhar o tipo (ORDEM/CONTEUDO) e repetir com menos carga. Nenhum aconteceu em B4.
  5. A baseline binaria fica em /tmp/opencode (efemera); o repositorio guarda saidas, commit e comandos.
  6. Raft (Fabric) nao e BFT: resultado BFT do JABS descreve uma alternativa de ordenacao, nao o Fabric atual.

7. A DEFINIR COM O HUMANO
-------------------------
  - Valores de lote e tx-size da pesquisa (a fixture 20/512 e so de teste). ADR-0010 PI-08/PI-16; ADR-0015 A-24/A-25.
  - Se o AbstractCSVLogger.flushBuffer recebe `synchronized` (ou outra correcao) em tarefa propria com critic (1.7).
  - Se o limite de 4 JVMs e definitivo ou se a correcao do logger o libera.
  - Se a convencao PROVISORIA de PosFinal/SemAltura (ADR-0015 D1) e a ponte para o Fabric seguem como estao (herdado da Parte A).
  - Se o nome/escopo de --allow-fixture-mismatch e aceitavel como unica via de override (decisao D4 deu o exemplo, nao a regra).

8. ARQUIVOS DESTE DIRETORIO
---------------------------
  s1/ e s2/ : <grupo>/<caso>/{base-a,atual-a}/<csv> (o aditivo mvp-results-cb.csv so em atual-a). TABELA.txt, ESTADO-ARVORE.txt.
  saida-sS-<grupo>.txt : saida integral de cada comando (inclui a linha "$ ..." com o comando exato).
  b1/AMOSTRAS.txt + b1/<rotulo>/<exec>/*.csv : amostras divergentes do B1 e as referencias e000, com sha256 e contagem de linhas
  com numero de colunas errado.
