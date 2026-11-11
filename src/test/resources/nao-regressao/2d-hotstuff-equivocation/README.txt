D3a-2a-2d-2-hotstuff-equivocation-mvpcomparison: evidencia de nao regressao (criterio 7), versionada no repositorio
===================================================================================================================

Data da execucao: 2026-11-10 (Java 25 instalado; pom mira 17; ver "Limites"). Padrao: o de ../2c-4-hotstuff-mvpcomparison/.
O que a mudanca tocou (git diff 40ac8ab -- src scripts):
  - MVPComparison: --attack=EQUIVOCATION --byzantine-count=f (so HOTSTUFF), attackForMain/noAttack/resolveHotStuffAttack/
    resolveByzantineCount/attackLines, runProtocol com 11o parametro (ByzantineConfig), linha '#' no mvp-results.csv SO com ataque.
  - ByzantineConfig: SO ADICOES (git diff 40ac8ab nao remove nenhuma linha do arquivo): byzantineStream/BYZANTINE_STREAM_SALT +
    withExactCount, sorteio do HOTSTUFF em RandomnessEngine DEDICADO. O laco legado (construtor por percentual, java.util.Random)
    esta BYTE A BYTE como em 40ac8ab (rodada 2 do loop 2: a rodada 1 o havia extraido para drawByzantineIds; revertido).
  - scripts: checar-determinismo.py, nao_regressao.py (--baseline-tem HOTSTUFF), arquivar-nao-regressao.py (rglob),
    nao_regressao_sweep.py (NOVO, ver secao 4).
Esta evidencia compara a SAIDA dos protocolos existentes (sem ataque e, para o laco de sorteio, COM ataque) antes e depois.

0. ACHADO DURANTE A EXECUCAO (relatado, corrigido, reexecutado; o critic deve revisar a correcao)
--------------------------------------------------------------------------------------------------
Primeira execucao desta evidencia (arvore ANTES da correcao; saida arquivada em saida-s1-ANTES-da-correcao-geral.txt, rc=1):
base-a x atual-a DIVERGENTE em AMBIFY, MCO2, OFFSET e EARTH, nas DUAS sementes. Ficaram IDENTICO: ARQ e DAGSPER (s1 e s2),
TENDERMINT, TREE (hashCode=2) e HOTSTUFF sem ataque (so s1 nessa passagem). Na semente 2 so rodei ARQ/AMBIFY/MCO2/OFFSET/EARTH/
DAGSPER (sem TENDERMINT/TREE/HOTSTUFF); a saida da semente 2 pre-correcao NAO foi arquivada, so a de semente 1
(saida-s1-ANTES-da-correcao-geral.txt). Os pares a x b de cada lado
eram IDENTICO: a mudanca de saida vinha do codigo, nao de ruido. Com `-XX:+UnlockExperimentalVMOptions -XX:hashCode=2` os quatro
voltavam a IDENTICO (conferido), isto e, a saida desses protocolos depende da sequencia de System.identityHashCode (ADR-0012/0013, N1)
e a mudanca deslocava essa sequencia.
Causa localizada por bissecao de variantes compiladas a parte (/tmp/opencode/exp, nada disso entrou na arvore): a chamada de
MVPComparison.main a resolveHotStuffAttack, que avaliava `ByzantineConfig.AttackType.NONE` na thread principal, ANTES da simulacao,
inicializava o enum AttackType ali e consumia identityHashCode; na baseline o enum so era inicializado dentro do cenario. Qualquer
variante que removesse essa avaliacao (e so ela) devolvia a saida da baseline (AMBIFY Tb_avg 0.448325 = base).
Correcao (src/main/java/jabs/example/MVPComparison.java): attackForMain(protocolos, cli) devolve null, SEM tocar o enum, quando
--attack esta ausente ou nenhum protocolo e HOTSTUFF; noAttack(null|NONE) substitui as comparacoes `== AttackType.NONE` nos
consumidores. Com --attack e HOTSTUFF delega a resolveHotStuffAttack (mesma regra de antes). Teste novo:
MVPComparisonHotStuffTest.attackForMainNaoTocaOEnumSemAtaqueEDelegaComHotStuff. Depois da correcao: tudo IDENTICO (secao 4).
IMPORTANTE (honestidade): isto NAO foi um desvio SOB ATAQUE; foi regressao no caminho SEM ataque dos protocolos existentes, fora de
-XX:hashCode=2. A parte "nao sinalizada" do pedido (parar se divergir sob ataque) nao foi acionada, mas a divergencia sem ataque
foi real, e o desenho da evidencia a pegou. Corrigi dentro do escopo (src/) e NAO consumi rodada de critic; fica a decidir pelo
orquestrador se a correcao pede nova revisao (recomendo que sim: e codigo em src/main/java/jabs).

