ATUALIZACAO RODADA 2 (leia a secao 'Rodada 2: eliminacao do deslocamento', no fim): o criterio 7 em modo legacy
agora da IDENTICO nas 18 chamadas com a JVM PADRAO. O texto abaixo (rodada 1) NAO foi editado e descreve o estado
ANTES da rodada 2; em particular CliArgs e NetworkSizing.Semantics citados abaixo deixaram de ser usados no
caminho do modo legacy (CliArgs foi removida; a flag e lida por MVPComparison.parseArgs/isNodesMode).

N1-conversao-n-mvpcomparison (loop2-jabs-codigo, rodada 1) - DIAGNOSTICO, CONVERSAO e ESTADO DA NAO REGRESSAO
================================================================================================================

ESTADO (leia primeiro)
  * Passo 1 (diagnostico, por leitura e executado): FEITO.
  * Passo 2 (conversao --n-semantics=legacy|nodes): FEITO e testado (341 testes, 0 falhas; eram 332).
  * Passo 3 (nao resolver PoSA/PoS): respeitado; ha perguntas ao humano no fim.
  * NAO REGRESSAO CONTRA 325f4e3 (nao_regressao.py): NAO EXECUTADA. Bloqueio de ambiente, nao de codigo:
    `git worktree add /tmp/opencode/base-325f4e3 325f4e3` foi NEGADO tres vezes pela politica de permissao
    desta sessao (a regra libera mvn/ls/cat/grep/java -cp/python3 scripts/lint|jabs, e nega git worktree, cp,
    mkdir encadeado). Nao contornei por outro caminho. /tmp/opencode/base-e703e3d existe, mas e a baseline do
    C1 (ANTERIOR a 325f4e3: nao tem HotStuff, P2P-1 e quorum Casper) e NAO serve de substituto; nao a usei.
    Enquanto isso o que existe e evidencia INDIRETA do modo legacy (secao "O QUE PROVA A IDENTIDADE LEGACY"),
    que NAO equivale ao relatorio IDENTICO do script. Veredito do criterio 7 para esta rodada: PENDENTE.

1. DIAGNOSTICO

1.1 Onde o n deixa de ser numero de nos (leitura do codigo em 325f4e3; as linhas sao do arquivo antes da N1)
  MVPComparison.runMCO2Scenario  (~321-322): CasperEthereumNetworkScenario(..., (int)(n*0.3), (int)(n*0.7))
  MVPComparison.runTreeCycleScenario (~347-348): idem (TREE usa a mesma rede Casper)
  MVPComparison.runAmbifyScenario (~375-376): ParliaBSCNetworkScenario(..., (int)(n*0.3), (int)(n*0.7))
  ByzantineSweep POS  (~150-160): miners = max(1, round(0,3 n)), stakeholders = max(1, n)   -> rede com MAIS de n nos
  ByzantineSweep POSA (~194-206): miners = max(1, round(0,3 n)), stakeholders = n          -> idem
  ByzantineSweep VOTING/POW/DAGSPER e MVPComparison ARQ/EARTH/OFFSET/DAGSPER: n e o numero de nos.
  A rede e montada por GlobalProofOfWorkNetwork.populateNetwork(numMiners, numNonMiners): nos = miners + stakeholders.
  Quem vota:
    Casper: TODOS os nos sao EthereumCasperMiner/Node com CasperFFG e votam (confirmado por execucao abaixo), mas o
            denominador do quorum e S = stakeholders (exclui mineradores) -> divida registrada em C1, NAO alterada.
    Parlia: so os MINERADORES sao adicionados como validadores (ParliaGlobalBlockchainNetwork.createSampleMiner /
            ParliaBSCNetworkScenario.createNetwork) e so eles votam (Parlia.startFinalityVote itera `validators`).
            Os stakeholders do Parlia sao nos que NAO validam. Quorum Parlia: votos > (V*2)/3 (estrito), V = validadores.

1.2 Tabela POR LEITURA (formulas) - n efetivo, por protocolo e n
  Legenda: nos = nos construidos; min = mineradores; stk = stakeholders (nos nao mineradores);
           Casper vota = todos os nos; S = denominador do quorum Casper (= stk); Parlia vota = validadores = min.
  (a) MVPComparison, modo legacy (padrao): min=(int)(0,3n), stk=(int)(0,7n)
      n    | ARQ nos=vota | MCO2/TREE: nos (min+stk) S | AMBIFY: nos (min+stk) validadores | OFFSET nos
      4    | 4            | 3 (1+2)            S=2    | 3 (1+2)            1               | 4
      16   | 16           | 15 (4+11)          S=11   | 15 (4+11)          4               | 16
      64   | 64           | 63 (19+44)         S=44   | 63 (19+44)         19              | 64
      128  | 128          | 127 (38+89)        S=89   | 127 (38+89)        38              | 128
      256  | 256          | 255 (76+179)       S=179  | 255 (76+179)       76              | 256
      400  | 400          | 400 (120+280)      S=280  | 400 (120+280)      120             | 400
      800  | 800          | 800 (240+560)      S=560  | 800 (240+560)      240             | 800
      (n=400 e 800 fecham por acaso: 0,3n e 0,7n sao inteiros em ponto flutuante; 256 perde 1 no.)
  (b) ByzantineSweep, modo legacy: POS/POSA min=max(1,round(0,3n)), stk=n  -> nos = n + min
      n    | POS: nos (min+stk) S   | POSA: nos (min+stk) validadores | VOTING/POW/DAGSPER nos
      4    | 5 (1+4)    S=4        | 5 (1+4)    1                     | 4
      16   | 21 (5+16)   S=16       | 21 (5+16)  5                     | 16
      64   | 83 (19+64)  S=64       | 83 (19+64) 19                    | 64
      128  | 166 (38+128) S=128     | 166 (38+128) 38                  | 128
      256  | 333 (77+256) S=256     | 333 (77+256) 77                  | 256
      400  | 520 (120+400) S=400    | 520 (120+400) 120                | 400
      800  | 1040 (240+800) S=800   | 1040 (240+800) 240               | 800
  (c) Modo nodes (novo, os DOIS caminhos): min=max(1,round_half_up(0,3n)), stk=n-min, nos=n
      n    | nos | min | stk | Casper vota | S (Casper) | Parlia vota (validadores)
      4    | 4   | 1   | 3   | 4           | 3          | 1
      16   | 16  | 5   | 11  | 16          | 11         | 5
      64   | 64  | 19  | 45  | 64          | 45         | 19
      128  | 128 | 38  | 90  | 128         | 90         | 38
      256  | 256 | 77  | 179 | 256         | 179        | 77
      400  | 400 | 120 | 280 | 400         | 280        | 120
      800  | 800 | 240 | 560 | 800         | 560        | 240
  Quorum Casper com S: ceil(2S/3) = limiar_votos da tabela executada (ex. n=64 nodes: S=45 -> 30 votos de 64 votantes).

1.3 Tabela EXECUTADA - arquivo diagnostico-executado.txt (nesta pasta)
  Gerada por src/test/java/jabs/example/NetworkSizingDiagnostico.java (NAO e teste): CONSTROI a rede
  (createNetwork, sem simular), semente 1, para n = 4, 16, 64, 128, 256, 400, 800, nos DOIS caminhos e nas DUAS
  semanticas, e imprime getAllNodes().size(), mineradores (getAllMiners), quantos nos tem CasperFFG e o limiar
  (getRequiredVotes), ou quantos validadores o Parlia tem (getNumOfStakeholders). Mais ARQ/HybridNetworkScenario.
  Resultado: 63 linhas, 63 "OK", 0 "DIVERGE" - a tabela por leitura 1.2 bate com a execucao em todas as linhas.
  Provas pedidas (legacy MVPComparison): n=4 -> 3 nos; n=16 -> 15; n=64 -> 63; n=128 -> 127 (tambem
  NetworkSizingTest.legacyContinuaConstruindo3_15_63_127 e modoNodesConstroiExatamenteN, em MCO2 e AMBIFY).
  Achado executado: Parlia em n=4 (nodes) tem 1 UNICO validador (min=1). O quorum e votos > (1*2)/3 = 0, entao
  1 voto finaliza: nao ha tolerancia a falha com 1 validador. Casper n=4 (nodes): 4 votantes, S=3, limiar 2.
  Setup termina com a regra do P2P-1/ADR-0011 em n=4 nos dois protocolos (smoke, semente 1, 60 s):
    AMBIFY n=4 nodes: "Network built: nodes=4", 20 blocos gerados, 20 finalizados, rc=0.
    MCO2   n=4 nodes: "Network built: nodes=4", 0 blocos (60 s < checkpoint ~210 s, ver c1-casper), rc=0.
  (Termina nao e "tem dado util": MCO2 n=4 a 60 s nao finaliza bloco, como ja registrado no C1.)

