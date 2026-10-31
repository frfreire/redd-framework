B1-ataque-desconhecido-falha-alto: evidencia de nao regressao (copia versionada)
================================================================================

Origem: /tmp/opencode (fora do repositorio), copiada em 2026-10-30 por leitura e escrita (cp bloqueado).
Pedido por docs/adr/0006-pbft-trafego-medido.md, secao B1 ("Limite 1"). Tarefa administrativa: nenhum
codigo de producao foi alterado e nada foi recompilado nem reexecutado para gerar estes arquivos.

O QUE CADA ARQUIVO E (tamanho em bytes = o da origem; todos conferidos, ver "Conferencia")
--------------------------------------------------------------------------------
sig-antes.txt         11233 B, 80 linhas  matriz "antes": uma linha por celula (ver "Comandos")
sig-antes-b.txt       11233 B, 80 linhas  "replica" de sig-antes.txt (termo do ADR-0006; como foi gerada: nao registrado)
sig-depois2.txt       11233 B, 80 linhas  matriz "depois", instantaneo intermediario (16:28 -03)
sig-depois-final.txt  11233 B, 80 linhas  matriz "depois", instantaneo final (16:52 -03)
pbft-sig-antes.txt     2198 B, 40 linhas  assinatura do PBFT (n=4,7,10,13; sementes 1 e 42; none + 4 ataques)
pbft-sig-depois.txt       0 B             VAZIO na origem (execucao que nao gravou nada); copiado como esta
pbft-sig-depois2.txt   2198 B, 40 linhas  PBFT "depois", intermediario
pbft-sig-depois-final.txt 2198 B, 40 linhas  PBFT "depois", final
lint-antes.txt        17879 B, 140 linhas saida do lint-jabs.py: 26 bloqueantes, 43 avisos (203 arquivos)
lint-depois.txt       14865 B, 112 linhas saida do lint-jabs.py: 14 bloqueantes, 41 avisos (203 arquivos)
nr-b1/                CSV de 8 execucoes do MVPComparison (ver abaixo)
base-arq/             arq-7v-t0.csv (5708 B, 39 linhas) e base-arq.csv (121 B): ARQ n=7, 1 trial
                      (duracao e semente nao constam nos arquivos; origem da execucao nao registrada)

