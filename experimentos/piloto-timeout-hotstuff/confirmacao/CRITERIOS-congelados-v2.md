# Criterios congelados v2 - A3'' (celula como unidade independente)

Data: 2026-11-13. Tarefa: PILOTO-timeout-hotstuff-confirmacao-v2. Proponente: jabs-experimento.
Este arquivo foi escrito e teve o SHA-256 gravado (CRITERIOS-congelados-v2.sha256) ANTES de qualquer calculo novo sobre a cauda.
Nenhuma simulacao nova: so os dados brutos existentes (confirmacao/dados-brutos/confirmacao, 80 celulas; e, como replicacao, as
celulas ROTATING L=100 de dados-brutos/medicao e dados-brutos/verificacao/ver_m3_*). O criterio v1 (CRITERIOS-congelados.md,
SHA bf955738...) NAO e reescrito; este e um criterio de inferencia adicional, pedido pelo humano apos o gap do critic (D consecutivos
sao dependentes; IC por ordem sobre visoes empilhadas assume independencia).

## 0. Divulgacao do que ja se sabia antes de congelar

- Conhecia, pelo PARECER-TECNICO.json v1, os resultados EMPILHADOS: D_max por n, UB v1 (= maximo do grupo, e_sup = 0), que so n=64
  tem uma visao > T/2 (1,1348 s, celula s10), autocorrelacao lag1 0,07/0,03/0,06/0,60 e razoes de pares adjacentes 1,4 a 1,9.
- NAO conhecia (nao calculei) os maximos por celula, o N efetivo, nem qualquer estatistica da cauda das celulas do piloto por celula.
- Consequencia estrutural, vista pela aritmetica abaixo e nao pelo resultado: com 20 celulas por n o limite superior do metodo primario
  e o maior dos maximos de celula, que e o mesmo numero do UB v1 (secoes 1.3 e 3). Logo o metodo v2 NAO pode ser mais favoravel que o v1
  no veredito primario; so pode ser igual (UB igual) ou tornar a conclusao condicional/inconclusiva (secundario). Isto foi escolhido
  por validade (o bootstrap por celula foi rebaixado a contraste por ser anticonservador no extremo, secao 1.4), nao para passar.

## 1. Metodo PRIMARIO (celula/semente = unidade independente; estatistica de ordem sobre maximos por celula)

### 1.1 Unidade e dado
- Unidade independente: a celula (uma semente; sementes distintas e inteiros nao colidentes, conferido em checar-sementes.py).
  Visoes dentro de uma celula NAO sao tratadas como independentes.
- Por celula c: D_c(h) = Rmax_s de observador-por-altura.csv.gz, alturas h > 50 (mesmo descarte do v1); m_c = numero de visoes;
  M_c = max_h D_c(h) (maximo da celula). Grupo = (ROTATING, L=100, n); K = numero de celulas do grupo (esperado 20).
- Por que o maximo da celula: com m_c ~ 2160 visoes por celula, o quantil p99,99 por celula (nearest-rank, posto ceil(0,9999 m_c) = m_c)
  e o proprio maximo da celula. Nao existe quantil de celula mais informativo para a cauda de 1e-4.

### 1.2 O que M_c estima e o que NAO estima (justificativa)
- Sob visoes independentes dentro da celula, P(M_c <= x) = F(x)^m_c, onde F e a CDF de D. Logo o quantil p = 0,9999 de D
  (x_p) e o quantil u de M_c com u_n = 0,9999^(mbar_n), mbar_n = media dos m_c do grupo (~0,806 para m ~ 2160).
- Sob dependencia positiva (indice extremal theta <= 1), P(M_c <= x) = F(x)^(m_c*theta); o quantil u_n de M_c passa a corresponder a
  F = 0,9999^(1/theta) <= 0,9999, isto e, Q_M(u_n) e um LIMITE INFERIOR de x_p. Sem hipotese sobre theta nao existe limite superior
  valido de x_p a partir de maximos por celula (a uniao de Bonferroni da P(M_c > x_p) <= m_c*1e-4, que tambem aponta para o lado errado).
  Logo: o UB primario e valido para x_p SOMENTE sob a hipotese H-cel: "nao ha agrupamento de extremos dentro da celula (theta = 1)".
  H-cel nao e provada; o metodo secundario (secao 2) e a sensibilidade a ela. O que o primario ganha sobre o v1 e que nao exige
  independencia entre 43 mil visoes, so entre as K celulas (o que o delineamento garante) e a ausencia de agrupamento de extremos.