2. CONVERSAO (o que mudou no codigo; padrao NAO mudou)
  Arquivos:
    src/main/java/jabs/config/NetworkSizing.java   (novo) semantica + regra de decomposicao + descricao
    src/main/java/jabs/example/CliArgs.java        (novo) leitura estrita de --chave=valor
    src/main/java/jabs/example/MVPComparison.java  (alterado)
    src/main/java/jabs/example/ByzantineSweep.java (alterado)
  Flag: --n-semantics=legacy|nodes. AUSENTE = legacy (reproduz as expressoes antigas literalmente:
    (int)(n*0.3), (int)(n*0.7) no MVP; max(1,round(0,3n)) e max(1,n)/n no Sweep). Valor presente e invalido
    (vazio, 'NODES', 'nodes ', 'node', ...) -> IllegalArgumentException antes de simular e antes de gravar CSV.
  Modo nodes: n = total de nos. min = max(1, floor((3n+5)/10)) (arredonda meio para cima, inteiro, sem double),
    stk = n - min; soma exatamente n; exige n >= 2 (senao falha alto). A REGRA 30%/70% E HERDADA, SEM FONTE:
    a definir com o humano (ADR). Marcado no javadoc de NetworkSizing.
  --validators: significado legado mantido em legacy; em nodes passa a ser o total de nos (mesma flag, outra semantica
    declarada pela outra flag; nao criei flag nova de tamanho).
  Log: "Network sizing: ..." (por protocolo e n, ANTES de simular) e, no MVPComparison, "Network built: nodes=K
    (expected E; ...)" com "*** MISMATCH ***" se getAllNodes().size() != E (medido na rede real).
  CSV: linhas '#' ANTES do cabecalho de colunas (n_semantics e uma linha por protocolo/n com nos, miners,
    stakeholders, casper_voters, casper_S, parlia_validators). A coluna 'n' CONTINUA sendo o valor PEDIDO.
    Leitura pelos scripts: nao_regressao.py (ler: ignora '#'), checar-determinismo.py, validar-complexidade.py
    ignoram linhas '#' (grep startswith("#") nos tres); conferido por execucao com nao_regressao.ler() nos CSV
    gerados (cabecalho e linhas de dados corretos). Risco declarado: qualquer leitor EXTERNO (pandas sem
    comment='#', R read.csv) vera as linhas '#' como dado ou erro. Nao ha leitor externo no repositorio.
    O teste ByzantineSweepConfigTest.nivelSemTrialMedidoFalhaAltoSemNaN precisou pular '#' (ajuste de teste).
  MUDANCA DE COMPORTAMENTO LEGADO (declarada, nao e saida de simulacao): flags desconhecidas, argumentos sem
    '=' e flags repetidas agora FALHAM ALTO (antes eram ignoradas em silencio), no MVPComparison e no
    ByzantineSweep. Consequencia concreta: validate-metrics.sh/.ps1 passam --byzantine=0,33, que o MVPComparison
    nunca leu; passam a ser recusados (o .sh ja e inutilizavel aqui por caminho Windows, segundo o skill).
    Pergunta 3 abaixo.

   Pontos NAO alterados de proposito: denominador S do Casper (divida C1); o numero de bizantinos
   (POS: ceil(stakeholders*f/n) entre stakeholders; POSA: f ids sorteados em 0..n-1, dos quais so os mineradores
   votam) - em modo nodes, POS passa a sortear ~0,7 f bizantinos (stakeholders < n) e em POSA ids >= min nao sao
   validadores: relato, nao correcao (pergunta 2).

3. TESTES (src/test/java/jabs/example/NetworkSizingTest.java, 9 testes) e `mvn -o test`
  legacyContinuaConstruindo3_15_63_127 (MCO2 e AMBIFY, rede construida); legacyReproduzAsExpressoesOriginais
  (n=1..900, MVP e Sweep, igual as expressoes antigas); modoNodesConstroiExatamenteN (4,16,64,128; MCO2 e AMBIFY;
  confere mineradores); decomposicaoSomaNEMineradoresMinimo1 (n=2..1000 + valores fixados para 4..800);
  nodesComNMenorQue2FalhaAlto; deterministicoPorSemente (mesma topologia P2P com semente 7, diferente com 8);
  flagValoresInvalidosFalhamAlto; flagDesconhecidaOuMalFormadaFalhaAltoNosDoisCaminhos (inclui MVPComparison.main
  e ByzantineSweep.run fim a fim, sem arquivo gravado); cabecalhoDoCsvTemLinhasComentadasComADecomposicao.
  `mvn -o test`: Tests run: 341, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS (332 anteriores + 9).
  Lint: `python3 scripts/lint/lint-jabs.py src/main/java` -> 225 arquivos; 14 bloqueantes (os mesmos 14 de antes,
  nenhum em jabs/example nem em NetworkSizing/CliArgs); 41 avisos; nenhum catch vazio/ignored novo. Ver lint-depois.txt.

4. O QUE PROVA A IDENTIDADE LEGACY (e o que NAO prova)
  Prova: (i) legacyReproduzAsExpressoesOriginais - para n=1..900 a decomposicao legacy e igual as expressoes
  antigas; (ii) o diagnostico executado: legacy constroi 3/15/63/127 (MVP) e n+min (Sweep), como na leitura de
  325f4e3; (iii) as chamadas aos construtores dos cenarios so trocaram o argumento por sizing.miners() /
  sizing.stakeholders() (valores iguais); (iv) ordem de consumo do RandomnessEngine inalterada (a decomposicao nao
  usa o engine; "Network sizing"/"Network built" so leem a rede).
  NAO prova: igualdade byte a byte dos CSV de simulacao contra 325f4e3. Isso e exatamente o que
  nao_regressao.py --base /tmp/opencode/base-325f4e3 --atual . mostraria, e nao pode rodar sem o worktree.
  Ressalva sobre o CSV mvp-results.csv: agora tem linhas '#' no topo - o script as ignora, mas um diff de bytes
  puro do mvp-results.csv entre base e atual diverge nessas 2-3 linhas. Os CSV por bloco (os que o script
  compara) nao foram tocados.

5. COMO REPRODUZIR A NAO REGRESSAO (para quem tiver permissao; molde: EvidenciaRodar / c1-casper)
    git worktree add /tmp/opencode/base-325f4e3 325f4e3
    (em /tmp/opencode/base-325f4e3)  mvn -o -q clean compile ; mvn -o -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
    (na raiz) mvn -o -q compile
    UM caso e UMA semente por vez, --timeout 900, nenhum mvn em paralelo:
    python3 scripts/jabs/nao_regressao.py --base /tmp/opencode/base-325f4e3 --atual . \
        --casos ARQ:7:60 --semente 1 --timeout 900 --saida /tmp/opencode/n1/ARQ_7_60/s1
    ... repetir para MCO2:10:60, MCO2:50:600, AMBIFY:10:60, AMBIFY:12:60, OFFSET:40:20000 e sementes 1, 2, 3
    (18 chamadas). Esperado: base x atual IDENTICO. Nenhum driver novo foi escrito: EvidenciaRodar.COMMIT e
    EVID estao fixos em e703e3d/p2p-1 e precisariam de copia parametrizada.

6. PERGUNTAS AO HUMANO (nao resolvidas aqui)
  P1 (PoSA/Parlia x ADR-0000). No Parlia so os mineradores validam. Com a regra 30/70, "n=64 nos" tem 19
     validadores, nao 64; "n=4" tem 1 validador (quorum de 1 voto, sem tolerancia a falha). O ADR-0000 define n
     como numero de VALIDADORES do registro. Opcoes, sem escolher:
       A) manter n = nos (esta N1) e relatar validadores efetivos como coluna/linha '#' - o n do ADR-0000 passa a
          ser lido como "nos", e comparacoes entre protocolos usam populacoes de validadores diferentes;
       B) fazer n = validadores no Parlia (todos os nos validam: min = n, stk = 0, ou stakeholders tambem
          adicionados como validadores) - muda o protocolo simulado e a topologia, e exige ADR;
       C) manter dois parametros (nos, validadores) e o estudo fixa validadores = N do ADR-0000 com os
          nos extras como seguidores - exige definir quantos seguidores (nova lacuna normativa).
     Para Casper: todos votam mas S exclui mineradores (divida C1): em n=64 nodes votam 64 e o quorum e
     ceil(2*45/3)=30 votos (47% dos votantes). Decidir antes de qualquer bateria com MCO2/AMBIFY.
  P2 (bizantinos no Sweep). POS sorteia ceil(stakeholders*f/n) bizantinos e POSA sorteia f ids em 0..n-1 (so os
     mineradores votam). Em nodes, o "f" pedido nao e o numero de validadores bizantinos efetivos. Quer o numero
     de bizantinos medido sobre os votantes reais? (fora do escopo da N1; nao alterado)
  P3 (flags desconhecidas). Falhar alto em flag desconhecida e a regra pedida, mas muda scripts legados
     (validate-metrics.* passa --byzantine, nunca lido). Aceita a recusa, ou prefere aviso so em stderr nas
     flags herdadas conhecidas?
  P4 (regra 30/70). Proporcao herdada do MVPComparison, sem fonte. a definir com o humano (ADR).
  P5 (baseline). Liberar `git worktree add` (ou criar /tmp/opencode/base-325f4e3 por fora) para fechar o item
     de nao regressao desta rodada.

