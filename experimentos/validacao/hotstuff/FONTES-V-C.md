# FONTES DO V-C (HotStuff): busca de números publicados e classificação

Data: 2026-11-16. Autor: `jabs-experimento` (proponente). Nada aqui foi simulado. **Regra: não invento número.**
Escala: **VERIFICADA** = li o conteúdo em fonte primária acessível e o trecho citado está no que li; **NÃO VERIFICADA** = só
vi título, resumo de buscador ou página que não carregou, ou o dado está em figura que não consegui ler.

## Fonte 1: Yin, Malkhi, Reiter, Golan-Gueta, Abraham (2019). HotStuff: BFT Consensus with Linearity and Responsiveness. PODC'19. arXiv:1803.05069 (v6, 23/07/2019)

Acesso: `ar5iv.labs.arxiv.org/html/1803.05069` (texto completo) e `arxiv.org/abs/1803.05069` (metadados). A versão do PODC'19
**não tem avaliação**; a avaliação está só na versão estendida do arXiv (campo `Comments` do arXiv).

### O que li e classifico como VERIFICADO (texto da fonte primária)

| Item | Conteúdo lido | Classe |
|---|---|---|
| Ambiente | EC2 c5.4xlarge, 16 vCPU; "latência de rede entre duas máquinas menor que 1 ms"; banda TCP máxima ~1,2 GB/s (iperf), **sem limitação de banda** | VERIFICADA |
| Cripto | secp256k1 em votos e **lista de assinaturas** no QC ("we currently still use a list of secp256k1 signatures for a QC"); threshold ficou como trabalho futuro | VERIFICADA |
| Desenho do experimento base | n=4 (f=1), carga 0/0, sem troca de visão; lotes 100, 400, 800; payload 0/0, 128/128, 1024/1024 (figuras 4 e 5) | VERIFICADA |
| Escalabilidade | payload 0/0, **lote 400**, varia n; também com latência de 5 ms ± 0,5 e 10 ms ± 1,0 (NetEm); 5 execuções por ponto (figuras 6 a 8) | VERIFICADA |
| Afirmação qualitativa | "latências comparáveis às do BFT-SMaRt **com degradação graciosa**" ao crescer n; HotStuff supera BFT-SMaRt em vazão | VERIFICADA (texto), magnitude NÃO |
| Complexidade | autenticadores por decisão: HotStuff O(n); PBFT O(n²); Tendermint/Casper O(n²) (tabela 1) | VERIFICADA |
| Medida | latência é **de ponta a ponta do cliente**; a vazão é medida no servidor | VERIFICADA |
| Implementação | HotStuff **encadeado (pipeline)**, ~4K linhas de C++; o JABS implementa o **HotStuff básico** (uma decisão por visão, 4 fases) | VERIFICADA (artigo e leitura do código do JABS) |

### O que NÃO consegui ler: todos os números de vazão e latência

Os valores de vazão (ops/s) e latência (ms) estão **só nas figuras 4 a 8**. As figuras vêm como imagem e o modelo desta
sessão não lê imagem (tentativa: `ar5iv.../assets/x4.png`, erro "this model does not support image input"). O texto
não repete nenhum número de vazão ou latência para n=4 ou n=16. **Logo, nenhum número publicado de HotStuff é VERIFICADO
nesta etapa. NÃO VERIFICADO e não usado:** qualquer valor de vazão ou latência para n=4 ou n=16 do artigo.

## Fontes 2 a 4: encontradas na busca, não lidas

| Fonte | O que obtive | Classe |
|---|---|---|
| "Performance Modeling and Analysis of Hotstuff for Blockchain Consensus" (IEEE, `ieeexplore.ieee.org/document/9922238`; a URL do ResearchGate sugere o autor Yahya Shahsavari; lista de autores e ano NÃO conferidos) | só o resumo do buscador ("modelo teórico que prevê vazão e tempo de confirmação esperado em função de nº de réplicas, latência de enlace e perda de pacotes"). A página do IEEE não devolveu conteúdo; o PDF do ResearchGate respondeu 403 | **NÃO VERIFICADA** |
| Survey "Performance evolution and optimization of the HotStuff consensus..." (Springer, `link.springer.com/article/10.1007/s00607-026-01695-0`; periódico e autores NÃO conferidos) | só o resumo do buscador; a página pediu desafio de cliente e não carregou | **NÃO VERIFICADA** |
| relab/hotstuff (DeepWiki, "Performance Analysis") | li a página: descreve **como** medir (latência em ms e vazão em comandos/s) e **não traz nenhum número**; não é fonte de resultado | VERIFICADA como documentação; **sem dado numérico** |

## Conclusão sobre a viabilidade do V-C (a confirmar pelo humano)

1. **Sem número comparável VERIFICADO.** O único artigo do protocolo guarda os números em figuras que eu não li. Sem eles,
   "mesma ordem de grandeza" **não pode ser julgada**. Declaração exigida pela skill: **não há dado comparável verificado**.
2. **Mesmo com os números, o regime não é o mesmo.** O artigo roda em LAN de EC2 com latência < 1 ms e 1,2 GB/s,
   **sem limite de banda**, e o texto do artigo atribui o comportamento em n < 32 ao custo **criptográfico** da lista de
   assinaturas do QC (frase lida: "The performance scales better than BFT-SMaRt when n<32. This is because we currently
   still use a list of secp256k1 signatures for a QC"; o sentido exato **não foi conferido** contra o PDF, pois a
   extração do ar5iv pode ter perdido uma negação). O JABS usa LAN de 100 Mbps com latência Pareto de média 20 ms
   e **não modela CPU**. Além disso, o artigo mede latência de ponta a ponta do cliente (pipeline encadeado) e o JABS mede
   `Tb` (criação do bloco à finalização) no HotStuff **básico**, sem pipeline. Uma discrepância de vazão da ordem do
   número de fases (3 a 4×) é esperada **por construção** e **não foi medida nem verificada**.
3. **O que o V-C ainda pode testar**, só como direção (pré-registrado no PREREGISTRO.md, seção 3.4): o **ordenamento**
   n=4 → n=16 (latência cresce, vazão cai), que o texto do artigo dá como "degradação graciosa" ao crescer n. É um teste
   fraco: o simulador pode passar por motivos que não são os do artigo. Não substitui o confronto numérico.
4. **Pedido ao humano:** fornecer (ou autorizar que se busque por outro meio, p. ex. PDF com figuras lidas por quem leia
   imagem) os valores de vazão e latência das figuras 4 e 6 para n=4 e n=16, **marcados como lidos de figura** (precisão de
   leitura limitada). Sem isso o V-C fica declarado **INVIÁVEL para o critério numérico** (a bar aceita).
