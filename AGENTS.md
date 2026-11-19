# REDD+ em blockchain: enxame de especialistas (Fabric + JABS)

Projeto de créditos de carbono REDD+ sobre blockchain, em duas trilhas que se alimentam:

- **Aplicação** (Hyperledger Fabric 2.5): chaincode Java, rede multi-organização, API.
- **Simulador** (JABS, Java): compara protocolos de consenso para o registro dos créditos, com tráfego medido e experimentos reprodutíveis.
- **Ponte**: calibra o simulador com medições da aplicação e traz requisitos REDD+ para as métricas.

É construído por um **enxame de agentes com Gauntlet Loop** (builder -> critic -> arbiter). Topologia em `enxame.config.json`; bars e regras em `gauntlet-loop.config.json`; agentes em `.opencode/agents/`; skills em `.agents/skills/`.

## Regras que valem para todos

1. **Código que decide validade de crédito (`chaincode/`) ou gera dado de pesquisa (`src/main/java/jabs`) nunca é aprovado sem o critic.** Quem escreve não aprova.
2. **Contexto limpo.** O critic recebe especificação + código, nunca a justificativa do builder. O arbiter recebe especificação + trecho + número do critério, nunca vereditos.
3. **Maior gap único.** Cada reenvio ao builder aponta um só gap (o de menor ordem na bar).
4. **Orçamento de 3 rodadas** por tarefa. O contador é do `scripts/enxame/registrar-rodada.py` (código de saída 20 = parar e pedir decisão humana), não da memória do modelo.
5. **Só modelos Claude (família Anthropic); a independência vem de contexto limpo e de tier distinto.** Critic e arbiter recebem sempre contexto limpo, e o arbiter roda em tier diferente do builder. Sonnet (`claude-sonnet-5-5`) = orquestrador, domínio, builders, critics, experimento e ponte; Opus (`claude-opus-5-5`, fallback `claude-sonnet-5-5`) só no arbiter; Haiku (`claude-haiku-5-5`) em `fabric-rede` e `fabric-api` (workers validados por build, teste e endosso). Fonte: `enxame.config.json` (campo `modelo`) e `gauntlet-loop.config.json` (`modelosPorPapel`). `validar-config.py` falha se algum modelo não for Anthropic, se o arbiter compartilhar tier com o builder ou se critic/arbiter não declararem `contextoLimpo`.
6. **Medido, não estimado.** Número de simulação só vale se veio de pacotes que passaram pela rede simulada, com semente, versão do simulador e granularidade declaradas.
7. **Sem segredo em lugar nenhum.** Chaves, certificados e `.env` ficam fora do git, do contexto e da memória. `memoria.py` (e o shim `memoria.sh`) recusa padrões de segredo.
8. **Lacuna normativa vai para o humano.** Prazos, limiares e ajuste correspondente (Art. 6) não se inventam: `a definir com o humano` + ADR.

## Quem faz o quê

| Agente | Papel | Escreve em | Modelo |
|---|---|---|---|
| `redd-orquestrador` | coordena, registra, memória | `.ia/`, `docs/` | claude-sonnet-5-5 |
| `redd-dominio` | especificação REDD+/MRV | `especificacoes/`, `docs/adr/` | claude-sonnet-5-5 |
| `fabric-builder` | chaincode (loop 1) | `chaincode/` | claude-sonnet-5-5 |
| `gauntlet-critic-chaincode` | revisa chaincode | nada | claude-sonnet-5-5 (contexto limpo) |
| `fabric-rede` | rede e lifecycle | `rede-fabric/`, `scripts/rede/` | claude-haiku-5-5 |
| `fabric-api` | API REST | `api-gateway/` | claude-haiku-5-5 |
| `jabs-builder` | simulador (loop 2) | `src/`, `pom.xml` | claude-sonnet-5-5 |
| `gauntlet-critic-jabs` | revisa código e resultados (loops 2 e 3) | nada | claude-sonnet-5-5 (contexto limpo) |
| `jabs-experimento` | baterias e análise (proponente do loop 3) | `experimentos/` | claude-sonnet-5-5 |
| `redd-ponte` | calibração Fabric<->JABS | `experimentos/calibracao/`, `especificacoes/calibracao/` | claude-sonnet-5-5 |
| `gauntlet-arbiter` | terceiro voto | nada | claude-opus-5-5 |

Os escopos de escrita são aplicados por `permission.edit` em cada agente, não só por convenção.

## Fases

| Fase | Paralelo | Condição |
|---|---|---|
| 0 | especificação de domínio, esqueleto da rede, auditoria do JABS | nenhuma |
| 1 | loop 1 (chaincode) e loop 2 (JABS) | `especificacoes/dominio-redd.md` com INV-xx |
| 2 | API e experimentos | loop 1 aprovado + chaincode instalado; loop 2 aprovado + V-A/B/C |
| 3 | ponte e loop 3 sobre cada resultado | fase 2 |