### 1.3 Intervalo (estatistica de ordem sobre os K maximos por celula)
- M_(1) <= ... <= M_(K) os maximos ordenados (continuos; empates tratados como no v1). Para o quantil u de M:
  P(M_(j) >= Q_u) = P(Bin(K,u) <= j-1), P(M_(i) <= Q_u) = P(Bin(K,u) >= i).
- IC95 bilateral (2,5 % por cauda): superior = M_(j) com j MENOR tal que P(Bin(K,u_n) <= j-1) >= 0,975; inferior = M_(i) com i MAIOR
  tal que P(Bin(K,u_n) <= i-1) <= 0,025. UB_prim = M_(j). Se nem j = K satisfaz (u_n^K > 0,025, i.e. K < ln(0,025)/ln(u_n)), NAO ha
  limite superior: veredito INCONCLUSIVO (sem UB).
- K minimo para haver UB: K_min = ceil(ln 0,025 / ln u_n) (~18 para u_n ~ 0,806). Com K = 20 ha UB; e j = K (o maximo dos maximos):
  P(Bin(20, 0,806) <= 18) ~ 0,92 < 0,975, entao j = 19 nao basta. Isto e conferido por assert no script e reportado.

### 1.4 Contrastes rotulados (nao decidem)
- Bootstrap por celula (reamostrar as K celulas inteiras com reposicao, B = 2000, semente 20261113; p99,99 nearest-rank do empilhado
  de cada replica; percentis 2,5 e 97,5): reportado so como contraste. Motivo de nao decidir: o percentil de um bootstrap nao pode
  exceder o maximo da amostra e e inconsistente para quantis extremos com K = 20 blocos (subestima a cauda).
- IC exato de Clopper-Pearson (95 %) para pi = P(M_c > T/2) a partir de #celulas com M_c > T/2. Reportado como descritivo.
- IC por ordem v1 (visoes empilhadas) recalculado so para confronto com o v1.

## 2. Metodo SECUNDARIO (N efetivo por autocorrelacao aplicado ao IC por ordem de visoes)

- Empilhado do grupo (todas as visoes h > 50 das K celulas), N = soma m_c. Autocorrelacao amostral de lag k pooled: rho_k =
  [ soma_celulas soma_i (x_i - mu)(x_{i+k} - mu) / soma_celulas (m_c - k) ] / var, com mu e var GLOBAIS do empilhado (so pares dentro da
  mesma celula; igual a funcao `autocorr` do v1, para ser comparavel com os numeros do critic).
- Truncamento declarado: K_tr = menor k >= 1 com rho_k < 2/sqrt(N) (primeiro lag nao significativo, ~0,0096 para N ~ 43 mil), com
  teto k_max = 200. Soma S = soma_{k=1}^{K_tr - 1} rho_k. Se k_max for atingido sem cruzar, usar a soma ate k_max e marcar
  "truncado_no_teto".
- DEFF = max(1, 1 + 2 S); N_eff = N / DEFF.
- IC por ordem com N_eff: e_sup(N_eff) pela binomial b(N_eff, 1e-4) do v1 (maior e com P(Bin <= e) <= 0,025; -1 se nem e = 0).
  Se e_sup(N_eff) = -1 (N_eff < 36887): INCONCLUSIVO (sem UB; nao ha dado independente suficiente para o p99,99). Se
  e_sup(N_eff) = 0: UB_sec = maximo do empilhado (conservador: o maximo das N visoes >= maximo de qualquer subamostra efetiva).
  (N_eff <= N < 55715, entao e_sup(N_eff) so pode ser -1 ou 0.)
- Observacao declarada: rho_k e do CORPO da distribuicao e e conservador para a cauda (no v1 as excedencias de p99 mostraram
  agrupamento bem menor que o do corpo em n=128). Por isso e tratado como sensibilidade, nao como estimador do theta de cauda.
