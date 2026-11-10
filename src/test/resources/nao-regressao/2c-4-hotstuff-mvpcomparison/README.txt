D3a-2a-2c-4-hotstuff-mvpcomparison: evidencia de nao regressao (criterio 7), versionada no repositorio
=========================================================================================================

Data da execucao: 2026-11-10 (Java 25 instalado; pom mira 17; ver "Limites").
Motivo: o critic (rodada 2) apontou que a saida dos protocolos EXISTENTES nao foi comparada de forma demonstravel com a
baseline, porque a evidencia vivia so em /tmp/opencode (efemera; mesmo "Limite 1" do b1/README.txt). Este diretorio guarda
a evidencia no repositorio e o teste MVPComparisonNaoRegressaoTest a reforca a cada `mvn test`.

O que a mudanca tocou nos protocolos existentes (git diff 45eb3e7 -- src/main/java/jabs/example/MVPComparison.java):
  - normalizeProtocol(): trim() + toUpperCase(Locale.ROOT) passou a ser a unica regra de nome, usada em parseStringList,
    parseProtocolDurations, no switch de runProtocol e em describeSizing (antes: toUpperCase() sem Locale; trim so em parte).
  - runProtocol: 9a/10a parametros novos (hotStuffConfig); "case HOTSTUFF" novo; a falha de trial segue engolida para todo
    protocolo que nao seja HOTSTUFF (rethrowIfHotStuff).
  Esta evidencia compara, byte a byte (e pelo criterio do nao_regressao.py), a SAIDA dos protocolos existentes antes e depois.