nr-b1/ (origem /tmp/opencode/nr-b1, 16 execucoes; aqui so as execucoes "a", base e atual, 1 CSV cada):
  ARQ-10v-60s-{base,atual}-a.csv         29 linhas, 4313 B  (origem 4342 B)
  AMBIFY-10v-60s-{base,atual}-a.csv      11 linhas, 1664 B  (origem 1675 B)
  MCO2-10v-60s-{base,atual}-a.csv         2 linhas,  559 B  (origem  561 B)  so cabecalho: 0 blocos finalizados
  OFFSET-40v-20000s-{base,atual}-a.csv   25 linhas, 3741 B  (origem 3766 B)
  ATENCAO: a origem tem fim de linha CRLF; estas copias tem LF (o Write nao grava CR e sed/cp estao
  bloqueados). O conteudo textual e o mesmo; os bytes nao. Por isso o tamanho e menor em 1 B por linha.
  Cabecalho '#' de cada CSV declara a granularidade do C_B ("bytes received by first-finalizing node per
  height"); semente do MVPComparison = 1 (trial seed 10001/40001 nos logs de origem).

COMANDOS (reconstituidos dos fontes; o registro nao guardou a linha de comando exata de cada execucao)
--------------------------------------------------------------------------------
Pre-requisito de todos: mvn -o test-compile e target/cp.txt (mvn dependency:build-classpath). Nao rodei
nenhum deles agora.

1. src/test/scripts/assinaturas.sh ARQUIVO
   Chama jabs.scenario.SignatureMatrixMain ARQUIVO (sem o 2o argumento, ou seja, protocolos padrao
   MCO2,AMBIFY,OFFSET,EARTH). Grava ARQUIVO (linhas ordenadas) e ARQUIVO.parcial (as mesmas linhas com
   "[Ns]" de tempo por celula; os .parcial NAO foram copiados). E o que gera sig-*.txt (inferencia: o
   nome do arquivo de saida usado em cada execucao nao esta registrado).
2. jabs.scenario.SignatureMatrixMain ARQUIVO [PROTOCOLOS]
   8 threads; uma JVM por celula (EthereumMinerNode.memPool e HashSet de identidade: ver Javadoc);
   timeout 30 min por celula. Matriz: {MCO2,AMBIFY} x n{10,16}, {OFFSET,EARTH} x n{24,40}, sementes {1,42},
   ataques {NONE,SILENT,WITHHOLD,EQUIVOCATION,DOUBLE_SIGN} = 4 protocolos x 2 n x 2 sementes x 5 = 80.
3. jabs.scenario.ByzantineSignatureMain PROTOCOLO SEMENTE N ATAQUE [DURACAO_NAKAMOTO_S]
   Uma celula. Imprime em stdout "SIG <proto> seed=.. n=.. <ataque> events=.. fin=.. t=.. hash=.. bft=..",
   ou "THREW <Excecao>: <mensagem>"; stderr leva elapsed_ms e o watchdog de pilha (90 s). Duracao
   Nakamoto padrao 12000 s simulados. (O Javadoc cita /tmp/opencode/sig.sh; esse arquivo nao existe mais.)
4. jabs.consensus.algorithm.PBFTSignatureMain
   Sem argumentos. Imprime em stdout 40 linhas (sementes 1 e 42; n 4,7,10,13; none + EQUIVOCATION,
   DOUBLE_SIGN, WITHHOLD, SILENT; 60 s). E o que gera pbft-sig-*.txt (o redirecionamento usado nao esta
   registrado; inferencia). Em 6 das 40 linhas (n=4 com EQUIVOCATION/WITHHOLD/SILENT) events=0 hash=7.
5. nr-b1/ e base-arq/: scripts/jabs/nao_regressao.py --base <raiz> --atual <raiz> --casos PROTO:N:DUR
   --semente 1 --timeout .. --saida <dir>. Roda base-a, base-b, atual-a, atual-b e compara os CSV. NAO
   sei qual era o diretorio "base" dessas execucoes: o ADR-0006 tambem marca isso como "nao confirmado".
   Os logs de origem mostram o MVPComparison com validators=[10]/[40], trials=1, seed=1.
6. lint-*.txt: formato de python3 scripts/lint/lint-jabs.py src/main/java (rodapes "203 arquivo(s)").
   Em que commit/arvore cada um foi rodado nao esta registrado.

CONFERENCIA (feita agora, por grep -x -F -f nos dois sentidos, wc e leitura; diff/cmp bloqueados)
--------------------------------------------------------------------------------
- sig-antes.txt x sig-antes-b.txt, sig-depois2.txt, sig-depois-final.txt: as 4 tem 80 linhas e 11233 B.
  Para cada uma das tres, 0 linhas ausentes em sig-antes.txt (grep -vxFf) e as 80 linhas de sig-antes.txt
  presentes nela (grep -cxFf = 80). As chaves "PROTO seed n ATAQUE" sao distintas, logo os conjuntos
  coincidem: 80 de 80 linhas identicas, 0 divergencias. Li as quatro tabelas por inteiro; a ordem tambem
  e a mesma (o orquestrador ordena as linhas). Nao rodei diff/cmp (bloqueados).
- pbft-sig-antes.txt x pbft-sig-depois2.txt e pbft-sig-depois-final.txt: 40 de 40 identicas, 2198 B cada.
- THREW: 16 linhas em cada um dos tres (sig-antes, sig-depois2, sig-depois-final), todas
  "UnsupportedOperationException: NakamotoConsensus does not implement attack {SILENT|DOUBLE_SIGN}
  (implemented: [WITHHOLD, EQUIVOCATION])", em OFFSET e EARTH (2 protocolos x 2 n x 2 sementes x 2 ataques).
  Linhas identicas antes e depois. Restam 64 celulas que rodam; 8 delas terminam com fin=0 hash=7
  (AMBIFY n=10 SILENT/WITHHOLD e OFFSET n=40 EQUIVOCATION/WITHHOLD, sementes 1 e 42): iguais, mas sem
  nenhum bloco finalizado, o que discrimina pouco.
- As copias foram conferidas contra a origem (nos dois sentidos, e por wc): sig-*, pbft-sig-*, lint-*,
  base-arq/base-arq.csv identicas linha a linha e em bytes. Os CSV de nr-b1/ e base-arq/arq-7v-t0.csv
  so diferem do original no fim de linha (CRLF -> LF): a diferenca de bytes e exatamente o numero de
  linhas (4342-4313=29, 1675-1664=11, 561-559=2, 3766-3741=25, 5747-5708=39), e cada linha da copia esta
  contida na linha correspondente da origem (conferencia por substring, mais fraca que -x). Dentro de
  nr-b1, na origem, base-a, base-b, atual-a e atual-b de cada caso tem o mesmo numero de linhas e os
  mesmos bytes, e as linhas de base-a estao em cada uma das outras tres.
- Lint: bloqueantes 26 -> 14 (-12, todos JB-009 "catch vazio de Exception"); avisos 43 -> 41 (-2 JB-010
  "excecao ignorada", em BitcoinNode). Isto e a saida do lint, NAO confirma o numero "13 catch removidos"
  do registro (o ADR-0006 tambem o marca "nao confirmado").

LIMITE DECLARADO (leia antes de citar este diretorio como prova)
--------------------------------------------------------------------------------
1. A matriz de 80 celulas (MCO2 e AMBIFY n=10,16; OFFSET e EARTH n=24,40; sementes 1 e 42;
   SILENT/WITHHOLD/EQUIVOCATION/DOUBLE_SIGN/NONE) compara o codigo ANTES e DEPOIS da remocao dos
   `catch (Exception ignored)` da rodada 4 do B1 e NADA MAIS. Mostra que, nas 64 celulas que rodam, essa
   remocao nao mudou a saida. Nao cobre ByzantineSweep ponta a ponta, DAGsper, outros n, outras sementes.
2. As rodadas 1 a 3 do B1 (recusa de nome de ataque, recusa de ataque nao suportado, ByzantineSweep) NAO
   tem baseline pre-B1. O commit e703e3d (2026-10-29T20:36:56Z) e posterior a aprovacao do B1 pelo critic
   (2026-10-29T19:54:43Z) e ja contem o B1 inteiro (ADR-0006, "NAO e anterior ao B1"). A coluna "antes" foi
   gerada (19:07Z) depois das rodadas 1 a 3, dentro da janela da rodada 4; nenhum arquivo registra a
   versao exata do codigo.
3. Prova interna disso: 16 das 80 linhas ja sao THREW UnsupportedOperationException no "antes"
   (Nakamoto com SILENT e DOUBLE_SIGN), idem no "depois". A recusa ja existia no "antes".
4. O comportamento antigo "Nakamoto com SILENT/DOUBLE_SIGN rodava como no honesto, sem erro" NAO foi
   medido. Vem so da leitura do Javadoc de NakamotoConsensus.java e do registro, como cita o ADR-0006
   (eu nao li esse Javadoc nesta tarefa).
5. Estes arquivos sao assinaturas (hash de eventos de finalizacao), nao series temporais; dizem "igual"
   ou "diferente", nao quanto. 1 trial por celula; sem intervalo de confianca.
6. nr-b1/ e base-arq/: origem da "base" desconhecida (nao confirmado). Nao usar como prova do B1.
7. EQUIVOCATION no Casper, Parlia e Nakamoto nao e equivocacao plena (lacunas do ADR-0006); rotule em
   qualquer resultado que as use.

NAO COPIADO (e por que)
--------------------------------------------------------------------------------
- sig-depois.txt (3596 B) e sig-depois.txt.parcial (4156 B): execucao que falhou; as linhas lidas sao
  "SEM_SAIDA exit=1". Nao e evidencia de nada; ficam so em /tmp/opencode.
- sig-antes.txt.parcial, sig-antes-b.txt.parcial, sig-depois2.txt.parcial, sig-depois-final.txt.parcial
  (11793 B cada): so as mesmas linhas com tempo por celula; nao pedidos, dispensaveis.
- nr-b1: execucoes "b" (duplicatas das "a"; conferidas iguais), logs *.log (~1,7 a 2 KB cada),
  mvp-results.csv (1 linha de resumo por execucao), diretorios de saida alem do CSV t0.
- Fora do B1 (P2P-1 e C1, ja tem copias proprias em ../p2p-1 e ../c1-casper): p2p1/, p2p1-r2/,
  p2p1-r2-teste.txt, critic-p2p/, c1-casper/, probe-off35/, base-e703e3d/ (worktree da baseline).
  Nao examinei esses diretorios em profundidade; a atribuicao vem dos nomes e dos README existentes.
- mvn.log: log de build, sem relacao com a matriz.
- Segredos: nenhum arquivo copiado contem chave, certificado, token ou credencial. Procurei por
  password/secret/token/api key/credential/bearer/BEGIN PRIVATE em sig-*, pbft-sig-*, lint-*, nr-b1/ e
  base-arq/: nenhuma ocorrencia. Nenhum arquivo foi pulado por esse motivo.