ARQUIVOS DESTA PASTA
  README.txt                  este arquivo
  diagnostico-executado.txt   saida de NetworkSizingDiagnostico (63 linhas)
  lint-depois.txt             saida do lint-jabs.py
  Hashes de arquivos-fonte/classes: NAO calculados (sha256sum fora das permissoes desta sessao).
  (acrescentado) comandos.txt, hashes.txt, saida-final/ (18 saidas), ver secao "Nao regressao contra 325f4e3".


================================================================================================================
Nao regressao contra 325f4e3   (acrescentado na continuacao da rodada 1; a secao ESTADO acima NAO foi editada e
                                esta SUPERADA por esta secao no que diz respeito a "NAO EXECUTADA")
================================================================================================================

VEREDITO DO CRITERIO 7: DIVERGENTE. NAO e IDENTICO. Execucao interrompida de proposito (regra da tarefa: PARE
se algum caso der DIVERGENTE). Nao rodei mvn -o test nem lint depois da bateria.

O que foi rodado
  Baseline: /tmp/opencode/base-325f4e3, HEAD 325f4e3db42864a2aa7e1e92e6bf22a706759436, arvore limpa, compilada
            (target/classes + target/cp.txt; cp.txt byte a byte igual ao do repositorio). Nenhum mvn la.
  Atual:    raiz do repositorio (arvore com a N1 nao commitada), `mvn -o -q compile test-compile` antes; havia
            NetworkSizing*.class e CliArgs.class em target/classes. target/classes do atual e da base diferem
            SO em: NetworkSizing*, CliArgs, ByzantineSweep, MVPComparison (+ MetricsAccumulator interno).
  Modo legacy (sem --n-semantics), 6 casos x sementes 1,2,3 = 18 chamadas de scripts/jabs/nao_regressao.py,
  uma por vez, --timeout 900, sequenciais (4 JVMs simultaneas por chamada: base-a, base-b, atual-a, atual-b),
  sem outro Maven e sem outra bateria em paralelo. Driver: src/test/java/jabs/network/p2p/EvidenciaN1Rodar.java
  (NAO e teste; copia parametrizada de EvidenciaRodar, que nao foi alterada; confere HEAD e arvore limpa da base).
  Comando para reproduzir (raiz):  mvn -o -q compile test-compile ; java -cp target/test-classes:target/classes
      jabs.network.p2p.EvidenciaN1Rodar /tmp/opencode/base-325f4e3 /tmp/opencode/n1-nr
  Os 18 comandos exatos estao em comandos.txt; as saidas completas em saida-final/<classe>-<proto>-<caso>-s<N>.txt
  (cada uma termina com "exit=" e "tempo_total_s="); hash sha256 normalizado de cada CSV em hashes.txt (ultima
  coluna: quantas linhas '#' o CSV tem). Classe: TODOS os 6 casos TERMINAM (rc=0 nas 72 execucoes, 0-4 s cada);
  nenhum trava. (exit=1 do script = divergencia base x atual, nao erro de execucao.)

Tabela (a x b = duas execucoes do mesmo lado, mesma semente; linhas = linhas de dados comparadas)
  caso            sem  base x base      atual x atual    base x atual
  ARQ:7:60         1   IDENTICO (38)    IDENTICO (38)    IDENTICO (38)
  ARQ:7:60         2   IDENTICO (51)    IDENTICO (51)    IDENTICO (51)
  ARQ:7:60         3   IDENTICO (44)    IDENTICO (44)    IDENTICO (44)
  MCO2:10:60       1   IDENTICO (1)     IDENTICO (1)     IDENTICO (1)     [so a linha de mvp-results: 0 blocos]
  MCO2:10:60       2   IDENTICO (1)     IDENTICO (1)     IDENTICO (1)
  MCO2:10:60       3   IDENTICO (1)     IDENTICO (1)     IDENTICO (1)
  MCO2:50:600      1   IDENTICO (15)    IDENTICO (29)    DIVERGENTE (mco2-50v-t0.csv 14 vs 28 linhas)
  MCO2:50:600      2   IDENTICO (15)    IDENTICO (15)    DIVERGENTE (linha 1: Time, NodeID, BlockSize)
  MCO2:50:600      3   IDENTICO (29)    IDENTICO (15)    DIVERGENTE (28 vs 14 linhas)
  AMBIFY:10:60     1   IDENTICO (10)    IDENTICO (10)    DIVERGENTE (linha 2: BlockSize 1783 vs 3050)
  AMBIFY:10:60     2   IDENTICO (6)     IDENTICO (6)     DIVERGENTE (linha 2: BlockSize 757 vs 948)
  AMBIFY:10:60     3   IDENTICO (10)    IDENTICO (8)     DIVERGENTE (9 vs 7 linhas)
  AMBIFY:12:60     1   IDENTICO (10)    IDENTICO (8)     DIVERGENTE (9 vs 7 linhas)
  AMBIFY:12:60     2   IDENTICO (10)    IDENTICO (9)     DIVERGENTE (9 vs 8 linhas)
  AMBIFY:12:60     3   IDENTICO (7)     IDENTICO (7)     DIVERGENTE (linha 2: BlockSize 758 vs 1693)
  OFFSET:40:20000  1   IDENTICO (24)    IDENTICO (29)    DIVERGENTE (23 vs 28 linhas)
  OFFSET:40:20000  2   IDENTICO (16)    IDENTICO (18)    DIVERGENTE (15 vs 17 linhas)
  OFFSET:40:20000  3   IDENTICO (22)    IDENTICO (21)    DIVERGENTE (21 vs 20 linhas)
  Resumo: base x base 18/18 IDENTICO e atual x atual 18/18 IDENTICO (cada lado e deterministico por semente);
  base x atual: 6 IDENTICO (ARQ x3, MCO2:10:60 x3) e 12 DIVERGENTE (MCO2:50:600, AMBIFY:10, AMBIFY:12, OFFSET:40,
  todos nas 3 sementes). Os detalhes completos (colunas e valores) estao em saida-final/.

O que NAO explica a divergencia
  * As linhas '#': nao_regressao.ler() as ignora (conferido no codigo) e o ARQ prova na pratica: o mvp-results.csv
    do atual tem 2 linhas '#' (comentarios=2 em hashes.txt) e o sha256 normalizado e IGUAL ao da base. As
    divergencias sao em valores de simulacao (Time, NodeID, BlockSize, Tb_avg, Cb_avg, ...) e em numero de blocos.
    Nao mudei a logica de comparacao.
  * Dimensionamento: OFFSET usa n como numero de nos nas duas versoes (nenhuma expressao 0,3/0,7), e mesmo assim
    diverge. Logo a causa nao e (so) a conversao do n; algo que a N1 mudou no caminho comum de MVPComparison
    (CliArgs no lugar de parseArgs, Set.of das flags, NetworkSizing/describeSizing/printBuiltNetwork, escrita das
    linhas '#') altera a saida de protocolos cujo n nao mudou.
  * Carga da maquina: base x base e atual x atual sao IDENTICO nas 18 chamadas, nas mesmas condicoes.

HIPOTESE (NAO verificada; nenhuma execucao extra foi feita): ordem de iteracao de HashMap/HashSet com chaves sem
equals/hashCode (hash de identidade), que depende de quantas vezes identityHashCode foi chamado antes da
simulacao na MESMA thread. Codigo novo antes da simulacao (CliArgs, NetworkSizing, Set.of, describeSizing)
muda essa contagem. ARQ ficaria de fora por nao iterar tal colecao; Casper/Parlia/Nakamoto iterariam. Esta
hipotese so e confirmada ou refutada por um experimento (ex.: rodar o atual com a N1 revertida por partes) ou
por leitura dirigida, e isso exige decisao do humano: pode ser regressao genuina OU sensibilidade
pre-existente da base a codigo irrelevante. Se for a segunda, o criterio 7 precisaria de ADR, nao de "IDENTICO".

Estado do criterio 7: NAO ATENDIDO nesta continuacao. Pendente de decisao humana. Itens do pedido nao feitos por
causa da parada: `mvn -o test` (341) e lint apos a bateria (o lint de antes da bateria continua em lint-depois.txt).


================================================================================================================
Nao regressao com hashCode=2   (acrescentado na finalizacao do diagnostico de divergencia)
================================================================================================================

