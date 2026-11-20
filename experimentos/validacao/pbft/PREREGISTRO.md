# PRÉ-REGISTRO (hash NÃO gravado): V-ABC-pbft (só V-A; V-B e V-C fora, com motivo)

Data: 2026-11-22 (revisão final do rascunho de 2026-11-21). Tarefa: `V-ABC-pbft` (sucede a "Conferência do PBFT", seção 10 do pré-registro do Tendermint). Autor: `jabs-experimento` (**não aprova o próprio resultado**).
Impacto: **ALTO** (portão de validação): o loop 3 vai ao humano, sem arbiter.

**ESTADO: PRONTO PARA CONGELAR; o hash AINDA NÃO foi gravado.** As decisões da seção 9 foram aceitas pelo humano em 22/11/2026 ("aceitar todas"). Falta: o humano ler e commitar este arquivo, autorizar `hash-preregistro-pbft.py gravar` (único e irrevogável) e autorizar a etapa 2. **Nenhuma célula de portão foi rodada.** O que foi executado e NÃO gera dado: leitura de código, sondas exploratórias (seção 0), build do pino, dois testes de fumaça do instrumento (seção 0) e o autoteste da análise sobre dado sintético.

## 0. Divulgação: o que eu já vi antes de fixar a regra (este pré-registro NÃO é cego)

