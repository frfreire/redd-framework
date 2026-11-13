# ADR-0014: tamanho máximo de página das consultas paginadas (`PAGE_SIZE_MAXIMO = 100`)

- **Estado:** Proposto
- **Data:** 2026-11-09
- **Decisores:** Fabricio (humano). Nenhum agente aceita este ADR.
- **Trava:** qualquer tarefa que altere o valor de `PAGE_SIZE_MAXIMO` em `chaincode/` ou em `api-gateway/`; qualquer tarefa que torne o limite configurável (chaincode, gateway ou `rede-fabric/`); qualquer tarefa que separe o limite de `listarCreditosPorProjeto` do de `historicoCredito`; qualquer mudança no limite de mensagem gRPC do cliente do gateway. **Não trava** o comportamento atual (100), que segue vigente.
- **Origem:** o Javadoc do contrato já registra o valor como provisório ("Valor definitivo: a definir com o humano (ADR)") e a cópia do gateway repete a ressalva. Este ADR dá lugar a essa decisão. **Não escolhe opção.**

Rótulos de evidência: **[FONTE]** li o texto citado (arquivo do repositório ou documento externo, acesso em 2026-11-09); **[HIPÓTESE]** inferência ou cálculo sem medição; **[NÃO VERIFICADO]** tentei ou não pude confirmar.

## Contexto

### O que o código faz hoje

1. `RedContract.PAGE_SIZE_MAXIMO = 100` (`chaincode/src/main/java/br/com/redd/chaincode/RedContract.java:86`). O Javadoc (`:81-85`) diz: "A especificação não fixa o valor: 100 é conservador (a resposta cabe com folga no limite de mensagem do gRPC). Valor definitivo: a definir com o humano (ADR)." [FONTE]
2. O mesmo valor limita **duas** consultas: `listarCreditosPorProjeto` (`:524-548`, rich query paginada por bookmark em CouchDB) e `historicoCredito` (`:594-631`, paginação por offset sobre `getHistoryForKey`). Fora de 1..100 o contrato falha, sem ajuste silencioso (`:528-531`, `:600-603`). [FONTE]
3. O gateway repete o valor em `api-gateway/src/main/java/br/com/redd/apigateway/dto/LimitesContrato.java:20` e valida na borda (`Paginacao.exigirPageSize`, `dto/Paginacao.java:15-21`) para devolver 400 antes de chamar o peer. O contrato continua sendo a autoridade (`LimitesContrato.java:4-9`). [FONTE]
4. O limite "1 a 100" também está em texto livre nas descrições OpenAPI (`controller/RedContractController.java:75` e `:141`). [FONTE]
5. A resposta de `listarCreditosPorProjeto` é uma página de objetos `CreditoCarbono` completos, incluindo o campo `historico` (`model/CreditoCarbono.java:33-34`), que **cresce a cada transferência** (`RedContract.java:410`) e a cada emissão/aposentadoria (`:367`, `:480`) e não tem teto no código lido. A resposta de `historicoCredito` é uma página de `TransferenciaHistorico` (4 campos curtos). Ou seja, o tamanho da resposta por item é muito diferente nas duas consultas, mas o limite é o mesmo. [FONTE: código; a conclusão sobre tamanho relativo é [HIPÓTESE], sem medição]
6. Não encontrei no repositório nenhuma configuração de `totalQueryLimit`, `internalQueryLimit`, `maxRecvMsgSize` ou `maxSendMsgSize` (`rede-fabric/docker-compose.yaml` define só `CORE_LEDGER_STATE_STATEDATABASE` e os `CORE_LEDGER_STATE_COUCHDBCONFIG_*` de endereço/credencial, linhas 190-193, 235-238, 280-283). Valem, portanto, os padrões da imagem `hyperledger/fabric-peer:2.5` (`docker-compose.yaml:169`). [FONTE]
7. O canal gRPC do gateway é criado em `FabricGatewayService.criarCanalGrpc` (`api-gateway/.../fabric/FabricGatewayService.java:111-126`) com `Grpc.newChannelBuilder(...).overrideAuthority(...).build()`, **sem** `maxInboundMessageSize`. [FONTE]
8. Nenhum requisito de domínio fixa o tamanho de página (`especificacoes/dominio-redd.md` não cita paginação). [FONTE: `grep` em `especificacoes/`, sem ocorrência]

### Limites do Fabric 2.5 e do gRPC (o que consultei)