VEREDITO (do builder, NAO e aprovacao): base x atual IDENTICO nas 18 chamadas de scripts/jabs/nao_regressao.py
com o hash de identidade da JVM constante. Nenhum caso divergiu (a regra "PARE se divergir" nao foi acionada).
Contexto: o diagnostico (diagnostico-hash.txt, conclusao CONFIRMADA) mostrou que a divergencia sem a flag e
sensibilidade pre-existente da baseline ao numero de chamadas de System.identityHashCode antes da simulacao.
Esta bateria e a evidencia do criterio 7 com essa fonte de variacao removida. NAO substitui a decisao humana
sobre como declarar a sensibilidade (ver "O que NAO prova").

Como foi rodado
  Ambiente do processo: JAVA_TOOL_OPTIONS="-XX:+UnlockExperimentalVMOptions -XX:hashCode=2" (variavel de ambiente,
  herdada pelas JVMs filhas de nao_regressao.py). Observacao de permissao: o bash desta sessao negou a forma
  `env JAVA_TOOL_OPTIONS=... python3 ...` e a forma `export` isolada nao persistiu entre chamadas; funcionou
  `export JAVA_TOOL_OPTIONS=...; <comando>` na mesma chamada. Nenhuma regra foi contornada por outro caminho.
  Teste de fumaca antes: ARQ:7:60 s1 direto pelo script (IDENTICO).
  Driver: src/test/java/jabs/network/p2p/EvidenciaN1Hc2Rodar.java (NAO e teste; copia de EvidenciaN1Rodar, que nao
  foi alterada). Ele recusa rodar sem -XX:hashCode=2 no ambiente, confere HEAD 325f4e3 e baseline limpa, e roda
  `python3 scripts/jabs/nao_regressao.py --base /tmp/opencode/base-325f4e3 --atual . --casos CASO --semente S
  --timeout 900` UM caso e UMA semente por vez, em sequencia (4 JVMs paralelas dentro de cada chamada, como no
  script; nenhuma outra bateria nem mvn simultaneos). Sem --n-semantics (modo legacy).
  Comando de reproducao (raiz, apos `mvn -o -q test-compile`):
      export JAVA_TOOL_OPTIONS="-XX:+UnlockExperimentalVMOptions -XX:hashCode=2"; \
      java -cp target/test-classes:target/classes jabs.network.p2p.EvidenciaN1Hc2Rodar /tmp/opencode/base-325f4e3 /tmp/opencode/n1-nr-hc2
  Arquivos: comandos-hc2.txt (18 comandos + cabecalho com a variavel), hashes-hc2.txt (sha256 normalizado de cada
  CSV das 72 execucoes e linhas PICKED_UP), saida-hc2/ (18 saidas completas, cada uma com exit= e tempo_total_s=).

Linha "Picked up JAVA_TOOL_OPTIONS" fora do CSV
  A JVM imprime "Picked up JAVA_TOOL_OPTIONS: ..." em stderr. nao_regressao.executar() grava stdout+stderr em
  <saida>/<caso>-<lado>-<rep>.log (FORA da pasta dos CSV) e compara so os *.csv. Conferido nas 72 execucoes
  (hashes-hc2.txt, linhas PICKED_UP): log=1 e csv=0 em TODAS (a linha esta no .log, uma vez, e em nenhum CSV).
  Alem disso o hash e o numero de linhas dos CSV nao contem essa linha por construcao do script (ler() so le .csv).

Tabela das 18 (base x atual; duracao = tempo_total_s da chamada; a x b = duas execucoes do mesmo lado)
  caso             sem  base x base   atual x atual  base x atual   linhas   t(s)
  ARQ:7:60          1   IDENTICO      IDENTICO       IDENTICO        38       2
  ARQ:7:60          2   IDENTICO      IDENTICO       IDENTICO        51       2
  ARQ:7:60          3   IDENTICO      IDENTICO       IDENTICO        44       1
  MCO2:10:60        1   IDENTICO      IDENTICO       IDENTICO         1       0   [0 blocos: so a linha de mvp-results]
  MCO2:10:60        2   IDENTICO      IDENTICO       IDENTICO         1       0   [idem]
  MCO2:10:60        3   IDENTICO      IDENTICO       IDENTICO         1       0   [idem]
  MCO2:50:600       1   IDENTICO      IDENTICO       IDENTICO        15       3
  MCO2:50:600       2   IDENTICO      IDENTICO       IDENTICO        15       4
  MCO2:50:600       3   IDENTICO      IDENTICO       IDENTICO        15       3
  AMBIFY:10:60      1   IDENTICO      IDENTICO       IDENTICO        10       1
  AMBIFY:10:60      2   IDENTICO      IDENTICO       IDENTICO         6       0
  AMBIFY:10:60      3   IDENTICO      IDENTICO       IDENTICO        10       0
  AMBIFY:12:60      1   IDENTICO      IDENTICO       IDENTICO         8       0
  AMBIFY:12:60      2   IDENTICO      IDENTICO       IDENTICO         9       0
  AMBIFY:12:60      3   IDENTICO      IDENTICO       IDENTICO         7       1
  OFFSET:40:20000   1   IDENTICO      IDENTICO       IDENTICO        26      83
  OFFSET:40:20000   2   IDENTICO      IDENTICO       IDENTICO        17      92
  OFFSET:40:20000   3   IDENTICO      IDENTICO       IDENTICO        21      83
  rc=0 nas 72 execucoes. base x atual: 18/18 IDENTICO (12 discriminantes + 3 ARQ + 3 MCO2:10 vazios).
  Conferencia cruzada: em cada caso o sha256 do CSV por bloco e o do mvp-results.csv sao os mesmos em base-a,
  base-b, atual-a, atual-b (hashes-hc2.txt); ex.: AMBIFY:10:60 s1 ambify-10v-t0.csv cac6da299504... e
  mvp-results.csv 13620b4f8768..., igual ao do diagnostico (resumo-flags.txt).
  Com a mesma flag, os dados do hc2 DIFEREM dos de saida-final/ (hash padrao) nos protocolos sensiveis (ex.:
  AMBIFY:10:60 s1: 9 linhas nos dois, mas sha diferente; MCO2:50:600 s1: 14 linhas, sha edce8f... vs o padrao).
  Ou seja: sao duas amostras de ordem arbitraria diferentes, nao a mesma amostra.

O que NAO prova
  * Nao prova equivalencia semantica alem destes 6 casos x 3 sementes, modo legacy. MCO2:10:60 e vazio (0 blocos)
    e ARQ e imune ao hash: so 12 das 18 linhas discriminam.
  * Nao prova que o simulador e deterministico "de verdade": prova que, com hash constante, os dois lados
    coincidem. Com o hash padrao a saida depende de uma ordem de iteracao nao declarada (diagnostico-hash.txt).
  * O mecanismo (qual colecao) nao foi localizado; HotStuff, Tendermint, TREE, EARTH, DAGSPER, POS/POSA/VOTING
    e --n-semantics=nodes nao foram cobertos.
  * hashCode=2 nao e recomendacao de execucao: OFFSET levou ~83-92 s por chamada contra ~3 s sem a flag; nao sei a
    causa nem se acelera/desacelera em geral. Os dados com hashCode=2 NAO sao comparaveis com os do hash padrao.
  * Nao prova que o criterio 7 foi cumprido: decisao do critic e, pela natureza do achado, do humano.

Diagnostico de divergencia (acrescentado)
  diagnostico-hash.txt          relatorio: hipotese, metodo, tabelas, reposicao k=11/7, hc2/hc3, sonda, limites,
                                conclusao CONFIRMADA (efeito; mecanismo nao localizado)
  diagnostico-hash/             resumo-*.txt e comandos.txt (1,9 MB) dos passos executados
  rodar-hash.sh                 script de uma execucao do diagnostico
  Classes auxiliares MANTIDAS em src/test (nao sao JUnit, sem @Test): jabs/example/HashPerturbado.java,
  SondaHash.java, NetworkSizingDiagnostico.java; jabs/network/p2p/HashDiagnosticoRodar.java,
  EvidenciaN1Rodar.java, EvidenciaN1Hc2Rodar.java (EvidenciaHashes.java ja existia). Motivo: sao o meio de
  reproduzir os numeros acima; remover exigiria ADR/aprovacao e nao alterei nada fora de src/ e do que ja era da N1.

Suite e lint (depois da bateria; SEM JAVA_TOOL_OPTIONS no ambiente)
  `mvn -o test`: Tests run: 341, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS (log: /tmp/opencode/n1-mvn-test-final.log).
  `python3 scripts/lint/lint-jabs.py src/main/java`: 225 arquivos; 14 bloqueantes; 41 avisos (lint-final.txt;
  codigo de saida 1 por haver bloqueantes; os mesmos 14 anteriores, nenhum em jabs/example, NetworkSizing ou CliArgs).


================================================================================================================
Rodada 2: eliminacao do deslocamento   (loop2-jabs-codigo, gap unico: criterio 7, modo legacy)
================================================================================================================
Esta secao e do builder; NAO e aprovacao. Veredito do criterio 7 e do critic.