1. **Sondas ANTES da T6 (21/11/2026)** mostraram 5 a 10 blocos decididos por altura; **foi corrigido pela T6** (PR #6, critic APROVADO). Esses números não valem mais e não entram em nada.
2. **Sondas DEPOIS da T6 (21/11/2026)**, semente 1, `PBFTLANScenario`, 60 s, primário rotativo, alturas 3 a hmax−3 (fora do repositório): pacotes por altura 27,0 (n = 4), 90,0 (7), 189,0 (10), 492,5 (16); bytes de consenso 1 038, 3 588, 7 650, 20 205. **Batem EXATAMENTE com `(n−1)(2n+1)` e `(n−1)·O + 2n(n−1)(H+O)` em n = 4, 7 e 10; em n = 16 deu −0,5 % nos dois, sem que eu saiba explicar.**
3. **Teste de fumaça do instrumento (22/11/2026), NÃO é dado do estudo:** 2 células fora do plano (n = 4 e n = 7, sementes 999 004 e 999 007, 60 s, em diretório temporário, com o build do pino): as duas JVMs deram CSV por bloco com o mesmo MD5; pacotes por altura 27,0 e 90,0; bytes de consenso 1 038 e 3 588 (exatamente a fórmula). **Vi esses valores antes de congelar.**
4. **Conheço o V-ABC do Tendermint (resultado de 22/11/2026): a fidelidade a uma fórmula "de livro" falhou a tolerância de 0,1 % em 4 de 5 n (erro máximo 0,67 % em n = 31), e a causa que levantei, pós-hoc e NÃO verificada, é um nó que decide pelos 2f+1 precommits antes de emitir o seu.** Por isso a tolerância deste V-A é **1 %**, fixada **depois** de ver o desvio do Tendermint; isso está declarado aqui e **não é cego**. O PBFT pode ter desvios semelhantes ou não.
5. **Conheço o V-ABC do HotStuff e do Tendermint.** Isso orienta a minha expectativa, **não o critério**.

## 1. O que se quer validar e a granularidade

**Origem do requisito:** `.experimento/PROTOCOLO-EXPERIMENTAL.md` define P4a (HotStuff) e P4b (Tendermint); **não existe pré-condição P4 do PBFT** lá. O pedido vem da seção 10 do pré-registro do Tendermint ("bloco V-A do PBFT: O(n²) agregado; por nó O(n)"). O humano decidiu em 22/11/2026 que este V-ABC **existe**.

| Granularidade | Medida | Fonte | Esperado |
|---|---|---|---|
| **AGREGADO DE REDE por altura decidida** (decide) | `rede_msgs` (pacotes), `rede_consenso`, `rede_total` | `ObservadorPbft` (`NetworkHeightLedger`, soma sobre os n nós receptores, sem corte local, nunca autoentrega) e `mvp-results-cb.csv` | `rede_msgs` e `rede_consenso`: **O(n²)**; `rede_total`: **O(n)** (a carga do bloco, enviada pelo primário, domina) |
| **POR NÓ** (só rótulo) | pacotes e bytes de consenso por nó e por altura, **líder** × **validador** | `obs/por-no-por-altura.csv.gz` | líder e validador **O(n)** em pacotes e bytes de consenso (previsão por código; **não decide**) |

- **Uma decisão por altura** (T6): "por altura decidida" é uma unidade correta. **Tt NÃO é medido** (`-1` no CSV agregado); nenhum critério usa Tt. **A autoentrega (D2) não entra** na soma da rede (regra 6). **Cb do primeiro nó não decide.**

## 2. Instrumento: commit, build, JVM

- **PINO: o MESMO do V-ABC-tendermint:** `COMMIT_PINADO = 2980d2695436ff4efea0138275aaf04a796ff6ff` (`src/` tree `326bf654091f16afa49323d2e88956fa4c12bf15`, `pom.xml` blob `32f934e332097d6bfed46830ff03132c36763670`), que já contém o PBFT puro, a T6 e a política de liderança. `preparar-pbft.py` recusa outro código. SHA-256 de `harness/ObservadorPbft.java` (LF, = blob do git) na tabela de artefatos.
- **Comando por célula:** `java -Xmx2g -XX:+UnlockExperimentalVMOptions -XX:hashCode=2 -cp <build do pino> jabs.example.MVPComparison --protocols=PBFT --validators=<n> --trials=1 --duration=300 --seed=<semente − n·1000> --output=<dir> --csv-out=<dir>/mvp-results.csv --pbft-max-future-horizon=1000 --pbft-leader-policy=STABLE`, e a segunda JVM `ObservadorPbft --run-seed=<semente> ...` com os mesmos flags. **`-XX:hashCode=2` obrigatório nas duas.** Teto 1000 **decidido** (ADR-0015); `STABLE` = modo do estudo (ADR-0010 PI-14).
- **Ambiente de execução (declarado, difere do V-ABC do HotStuff):** Windows 11, Java **21.0.8** (Oracle HotSpot), Maven **3.9.11**; o do HotStuff foi Linux, OpenJDK 25.0.5, Maven 3.8.7. O alvo do pom é Java 17. Equivalência medida em **uma célula de HotStuff** e, depois, **verificada no Tendermint** (35 de 35 células com MD5 igual entre as duas JVMs; 4 células de determinismo idênticas). **Para o PBFT não há comparação com outra plataforma**: só a igualdade entre as duas JVMs nesta máquina. `max_rss_mb` = −1 (não medido no Windows).
- **Há segunda JVM** (`ObservadorPbft`): a integridade pode conferir o MD5 do CSV por bloco entre as duas, **só em V-A**.
- **Fixtures herdadas do código (não são parâmetros do estudo):** PREPARE/COMMIT = hash 32 B + overhead 10 B = **42 B**; PRE_PREPARE = tamanho do bloco + 10 B. **O tamanho do bloco NÃO é parâmetro**: é amostrado de `BITCOIN_BLOCK_SIZE_2020` (`TODO: Size of PBFT Blocks` no código); a contagem de transações (20 a 79) não altera o tamanho. **Consequência:** `rede_total` tem variância alta e **não decide**; pacotes e bytes de consenso não dependem do tamanho do bloco.

## 3. Critérios (congelados com o hash)

### 3.1 Regime e carga
LAN simulada do JABS (`LAN100MNetworkStats`), malha completa, `PBFTLANScenario`, **líder estável** (nó 0 propõe toda altura), sem ataque, sem view change, 1 trial por célula, duração **300 s**. `ROTATING` **não é rodado**.

### 3.2 V-A, agregado de rede (decide)
**Níveis:** n ∈ {4, 7, 10, 16, 31}; 5 sementes por n ⇒ **25 células**. n mínimo = 4 (o código recusa n < 4).
**Previsão pela fórmula (por altura decidida; H = 32, O = 10):** pacotes `(n−1)(2n+1)`: 27, 90, 189, 495, 1 890; bytes de consenso `(n−1)·O + 2n(n−1)·(H+O)`: 1 038, 3 588, 7 650, 20 310, 78 420 (n = 4, 7, 10, 16, 31). `rede_total` não tem previsão (bloco amostrado).
- **I (integridade, por célula):** rc = 0 nas duas JVMs; blocos > 0; MD5 do CSV por bloco (sem `BlockHashCode`) igual entre as JVMs; **alturas de regime ≥ 50** e iguais ao número de linhas de `pacotes-por-altura`; bytes do observador = `CbRede_avg_B` e `CbRedeConsenso_avg_B` do `MVPComparison` (±0,01); `SemAltura_B` = 0 (cb e observador); no máximo **1 finalização por nó por altura**; **0 PRE_PREPARE rejeitados** por não-primário; **0 votos descartados** além do teto; `Tt_avg = -1`. Célula não íntegra **não é excluída**: o veredito vira INCONCLUSIVO (instrumento).
- **F (fidelidade à fórmula):** `|medido − fórmula| / fórmula ≤ 1 %` em toda célula íntegra, em `rede_msgs` e `rede_consenso`. **Tolerância decidida pelo humano em 22/11/2026, depois de ver o desvio do Tendermint** (seção 0, item 4).
- **E (classe):** `b` pela OLS de ln(métrica) em ln(n) sobre as médias por n: b < 0,5 O(1); 0,5 ≤ b < 1,5 O(n); 1,5 ≤ b < 2,5 O(n²); b ≥ 2,5 "> O(n²)".
- **A1 (decide):** `rede_msgs` com 1,5 ≤ b < 2,5 **e** F. **A2 (decide):** `rede_consenso` com 1,5 ≤ b < 2,5 **e** F. `rede_total` e tudo por nó: **só rótulo**.
- **Veredito mecânico** (`analisar-va-pbft.py`): INCONCLUSIVO (instrumento) = célula faltante, extra ou não íntegra, ou n sem célula íntegra. INCONSISTENTE = A1 ou A2 falha. CONSISTENTE = nenhum dos anteriores. **Não é aprovação**: o loop 3 (humano) decide.
- **Réplica:** reportam-se média, mínimo, máximo e desvio entre as 5 sementes; sem IC com precisão fingida.
- **O que NÃO se afirma:** o V-A mede a **classe** e a fidelidade à fórmula **do código**; **não** diz que o modelo reproduz o PBFT do artigo (sem view change, sem checkpoint, sem assinatura individual modelada, bloco amostrado de distribuição Bitcoin). `validar-complexidade.py` (regra S) **não é usado**.

### 3.3 V-B (limiar bizantino): **FORA**
O PBFT puro não lê ataque no `MVPComparison`. Mesmo com ataque, o `STABLE` sem view change trava se o nó 0 for bizantino (resultado bimodal medido: n = 10, f = 3, sementes 1 a 10: as sementes 2, 6 e 7 dão 0 blocos; as outras 7, 51 a 61). Abrir V-B exige tarefa própria **e** a decisão do PI-14, mais novo pré-registro.

### 3.4 V-C (confronto com publicado): **INVIÁVEL (decidido)**
Não há fonte VERIFICADA de vazão/latência do PBFT em LAN comparável (sem Tt, bloco não parametrizável). **Eu não busquei fontes**: o argumento é por ausência de Tt e de bloco parametrizável, **não** por revisão de literatura.

## 4. Parâmetros
Dois `--pbft-*`: **teto 1000 (decidido, ADR-0015)** e **política `STABLE` (modo do estudo, PI-14)**. Não parametrizáveis: tamanho do bloco, número de transações, tamanho de PREPARE/COMMIT, timeouts de view (não há view change).

## 5. V-B: não se aplica.

## 6. Sementes, replicações, regime
- `semente = 1 120 345 + n·1000 + replicação` (1 a 5); `MVPComparison --seed = semente − n·1000`; `ObservadorPbft --run-seed = semente`. Faixa 1 124 346 a 1 151 350. **Disjunção conferida** por `checar-sementes-pbft.py` contra 1 827 `cfg.json` (HotStuff, Tendermint, K40, piloto): **0 colisões** (as sementes das sondas, 1 e 7, e do teste de fumaça, 999 004 e 999 007, não entram).
- **Réplicas:** 5. **Duração: 300 s.** **Mínimo de 50 alturas de regime por célula** (n = 4 chega a ≈ 600; n = 16 a ≈ 180; **n = 31 não foi medido e pode ficar abaixo de 50, e então a célula é INCONCLUSIVA por regra**). Dado das sondas: ≈ 0,6 altura/s em n = 16.
- **Transiente:** a janela de regime do código (alturas 2 a hmax−2) já exclui o arranque.
- **Determinismo (verificação extra):** 2 células rodadas **duas vezes** com a mesma semente, `pva_n7_s01` e `pva_n16_s01`; saída idêntica (CSVs agregados e por bloco, pacotes por altura, por nó). **Não entram nas estatísticas.**

## 7. Custo e paralelismo
**Não medido** para n = 31. O teste de fumaça (60 s simulados, n = 4 e 7) levou ≈ 1 s por JVM; o lote roda com até 8 JVMs em paralelo. Nenhuma célula piloto de portão foi rodada.

## 8. O que depende do pino (e o que o invalida)
Qualquer mudança em `src/` depois do pino (em particular `PBFT.java`, `PBFTP2P`, `PeerBlockchainNode`, `NetworkHeightLedger`, `CbRedeMedida`, `PBFTLocalLANNetwork`) invalida este V-A. A tabela de artefatos e o hash só valem depois da gravação.

## 9. Decisões do humano em 22/11/2026 ("aceitar todas")
1. Este V-ABC **existe**; roda **depois** do Tendermint (já executado).
2. **Duração 300 s e mínimo de 50 alturas** de regime por célula, para todo n.
3. **Fidelidade à fórmula com tolerância de 1 %**, **fixada depois de ver o desvio do Tendermint** (seção 0, item 4).
4. **Política `STABLE`**; o PI-14 (o líder pode ser bizantino?) **não afeta este V-A**, só um futuro V-B.
5. **V-B e V-C fora.**
6. **Pino = o do Tendermint; sementes base 1 120 345.**
7. **Pino e commit** do pré-registro congelado: este agente **não commita** o congelamento nem roda `gravar` sem autorização.

## 10. O que NÃO foi verificado (limites)
- **Nenhuma célula de portão foi simulada.**
- **A diferença de −0,5 % em n = 16** (seção 0) não foi explicada; o teste de fumaça só cobriu n = 4 e 7.
- **Por nó:** previsão por código; só rótulo.
- **Equivalência de ambiente (Windows/Java 21 × Linux/OpenJDK 25) para o PBFT:** não medida; só o MD5 entre as duas JVMs na mesma máquina.
- **Custo e taxa de alturas em n = 31.**
- **Distâncias do PBFT real:** sem view change e sem checkpoint; sem assinaturas individuais modeladas (42 B por voto); LAN simulada; bloco amostrado de uma distribuição Bitcoin; líder estável fixo no nó 0.
- Independência entre sementes consecutivas (MersenneTwister por semente).

## 12. Etapa 2: comandos exatos (só depois do hash gravado e commitado)
Raiz do repositório = diretório de trabalho. `V=experimentos/validacao/pbft`.
```
0.  python $V/scripts/hash-preregistro-pbft.py estado                     # exit 0 = pino definido
    python $V/scripts/hash-preregistro-pbft.py conferir --artefatos        # exit 0 obrigatorio
    git status --short -- $V                                               # sem alteracao nao commitada
1.  python $V/scripts/checar-sementes-pbft.py                              # exit 0
2.  python $V/scripts/preparar-pbft.py                                     # build do pino em /tmp/opencode/val-pbft; grava $V/versao-simulador.json
3.  python $V/scripts/gerar-celulas-pbft.py gerar va --saida $V/celulas/va --simulador-commit 2980d2695436ff4efea0138275aaf04a796ff6ff
    python $V/scripts/gerar-celulas-pbft.py conferir-manifesto va $V/celulas/va/manifesto.json   # exit 0
4.  python $V/scripts/executar-lote-pbft.py $V/celulas/va --saida $V/dados-brutos/va --paralelo 8
5.  rodar de novo pva_n7_s01 e pva_n16_s01 em $V/dados-brutos/determinismo-rep e comparar com comparar-determinismo-pbft.py (exit 0)
6.  python $V/scripts/analisar-va-pbft.py $V/dados-brutos/va --saida $V/analise-va.json
```
Depois: `PARECER-TECNICO.json` (campos: `afirmacao`, `impacto` = ALTO, `requisito`, `fontesPrimarias`, `n_celulas_esperadas` = **25**, `n_celulas_obtidas`) e entrega ao loop 3. **O parecer não inclui o "porquê está certo".**

## 13. O que é pós-hoc (mistura proibida)
Resultado primário = **só as 25 células** do bloco `va`. **Pós-hoc (rotulado e fora do veredito):** qualquer fórmula escrita **depois** de ver dado do portão; outra tolerância; outro limiar de `b`; exclusão de célula; conclusão sobre n > 31; **qualquer comparação PBFT × Tendermint × HotStuff**; qualquer número publicado não VERIFICADO; as sondas e o teste de fumaça da seção 0 (**não são dado do estudo**).

---
### Artefatos (SHA-256)

Calculados em 22/11/2026 com `hash-preregistro-pbft.py listar` (SHA-256 do conteudo com fim de linha LF, independente de `core.autocrlf`). Conferir com `hash-preregistro-pbft.py conferir --artefatos`. `versao-simulador.json` NAO esta aqui: e gravado na etapa 2, com o caminho real do build.

| arquivo | sha256 |
|---|---|
| `experimentos/validacao/pbft/config/fatores-va.json` | `bfe7be784dabffb24620ef0ee718f72b09ea0f74ff4b4bb29d0d57a184cc4ea8` |
| `experimentos/validacao/pbft/celulas-esperadas.json` | `f277c151431200d1568d8aa1ec61a8b5deaadcc1f74ebab9d2bc38c6fcc13fd5` |
| `experimentos/validacao/pbft/harness/ObservadorPbft.java` | `d0638ff3dcdf908e7a9750be6993754f0800e76d6cc967757633b5fc8ee7737c` |
| `experimentos/validacao/pbft/scripts/analisar-va-pbft.py` | `29f51eee0ed67cf4fc30d1bf528ced1b17ae8ab77a19b39e0408d76f44203690` |
| `experimentos/validacao/pbft/scripts/checar-sementes-pbft.py` | `45e17d442706eece7202720c7517120fe5a488f22ad1beb08ec98e48c32c7fd8` |
| `experimentos/validacao/pbft/scripts/comparar-determinismo-pbft.py` | `63a6077392e0ca7204501c3bcff15a940497a8f33deb4b2db469020fd0cd8153` |
| `experimentos/validacao/pbft/scripts/executar-lote-pbft.py` | `5681d25d11cdbd8f1f03040942588073f8e5b0efbbc1000200b9ebeea750a549` |
| `experimentos/validacao/pbft/scripts/gerar-celulas-pbft.py` | `5e60dc8e9e8a0e4cffac57996b2bcc1543be465f021701dc9e94da8cddd03119` |
| `experimentos/validacao/pbft/scripts/hash-preregistro-pbft.py` | `9b3f93a47745541c8b3991fd1a189d60dc6e555a6d4513bbdec02b8cab277df8` |
| `experimentos/validacao/pbft/scripts/preparar-pbft.py` | `ae59cb34fe61f484f2d5cef28154ff04b3d80b4dce69f5cb4dbf9859f982a645` |
| `experimentos/validacao/pbft/scripts/rodar_celula.py` | `cdbc738ac6608f30013fb14237549555f25396e875683bc0ea753cf957ca1404` |