Paralelismo real = **Task tool do OpenCode**, várias chamadas na mesma mensagem. Builder, critic e arbiter do mesmo loop são sequenciais.

## Três loops

| Loop | Disputa sobre | Reenvio | Divergência |
|---|---|---|---|
| `loop1-chaincode` | código do contrato (10 critérios) | sim, 3 rodadas | arbiter decide gravidade |
| `loop2-jabs-codigo` | código do simulador (8 critérios) | sim, 3 rodadas | arbiter decide gravidade |
| `loop3-resultado-decisao` | resultado ou ADR (5 critérios) | não | por impacto: BAIXO sem critic; MEDIO arbiter; ALTO/CRITICO humano |

## Comandos

| Comando | Faz |
|---|---|
| `/enxame-status` | valida config, mostra loops e gates |
| `/fase0` | especificação + auditoria + rede, em paralelo |
| `/gauntlet-chaincode <tarefa>` | um ciclo do loop 1 |
| `/gauntlet-jabs <tarefa>` | um ciclo do loop 2 |
| `/validar-resultado <afirmação>` | loop 3 |
| `/memoria buscar <termo>` / `gravar <chave> \| <resumo>` | memória Ruflo |

## Scripts

```bash
python3 scripts/enxame/validar-config.py            # coerência de tudo acima
python3 scripts/enxame/status.py
python3 scripts/enxame/registrar-rodada.py --help   # orçamento e gates
python3 scripts/enxame/memoria.py {init|store|search|get|list}   # forma preferida (multiplataforma)
# scripts/enxame/memoria.sh é um shim equivalente para Linux/Git Bash (chama o .py)
scripts/enxame/importar-base.sh [chaincode|api|rede|todos]   # traz bases de redd+_blockchain sem chaves
python3 scripts/lint/lint-chaincode.py chaincode/src/main/java
python3 scripts/lint/lint-jabs.py src/main/java
python3 scripts/jabs/checar-determinismo.py --jabs . --protocolo ARQ --validadores 7
python3 scripts/jabs/validar-complexidade.py dados.csv --esperado linear
python3 scripts/jabs/gerar-grid.py fatores.json
```

Os linters acham padrões mecânicos e **não provam corretude**; o critic ainda lê a lógica.

## Ruflo

Memória persistente via CLI (`.enxame/memoria-redd-jabs.db`, namespace `redd-jabs`). Não faz o spawn dos subagentes: isso é o Task tool. O servidor MCP está declarado em `opencode.json` com `enabled: false`; ligue-o só se quiser as ferramentas do Ruflo dentro da sessão. Só o orquestrador grava memória, e só resumo de decisão.

A memória Ruflo (`.enxame/memoria-redd-jabs.db`, namespace `redd-jabs`) foi reindexada em 2026-11-18 com embeddings de 384 dimensões (Xenova/all-MiniLM-L6-v2) em todas as 61 entradas; busca semântica por `python3 scripts/enxame/memoria.py search`.

## Ambiente (verificado em 2026-11-18, máquina Windows)

- Java 21.0.8 (JABS mira 17), Maven 3.9.11, Docker Desktop com engine ativo, Node 24, Python (sem pandas: scripts usam só biblioteca padrão).
- Docker Hub direto já falhou aqui; usar `mirror.gcr.io/<imagem>`.
- `java -cp target/classes` sozinho falha no JABS (faltam dependências): montar o classpath com `mvn dependency:build-classpath`.
- Parlia/Casper do JABS exigem `n >= 10` (senão o setup da rede não termina).
- No PowerShell desta máquina `npx.ps1` é bloqueado pela política de execução; usar `npx.cmd`.
- O `bash` do PATH desta máquina é o do WSL: não enxerga `npx`/`ruflo` do Windows (`memoria.sh` falha com "Database not found"). Por isso a memória usa `python3 scripts/enxame/memoria.py`, que resolve `ruflo` ou `npx.cmd --yes ruflo@latest` por conta própria. `importar-base.sh` continua só para Linux/Git Bash (precisa de `rsync` e das bases em `~/Projetos`).

## Limites

- Não substitui julgamento humano quando o orçamento acaba, quando há divergência em impacto ALTO/CRITICO, ou em lacuna normativa.
- Raft (Fabric) não é tolerante a falha bizantina; resultados BFT do JABS descrevem uma alternativa de ordenação, não o Fabric atual. A ponte documenta essa distância.
- Config do OpenCode é lida na inicialização: reinicie o opencode após alterar agentes, skills ou `opencode.json`.