- Sensibilidade complementar S2 (informativa, nao decide): indice extremal por runs com corrida 1: theta_hat = (#excedencias de p99
  do grupo que nao sao precedidas imediatamente por excedencia de p99 na mesma celula) / (#excedencias de p99). N_eff_cauda = N *
  theta_hat. Mesmo tratamento (< 36887 -> sem UB).
- Se N_eff estiver a menos de 5 % de 36887, o grupo e rotulado "borderline" (o ruido de rho_k ~ 1/sqrt(N) pesa); o veredito mecanico nao muda.

## 3. Teste e T exigido

- T por n = o T do piloto com m = 3 (leitura (a) do v1): 0,918 / 1,090 / 2,065 / 3,396 s para n = 4 / 16 / 64 / 128.
  (Circular na replicacao com as celulas do piloto: o T vem do D^ do piloto; rotular.)
- Veredito por n e metodo: PASSA se UB <= T/2; FALHA se UB > T/2; INCONCLUSIVO se nao ha UB (secoes 1.3 e 2).
- Aceite v2 por n = veredito do metodo PRIMARIO (pedido do humano). O secundario rotula a robustez: "PASSA robusto" (primario e secundario
  PASSA), "PASSA condicionado a dependencia" (primario PASSA e secundario INCONCLUSIVO), "FALHA" (primario FALHA).
  O aceite global v2 so e "atendido" se os quatro n passam no primario; o rotulo de robustez e reportado ao lado e NAO o altera.
- T exigido pelo metodo (descritivo, NAO proposta de timeout): T_req = 2 * UB por n e metodo; se o metodo e INCONCLUSIVO, T_req
  nao e definido (nenhum T e certificavel com estes dados), e reporta-se N_necessario = ceil(36887 * DEFF) visoes e celulas
  equivalentes (N_necessario / mbar), tambem descritivo.

## 4. O que fazer se 20 celulas nao permitirem limite superior (declarado, sem inventar)

- Primario: ha UB se K >= K_min (~18 para m ~ 2160); com K = 20 ha. Se K < K_min em algum grupo (celula faltante): INCONCLUSIVO
  naquele n, sem completar com outra amostra.
- Secundario: se N_eff < 36887, INCONCLUSIVO; nao se substitui por estimativa, nem se multiplica o UB por fator de inflacao.
- Nao se muda m, T, nivel de confianca, quantil, truncamento nem limiar depois de ver o resultado. Qualquer reanalise com outros
  parametros e POS-HOC rotulada.

## 5. Replicacao do metodo (nao misturada com a confirmacao no resultado primario)

- Conjunto R: celulas ROTATING, L=100, f=00, dos diretorios dados-brutos/medicao (med_rotating_L100_n*, 10 por n, semente_base 12345)
  e dados-brutos/verificacao (ver_m3_rotating_L100_n*, 10 por n, semente_base 22345): 20 celulas por n (80 no total), nao 40 por n.
  (40 por n so existe empilhando com a confirmacao, o que e POS-HOC.) ver_m2_* tem as mesmas sementes que ver_m3_* e fica de fora
  (duplicaria amostras); STABLE tambem fica de fora (outra politica).
- O --hs-view-timeout diferia entre medicao (60 s) e verificacao (T); o script exige timeouts_disparados_total = 0 em cada celula R
  e, se alguma tiver timeout, a exclui e declara (nao completa).
- Metodo, truncamento e T identicos aos da confirmacao. Resultado reportado em bloco separado.

## 6. Pos-hoc a rotular (nada disso entra no aceite)

1. Uniao das celulas da confirmacao e do conjunto R (K = 40 por n; unidade celula; mesmos metodos 1 e 2).
2. Bootstrap por celula, Clopper-Pearson, theta_hat de runs (secao 1.4 e 2 S2): contrastes.
3. Distribuicao dos maximos por celula e a celula do maior (diagnostico).
4. Qualquer T alternativo, qualquer outro truncamento ou nivel de confianca, e a comparacao v1 x v2.

## 7. Implementacao

- Scripts novos (nao editam os ja usados): confirmacao/scripts/analisar-confirmacao-v2.py (escrito DEPOIS deste congelamento; o SHA-256
  do script e gravado no JSON de saida). Saida: confirmacao/analise-confirmacao-v2.json.
- Comando: python3 experimentos/piloto-timeout-hotstuff/confirmacao/scripts/analisar-confirmacao-v2.py
- Conferencia do congelamento: sha256sum -c CRITERIOS-congelados-v2.sha256 (em confirmacao/).