1. BASELINE
-----------
commit base : 40ac8ab54abe1ee55fe5e3281034a4550077525e (rev-parse conferido pelo BASELINE.txt do orquestrador)
binario     : /tmp/opencode/base-40ac8ab/{target/classes,target/cp.txt}; BASELINE.txt = "commit=40ac8ab5...; arvore_limpa=sim;
              fonte=/tmp/opencode/wt-40ac8ab" (worktree ja removido pelo orquestrador; NAO rodei git worktree).
Limite: o binario da baseline fica em /tmp/opencode (efemero); o repositorio guarda as SAIDAS (base-a), o commit e os comandos.

2. ESTADO DA ARVORE ATUAL (ver ESTADO-ARVORE.txt; NADA COMMITADO)
------------------------------------------------------------------
git diff 40ac8ab -- src scripts | sha256sum = be9dc5347fae6fd9c23f1b5add691bb9c00856d156611073c3f8a3874fd1d1c1 (55387 B, 869 linhas)  [RODADA 2; a da rodada 1 era 3e43f9b1...]
nao rastreado em src/scripts fora deste diretorio: scripts/jabs/nao_regressao_sweep.py sha256 ca1d8a8f...cf66f3 (ver ESTADO-ARVORE.txt).
Todas as execucoes da secao 4 (REEXECUTADAS na rodada 2, saidas em /tmp/opencode/nr-2d/final3) foram feitas com a arvore deste hash (mvn -q -o compile antes; a unica edicao depois do compile
final foi no teste e nos scripts de evidencia, todas antes do arquivamento; o hash acima foi calculado NO arquivamento).
As execucoes de DIAGNOSTICO da secao 0 usaram variantes em /tmp/opencode/exp e uma copia mutante em /tmp/opencode/exp/mut: nada
disso esta na arvore.

3. COMANDOS EXATOS (cwd = raiz do repositorio; pre-requisito: mvn -q -o compile; classpath em target/cp.txt)
-----------------------------------------------------------------------------------------------------------
B=/tmp/opencode/base-40ac8ab ; O=/tmp/opencode/nr-2d/final2
TM = as 11 flags de FIXTURE (nao sao valor de pesquisa), cada uma como --extra-flag-protocolo TENDERMINT:<flag>:
  --tm-propose-timeout=1.0 --tm-propose-delta=0.5 --tm-prevote-timeout=1.0 --tm-prevote-delta=0.5 --tm-precommit-timeout=1.0
  --tm-precommit-delta=0.5 --tm-batch-size=10 --tm-tx-size=512 --tm-signature-size=64 --tm-proposer-signature-size=80
  --tm-message-overhead=10
HS = as 11 flags de FIXTURE, cada uma como --extra-flag-protocolo HOTSTUFF:<flag>:
  --hs-view-timeout=10.0 --hs-max-future-view-horizon=1000 --hs-batch-size=20 --hs-tx-size=512 --hs-individual-signature-size=64
  --hs-aggregated-signature-size=96 --hs-aggregated-share-size=96 --hs-message-overhead=10 --hs-signature-mode=INDIVIDUAL
  --hs-leader-policy=ROTATING --hs-voter-encoding=BITMAP