1. BASELINE
-----------
commit base : 45eb3e730fd8ae6630ac01f3fc6e5e2c624af4fc  (git rev-parse 45eb3e7 conferido hoje; "HotStuff: troca de visao
              por timeout e defesa de retencao (D3a-2a-2c-3)")
binario     : /tmp/opencode/base-45eb3e7/{target/classes,target/cp.txt}; BASELINE.txt = "commit=45eb3e730fd8ae6630ac01f3fc6e5e2c624af4fc /
              arvore_limpa=sim". Conferido hoje: o MVPComparison.class da baseline NAO contem a cadeia "HOTSTUFF" (0 ocorrencias)
              e a atual contem (6) => a baseline e anterior ao HOTSTUFF; contem "tm-propose-timeout" (tem `case TENDERMINT`).
              Nao recongelei: estava consistente. (Recongelar exigiria worktree limpo de 45eb3e7 + scripts/jabs/congelar-baseline.py.)
Limite: a baseline congelada continua em /tmp/opencode (binario, 'target/classes'); o que fica no repositorio sao as SAIDAS dela
(base-a de cada caso, aqui) e o commit. Reconstruir: git worktree add /tmp/opencode/wt-45eb3e7 45eb3e7; no worktree
`mvn -q -o clean compile && mvn -q -o dependency:build-classpath -Dmdep.outputFile=target/cp.txt`; python3 scripts/jabs/congelar-baseline.py
--jabs /tmp/opencode/wt-45eb3e7 --commit 45eb3e7 --destino /tmp/opencode/base-45eb3e7.

2. ESTADO DA ARVORE ATUAL (ver ESTADO-ARVORE.txt; nada commitado)
-----------------------------------------------------------------
git diff 45eb3e7 -- src scripts | sha256sum = e7c3ec8f4725eae24008cd2cf2710cfb9ecd1b55f70336546aed37d3f84b955e (34726 B, 594 linhas)
rastreados modificados: scripts/jabs/checar-determinismo.py, scripts/jabs/nao_regressao.py, src/main/java/jabs/example/MVPComparison.java
nao rastreados (sha256):
  15b914a9674ced5e19ea60ed972f6ebf5b56ac0ad4515eba274166b5af882dd8  scripts/jabs/arquivar-nao-regressao.py
  67220625d62e08285b1ddb524523e704ebc7b77a0ad6b03a9e541ce766273187  scripts/jabs/congelar-baseline.py
  943ae3b04f659ff4cca8fcd74599f5971f8a41ca90cdda9c0392521b3f2925c3  src/test/java/jabs/example/MVPComparisonHotStuffTest.java
  a88e4e9a3fcb913ecc2fcf1fb0dfdebd337367c3ec283be29b8d315021bb724c  src/test/java/jabs/example/MVPComparisonNaoRegressaoTest.java
  (+ este diretorio src/test/resources/nao-regressao/2c-4-hotstuff-mvpcomparison/, cujos arquivos tem sha256 em TABELA.txt)
`git status --short` nao lista nenhum outro arquivo em src/main: so MVPComparison.java mudou no codigo de producao.
As execucoes abaixo usaram a arvore com esse hash (mvn -q -o compile em 2026-11-10 antes das execucoes). Depois delas editei so
o teste novo (fora do hash do diff: nao rastreado) e fiz uma mutacao em MVPComparison.java que foi REVERTIDA; o hash do diff
foi recalculado depois da reversao e e o mesmo (e7c3ec8f...).

3. COMANDOS EXATOS (cwd = raiz do repositorio; pre-requisito: mvn -q -o compile e
   mvn -q -o dependency:build-classpath -Dmdep.outputFile=target/cp.txt)
-----------------------------------------------------------------------------------------------------------------------
Flags TM (FIXTURE DE TESTE, nao e valor de pesquisa), repetidas como --extra-flag-protocolo TENDERMINT:<flag> em cada comando:
  --tm-propose-timeout=1.0 --tm-propose-delta=0.5 --tm-prevote-timeout=1.0 --tm-prevote-delta=0.5 --tm-precommit-timeout=1.0
  --tm-precommit-delta=0.5 --tm-batch-size=10 --tm-tx-size=512 --tm-signature-size=64 --tm-proposer-signature-size=80
  --tm-message-overhead=10

(G1, semente 1, grupo "geral": ARQ/PBFT, AMBIFY, MCO2, OFFSET, EARTH, DAGSPER, TENDERMINT n=4 e n=7; 32 execucoes)
python3 scripts/jabs/nao_regressao.py --base /tmp/opencode/base-45eb3e7 --atual . --semente 1 --timeout 900 \
  --saida /tmp/opencode/nr-final/s1/geral \
  --casos ARQ:10:60,AMBIFY:10:60,MCO2:50:600,OFFSET:40:20000,EARTH:40:20000,DAGSPER:10:600,TENDERMINT:4:20,TENDERMINT:7:20 \
  --baseline-tem TENDERMINT \
  --extra-flag-protocolo TENDERMINT:--tm-propose-timeout=1.0 --extra-flag-protocolo TENDERMINT:--tm-propose-delta=0.5 \
  --extra-flag-protocolo TENDERMINT:--tm-prevote-timeout=1.0 --extra-flag-protocolo TENDERMINT:--tm-prevote-delta=0.5 \
  --extra-flag-protocolo TENDERMINT:--tm-precommit-timeout=1.0 --extra-flag-protocolo TENDERMINT:--tm-precommit-delta=0.5 \
  --extra-flag-protocolo TENDERMINT:--tm-batch-size=10 --extra-flag-protocolo TENDERMINT:--tm-tx-size=512 \
  --extra-flag-protocolo TENDERMINT:--tm-signature-size=64 --extra-flag-protocolo TENDERMINT:--tm-proposer-signature-size=80 \
  --extra-flag-protocolo TENDERMINT:--tm-message-overhead=10
(G2, semente 1, TREE com hash de identidade fixo)
python3 scripts/jabs/nao_regressao.py --base /tmp/opencode/base-45eb3e7 --atual . --semente 1 --timeout 900 \
  --saida /tmp/opencode/nr-final/s1/tree --casos TREE:50:6000 \
  --jvm-flag=-XX:+UnlockExperimentalVMOptions --jvm-flag=-XX:hashCode=2
(G3 e G4: identicos a G1 e G2 trocando --semente 1 por --semente 2 e s1 por s2 em --saida)
(G5, semente 1, casos que alimentam o teste JUnit; ARQ n=7 e TENDERMINT n=4 5 s)
python3 scripts/jabs/nao_regressao.py --base /tmp/opencode/base-45eb3e7 --atual . --semente 1 --timeout 300 \
  --saida /tmp/opencode/nr-final/s1/teste --casos ARQ:7:60,TENDERMINT:4:5 --baseline-tem TENDERMINT \
  <as mesmas 11 --extra-flag-protocolo TENDERMINT:--tm-*, como em G1>
Cada comando foi redirecionado para /tmp/opencode/nr-final/saida-<semente>-<grupo>.txt (copiados aqui: saida-s1-geral.txt,
saida-s1-tree.txt, saida-s1-teste.txt, saida-s2-geral.txt, saida-s2-tree.txt). Codigo de saida de todos: 0.
Cada caso do nao_regressao.py roda 4 JVM: base-a, base-b, atual-a, atual-b (mesma semente do MVPComparison, trial 1).
Arquivamento: python3 scripts/jabs/arquivar-nao-regressao.py --raiz /tmp/opencode/nr-final \
  --destino src/test/resources/nao-regressao/2c-4-hotstuff-mvpcomparison --base-commit 45eb3e7 --grupos geral,tree,teste --sementes s1,s2
Autoteste do script (sem JVM): python3 scripts/jabs/nao_regressao.py --selftest -> SELFTEST OK.

4. RESULTADO (criterio do nao_regressao.py: ignora colunas cujo nome contem "hash" e linhas '#')
"linhas de dados" abaixo = o numero que o script imprime = SOMA das linhas de dados (sem cabecalho, sem '#') de TODOS os CSV do
caso; o mvp-results.csv contribui com 1. Ex.: ARQ n=10 sem 1: arq-10v-t0.csv tem 29 linhas fisicas - 1 '#' - 1 cabecalho = 27, +1 = 28.
TREE soma tree-50v-t0.csv + tree-50v-t0-basic-log.csv + mvp-results.csv.
------------------------------------------------------------------------------------------------------------------------
caso (n, duracao s)          | semente | base-a x base-b | atual-a x atual-b | base-a x atual-a | linhas de dados
ARQ/PBFT        (10, 60)     | 1       | IDENTICO        | IDENTICO          | IDENTICO         | 28
ARQ/PBFT        (10, 60)     | 2       | IDENTICO        | IDENTICO          | IDENTICO         | 34
AMBIFY          (10, 60)     | 1       | IDENTICO        | IDENTICO          | IDENTICO         | 10
AMBIFY          (10, 60)     | 2       | IDENTICO        | IDENTICO          | IDENTICO         | 6
MCO2            (50, 600)    | 1       | IDENTICO        | IDENTICO          | IDENTICO         | 15
MCO2            (50, 600)    | 2       | IDENTICO        | IDENTICO          | IDENTICO         | 15
OFFSET          (40, 20000)  | 1       | IDENTICO        | IDENTICO          | IDENTICO         | 24
OFFSET          (40, 20000)  | 2       | IDENTICO        | IDENTICO          | IDENTICO         | 16
EARTH           (40, 20000)  | 1       | IDENTICO        | IDENTICO          | IDENTICO         | 39
EARTH           (40, 20000)  | 2       | IDENTICO        | IDENTICO          | IDENTICO         | 35
DAGSPER         (10, 600)    | 1       | IDENTICO        | IDENTICO          | IDENTICO         | 8
DAGSPER         (10, 600)    | 2       | IDENTICO        | IDENTICO          | IDENTICO         | 19
TENDERMINT (n=4, 20, fixture)| 1       | IDENTICO        | IDENTICO          | IDENTICO         | 347
TENDERMINT (n=4, 20, fixture)| 2       | IDENTICO        | IDENTICO          | IDENTICO         | 345
TENDERMINT (n=7, 20, fixture)| 1       | IDENTICO        | IDENTICO          | IDENTICO         | 328
TENDERMINT (n=7, 20, fixture)| 2       | IDENTICO        | IDENTICO          | IDENTICO         | 327
TREE (50, 6000; hashCode=2)  | 1       | IDENTICO        | IDENTICO          | IDENTICO         | 3515 (soma dos 2 CSV)
TREE (50, 6000; hashCode=2)  | 2       | IDENTICO        | IDENTICO          | IDENTICO         | 3571 (soma dos 2 CSV)
ARQ/PBFT        (7, 60)      | 1       | IDENTICO        | IDENTICO          | IDENTICO         | 38  (alimenta o JUnit)
TENDERMINT (n=4, 5, fixture) | 1       | IDENTICO        | IDENTICO          | IDENTICO         | 87  (alimenta o JUnit)
Alem do criterio do script, TABELA.txt compara base-a e atual-a BYTE A BYTE (sha256 do arquivo inteiro, inclusive a coluna de
hash e a linha '#'): em todos os 42 CSV tabelados a coluna "byte_a_byte" e IGUAL. (Isso so e esperado por causa da semente fixa;
o hash de objeto poderia variar entre JVM, e para o TREE so fica estavel com -XX:hashCode=2.)
Nenhum caso saiu com so cabecalho ou zeros nesta rodada. Descarte declarado: MCO2 n=10/60 s (so cabecalho) e DAGSPER n=10/60 s
e EARTH n=40/2000 s e TREE n=10/60 s (so cabecalho) ja eram vazios nas execucoes anteriores (hs4-nr/s1/{casper-parlia,outros}) e
foram trocados por MCO2 n=50/600, DAGSPER n=10/600, EARTH n=40/20000 e TREE n=50/6000, que finalizam blocos. Os casos vazios nao
entram como evidencia.
"IDENTICO" so vale com dados: o script devolve SEM_DADOS (saida 2) se o CSV nao tem linhas; saida 0 em todos aqui.

5. ARQUIVOS DESTE DIRETORIO
---------------------------
s1/ e s2/ : <grupo>/<caso>/{base-a,atual-a}/<csv>   (grupo = geral | tree | teste; "teste" so na semente 1). Copia byte a byte
            (fim de linha CRLF preservado, como saiu do MVPComparison). Sao os CSV "a" da baseline e da arvore atual.
TABELA.txt           : por CSV: bytes, linhas fisicas, sha256 de base-a e atual-a, igual byte a byte, copiado ou nao.
ESTADO-ARVORE.txt    : hash do diff e arquivos nao rastreados com sha256 (saida do arquivar-nao-regressao.py).
saida-*.txt          : saida integral de cada comando nao_regressao.py (CSV "b" e logs *.log nao foram copiados: os "b" so provam
                       base-a x base-b e atual-a x atual-b, ja resumidos na tabela acima e nas saidas).
NAO COPIADOS por tamanho (> 200 KB; so sha256 + linhas em TABELA.txt):
  s1/tree/TREE-50v-6000s/tree-50v-t0-basic-log.csv  260269 B, 3446 linhas  (sha256 da@base e @atual iguais: ver TABELA.txt)
  s2/tree/TREE-50v-6000s/tree-50v-t0-basic-log.csv  267138 B, 3502 linhas
O tree-50v-t0.csv de cada semente (9493 B e 9584 B) foi copiado. O comando de reproducao reconstroi tudo.

6. TESTE AUTOMATIZADO (MVPComparisonNaoRegressaoTest, mvn -q -o test)
---------------------------------------------------------------------
Roda MVPComparison.main para ARQ n=7/60 s semente 1 e TENDERMINT n=4/5 s semente 1 (fixture TM acima) e compara com os CSV
de s1/teste/*/base-a (saida da baseline 45eb3e7), normalizando so o que o projeto ja normaliza (colunas "hash", linhas '#'),
linha a linha, incluindo mvp-results.csv. Mais: "  arq " (minusculas e espacos) tem de produzir a saida do ARQ.
Mutacao (executada hoje e REVERTIDA; hash do diff conferido depois = e7c3ec8f...):
  M1: normalizeProtocol sem trim() -> protocoloEmMinusculasEComEspacosEquivaleAoCanonico falha (NoSuchFileException: o protocolo
      "  ARQ" nao existe e nenhum CSV e gerado). Os outros 3 testes continuam verdes (so o teste de nome pega a regra do trim).
  M2: runProtocol, case ARQ, semente seed+1 -> arqMesmaSaidaQueABaseline45eb3e7 e protocoloEmMinusculas... falham
      ("numero de linhas ==> expected: <38> but was: <51>"). O teste do TENDERMINT nao falha (nao foi tocado).
mvn -q -o test completo depois da reversao: rc=0, 60 classes de teste, 0 falhas/erros (MVPComparisonNaoRegressaoTest 4 testes,
MVPComparisonHotStuffTest 19, MVPComparisonTendermintTest 10).
lint-jabs.py src/main/java: 253 arquivos, 14 bloqueantes, 41 avisos (rc=1); igual ao /tmp/opencode/hs4-lint-depois.txt da rodada
anterior; zero achados em MVPComparison.java. NAO rodei o lint no worktree de 45eb3e7 nesta rodada; entao "14 pre-existentes" nao
e medido aqui. Os 14/41 coincidem com o lint-depois do b1 (b1/README.txt) e os achados vistos sao JB-009 em arquivos que o diff
nao toca (ex.: CasperVoteDeliveryEvent.java:26): fora do escopo desta tarefa.

7. LIMITES DECLARADOS (leia antes de citar como prova)
-----------------------------------------------------
1. Os valores de duracao e n (ex.: n=10/60 s, n=50/600 s) sao os que finalizam blocos neste ambiente; nao sao valor de pesquisa.
   Os --tm-* sao FIXTURE (a definir com o humano), nao calibracao.
2. TREE so e estavel com `-XX:+UnlockExperimentalVMOptions -XX:hashCode=2` (a saida depende de System.identityHashCode:
   ADR-0012/0013). Sem essa flag TREE nao foi comparado e nao se afirma nada sobre ele. Casper/Parlia/Nakamoto idem: o ARQ,
   MCO2, AMBIFY, OFFSET, EARTH, DAGSPER e TENDERMINT deram IDENTICO sem a flag nestes casos, mas nao foram testados com muitas
   sementes/n.
3. Duas sementes (1 e 2), 1 trial, 1 ou 2 valores de n por protocolo. Nao cobre outras sementes, outros n, nem as variantes
   `--n-semantics=nodes`, `--durations`, `--enable-tx=false`, `--load-record`. Nao cobre ByzantineSweep nem
   HybridArchitectureValidation (nao foram tocados; o diff e so em MVPComparison e nos scripts).
4. HOTSTUFF nao entra aqui: a baseline nao tem `case HOTSTUFF` (nao_regressao.py o recusa de proposito). A prova do proprio
   HotStuff e a de determinismo atual x atual (checar-determinismo.py e MVPComparisonHotStuffTest), fora deste diretorio.
5. A baseline ARQ/PBFT de 45eb3e7 ja e a com trafego medido (ADR-0006): "ARQ identico" significa identico ao 45eb3e7, nao
   identico ao codigo original 5102fe2. Para ARQ n=10 semente 1 o arquivo (29 linhas fisicas, 4342 B) tem o mesmo tamanho e as
   mesmas contagens da execucao anterior em /tmp/opencode/hs4-nr (conferi so as contagens, nao reconferi o conteudo dela).
6. Java 25 executou as quatro execucoes (o pom mira 17; nao testei em 17). O teste JUnit depende do fim de linha CRLF e do
   mesmo JDK apenas via normalizacao por linha ('\R'); se algum Git normalizar CRLF->LF o teste continua valendo, mas os
   sha256 de TABELA.txt deixariam de bater byte a byte (o criterio do teste e por linha, nao por byte).
7. O JUnit cobre so ARQ e TENDERMINT (o que a mudanca tocou diretamente com menor custo); os demais protocolos ficam com esta
   evidencia arquivada + o comando de reproducao, nao com teste automatizado.
8. A baseline binaria continua em /tmp/opencode/base-45eb3e7 (efemera). O repositorio guarda as SAIDAS dela (base-a), o commit
   e o procedimento para recongelar; nao guarda o binario.
9. Mutacoes: so M1 e M2 (secao 6). Nao fiz mutacao que quebre o TENDERMINT; o teste dele nunca foi visto falhar.
10. Nao ha ADR para esta evidencia; nao alterei output/, data/, docs/_build/ nem artefato de pesquisa publicado.