| Parâmetro | Valor | Onde | Rótulo |
|---|---|---|---|
| `ledger.state.totalQueryLimit` | **100000** (arquivo `sampleconfig/core.yaml`, ramo `release-2.5`: "Limit on the number of records to return per query") | `https://raw.githubusercontent.com/hyperledger/fabric/release-2.5/sampleconfig/core.yaml` | [FONTE] |
| `totalQueryLimit`, descrição | "Regardless of whether the pagination APIs are utilized, all chaincode queries are bound by `totalQueryLimit` (default 100000) from `core.yaml`. This is the maximum number of results that chaincode will iterate through and return to the client" | `https://hyperledger-fabric.readthedocs.io/en/release-2.5/couchdb_as_state_database.html`, seção "CouchDB pagination" | [FONTE] |
| **Divergência na própria documentação** | o bloco YAML de exemplo da mesma página mostra `totalQueryLimit: 10000` (dez mil), enquanto o texto e o `core.yaml` do ramo dizem 100000 | idem, seção "CouchDB Configuration" | [FONTE] a divergência. Qual valor a imagem `fabric-peer:2.5` traz de fato: [NÃO VERIFICADO] (não li o `core.yaml` dentro da imagem nem executei o peer) |
| `ledger.state.couchDBConfig.internalQueryLimit` | 1000. "the peer will query CouchDB in batches based on `internalQueryLimit` [...] transparent to chaincode and the calling client" | mesma página e `core.yaml` | [FONTE] |
| `peer.limits.concurrency` | só limita **requisições concorrentes** (endorserService 2500, deliverService 2500, gatewayService 500). Não limita tamanho de resposta nem de resultado de query | `core.yaml` ramo `release-2.5`, seção `peer.limits` | [FONTE] |
| `peer.maxRecvMsgSize` / `peer.maxSendMsgSize` | 104857600 (100 MB); o comentário recomenda manter o padrão "since all nodes should be consistent" | `core.yaml` ramo `release-2.5` | [FONTE] |
| Shim Java do chaincode, entrada máxima | `DEFAULT_MAX_INBOUND_MESSAGE_SIZE = "104857600"` (100 MB), sobrescrevível por `MAX_INBOUND_MESSAGE_SIZE` em `config.props` | `ChaincodeBase.java`, tag `v2.5.11` (a mesma versão de `chaincode/pom.xml:24`) | [FONTE] |
| Cliente gRPC Java, entrada máxima padrão | `ManagedChannelBuilder.maxInboundMessageSize`: "If not called, defaults to 4 MiB". A Javadoc consultada é da 1.85.1; o gateway usa `grpc.version` 1.83.1 (`api-gateway/pom.xml:30`) | `https://grpc.github.io/grpc-java/javadoc/io/grpc/ManagedChannelBuilder.html` | [FONTE] para 1.85.1. Idêntico em 1.83.1: [HIPÓTESE] |
| Timeouts relevantes | `chaincode.executetimeout: 30s`; `couchDBConfig.requestTimeout: 35s` | `core.yaml` ramo `release-2.5` | [FONTE] |

### Leitura dos números (efeito do `totalQueryLimit`)

1. **`totalQueryLimit` (100000, ou 10000 conforme a divergência acima) é maior que 100 em qualquer das duas leituras.** Logo ele **não é o fator que determina** o valor 100: uma página de até 100 itens nunca chega perto do teto de resultados por query. Ele só passaria a limitar se `PAGE_SIZE_MAXIMO` fosse maior que 10000 (ou 100000). [FONTE para os valores; a conclusão é aritmética]
2. **`internalQueryLimit` (1000) é um joelho de custo, não um teto.** Até 1000 itens por página a consulta cabe em um lote de CouchDB; acima disso o peer faz vários lotes por chamada ("transparent to chaincode"). Isso é custo extra, não erro. [FONTE para o mecanismo; o efeito em latência: [HIPÓTESE], não medido]
3. **O limite que realmente pode morder é o do cliente gRPC do gateway (4 MiB por padrão), não os 100 MB do peer e do shim.** A afirmação do Javadoc do contrato ("cabe com folga no limite de mensagem do gRPC") só é verdadeira se se refere aos 100 MB do peer/shim, ou se a resposta de uma página for bem menor que 4 MiB. Sobre o canal do gateway, o limite aplicável é o padrão do cliente (item 7 do contexto). [HIPÓTESE: não verifiquei se o `fabric-gateway` 1.12.1 altera esse limite internamente]
4. **Conta de ordem de grandeza (não é medição).** Com 4 MiB = 4194304 B por mensagem e 100 itens, o orçamento é cerca de 41943 B por `CreditoCarbono`. Se cada entrada de `historico` ocupar algo como 150 B de JSON (4 campos: dois MSP, timestamp, tipo; **tamanho assumido, não medido**), o limite seria atingido com algumas centenas de entradas de histórico por crédito. Como o histórico não tem teto no código lido, o pior caso depende da vida do crédito, não do `pageSize`. [HIPÓTESE; o valor 150 B é suposição]
5. **Nenhum dado medido de tamanho de resposta existe no repositório.** Quem produz esse dado é a ponte (`redd-ponte`), que pode medir o tamanho real da resposta serializada por item e por página, em `experimentos/calibracao/`. Ainda não foi feito. [FONTE: o diretório `experimentos/` não existe nesta data]