Para S em 1 e 2 (SAIDA em $O/saida-sS-<grupo>.txt, copiada para este diretorio):
G1 geral  : python3 scripts/jabs/nao_regressao.py --base $B --atual . --semente S --timeout 900 --saida $O/sS/geral \
              --casos ARQ:10:60,AMBIFY:10:60,MCO2:50:600,OFFSET:40:20000,EARTH:40:20000,DAGSPER:10:600,TENDERMINT:4:20,TENDERMINT:7:20 \
              --baseline-tem TENDERMINT <TM>
G2 tree   : python3 scripts/jabs/nao_regressao.py --base $B --atual . --semente S --timeout 900 --saida $O/sS/tree --casos TREE:50:6000 \
              --jvm-flag=-XX:+UnlockExperimentalVMOptions --jvm-flag=-XX:hashCode=2
G3 hotstuff (SEM ataque; a baseline 40ac8ab tem `case HOTSTUFF`):
            python3 scripts/jabs/nao_regressao.py --base $B --atual . --semente S --timeout 900 --saida $O/sS/hotstuff \
              --casos HOTSTUFF:4:30,HOTSTUFF:7:30 --baseline-tem HOTSTUFF <HS>
G4 offset-hash2 (OFFSET com hash de identidade fixo; ver Limite 5):
            python3 scripts/jabs/nao_regressao.py --base $B --atual . --semente S --timeout 900 --saida $O/sS/offset-hash2 \
              --casos OFFSET:40:20000 --jvm-flag=-XX:+UnlockExperimentalVMOptions --jvm-flag=-XX:hashCode=2
G5 sweep-voting (SOB ATAQUE, laco de sorteio de ByzantineConfig por percentual; ver secao 4):
            python3 scripts/jabs/nao_regressao_sweep.py --base $B --atual . --saida $O/sS/sweep-voting-novo --tipo VOTING \
              --validators 10 --max-byz 3 --trials 2 --duration 60 --attack EQUIVOCATION --semente S
G6 sweep-posa (s1 apenas; Parlia, 25 nos):
            python3 scripts/jabs/nao_regressao_sweep.py --base $B --atual . --saida $O/s1/sweep-posa-novo --tipo POSA \
              --validators 25 --max-byz 3 --trials 2 --duration 300 --attack EQUIVOCATION --semente 1 \
              --jvm-flag=-XX:+UnlockExperimentalVMOptions --jvm-flag=-XX:hashCode=2
G7 mutacao do comparador de sweep (evidencia de que ele DETECTA; atual = copia em /tmp/opencode/exp/mut com
   `new Random(seed + 1)` no laco do construtor por percentual de ByzantineConfig; mutacao da rodada 1, ver nota): igual a G5 com --atual /tmp/opencode/exp/mut --semente 1.
G8 HOTSTUFF SOB ATAQUE (atual x atual, nao ha baseline com ataque): checar-determinismo.py, saidas neste diretorio:
   python3 scripts/jabs/checar-determinismo.py --jabs . --protocolo HOTSTUFF --validadores 4 --duracao 30 --semente 1 \
       <as 11 --extra-flag=--hs-*=valor acima> --extra-flag=--attack=EQUIVOCATION --extra-flag=--byzantine-count=1
   (n=7, f=2, semente 2: igual, com --validadores 7 --semente 2 --extra-flag=--byzantine-count=2)
Cada caso do nao_regressao.py roda 4 JVM: base-a, base-b, atual-a, atual-b (trial 1, semente do MVPComparison). O sweep roda
4 execucoes sequenciais do ByzantineSweep (--trials 2 dentro de cada).

