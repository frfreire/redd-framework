# FONTES DO V-C (Tendermint): busca de números publicados e classificação

Data: 2026-11-16. Autor: `jabs-experimento` (proponente; rascunho da etapa 1). Nada aqui foi simulado. **Regra: não invento número.**
Escala (a mesma de `validacao/hotstuff/FONTES-V-C.md`): **VERIFICADA** = li o conteúdo em fonte primária acessível e o trecho citado está no que li;
**NÃO VERIFICADA** = só vi título, resumo de buscador, página que não carregou, ou o dado está em figura/tabela que não consegui ler.
Método de leitura de PDF: `scripts/aux-ler-pdf.py` (extrai o texto dos fluxos FlateDecode sem biblioteca externa; reporta a fração de caracteres
legíveis: tese de Buchman 0,91; whitepaper de Kwon 0,93). **Limite do método:** tabelas e legendas de figura saem sem separação de colunas, e
**figuras (imagens) não são lidas**: o modelo desta sessão não lê imagem.

## Fonte 1: Buchman, E. (2016). *Tendermint: Byzantine Fault Tolerance in the Age of Blockchains*. MASc thesis, University of Guelph. hdl.handle.net/10214/9769

Acesso: `atrium.lib.uoguelph.ca/bitstream/handle/10214/9769/Buchman_Ethan_201606_MAsc.pdf` (662 kB; texto extraído, 186 970 caracteres). Capítulo 9
("Performance and Fault Tolerance").

### O que li e classifico como VERIFICADO (texto da fonte primária)

| Item | Conteúdo lido | Classe |
|---|---|---|
| Ambiente | contêineres Docker em instâncias Amazon EC2 `t2.medium` (2 vCPU, 4 GB) ou `c3.8xlarge` (32 vCPU, 60 GB), **sete datacenters em cinco continentes**; cada validador conectado direto a todos os outros | VERIFICADA |
| Carga | transações de **250 bytes**; pré-carregadas em cada validador; cada experimento roda **16 blocos** | VERIFICADA |
| Configuração do controle | `TimeoutPropose` = **10 s** (artificialmente alto) e **todos os demais timeouts = 1 ms**; mempool desativado; aplicação nula em processo | VERIFICADA |
| Varredura | validadores de 2 a 64 (dobrando); blocos de 128 a 32 768 transações | VERIFICADA |
| Afirmação qualitativa (texto) | "manipula milhares de transações por segundo com latência de bloco de cerca de um segundo"; "limite de capacidade em torno de dez mil tx/s"; "milhares de tx/s em até 64 nós ... latências na maioria entre 1 e 2 s" | VERIFICADA (texto), **magnitude por (n, bloco) NÃO** |
| Falha bizantina | "propostas conflitantes": o validador bizantino assina duas propostas e envia cada uma, **com prevote e precommit**, a **metades separadas dos seus pares**; "sem voto nil"; "assina toda proposta"; N_fault = ⌊(N−1)/3⌋; testado com N de 4 a 32 | VERIFICADA |
| Resultado sob falha | "latências respeitáveis" sob falha bizantina; desempenho cai com conjuntos de validadores maiores; queda de ~50 % sob falha de parada | VERIFICADA (texto) |
| Autoria da comparação | modelou a vazão em [67] (PBFT vs HoneyBadgerBFT): PBFT "acima de 15 000 tx/s com 4 nós, decai exponencialmente com n" | VERIFICADA (texto; é afirmação sobre OUTRO artigo) |

### O que NÃO consegui ler: todos os números de vazão e latência por (n, tamanho de bloco)

