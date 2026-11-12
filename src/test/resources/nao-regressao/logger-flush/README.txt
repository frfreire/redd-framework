LOGGER-csv-flushBuffer-corrida (Loop 2, rodada 1 de 3): corrida de escrita no AbstractCSVLogger
=================================================================================================

Base: b5be364 (arvore limpa no inicio: git status vazio). Baseline binaria: /tmp/opencode/base-8c68084
(BASELINE.txt: commit=8c68084, arvore_limpa=sim). Autorizacao de usar 8c68084: `git log --oneline 8c68084..b5be364 --
src/main/java/jabs/log/AbstractCSVLogger.java` NAO lista nenhum commit, ou seja o arquivo e identico nos dois. (O ambiente
negou `git diff 8c68084 b5be364 -- <arquivo>` e `diff`; usei `git log` sobre o caminho, que prova o mesmo: nenhum commit toca o
arquivo no intervalo.) Eu sou o builder: NAO me autoaprovo; nada commitado. Java 25 (pom mira 17; nao testei em 17). 28 CPUs.
Nao toquei em docs/adr, PBFT/HotStuff/Tendermint/ByzantineConfig, output/, data/, docs/_build/.

1. O que mudou (3 arquivos de codigo + 1 script)
-------------------------------------------------
  src/main/java/jabs/log/AbstractCSVLogger.java     (unico arquivo de producao)
  src/test/java/jabs/log/AbstractCSVLoggerCorridaTest.java   (23 execucoes JUnit: 10 + 10 repetidas + 3 testes)
  src/test/java/jabs/log/MedeFechamentoLogger.java            (medicao de tempo de fechamento; main, nao e teste JUnit)
  scripts/jabs/arquivar_logger_flush.py                       (copia as amostras da baseline para este diretorio; --selftest OK)

  Corrigido em AbstractCSVLogger:
  (a) flushBuffer(int max): poll do lote E escrita E flush do writer agora acontecem numa UNICA secao critica sob um lock
      DEDICADO (private final Object writeLock). A fila e FIFO; com poll e escrita atomicos entre a thread principal
      (lote de 128, bufferRow) e a thread csv-flusher, a ordem do CSV e a ordem de enfileiramento e o CsvWriter (que nao e
      thread-safe) tem um unico escritor por vez.
  (b) stopFlusherAndFlush() deixou de ser `synchronized` (monitor this). Ele nao segura nenhum monitor durante o join, entao o
      flusher nunca bloqueia na entrada de flushBuffer esperando por quem esta no join (a armadilha do pedido). Ele drena TUDO
      o que sobrou (flushBuffer(Integer.MAX_VALUE)), nao so um lote de 128: antes, com mais de 128 linhas pendentes, o
      fechamento so escrevia um lote se o join expirasse por timeout.
  (c) initialLog e as linhas por no de finalLog tambem escrevem sob writeLock (o CsvWriter e unico; isso fecha a mesma classe
      de corrida contra o flusher, que ja esta rodando desde o construtor).
  (d) fail-loud: o `catch (Exception ignored) {}` do flush foi removido. A PRIMEIRA falha de writeRow (UncheckedIOException) ou
      de flush (IOException) e guardada (AtomicReference), uma linha e impressa em stderr, e finalLog() fecha o writer e
      lanca IOException("falha ao escrever o CSV do logger ...", causa=primeira falha). Falhas seguintes ficam suprimidas.
  (e) construtor package-private AbstractCSVLogger(Writer, long intervaloMs) so para o teste poder forcar a competicao
      (intervalo do flusher configuravel). Os construtores publicos (Writer) e (Path) continuam com 250 ms; o de Path agora
      delega para o de pacote (openWriter(path) faz o mkdirs e abre o BufferedWriter, igual ao antes).

  MUDANCA DE COMPORTAMENTO RELATADA (a decidir com o humano, item 8): antes, uma falha de flush era ignorada e o
  CSV podia sair truncado sem aviso; agora o cenario termina com IOException em finalLog (AbstractScenario.run ja propaga
  IOException de finalLog; nenhuma assinatura mudou). Na saida existente (sem falha) nada muda: nao_regressao IDENTICO (item 3).
  Uma falha de writeRow descarta a linha que falhou (ela ja foi retirada da fila) mas a falha e relancada no fechamento.