4. RESULTADO (criterio do nao_regressao.py: ignora colunas cujo nome contem "hash" e linhas '#')
"linhas de dados" = numero que o script imprime = soma das linhas de dados (sem cabecalho, sem '#') de todos os CSV do caso.
No sweep sao TODOS os CSV da pasta (s.csv agregado + tmp/<TIPO>/*.csv por trial).
-----------------------------------------------------------------------------------------------------------------------
caso (n, duracao s)               | semente | base a x b | atual a x b | base a x atual a | linhas de dados
ARQ/PBFT        (10, 60)          | 1       | IDENTICO   | IDENTICO    | IDENTICO         | 28
ARQ/PBFT        (10, 60)          | 2       | IDENTICO   | IDENTICO    | IDENTICO         | 34
AMBIFY          (10, 60)          | 1       | IDENTICO   | IDENTICO    | IDENTICO         | 10
AMBIFY          (10, 60)          | 2       | IDENTICO   | IDENTICO    | IDENTICO         | 6
MCO2            (50, 600)         | 1       | IDENTICO   | IDENTICO    | IDENTICO         | 15
MCO2            (50, 600)         | 2       | IDENTICO   | IDENTICO    | IDENTICO         | 15
OFFSET          (40, 20000)       | 1       | IDENTICO   | IDENTICO    | IDENTICO         | 24
OFFSET          (40, 20000)       | 2       | DIVERGENTE*| DIVERGENTE* | IDENTICO         | 16   (*ver Limite 5)
OFFSET hashCode=2 (40, 20000)     | 1       | IDENTICO   | IDENTICO    | IDENTICO         | 26
OFFSET hashCode=2 (40, 20000)     | 2       | IDENTICO   | IDENTICO    | IDENTICO         | 17
EARTH           (40, 20000)       | 1       | IDENTICO   | IDENTICO    | IDENTICO         | 39
EARTH           (40, 20000)       | 2       | IDENTICO   | IDENTICO    | IDENTICO         | 35
DAGSPER         (10, 600)         | 1       | IDENTICO   | IDENTICO    | IDENTICO         | 8
DAGSPER         (10, 600)         | 2       | IDENTICO   | IDENTICO    | IDENTICO         | 19
TENDERMINT (n=4, 20, fixture)     | 1       | IDENTICO   | IDENTICO    | IDENTICO         | 347
TENDERMINT (n=4, 20, fixture)     | 2       | IDENTICO   | IDENTICO    | IDENTICO         | 345
TENDERMINT (n=7, 20, fixture)     | 1       | IDENTICO   | IDENTICO    | IDENTICO         | 328
TENDERMINT (n=7, 20, fixture)     | 2       | IDENTICO   | IDENTICO    | IDENTICO         | 327
TREE (50, 6000; hashCode=2)       | 1       | IDENTICO   | IDENTICO    | IDENTICO         | 3515 (soma dos 2 CSV + mvp-results)
TREE (50, 6000; hashCode=2)       | 2       | IDENTICO   | IDENTICO    | IDENTICO         | 3571
HOTSTUFF  (n=4, 30, fixture, SEM ataque) | 1 | IDENTICO | IDENTICO    | IDENTICO         | 193
HOTSTUFF  (n=7, 30, fixture, SEM ataque) | 1 | IDENTICO | IDENTICO    | IDENTICO         | 186
HOTSTUFF  (n=4, 30, fixture, SEM ataque) | 2 | IDENTICO | IDENTICO    | IDENTICO         | 193
HOTSTUFF  (n=7, 30, fixture, SEM ataque) | 2 | IDENTICO | IDENTICO    | IDENTICO         | 185
SWEEP VOTING EQUIVOCATION (n=10, f=0..3, 2 trials, 60 s) | 1 | IDENTICO | IDENTICO | IDENTICO | 154
SWEEP VOTING EQUIVOCATION (n=10, f=0..3, 2 trials, 60 s) | 2 | IDENTICO | IDENTICO | IDENTICO | 138
SWEEP POSA   EQUIVOCATION (n=25, f=0..3, 2 trials, 300 s; hashCode=2) | 1 | IDENTICO | IDENTICO | IDENTICO | 77
MUTACAO (G7: Random(seed+1) no sorteio), sweep VOTING s1 | base a x b IDENTICO | atual a x b IDENTICO | base x atual DIVERGENTE
   (s.csv linha 2: avgFinalizationTime 1.063669 -> 1.033214; saida-s1-mutacao-sweep-voting.txt, rc=1): o comparador de sweep
   enxerga uma mudanca no sorteio; portanto o IDENTICO do sweep nao e vacuo.
HOTSTUFF SOB ATAQUE (G8; atual x atual, sem baseline): n=4 f=1 semente 1: IDENTICO, 9 linhas; n=7 f=2 semente 2: IDENTICO, 8 linhas
   (saida-s1-determinismo-hotstuff-eq-n4-f1.txt, saida-s2-determinismo-hotstuff-eq-n7-f2.txt).
Alem do criterio do script, TABELA.txt compara base-a e atual-a BYTE A BYTE (sha256, inclusive coluna de hash e linha '#'):
todos os 77 CSV tabelados dao IGUAL. Nenhum caso saiu so com cabecalho ou zeros nesta rodada; o script devolveria SEM_DADOS (saida 2).
Descarte declarado (so cabecalho/zeros; ja descartados em 2c-4 e NAO reexecutados aqui): MCO2/DAGSPER/EARTH/TREE com n=10/60 s.
Testes: mvn -q -o test final (rodada 2): rc=0; 61 classes de teste, 649 testes, 0 falhas, 0 erros. O teste do sorteio foi trocado
(sorteioDeBizantinosDoHotStuffUsaFluxoRandomnessEngineDedicadoEAninhado): compara com a referencia RandomnessEngine(seed ^ sal),
verifica aninhamento f -> f+1 para f=0..n, n em {4,7,10}, sementes 1,2,5004, e fixa 2 conjuntos (seed 1 n=10 f=3 = {4,5,7};
seed 2 n=7 f=2 = {3,4}).
MUTACAO da rodada 2 (cada uma revertida; arquivo final conferido): (a) withExactCount com java.util.Random(seed) -> o teste FALHA
(seed=1 n=4 f=3: esperado [0,2,3], obtido [0,1,2]); (b) fluxo semeado por seed+count (quebra o aninhamento) -> FALHA
(seed=1 n=4 f=1: esperado [2], obtido [3]).
Autotestes sem JVM: nao_regressao.py --selftest OK; checar-determinismo.py --selftest OK; nao_regressao_sweep.py --selftest OK.
lint-jabs.py src/main/java: 253 arquivos, 14 bloqueantes, 41 avisos, 5 JB-003. Baseline 40ac8ab: as mesmas 5 linhas JB-003 (grep do
padrao do JB-003 em `git show 40ac8ab:...`: HybridNetworkScenario x2, BitcoinGlobalNetworkScenario, CasperEthereumNetworkScenario,
ByzantineConfig:118). Hoje o de ByzantineConfig esta na linha 120 (so por causa do `import` novo): NENHUM JB-003 novo. Nao rodei o
lint-jabs.py INTEIRO na 40ac8ab (git archive/extracao em /tmp foi barrado pela politica do ambiente); a comparacao e por grep do
mesmo regex do lint e pelos totais (253/14/41) iguais ao 2c-4.

5. ARQUIVOS DESTE DIRETORIO
---------------------------
s1/ e s2/ : <grupo>/<caso>/{base-a,atual-a}/<csv> (grupos: geral, tree, hotstuff, offset-hash2, sweep-voting-novo, sweep-posa-novo
            [s1 apenas]); no sweep os CSV por trial ficam em tmp/<TIPO>/. Copia byte a byte.
TABELA.txt, ESTADO-ARVORE.txt : como em 2c-4.
saida-sS-<grupo>.txt : saida integral de cada comando. saida-s1-ANTES-da-correcao-geral.txt = a execucao com a arvore ANTES de
            attackForMain (rc=1, DIVERGENTE): guardada de proposito, nao e evidencia de aprovacao.
            saida-s1-mutacao-sweep-voting.txt (G7) e saida-s?-determinismo-hotstuff-eq-*.txt (G8).
NAO COPIADOS por tamanho (> 200 KB; so sha256 + linhas em TABELA.txt):
  s1/tree/TREE-50v-6000s/tree-50v-t0-basic-log.csv 260269 B, 3446 linhas; s2/tree/... 267138 B, 3502 linhas.
CSV "b" e *.log nao foram copiados (provam so a x b, ja resumido acima).
Observacao: saida-s1-sweep-voting.txt, saida-s2-sweep-voting.txt e saida-s1-sweep-posa.txt mostram o nome de pasta
"sweep-voting-novo"/"sweep-posa-novo" porque o comparador foi renomeado para o padrao do arquivador e os sweeps reexecutados
(mesmos parametros e mesmos resultados da primeira passagem, que tinha dado IDENTICO com 154/138/77 linhas).

6. LIMITES DECLARADOS (leia antes de citar como prova)
-----------------------------------------------------
1. Java 25 executou tudo (o pom mira 17; nao testei em 17). O jar/classe foi compilado por mvn no JDK 25 com --release do pom.
2. Cobertura estreita: 2 sementes, 1 trial por caso (2 por f no sweep), 1 ou 2 valores de n por protocolo. Nao cobre outros n,
   outras sementes, --n-semantics=nodes, --durations, --enable-tx=false, --load-record, ByzantineSweep POS/POW/DAGSPER
   (que constroem o ByzantineConfig por ids explicitos, caminho que a extracao nao altera) nem HybridArchitectureValidation.
3. O sweep cobre VOTING (ArqReddVotingScenario/HybridNetworkScenario) e POSA (Parlia) com EQUIVOCATION: os dois usam o construtor por
   PERCENTUAL de ByzantineConfig, que e quem chama o laco extraido. O MVPComparison nunca chega a esse construtor para protocolos
   existentes. nao_regressao_sweep.py e comparador MINIMO (sequencial, 2 trials, f ate 3), complementar ao nao_regressao.py.
4. HOTSTUFF com ataque nao tem baseline (40ac8ab recusa --attack=EQUIVOCATION no HotStuff: "ainda nao implementado"): so ha
   determinismo atual x atual (G8) e os testes JUnit; NAO ha prova de corretude do ataque aqui. V-A/V-B/V-C do HotStuff (portao
   antes de qualquer bateria) CONTINUAM PENDENTES: V-ABC-hotstuff nao foi executado. Nenhum numero deste diretorio e resultado de
   pesquisa; os parametros hs-*/tm-* sao FIXTURE.
5. OFFSET semente 2 sem -XX:hashCode=2 NAO e deterministico nem na baseline (base a x b DIVERGENTE, tambem atual a x b). A
   comparacao base x atual saiu IDENTICO nessa passagem, mas e coincidencia de uma execucao instavel e NAO conta como prova. A prova
   para OFFSET e a linha "OFFSET hashCode=2" (IDENTICO nas duas sementes). Em 2c-4 o mesmo caso saiu estavel; a instabilidade nao
   foi investigada aqui (OFFSET e sensivel a identityHashCode, ADR-0012/0013).
6. A correcao de attackForMain conserva o consumo de identityHashCode da baseline NESTE conjunto de casos (AMBIFY, MCO2, OFFSET s1,
   EARTH, TREE com e sem a flag). Nao provei isso para toda combinacao de flags: um uso de --attack COM HOTSTUFF toca o enum
   (inevitavel) e por isso o HotStuff sob ataque nao e comparavel a nenhuma baseline.
7. Partição bizantina PROVISORIA (ADR-0015 Q28): quem e bizantino, a abrangencia (todos os nos x so validadores, B-37/Q30), a regra de
   fracao -> contagem (B-33/Q34) e a coordenacao dos bizantinos seguem "a definir com o humano". --byzantine-count e inteiro >= 1,
   sem padrao. O sorteio do HOTSTUFF e RandomnessEngine(seed do trial ^ BYZANTINE_STREAM_SALT).nextInt(n) em [0,n), n = validadores:
   fluxo DEDICADO, nao o do engine do cenario, e NAO o mesmo conjunto que o laco do ARQ para a mesma semente (decisao da rodada 2).
8. Raft (Fabric) nao e BFT: resultado BFT do JABS descreve uma alternativa de ordenacao, nao o Fabric atual.
9. A baseline binaria continua em /tmp/opencode/base-40ac8ab (efemera). Nada commitado. Nao alterei output/, data/, docs/_build/
   nem artefato de pesquisa publicado; nao ha ADR nova.
10. Eu sou o builder: nao me autoaprovo. Os achados da secao 0 e a decisao de reverter ou manter attackForMain sao do critic/orquestrador.
