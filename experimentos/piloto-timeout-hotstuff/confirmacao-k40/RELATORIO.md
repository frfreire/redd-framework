# RELATORIO: PILOTO-timeout-hotstuff-K40, etapa 2 (execucao e analise)

Data: 2026-11-13. Proponente: `jabs-experimento`. Impacto ALTO (loop 3: humano, sem arbiter). Parecer: `PARECER-TECNICO.json`.
Este relatorio nao propoe valor final de `--hs-view-timeout`.

## Execucao
- Pre-registro `06c67b5`, SHA-256 `f3896e4f...` conferido inalterado (antes e depois). `hash-preregistro.py conferir --artefatos`: exit 0, 19 artefatos OK.
- Commit construido: `06c67b51d93edcc08d9fe90432a3ed9498a8f2c9`. Codigo: `HEAD:src` = `9f305dd:src` = `38820851...`, `pom.xml` `32f934e3...`, observador `4024e15b...`. Build por `git archive` (arvore de trabalho nao usada). OpenJDK 25.0.5, Maven 3.8.7, `-Xmx2g`, sem `-XX:hashCode=2`.
- Sementes: base 52345; n=64: 116346..116385, n=128: 180346..180385. 0 colisoes. Manifesto == especificacao (exit 0).
- 80 de 80 celulas, rc=0 nas duas JVMs, 0 reexecucoes, MD5 por bloco igual entre as JVMs nas 80, `visao_final - altura_final` em {0,1}, A1' = 0 timeouts em todas.
- Custo real: parede do lote 738,5 s (~12,3 min), CPU 5 866 s (estimado: 726 s / 5 396 s), RSS max 751 MB, 31 MB em disco. Por celula: n=64 media 44,7 s (max 48,3); n=128 media 102,1 s (max 116,1) de parede com 8 em paralelo.

## Resultado primario (decide)
| n | T (s) | T/2 (s) | N visoes | j | UB = M_(j) (s) | celulas M_c > T/2 | veredito |
|---|---|---|---|---|---|---|---|
| 64 | 2,065 | 1,0325 | 86 516 | 38 | **0,7400** | 0 de 40 (max 0,9021) | **PASSA** |
| 128 | 3,396 | 1,698 | 86 521 | 38 | **1,1397** | 0 de 40 (max 1,2726) | **PASSA** |

Aceite global (n=64 e n=128): **atendido**. n=4 e n=16 nao foram tratados.

## Rotulos (nao alteram o veredito)
- **H-cel**: theta_hat em p99,9 (corridas; Ferro-Segers) = 1,0 / 1,0 em ambos os n, theta* = 0,857. Rotulo "NAO CONTRADITA" (descritivo, nao e prova). p99: corridas 0,990 (n=64) e 0,995 (n=128); Ferro-Segers 1,0 e 0,995. Sensibilidade: o UB passa mesmo com theta=0,5 (j=40): 0,9021 s (n=64) e 1,2726 s (n=128).
- **Secundario N_eff**: n=64 DEFF 1,694, N_eff 51 066 (>= 36 887, e_sup 0), UB_sec 0,9021 s, PASSA, "PASSA robusto". n=128 DEFF 16,33, N_eff 5 299, **INCONCLUSIVO**, "PASSA condicionado a dependencia" (precisaria de ~602 mil visoes, ~279 celulas).
- **T que o metodo exigiria (2*UB, circular, nao e recomendacao)**: primario n=64 1,480 s, n=128 2,279 s (0,717 e 0,671 do T do piloto). Secundario n=64 1,804 s; n=128 indefinido. Ambos passam com o T do piloto, entao a regra de T_req so seria obrigatoria numa FALHA.

## Complementar (POS-HOC, nao decide)
Empilhado K=40 + confirmacao K=20 (K=60, j=55): n=64 UB 0,7400 s (1 celula > T/2, a s10 antiga com 1,1348 s), n=128 UB 1,1394 s. K=80 com R: 0,7243 s e 1,1394 s. Todos PASSA.

## Desvios (detalhe no parecer)
D1 portao V-A/B/C sem registro escrito da dispensa em `.ia/` ou ADR (gate segue fechado); D2 `--simulador-commit` com o hash literal e `$K`/`$P` expandidos a mao; D3 script auxiliar `aux-esperar.py` (so dorme e conta pastas, nao hasheado, nao le dados); D4 stdout da analise redirecionado; D5 mudancas nao commitadas de outro agente em `src/` excluidas do build; D6 horarios obtidos do script auxiliar. Nenhuma celula falhou, foi substituida ou excluida; nenhum parametro da analise mudou.

## Ressalvas principais
H-cel nao provada; T circular com o piloto (m=3 nao validado; T menor nao testado); K=40 foi escolhido apos a falha por um outlier em K=20; este n=64 nao reproduziu o outlier (maximo 0,9021 s), o que nao exclui outliers raros (1 em 60 celulas no empilhado pos-hoc); a previsao do pre-registro para N_eff em n=64 (53,7 a 55,7 mil) errou (51 066).