## Opções

Opções independentes de (a) valor e (b) onde o valor mora. Sem recomendação própria; a do orquestrador está registrada abaixo.

- **O1. Manter 100 nas duas consultas, como está.**
  - Prós: nenhuma mudança; valor "conservador" de fato (muito abaixo de `totalQueryLimit` e de `internalQueryLimit`); nenhum teste a reescrever.
  - Contras: o 100 não tem fonte (nem norma, nem limite medido); pode ser alto demais para `listarCreditosPorProjeto` se créditos tiverem histórico longo (contexto, leitura 3 e 4), e baixo demais para `historicoCredito`, cujos itens são curtos; o Javadoc diz "cabe com folga" sem medição.
- **O2. Outro valor, derivado de um limite com fonte.** As fontes lidas dão três âmbitos possíveis, nenhum fixando um número para este contrato:
  - teto estrito: a menor das mensagens (4 MiB do cliente do gateway, hoje) dividida pelo tamanho máximo de um item; o item máximo depende de um teto de `historico` que **não existe**;
  - joelho de custo: até 1000 (`internalQueryLimit`), acima disso há vários lotes por chamada;
  - teto de resultados: `totalQueryLimit` (≥ 10000), irrelevante abaixo disso.
  - Prós: o valor passa a ter fonte e margem calculada.
  - Contras: o número final **depende de medição** (tamanho de item) e de decisão sobre limitar o `historico`; sem isso, qualquer valor novo também é opinião. Valor: `a definir com o humano`.
- **O3. Limite configurável por canal.**
  - Variante A: valor guardado no estado do canal (chave de configuração escrita por transação administrativa, lida por `getState`). É determinístico e por canal por construção (o ledger é por canal). Consequência: nova transação com controle de quem pode escrever, novo evento/histórico, e o gateway precisa ler o valor (uma consulta a mais, ou cache).
  - Variante B: variável de ambiente ou `config.props` do chaincode. **Não serve ao contrato:** o linter bloqueia `System.getenv`/`System.getProperty` (CC-004, `scripts/lint/lint-chaincode.py:23`) porque o ambiente do peer diverge entre organizações e quebra o determinismo do endosso. Só valeria para o **gateway** (que não é endossante).
  - Prós: ajusta sem recompilar/reinstalar o chaincode.
  - Contras: complexidade e superfície de ataque (quem pode mudar o limite? ver INV-10); mais código para revisar pelo critic; a variante A cria dado de governança no ledger.
- **O4. Manter 100 e elevar `maxInboundMessageSize` do canal gRPC do gateway.** Resolve o risco do contexto (leitura 3) sem mexer em contrato nem em testes; o teto passa a ser o do peer (100 MB, mantido no padrão). Contras: não reduz o custo de resposta grande (memória e latência no gateway); só desloca o limite.
- **O5. Separar as duas constantes** (uma para `listarCreditosPorProjeto`, outra para `historicoCredito`), combinável com O1-O4. Prós: reflete que os itens têm tamanhos diferentes. Contras: mais um parâmetro e mais um ponto de cópia no gateway.

## Decisão

Pendente. Nenhuma opção escolhida.

- Valor de `PAGE_SIZE_MAXIMO`: a definir com o humano
- Um limite único ou um por consulta: a definir com o humano
- Fixo no código ou configurável por canal: a definir com o humano
- Limitar o tamanho do `historico` do crédito (condição para qualquer teto estrito calculável): a definir com o humano

### Recomendação do orquestrador (registrada para o humano avaliar; não é decisão)

Manter 100 (O1), com a ressalva de que a ponte meça o tamanho real da resposta e que o ajuste, se necessário, seja feito com a mudança concentrada **em um ponto no gateway e um no contrato**.

Avaliação do domínio, como hipótese:

1. A recomendação é compatível com as fontes lidas: 100 está muito abaixo de `internalQueryLimit` (1000) e de `totalQueryLimit` (≥ 10000), logo não há razão técnica documentada para mudar antes de medir.
2. "Um ponto no gateway e um no contrato" é o desenho de hoje **só no código**: as constantes são `RedContract.java:86` e `LimitesContrato.java:20`. Há ainda cópias **textuais** do 100 fora delas: as descrições OpenAPI (`RedContractController.java:75`, `:141`) e literais em testes (ver Consequências). Para a recomendação valer, essas cópias deveriam derivar da constante (decisão de implementação, não deste ADR).
3. A ressalva "medir antes de ajustar" deixa de bastar se o `historico` continuar sem teto: o tamanho máximo de item é desconhecido por construção, e nenhuma medição de um conjunto finito de créditos fecha o pior caso. Isso reforça perguntar ao humano se o histórico deve ter teto (ver pendências).
4. O4 é o custo mínimo de proteção contra o limite de 4 MiB caso a ponte meça uma página que se aproxime dele, e não toca no contrato.