2. Teste de regressao da corrida: FALHA sem a correcao, PASSA com ela
----------------------------------------------------------------------
  Comando: mvn -o test -Dtest=AbstractCSVLoggerCorridaTest
  Nao depende de sorte: o Writer de destino e instrumentado (WriterInstrumentado). Cada write/flush espera ~2-20 us ativos e
  conta quantas threads estao DENTRO do writer ao mesmo tempo (max); sobreposicao de duas threads (defeito) e detectada
  diretamente. Alem disso confere o arquivo: exatamente N linhas, na ordem de enfileiramento, "i,carga-i" completas (sem
  truncamento/entrelacamento), comentario + cabecalho + fim de linha final.
  Testes: escritaConcorrenteNaoSobrepoeNemTrocaOrdem (@RepeatedTest 10; N=3000, flusher a 1 ms, lote de 128),
          ordemDeEnfileiramentoSobFlusherContinuo (@RepeatedTest 10 x 15 rodadas; flusher a 0 ms, cobre a ORDEM),
          fechamentoNaoPerdeLinhasComIntervaloPadraoEQuantidadeNaoMultiplaDoLote (1000 = 7x128+104),
          fechamentoNaoEsperaTimeoutDoJoinDoFlusher (finalLog < 600 ms com linhas pendentes e flusher dormindo),
          falhaDeFlushNaoEEngolidaEEhRelancadaNoFechamento (IOException com a causa original; writer fechado).

  ANTES da correcao (so o construtor de pacote, flushBuffer original): 10 FALHAS em 13 execucoes da versao inicial do teste
    - sobreposicao: 8 de 10 repetições com `expected: <1> but was: <2>`;
    - fechamento: 1010 linhas em vez de 1003 (bytes/linhas a mais: o CsvWriter foi usado por duas threads);
    - falha de flush: "Expected IOException to be thrown, but nothing was thrown" (engolida).
  DEPOIS: 23 de 23 verdes; 10 invocacoes consecutivas de `mvn -o test -Dtest=AbstractCSVLoggerCorridaTest` = 230 execucoes,
  0 falhas.

  Mutacoes (cada uma revertida; `grep -r MUTACAO- src` limpo):
  (A) lock descartavel `synchronized (new Object())` em flushBuffer (= reverter a correcao): 10 de 10 invocacoes de mvn
      falharam (2 a 3 testes cada); a repeticao de sobreposicao falha 10/10 na 1a invocacao.
  (B) poll do lote FORA do lock, so a escrita dentro (impede a sobreposicao mas pode inverter a ordem): a 1a versao do teste
      so pegou 1 a 2 de 10 repeticoes (deteccao probabilistica); por isso criei ordemDeEnfileiramentoSobFlusherContinuo, que com
      a mutacao B falha 10 de 10 (ex.: "expected <0,carga-0> but was <1,carga-1>").
  (C) a armadilha: stopFlusherAndFlush `synchronized` (this) + flushBuffer sob `synchronized (this)`: fechamentoNaoEspera
      TimeoutDoJoinDoFlusher FALHA ("finalLog demorou 1000 ms"). Com writeLock, passa.

3. Nao regressao (criterio 7) - serial, max 4 JVMs, sementes 1 e 2
-------------------------------------------------------------------
  Comandos (cwd = raiz; antes `mvn -q -o compile`):
    python3 scripts/jabs/nr_hscb_fase2.py --base /tmp/opencode/base-8c68084 --atual . --raiz /tmp/opencode/nr-logger/final --semente 1
    python3 scripts/jabs/nr_hscb_fase2.py --base /tmp/opencode/base-8c68084 --atual . --raiz /tmp/opencode/nr-logger/final --semente 2
  (cada um imprime o `$ python3 scripts/jabs/nao_regressao.py ...` completo, com as 11 flags --tm-*/--hs-* de fixture, SEM flag de
  hash para ARQ/AMBIFY/MCO2/TENDERMINT/HOTSTUFF/EARTH/DAGSPER, e -XX:hashCode=2 para TREE e para o 2o OFFSET)
  Resultado: rc=0 nos 4 grupos das 2 sementes; em TODOS os 12 casos por semente "baseline a x b", "atual a x b" e
  "baseline a x atual a" = IDENTICO; aditivo mvp-results-cb.csv atual a x b IDENTICO. Linhas de dados (semente 1 / 2):
    ARQ 28/34, AMBIFY 10/6, MCO2 15/15, OFFSET 24/16, EARTH 39/35, DAGSPER 8/19, TENDERMINT n=4 339/339, n=7 319/319,
    TREE(hashCode=2) 3515/3571, HOTSTUFF n=4 193/193, n=7 186/185, OFFSET(hashCode=2) 26/17.
  (Mesmos numeros de linhas da fase anterior em hs-cb-fase2/README.txt 4.) A saida completa dos comandos nao foi gravada em
  arquivo (o ambiente nao deixou redirecionar); o que esta acima e a transcricao dos resumos.