Os pares (vazão, latência) das figuras 9.1 a 9.3 estão **só em figura** (a extração devolveu apenas os rótulos de eixo e de legenda: "2 vals … 64 vals",
eixos de 10² a 10⁵ tx/s e 0 a 10 s). Das Tabelas 9.1 a 9.3 (latência sob falha) a extração devolveu **dígitos concatenados sem colunas** (p.ex. "1000 873 2796
1437 1036 2627 …"); **não reconstruo colunas por palpite**. **Nenhum número de (vazão, latência) para n = 4 ou n = 16 é VERIFICADO.**

## Fonte 2: Kwon, J. (2014). *Tendermint: Consensus without Mining* (whitepaper). `tendermint.com/static/docs/tendermint.pdf`

Acesso: PDF 250 kB lido (texto extraído, 21 776 caracteres). **Não contém nenhum resultado de desempenho** (busca por vazão, latência, tx/s, "validators",
"experiment" devolve só a menção a latência de rede como suposição de modelo). Classe: **VERIFICADA como documento; sem dado numérico comparável**.

## Fonte 3: Buchman, Kwon, Milosevic (2018). *The latest gossip on BFT consensus*. arXiv:1807.04938 (v3, 22/11/2019)

Acesso: `arxiv.org/html/1807.04938` (texto completo lido). É o **artigo do algoritmo implementado** (Algoritmo 1; o próprio `TendermintCore.java` o cita).
**Não tem avaliação de desempenho**: apresenta o algoritmo e a prova (seções I a V). Classe: **VERIFICADA como definição do protocolo; sem dado numérico**.
O que ela dá e é usado pelo pré-registro: o regime de timeouts (`timeoutX(r) = initTimeoutX + r·timeoutDelta`, crescente com o round), "decide em três passos de
comunicação em condições favoráveis" e a necessidade de *gossip* (o JABS faz **difusão direta**: distância declarada no PREREGISTRO).

## Fonte 4: documentação do CometBFT (QA v0.34, `references/qa/CometBFT-QA-34.md` e `method.md`)

Acesso: `raw.githubusercontent.com/cometbft/cometbft/main/docs/references/qa/{CometBFT-QA-34,method}.md` (texto lido). **Testbed de 200 nós em Digital Ocean
(175 validadores)**, carga de 200 tx/s com 2 conexões; **só afirma** que o resultado "oscila ~20 blocos/min" e "~20 000 tx/min, até ~30 k" em **rede de 200 nós**.
Os gráficos são imagem. Classe: **VERIFICADA (texto) para a ordem de grandeza em n = 175; NÃO COMPARÁVEL** a n = 4 e n = 16 (regime, escala e software de produção
com `timeoutCommit` de 1 s, que o JABS **não** modela).

## Fonte 5: Cosmos / CometBFT `docs.cometbft.com/v0.38/introduction` e `/qa/v034`

A página devolveu só o índice ("CometBFT Documentation"), sem dado de desempenho. Classe: **NÃO VERIFICADA** (nada a classificar).

## Fonte 6: Kwon & Buchman, *Cosmos: A Network of Distributed Ledgers* (whitepaper)

**Não tentei acessar** (o título vem só da lembrança do autor deste rascunho; nenhum conteúdo lido). Classe: **NÃO VERIFICADA**, não usada.

## Conclusão sobre a viabilidade do V-C numérico (PROPOSTA; aguarda confirmação do humano)

1. **Nenhum número VERIFICADO e comparável** de vazão/latência do Tendermint para n = 4 e n = 16 em LAN. O único com (n, bloco) está em figuras que eu não leio.
2. **Mesmo com os números, o regime difere** (fato do texto lido): artigo/tese rodam **redes de longa distância (sete datacenters, cinco continentes)**,
   transações de **250 B**, **timeouts a 1 ms e `TimeoutPropose` de 10 s** (controle artificial), mempool desativado, **bloco de 128 a 32 768 tx**, **gossip**; o JABS
   roda **LAN de 100 Mbps com latência Pareto de média 20 ms**, **difusão direta** (sem gossip), `s_tx = 512 B`, `L = 100`, timeouts de produção (a definir), **sem
   `timeoutCommit`** e sem CPU. Diferenças de ordem de grandeza na vazão e na latência são **esperadas por construção** e **não medidas**.
3. **Proposta, marcada "aguarda confirmação do humano":** declarar o **V-C numérico INVIÁVEL** para o Tendermint (mesma decisão tomada para o HotStuff em 16/11,
   `PREREGISTRO.md` do HotStuff, E4) e manter **só o teste ordinal fraco**: com L = 100, `Tb(n=16) > Tb(n=4)` e `vazão(n=16) < vazão(n=4)`. **O texto lido da tese**
   dá "desempenho cai com conjuntos de validadores maiores" para o caso bizantino e mostra curvas por n em figura; **não** dá a direção por n sem falha em texto.
   Passar ou falhar **não valida magnitude**, **não é confronto numérico** e **não entra no veredito de V-A**.
4. **Se o humano quiser o confronto numérico:** precisa fornecer (ou autorizar leitura por quem leia imagem) os pares (vazão, latência) das figuras 9.1 e 9.3 da tese,
   para 4 e 16 validadores, **marcados como lidos de figura**; e decidir se aceita a **diferença de regime** acima como comparável.