## Consequências

- Até a decisão, o comportamento atual (100, duas consultas, falha explícita fora de 1..100) segue valendo.
- **Se mudar só o valor (O1 -> O2):** muda `RedContract.PAGE_SIZE_MAXIMO` e `LimitesContrato.PAGE_SIZE_MAXIMO`; passa pelo loop 1 (critic) e atualiza o texto do Javadoc (`RedContract.java:81-85`, `:517-522`, `:580-584`) e de `LimitesContrato.java:15-19`. **Assinaturas do contrato não mudam** (`listarCreditosPorProjeto(ctx, idProjeto, pageSize, bookmark)`, `historicoCredito(ctx, idCredito, offset, pageSize)`); o contrato de erro (mensagem "pageSize deve estar entre 1 e N") muda de texto.
- **Testes afetados por mudança de valor:**
  - Chaincode, usam a constante e se ajustam sozinhos: `RedContractPaginacaoTest.java:161,163,171,174`; `RedContractHistoricoPaginacaoTest.java:173,175,181,183,185`.
  - Gateway, com **literais** a reescrever: `RedContractControllerTest.java:126,128` (`"100"`), `:133,137` (`"101"` e `"entre 1 e 100"`), `:180` (`"101"`); `ErroChaincodeTest.java:57` (`"entre 1 e 100 (recebido: 500)"`).
  - Textos OpenAPI: `RedContractController.java:75` e `:141`.
- **O3-A (config no ledger):** nova função transacional e nova consulta; mudança de assinatura **só se** o `pageSize` máximo passar a ser argumento; novo evento de domínio (INV-07); define quem pode escrever (INV-10); coleção/endosso para a chave de config; testes novos no contrato e no gateway; atualização de `dominio-redd.md`. Passa pelo loop 1.
- **O3-B (config por ambiente):** só no gateway; o contrato não pode usar (CC-004). Cria o risco de gateway e contrato divergirem; o contrato continua sendo a autoridade (falha se o gateway deixar passar valor maior), o que o desenho atual já tolera.
- **O4:** só `api-gateway/` (`FabricGatewayService.criarCanalGrpc`); sem mudança de contrato nem de assinatura; teste novo que verifica a configuração do canal.
- **O5:** duas constantes no contrato e duas cópias no gateway; os testes de cada consulta passam a ter limite próprio.
- Qualquer mudança no valor muda a forma da **resposta paginada** apenas no campo `pageSize` ecoado (`PaginaCreditos`, `PaginaHistorico`); o formato do JSON não muda. Clientes que fixaram "100" na documentação OpenAPI precisam ser avisados.
- Não afeta o simulador nem as métricas. A ponte pode usar a medição de tamanho de resposta como entrada para REQ-07 (`dominio-redd.md`, seção 6), mas isso é um subproduto, não parte deste ADR.

## Verificação

Para quando houver decisão (nada disto foi executado nesta data):

- **Medição (ponte):** em rede com os `rede-fabric/` atuais, tamanho em bytes da resposta de `listarCreditosPorProjeto` para `pageSize` = 1, 10, 50, 100 com créditos de histórico curto e longo, e da resposta de `historicoCredito`; registrar com semente, versão do contrato e granularidade (regra 6). Sem isso, nenhum teto estrito (O2) é afirmável.
- **Confirmar o padrão do peer:** ler `core.yaml` dentro da imagem `mirror.gcr.io/hyperledger/fabric-peer:2.5` e fixar qual `totalQueryLimit` vale (100000 ou 10000), resolvendo a divergência da documentação. Alternativa: fixar explicitamente `CORE_LEDGER_STATE_TOTALQUERYLIMIT` em `rede-fabric/docker-compose.yaml` (decisão da trilha de rede, fora deste ADR).
- **Confirmar o limite efetivo do cliente do gateway** com `fabric-gateway` 1.12.1 e `grpc` 1.83.1: teste que gera uma resposta maior que 4 MiB e verifica o comportamento (sucesso ou `RESOURCE_EXHAUSTED`).
- Testes do contrato e do gateway com o novo limite (valor, borda `N` aceita, `N+1` recusada sem consultar o ledger), e `python3 scripts/lint/lint-chaincode.py chaincode/src/main/java` mais revisão do critic.
- O humano registra a opção e muda o estado para Aceito.