4. Diagnostico de carga: TENDERMINT n=4 20 s semente 1, 48 JVMs simultaneas (flags --tm-* como em hs-cb-fase2 1.8, batch 10)
-------------------------------------------------------------------------------------------------------------------------
  Comando (com a correcao; a baseline usa --raiz /tmp/opencode/base-8c68084):
    python3 scripts/jabs/diag_determinismo_carga.py --raiz . --proto TENDERMINT --n 4 --dur 20 --semente 1 --execucoes 2400 \
      --paralelo 48 --saida /tmp/opencode/nr-logger/diag-p48-corr2400 --extra=--tm-propose-timeout=1.0 --extra=--tm-propose-delta=0.5 \
      --extra=--tm-prevote-timeout=1.0 --extra=--tm-prevote-delta=0.5 --extra=--tm-precommit-timeout=1.0 --extra=--tm-precommit-delta=0.5 \
      --extra=--tm-batch-size=10 --extra=--tm-tx-size=512 --extra=--tm-signature-size=64 --extra=--tm-proposer-signature-size=80 \
      --extra=--tm-message-overhead=10
  CORRIGIDO: 600 execucoes -> RESUMO divergentes=0 corrompidos=0 rc_erro=0 (4,5 s medios por JVM);
             2400 execucoes -> divergentes=0 corrompidos=0 rc_erro=0 (6,4 s medios por JVM). Total 3000, 0 divergencias.
  BASELINE (8c68084), mesmo comando e carga, 2400 execucoes: divergentes=2 (1 CONTEUDO e350: 346 vs 348 linhas e 2 linhas com
  numero de colunas errado; 1 ORDEM e576: Time desce na linha 217), corrompidos=1, rc_erro=0 (6,2 s medios por JVM).
  Amostras da baseline arquivadas em amostras-baseline/ (e000 referencia, e350, e576; AMOSTRAS.txt com sha256 e colunas erradas;
  149 KB no total).
  LEITURA HONESTA: a taxa de divergencia da baseline NESTA rodada foi 2/2400 = 0,083 % (em hs-cb-fase2 foi 0,32 a 0,44 %, depende
  da carga do momento). Com 0,083 %, P(0 em 3000) = exp(-2,5) ~ 8 %: o diagnostico de carga SOZINHO nao prova a correcao
  estatisticamente (e coerente com 0, mas tambem teria dado 0 por sorte em ~8 % das vezes). A prova e o teste do item 2 (detecta a
  sobreposicao e a inversao de ordem diretamente, 100 % de deteccao nas mutacoes A e B) e a reproducao do defeito na baseline
  neste mesmo comando.

5. Tempo de fechamento (nao atrasa)
-----------------------------------
  Comando (classes da baseline vs do codigo atual; mesma classe de medicao, so API publica):
    java -cp "/tmp/opencode/base-8c68084/target/classes:target/test-classes:$(cat target/cp.txt)" jabs.log.MedeFechamentoLogger 40
    java -cp "target/classes:target/test-classes:$(cat target/cp.txt)" jabs.log.MedeFechamentoLogger 40
  Tempo de finalLog(), 40 repeticoes cada (mediana / p95 / max, ms):
    cenario                          baseline 8c68084          corrigido
    A: 0 pendentes                   230,288 / 230,517 / 230,583   230,277 / 230,442 / 230,818
    B: 50 pendentes, flusher dorme   480,475 / 480,757 / 481,017   480,480 / 480,753 / 480,788
    C: 1000 enfileiradas             500,312 / 500,616 / 500,630   500,387 / 500,580 / 500,594
  Diferenca < 0,1 ms em todas: o lock nao atrasa o fechamento. OBSERVACAO: o fechamento leva ~230-500 ms nos DOIS (nao e
  efeito da correcao): o join espera o flusher acordar do sleep de 250 ms e drenar. Pre-existente; nao alterei (fora do escopo).

6. Build, testes e lint
-----------------------
  mvn -o test: Tests run: 698, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS (675 anteriores + 23 novos). Inclui
  HashIdentidadePreSimulacaoTest (3 testes, verde).
  python3 scripts/lint/lint-jabs.py src/main/java: 255 arquivos, 13 bloqueantes, 41 avisos. b5be364 tinha 14 bloqueantes e 41
  avisos: nenhum achado novo e UM JB-009 A MENOS (o catch engolido do flush de AbstractCSVLogger).

7. Restricao 2 (sem alterar eventos, sem aleatoriedade, sem hashCode)
---------------------------------------------------------------------
  So AbstractCSVLogger foi tocado; ele roda na thread principal DEPOIS da execucao do evento e so enfileira/escreve linhas. Nao
  ha Random, hashCode nem colecao chaveada por objeto; nao altera a sequencia de eventos (nao_regressao IDENTICO, item 3).

8. LIMITES e A DEFINIR COM O HUMANO
-----------------------------------
  - Java 25; pom mira 17 (nao testei em 17). UMA maquina (28 CPUs); a taxa de divergencia depende da maquina e da carga.
  - O diagnostico de carga tem poder estatistico baixo nesta taxa (item 4); o critic deve pesar o teste instrumentado.
  - Cobertura: nao_regressao com 2 sementes, 1 trial, n=4/7/10/40/50 e duracoes de hs-cb-fase2; nao cobre HOTSTUFF sob ataque,
    ByzantineSweep, --load-record. Testei TENDERMINT sob carga; HOTSTUFF nao foi re-rodado sob 48 JVMs (a causa e o mesmo codigo).
  - Se a falha de flush/escrita deve derrubar o cenario (IOException em finalLog, como esta) ou so avisar: a decidir.
  - Se o limite de 4 JVMs de nao_regressao.py (hs-cb-fase2 1.7) pode ser relaxado: a decidir, depois do critic; NAO alterei o script.
  - Pre-existente e nao tocado: se o flusher for interrompido (InterruptedException), o laco gira sem pausa ate a fila esvaziar.
  - O Loop 2 exige critic (gpt-6-luna) e eventual arbiter antes de aprovar; nao aprovei nada.