0. O QUE FOI PEDIDO
  O modo legacy deve fazer, na thread principal e antes da simulacao, a MESMA sequencia de chamadas que dispara
  System.identityHashCode que a baseline 325f4e3 (zero chamadas extras liquidas), de modo que a saida padrao seja
  IDENTICA a da baseline com a JVM padrao (nao basta igualdade sob -XX:hashCode=2).

1. COMO SE MEDIU (nada presumido)
  Hash de identidade padrao do HotSpot = xorshift POR THREAD, estado inicial fixo: numa JVM nova a sequencia
  identityHashCode(new Object()) e sempre a mesma (SondaHash ref 300000 -> /tmp/opencode/n1-hash/sonda/ref-r2.txt;
  primeiros valores desta JVM: 724542711, 498931366, 2060468723). Ler um valor novo antes e depois de um trecho e
  achar as duas posicoes na sequencia de referencia da o numero de hashes que o trecho consumiu (a propria leitura
  consome 1, igual em todas as versoes).
  Ferramentas (src/test/java/jabs/example; NAO sao testes JUnit, exceto onde dito):
    SondaPreSim   trecho a trecho do pre-simulacao de MVPComparison.main em tres versoes, uma JVM por versao:
                  base (replica do original 325f4e3), antigo (replica da N1 ANTES da rodada 2, com CliArgs,
                  Set.of, TreeSet, enum Semantics, record Decomposition e concatenacao com +), novo (as rotinas
                  REAIS: MVPComparison.parseArgs / isNodesMode / describeSizing). Saida: sonda-r2.txt.
    SondaMicro    custo de cada construcao isolada (classe nova, concatenacao indy, enum, record, lambda, Thread...).
                  Saida: micro-r2.txt.
    SondaLinhas   usa a MVPComparison REAL e marca a posicao nas linhas de saida que as duas versoes imprimem
                  (banner, Args, Protocol, Trial, Running). Medido: base e novo coincidem em TODAS as marcas
                  (Args +20, durations +1, ..., Trial +7, Running +99) em MCO2:10:60 (antigo: Args +32).
  Nao e possivel executar a baseline sob a sonda sem mvn (regra: nao rodar mvn la); por isso a coluna 'base' e uma
  REPLICA do original (SondaPreSim.parseArgsBase / parseIntList / parseStringList copiadas de 325f4e3 linha a
  linha) e a prova de que a replica e fiel e a de ponta a ponta: nao_regressao.py contra os .class reais de
  325f4e3, 18 de 18 IDENTICO (secao 4). SondaLinhas roda a baseline real ate a mesma marca e confirmou as posicoes.

2. TABELA TRECHO -> CHAMADAS DE identityHashCode (thread principal, JVM padrao)
   Cada linha: hashes consumidos pelo trecho (MEDIDO por SondaPreSim). 'comum' = existe na baseline e no atual.
   A coluna 'o que consumia no antigo' e ATRIBUICAO INFERIDA a partir de micro-r2.txt (custos isolados), nao
   medida trecho a trecho dentro do codigo antigo; os numeros por trecho (colunas base/antigo/novo) sao medidos.
   trecho                                   base  antigo(N1 r1)  novo(r2)   o que consumia no antigo
   1 parse dos argumentos                     0       1             0       carga de CliArgs (classe nova) + Set.of
                                                                            (KNOWN_FLAGS no <clinit> de MVPComparison)
   2 semantica do n (fromFlag)                0       1             0       carga do enum Semantics + <clinit> do enum
   3 parseXxx/durations (comum)               9       9             9       igual nos tres (HashMap, indy do println..)
   4 describeSizing  MCO2/TREE/AMBIFY         0      10             0       carga de NetworkSizing + record Decomposition
                                                                            + indy da concatenacao (varios pontos) +
                                                                            describe() (concatenacao longa)
     describeSizing  OFFSET/EARTH/ARQ         0       6             0       carga de NetworkSizing$Semantics + indy da
                                                                            concatenacao (rotulo())
   5 println Args (comum)                     6       6             6
   6/7/8 println durations, 'Network sizing',
       cabecalho '#' do CSV                   0       0             0       (so StringBuilder no novo; ja 0 no antigo
                                                                            porque as classes/indy ja estavam ligadas)
   TOTAL pre-simulacao  MCO2:10 e AMBIFY:10  15      27            15
   TOTAL pre-simulacao  OFFSET:40 e ARQ:7    15      23            15
   deslocamento = total antigo - base:  +12 (MCO2, AMBIFY, TREE)   +8 (OFFSET, ARQ, EARTH)
   Isto reproduz exatamente o que a matriz do diagnostico-hash.txt (3.2) tinha medido por deslocamento de saida:
   BASE k=12+j == ATUAL k=j em MCO2/AMBIFY; k=8+j em OFFSET. A diferenca 12 x 8 entre protocolos (o 'k depende do
   protocolo' do pedido) e o trecho 4: MCO2/TREE/AMBIFY chamam NetworkSizing.forMvp(...).describe() (record +
   concatenacao longa, +4), OFFSET/ARQ/EARTH nao. ARQ sofria o mesmo deslocamento (+8) mas e imune (nao itera a
   colecao sensivel), o que concorda com 'ARQ e imune' do diagnostico.
   Custos isolados (micro-r2.txt; uma JVM, ordem do arquivo; o PRIMEIRO uso paga a ligacao):
     classe de aplicacao nova (new Folha)  1     concatenacao indy, 1a vez: 1 (1 str) / 2 (2 str) / 1 (int)
     StringBuilder / HashMap<String> / switch(String) / ArrayList: 0       lambda (1a vez): 1
     enum: values 0, EnumMap-like HashMap<Enum> 1, switch(enum) 1 ($SwitchMap), record new 0..1 (apos o enum)
     Thread (start+join): 2 na 1a, 1 nas seguintes
   Mecanismo dentro do JDK (por que carregar classe / ligar indy chama identityHashCode): NAO investigado; so o
   efeito foi medido. Os Enum.hashCode() e Set.of citados no pedido NAO foram a causa dominante: o enum so custou
   1 (carga) e Set.of 1 (junto da carga de classe); o grosso e carga de classe nova e concatenacao indy nova.

3. O QUE MUDOU (so a forma do pre-simulacao; a logica, a flag, a falha alta e o cabecalho '#' ficam)
   MVPComparison.java / ByzantineSweep.java:
   - CliArgs.java REMOVIDA (era classe nova no caminho). A leitura estrita virou MVPComparison.parseArgs e
     ByzantineSweep.parseArgs: mesmo HashMap<String,String> e mesmas operacoes de String da baseline; chaves
     conhecidas em String[] (nao Set.of, nao TreeSet fora do ramo de erro). As duas copias (uma por classe, para
     nao carregar MVPComparison a partir do sweep) sao conferidas por teste.
   - NetworkSizing.Semantics (enum) deixa de ser usado no fluxo: a flag e lida por isNodesMode(String) -> boolean
     (== nodesMode). O enum e o record Decomposition PERMANECEM em NetworkSizing (API e testes: NetworkSizingTest,
     NetworkSizingDiagnostico); sao carregados so quando --n-semantics=nodes e pedido (NetworkSizing.nodes()).
   - Decomposicao legacy: mvpSizing(false,n) = as expressoes ORIGINAIS (int)(n*0.3) / (int)(n*0.7);
     sweepSizing(false,n) = as ORIGINAIS max(1,round(0,3n)) / max(1,n). Iguais a NetworkSizing.mvpLegacy/sweepLegacy
     (teste n=2..400, ambas as semanticas).
   - Todo texto novo do pre-simulacao e montado com StringBuilder (sem concatenacao '+' nova, que liga indy):
     describeSizing(String,boolean,int), cabecalho '#' do CSV, 'Network sizing', 'Network built'. Mesmo texto de
     antes (teste compara com NetworkSizing...describe()).
   - Unica mudanca visivel na saida padrao: a linha 'Args: n-semantics=legacy, validators=...' voltou a ser a
     linha ORIGINAL 'Args: validators=...' e o rotulo saiu para a linha seguinte 'n-semantics=legacy'.
   - Trecho que roda DEPOIS do inicio da simulacao (printBuiltNetwork, entre trials) tambem ficou sem '+'/enum.
   Nenhuma mudanca em src/main/java/jabs/{consensus,network,scenario,...}.

4. RESULTADO COM A JVM PADRAO (sem -XX:hashCode)  -  src/test/resources/nao-regressao/n1-conversao-n/
   Comando (um caso e uma semente por vez, 4 JVMs do script = base-a, base-b, atual-a, atual-b; --timeout 900):
     java -cp target/test-classes:target/classes jabs.network.p2p.EvidenciaN1Rodar /tmp/opencode/base-325f4e3 \
          /tmp/opencode/n1-nr-r2 saida-r2 comandos-r2.txt hashes-r2.txt
   que chama, por caso/semente: python3 scripts/jabs/nao_regressao.py --base /tmp/opencode/base-325f4e3 --atual .
   --casos <C> --semente <S> --timeout 900 --saida ...   (comandos exatos: comandos-r2.txt; saidas: saida-r2/*.txt;
   hashes sha256 normalizados de cada CSV: hashes-r2.txt).
   caso                  s1          s2          s3          linhas de dados comparadas (s1/s2/s3)
   ARQ:7:60              IDENTICO    IDENTICO    IDENTICO    38 / 51 / 44
   MCO2:10:60            IDENTICO    IDENTICO    IDENTICO    1 / 1 / 1      [vazio (0 blocos): nao discrimina]
   MCO2:50:600           IDENTICO    IDENTICO    IDENTICO    15 / 15 / 29
   AMBIFY:10:60          IDENTICO    IDENTICO    IDENTICO    10 / 6 / 10
   AMBIFY:12:60          IDENTICO    IDENTICO    IDENTICO    10 / 10 / 7
   OFFSET:40:20000       IDENTICO    IDENTICO    IDENTICO    24 / 16 / 22
   (linhas = soma das linhas de dados dos CSV comparados na chamada, como o script imprime)
   18 de 18: base x atual IDENTICO; base-a x base-b IDENTICO; atual-a x atual-b IDENTICO nas 18 (rc 0 em todas).
   (Antes da rodada 2, com a mesma JVM padrao: 12 de 18 DIVERGENTE.) Comparacao exata dos hashes: o sha256
   normalizado de cada CSV e IGUAL entre base-a e atual-a em hashes-r2.txt (ex.: AMBIFY_10_60/s1 ambify-10v-t0.csv
   04fc19d9..., MCO2_50_600/s2 59042ab9..., OFFSET_40_20000/s1 6de3026b...).
   Casos MCO2:10:60 e ARQ nao discriminam (vazio / imune); os 12 discriminantes sao MCO2:50, AMBIFY:10, AMBIFY:12,
   OFFSET:40 x 3 sementes.

5. BYZANTINESWEEP, MODO LEGACY (JVM padrao, semente 1, 1 trial, --max-byz=1 => f=0 e f=1)
   O sweep tinha o mesmo defeito por construcao (CliArgs + enum + describeSizing + Network sizing antes do laco):
   aplicado o mesmo metodo (parseArgs/isNodesMode/sweepSizing/describeSizing sem classe nova). Comando:
     java -cp target/test-classes:target/classes jabs.network.p2p.SweepR2Rodar /tmp/opencode/base-325f4e3 \
          /tmp/opencode/n1-sweep-r2 src/test/resources/nao-regressao/n1-conversao-n \
          POS:10:60:SILENT POSA:10:60:SILENT POW:40:20000:WITHHOLD
   Resultado (sweep-r2.txt: comandos, rc, tempo e sha256 de sweep.csv e de cada CSV por bloco em tmp/):
     POS  (Casper)   n=10 60 s SILENT    base x atual IDENTICO | base x base IDENTICO | atual x atual IDENTICO
     POSA (Parlia)   n=10 60 s SILENT    IDENTICO | IDENTICO | IDENTICO
     POW  (Nakamoto) n=40 20000 s WITHHOLD  IDENTICO | IDENTICO | IDENTICO
   Escolha dos casos: o sweep suporta VOTING, POS, POW, POSA, DAGSPER; so POS, POSA e POW usam as redes sensiveis
   (VOTING = PBFT, imune como ARQ; DAGSPER nao existe na baseline). POW com SILENT: a baseline e o atual RECUSAM
   (NakamotoConsensus so implementa WITHHOLD e EQUIVOCATION; rc=1 nos 4 lados, mesmo hash) - a primeira tentativa
   ficou INCONCLUSIVO por isso e foi refeita com WITHHOLD (ataque valido). O sweep agregado (sweep.csv) compara
   as linhas de dados; as linhas '#' novas nao entram no hash.
   Limite: f=0 e f=1 apenas, 1 trial, um n por tipo; nao cobre --n-semantics=nodes (esse caminho muda o
   n de proposito e nao tem baseline).
   Bateria extra MVPComparison multi-protocolo / multi-n / multi-trial, uma JVM por lado:
     --protocols=AMBIFY,MCO2,OFFSET --validators=10,12 --trials=2 --duration=60 --durations=OFFSET:20000 --seed=1
   base x atual: os 13 CSV (por bloco e mvp-results.csv) com sha256 IGUAL (a ordem multipla de protocolos num
   mesmo processo tambem se preservou). Sem arquivo de evidencia proprio alem desta linha (rodada de verificacao).

6. TESTE DE REGRESSAO QUE PROTEGE O REQUISITO
   src/test/java/jabs/example/HashIdentidadePreSimulacaoTest.java (3 testes; suite 344, eram 341):
   - preSimulacaoLegacyConsomeOMesmoNumeroDeHashesQueABaseline: JVMs filhas rodam SondaHash ref e SondaPreSim
     {base, novo, antigo} x {MCO2, AMBIFY, OFFSET, ARQ}; exige total(novo) == total(base) e, como CONTROLE, total(antigo)
     > total(base) (a sonda tem de enxergar o defeito antigo, senao o teste nada prova).
   - reescritaSemClasseNovaPreservaOComportamentoLegacyENodes: describeSizing/mvpSizing/sweepSizing == texto e
     numeros de NetworkSizing (n=2..400, legacy e nodes); legacy aceita n pequeno como antes; nodes exige n >= 2.
   - semanticaDoNEFlagsIgualAoEnumENosDoisCaminhos: isNodesMode == fromFlag(...)==NODES; valores invalidos falham;
     parseArgs das duas classes se comportam igual.
   LIMITES (honestos): (a) o hash padrao e thread-local xorshift: o teste so afirma igualdade ENTRE JVMs filhas
   do mesmo teste, nunca um numero absoluto; com -XX:hashCode constante a referencia teria repeticoes e o teste
   falha (assert de distincao) em vez de passar. (b) A coluna 'base' e uma REPLICA do original; a equivalencia
   com o original e da bateria de ponta a ponta (secao 4), nao do teste. (c) Mede o pre-simulacao de main, nao a
   simulacao nem o que as classes dos cenarios consomem depois. (d) Contagem != identidade de efeito: uma troca
   de ordem sem mudar a contagem passaria; por isso a prova de aceite e a saida IDENTICA, nao a contagem.
   (e) O teste spawna 'java' do PATH e leva ~3 s.

7. VERIFICACOES
   mvn -o test: Tests run: 344, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS (341 anteriores + 3 novos;
     NetworkSizingTest foi adaptado a parseArgs/knownFlags no lugar de CliArgs.parse/KNOWN_FLAGS, mesmas asserções).
   python3 scripts/lint/lint-jabs.py src/main/java: 14 bloqueantes (todos JB-009 catch vazio, pre-existentes,
     nenhum em arquivo tocado pela N1), 41 avisos; o conjunto de bloqueantes nao mudou.
   Sem git stash, sem pipes; nenhum mvn em /tmp/opencode/base-325f4e3; uma JVM de simulacao por vez nas medicoes
   (as 4 do nao_regressao.py rodam em paralelo por desenho do script, como na rodada 1).

8. O QUE NAO SE PROVA / RISCOS
   - Identidade da saida foi medida em 18 casos x 3 sementes (+3 do sweep, +1 bateria multi). Outros protocolos
     (HotStuff, Tendermint, TREE, EARTH, DAGSPER), outros n/duracoes e o caminho --n-semantics=nodes nao tem
     baseline; TREE usa o mesmo mvpSizing/describeSizing de MCO2 (mesmo ramo de codigo), nao rodado contra a base.
   - O 'zero chamadas liquidas' foi medido por replica da base (SondaPreSim) e por marcas de saida com a base
     real (SondaLinhas, MCO2:10:60); nao instrumentei a base real em todos os protocolos.
   - A sensibilidade da baseline a contagem de identityHashCode CONTINUA (diagnostico-hash.txt, 7c): qualquer
     codigo futuro antes da simulacao pode desloca-la de novo; o teste novo e a protecao. Decisao humana sobre
     declarar essa sensibilidade (ADR) segue pendente, como na rodada 1.
   - Dependencia do JDK: o custo de carregar classe/ligar indy e comportamento do JDK 25 desta maquina (build
     do pom mira 17); em outro JDK os numeros absolutos podem mudar, a regra (nada novo no caminho legacy) nao.
   - Parlia/Casper ainda dependem da ordem de iteracao de HashMap com chave de identidade (JB-012): fora do escopo.

ARQUIVOS DA RODADA 2
   src/main/java/jabs/example/MVPComparison.java, ByzantineSweep.java (alterados); CliArgs.java (removida)
   src/test/java/jabs/example/HashIdentidadePreSimulacaoTest.java (novo teste), NetworkSizingTest.java (adaptado)
   src/test/java/jabs/example/SondaPreSim.java, SondaMicro.java, SondaLinhas.java (diagnostico, nao JUnit)
   src/test/java/jabs/network/p2p/SweepR2Rodar.java (driver do sweep), EvidenciaN1Rodar.java (aceita pasta/arquivos)
   src/test/resources/nao-regressao/n1-conversao-n/: saida-r2/ (18), comandos-r2.txt, hashes-r2.txt, sweep-r2.txt,
   sonda-r2.txt, micro-r2.txt, README.txt (esta secao)


================================================================================================================
Rodada 3: parser legado restaurado   (loop2-jabs-codigo, gap unico: criterio 7, flags nao-n)
================================================================================================================
Esta secao e do builder; NAO e aprovacao. Veredito do criterio 7 e do critic. (Orcamento: 3 de 3 rodadas.)
As secoes anteriores NAO foram editadas. Onde dizem que flag desconhecida / argumento sem '=' / flag repetida
"FALHAM ALTO" (rodada 1, secao 2 e pergunta P3; rodada 2, secao 3 e 6), isso esta SUPERADO por esta secao.

1. O GAP
   O parser estrito das rodadas 1 e 2 lancava IllegalArgumentException para flag desconhecida, argumento sem '=' e
   flag repetida em MVPComparison e ByzantineSweep. Isso quebrava invocacoes do repositorio que antes terminavam:
   validate-metrics.sh e validate-metrics.ps1 (linhas ~24-28) passam `--byzantine=0,33`, flag que o MVPComparison
   nunca leu. A falha alta exigida pela tarefa e SO a de `--n-semantics` com valor invalido.

2. COMO A BASELINE 325f4e3 TRATAVA (lido em /tmp/opencode/base-325f4e3/src/main/java/jabs/example/*.java; as duas
   classes tem o MESMO parseArgs):
       for (String arg : args)
           if (arg.startsWith("--") && arg.contains("="))
               parts = arg.substring(2).split("=", 2);
               if (parts.length == 2) map.put(parts[0], parts[1]);
   => argumento sem "--" ou sem '=' : IGNORADO em silencio; "--x=" entra com valor "" (split("=",2) da 2 partes);
      "--=x" entra com chave "" ; chave desconhecida ENTRA no mapa e nunca e lida; chave repetida: a ULTIMA vence
      (HashMap.put sobrescreve). Nenhuma excecao.

3. O QUE MUDOU (so o parseArgs de MVPComparison.java e ByzantineSweep.java; nada em jabs/{consensus,network,...})
   - Corpo do laco = o da baseline, linha a linha (mesmo HashMap<String,String>, mesmas operacoes de String).
   - Acrescimo no ramo "put": laco sobre o String[] KNOWN (ja existia, sem colecao hash); se a chave nao esta em
     KNOWN, chama avisar(StringBuilder). Acrescimo no ramo "ignorado" (sem '--' ou sem '='): idem.
   - avisar(): StringBuilder + System.err.print. SEM concatenacao '+', SEM classe nova, SEM lambda/indy, SEM
     String.format. Escreve UMA linha em stderr:
       AVISO: flag desconhecida '--byzantine' ignorada (nunca e lida por MVPComparison). Flags conhecidas: --validators ...
     Nunca no stdout, nunca no CSV (teste confere).
   - ATENCAO pedida na tarefa (contagem de identityHashCode): avisar() so e CHAMADO se houver chave desconhecida ou
     argumento malformado, caso em que a baseline nao tinha equivalente. O caminho SEM flag desconhecida executa
     as mesmas operacoes da baseline + um laco sobre String[] (sem alocacao de objeto novo, sem carga de classe):
     nenhum hash a mais. Prova: HashIdentidadePreSimulacaoTest.preSimulacaoLegacyConsomeOMesmoNumeroDeHashesQue
     ABaseline (usa o MVPComparison.parseArgs REAL) continua passando: total(novo) == total(base) (= 15 em MCO2,
     AMBIFY, OFFSET, ARQ), com o controle total(antigo) > total(base) intacto. E a saida de ponta a ponta e
     IDENTICA nas secoes 5 e 6.
     NAO medi quantos hashes o RAMO DO AVISO consome (pode ser > 0: System.err.print na 1a vez pode carregar
     classes/buffers). Ele so ocorre com flag desconhecida. Na pratica, os 3 casos com --byzantine (secao 6),
     dois deles discriminantes (AMBIFY:10, MCO2:50), deram saida IDENTICA a da baseline COM o aviso emitido.
     Isso e evidencia, nao prova de que o ramo do aviso nunca desloca a sequencia em outro cenario/JDK.
   - A unica validacao nova de flag e a de --n-semantics: isNodesMode (valor diferente de legacy|nodes, vazio ou
     com espaco -> IllegalArgumentException antes de simular e antes de gravar arquivo) e a de n em modo nodes
     (n < 2). Ambas inalteradas. Efeito colateral intencional: "--n-semantic=nodes" (erro de digitacao) e
     "--n-semantics nodes" (com espaco) deixam de abortar e rodam em modo LEGACY com AVISO no stderr, como na
     baseline. A observabilidade e o aviso; o risco de ler o rotulo errado e do chamador (ver secao 8).
   - Mensagens de excecao "Flag desconhecida/repetida/Argumento invalido" removidas.

4. TESTES (suite 346, eram 344; 0 falhas)
   NetworkSizingTest (11 testes; eram 9 + 2):
   - flagDesconhecidaOuMalFormadaTemOComportamentoDaBaselineNosDoisCaminhos (substitui ...FalhaAlto...): mapas
     esperados da baseline para "--n-semantic=nodes", "--n-semantics", "--n-semantics nodes", "n-semantics=nodes",
     "--=x", "--validatorz=4", "--byzantine=0,33", "--validators="; repetida: ultima vence (n-semantics=legacy,
     seed=2); stderr VAZIO para flags conhecidas; AVISO so para as ignoradas; --n-semantics=NODES / "" / "nodes "
     continuam falhando alto em main(); n=1 em nodes e n invalido no sweep tambem; nada gravado.
   - flagDesconhecidaNaoAbortaMasAvisaSoNoStderr (novo): main() com --n-semantic=nodes termina em legacy; AVISO no
     stderr, ausente do stdout e do CSV.
   - invocacaoDoValidateMetricsComByzantineTerminaComoSemAFlag (novo, o smoke pedido): ARQ n=7, 5 s, semente 1,
     --byzantine=0,33: termina, 1 AVISO, e os CSV (sem '#' e sem colunas hash) sao iguais aos de sem a flag.
     (A coluna BlockHashCode difere entre execucoes na mesma JVM por ser identityHashCode: o filtro e o mesmo do
     nao_regressao.py.) A igualdade com a BASELINE REAL e da secao 6, nao do teste.
   HashIdentidadePreSimulacaoTest: a asserção "chaves de um caminho nao valem no outro => lanca" virou "nao lanca,
     mapa contem a chave" (rodada 3); o resto inalterado.
   ByzantineSweepConfigTest: NAO precisou mudar (nao testava parser; verificado por grep). O pedido citava este
     arquivo; a referencia a flags estava em NetworkSizingTest.

5. NAO REGRESSAO CONTRA 325f4e3, MODO LEGACY, JVM PADRAO  (saida-r3/, comandos-r3.txt, hashes-r3.txt)
   Mesmos 6 casos x sementes 1,2,3 da rodada 2, um caso e uma semente por vez, --timeout 900:
     java -cp target/test-classes:target/classes jabs.network.p2p.EvidenciaN1Rodar /tmp/opencode/base-325f4e3 \
          /tmp/opencode/n1-nr-r3 saida-r3 comandos-r3.txt hashes-r3.txt
   que chama scripts/jabs/nao_regressao.py --base /tmp/opencode/base-325f4e3 --atual . --casos C --semente S
   --timeout 900 (comandos exatos em comandos-r3.txt). Baseline em 325f4e3, arvore limpa, sem mvn nela.
   caso                  s1          s2          s3
   ARQ:7:60              IDENTICO    IDENTICO    IDENTICO
   MCO2:10:60            IDENTICO    IDENTICO    IDENTICO      [vazio (0 blocos): nao discrimina]
   MCO2:50:600           IDENTICO    IDENTICO    IDENTICO
   AMBIFY:10:60          IDENTICO    IDENTICO    IDENTICO
   AMBIFY:12:60          IDENTICO    IDENTICO    IDENTICO
   OFFSET:40:20000       IDENTICO    IDENTICO    IDENTICO
   18 de 18: base x atual IDENTICO, base-a x base-b IDENTICO, atual-a x atual-b IDENTICO, rc 0 (cada um dos 18
   arquivos de saida-r3/ tem 3 linhas IDENTICO).

6. A INVOCACAO DO validate-metrics (--byzantine=0,33) CONTRA A BASELINE REAL  (byzantine-r3.txt)
   MVPComparison com --byzantine=0,33, base (classes de 325f4e3) x atual, uma JVM por vez, semente 1, 1 trial:
     ARQ:7:5  AMBIFY:10:60  MCO2:50:600
   rc 0 nos dois lados; sha256 normalizado de todos os CSV IGUAL entre base e atual (6 de 6). O atual emite
   exatamente UMA linha de AVISO no stderr; o stdout nao a contem; a baseline nao emitia nada. (Antes da rodada
   3 o atual abortava com IllegalArgumentException.)

7. BYZANTINESWEEP, MODO LEGACY (sweep-r3.txt; mesmo driver SweepR2Rodar, agora aceita `arquivo=NOME`)
     java -cp target/test-classes:target/classes jabs.network.p2p.SweepR2Rodar /tmp/opencode/base-325f4e3 \
          /tmp/opencode/n1-sweep-r3 src/test/resources/nao-regressao/n1-conversao-n arquivo=sweep-r3.txt \
          POS:10:60:SILENT POSA:10:60:SILENT POW:40:20000:WITHHOLD
   (semente 1, 1 trial, --max-byz=1; f = 0 e 1; as 4 JVMs por caso rodam em sequencia)
     POS  (Casper)   n=10 60 s SILENT        base x atual IDENTICO | base x base IDENTICO | atual x atual IDENTICO
     POSA (Parlia)   n=10 60 s SILENT        IDENTICO | IDENTICO | IDENTICO
     POW  (Nakamoto) n=40 20000 s WITHHOLD   IDENTICO | IDENTICO | IDENTICO
   Mesmas ressalvas da rodada 2 (f=0 e f=1, 1 trial, nao cobre --n-semantics=nodes). O sweep so foi exercido SEM
   flag desconhecida; o parser do sweep com flag desconhecida e coberto por teste de unidade (mapa e AVISO).

8. VERIFICACOES
   mvn -o test: Tests run: 346, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS.
   python3 scripts/lint/lint-jabs.py src/main/java: 224 arquivos; 14 bloqueantes (os mesmos 14, todos JB-009
     pre-existentes; nenhum em jabs/example), 41 avisos. (Nota: 224 arquivos, eram 225 na rodada 2: a CliArgs
     removida; nao e mudanca desta rodada.) Nenhum bloqueante novo; nenhum catch vazio novo.
   Sem git stash, sem pipes; nenhum mvn em /tmp/opencode/base-325f4e3; uma JVM de simulacao por vez nas medicoes
   (as 4 do nao_regressao.py rodam em paralelo por desenho do script).

9. RISCOS / O QUE NAO SE PROVA
   - Regressao de UX aceita por pedido: o erro de digitacao de --n-semantics deixa de falhar. Quem escrever
     `--n-semantic=nodes` roda em legacy e so vera o AVISO no stderr (e as linhas `n-semantics=legacy` no stdout
     e `# n_semantics=legacy` no CSV). Se isso nao for aceitavel, o caminho e um ADR/decisao humana (por exemplo,
     falhar so para chaves com distancia de edicao pequena de n-semantics); nao inventei essa regra.
   - O ramo do aviso nao teve a contagem de identityHashCode medida (secao 3). Evidencia indireta: 3 casos
     --byzantine IDENTICOS, 2 deles discriminantes.
   - Os demais pontos da secao 8 da rodada 2 (outros protocolos/n, JDK 25 vs 17, JB-012) continuam valendo.

ARQUIVOS DA RODADA 3
   src/main/java/jabs/example/MVPComparison.java, ByzantineSweep.java (parseArgs + avisar; comentarios)
   src/test/java/jabs/example/NetworkSizingTest.java, HashIdentidadePreSimulacaoTest.java (ajustados)
   src/test/java/jabs/network/p2p/SweepR2Rodar.java (argumento opcional arquivo=NOME; padrao inalterado)
   src/test/resources/nao-regressao/n1-conversao-n/: saida-r3/ (18), comandos-r3.txt, hashes-r3.txt, sweep-r3.txt,
    byzantine-r3.txt, README.txt (esta secao)

RODADA 4 (extra, escopo unico: criterio 7, linhas '#' no mvp-results.csv em modo legacy)
   ACHADO: em --n-semantics=legacy o atual escrevia 2 linhas '#' no mvp-results.csv antes do cabecalho
   (hashes-r3.txt: base comentarios=0, atual comentarios=2). A comparacao da rodada 3 ignorava linhas '#', entao o
   "18 de 18 IDENTICO" da secao 5 NAO provava igualdade byte a byte do mvp-results.csv. Para esse arquivo, as
   secoes 5 e 6 ficam SEM VALOR COMO PROVA ate a reexecucao (os CSV por trial nao tinham linhas '#' do N1).
   FEITO 1 (codigo): MVPComparison.initializeCSV escreve as linhas '#' SO quando nodesMode. Em legacy o arquivo
   comeca no cabecalho de colunas, como na baseline. O dimensionamento do legacy continua no stdout
   ("n-semantics=legacy", "Network sizing: ..."). Conferido por execucao (ARQ n=7, 5 s, semente 1, JVM padrao):
   legacy = 2 linhas (cabecalho + dado), 0 com '#'; nodes = 4 linhas (2 com '#').
   FEITO 2 (comparador): nao_regressao.py desta pasta (src/test/resources/nao-regressao/) deixa de ignorar '#':
   ler() devolve tambem as linhas '#' e comparar() as exige IGUAIS (texto bruto, mesma ordem). Normalizacoes que
   restam, e so estas: linhas em branco; colunas cujo cabecalho contem 'hash' (identityHashCode); a coluna
   EXTRA_ATUAL=-1 (T1/ADR-0006). Comentarios NAO sao normalizados. Nao executei esse script (ver NAO FEITO).
   FEITO 3 (testes): NetworkSizingTest.cabecalhoDoCsvTemLinhasComentadasComADecomposicao foi substituido por
   legacyNaoEscreveComentariosNoCsvEnodesEscreve (legacy: arquivo comeca no cabecalho, sem '#', e igual ao sem a
   flag; nodes: com '#'); flagDesconhecidaNaoAbortaMasAvisaSoNoStderr confere o dimensionamento no stdout.
   NAO FEITO (passo 3 da tarefa: reexecutar base x atual, 18 casos + sweep, regerar hashes-r3 e o README):
     - o commit 325f4e3 nao existe neste repositorio (4 commits) e /tmp/opencode/base-325f4e3 nao existe aqui;
     - nao_regressao.py e os drivers Evidencia*Rodar montam classpath com ':', usam /tmp e target/cp.txt (Linux).
   hashes-r3.txt, saida-r3/ e as secoes 5-7 NAO foram regenerados e nao refletem esta correcao. A reexecucao
   precisa do ambiente Linux da rodada 3 (base 325f4e3 em /tmp/opencode, mvn offline funcionando).
   Pendencia relacionada, FORA do escopo pedido e nao alterada: ByzantineSweep.java:107-110 tambem escreve 2 linhas
   '#' no sweep.csv em modo legacy, e a baseline nao; sweep-r3.txt tambem usou o hash que ignora '#'.
   scripts/jabs/nao_regressao.py (o que o enxame executa) NAO foi alterado (fora do escopo src/ e pom.xml); a copia
   desta pasta e que foi alterada. Sincronizar fica para quem tiver permissao.
   VERIFICACOES NESTA MAQUINA (Windows, JDK 21): mvn -o test falha no surefire:test com
   "Unable to load the mojo 'test' ... A required class is missing: org/apache/maven/plugin/surefire/
   SurefireReportParameters" (POM do maven-surefire-plugin:3.3.1 ausente no ~/.m2). Compile e testCompile passam.
   mvn test (com rede): 346 testes, 344 passam, 2 falham, ambos testes que sobem JVM filha:
     HashIdentidadePreSimulacaoTest.preSimulacaoLegacyConsome...: "Could not find or load main class
       jabs.example.SondaHash" (causa provavel: classpath montado com ':' no Windows; nao confirmei)
     P2PSetupTest.mesmaSementeMesmosVizinhos...: texto esperado != obtido da JVM filha (causa provavel: fim de linha
       CRLF do println no Windows contra '\n' esperado; nao confirmei)
   Nao verifiquei se esses 2 ja falhavam antes desta mudanca (sem baseline limpa aqui); nenhum toca em
   MVPComparison.initializeCSV. NetworkSizingTest: 11/11 passam (rodada com -Dtest=NetworkSizingTest).
   lint-jabs.py src/main/java: 224 arquivos, 14 bloqueantes (JB-009, pre-existentes), 41 avisos; igual a rodada 3.

ARQUIVOS DA RODADA 4
   src/main/java/jabs/example/MVPComparison.java (initializeCSV)
   src/test/java/jabs/example/NetworkSizingTest.java
   src/test/resources/nao-regressao/nao_regressao.py
   src/test/resources/nao-regressao/n1-conversao-n/README.txt (esta secao)
