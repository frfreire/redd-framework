# Especificação de domínio REDD+ / MRV

> Fonte única citada pelas bars do loop 1 (chaincode) e do loop 3 (critério 5). Escrita pelo agente `redd-dominio` (skill `redd-dominio-mrv`). Cada invariante tem identificador estável (INV-xx). Não renumere: acrescente ao fim.
>
> **Regra de lacuna.** Prazo, limiar numérico, reserva e ajuste correspondente (Art. 6) não são inventados. Onde a norma ou o humano ainda não fixou valor, o texto diz `a definir com o humano` e aponta o ADR (todos em estado Proposto até o humano aceitar).
>
> **Estado do código na data desta revisão** (verificado em `chaincode/src/main/java/br/com/redd/chaincode/`): toneladas são `double` (`RelatorioMRV.toneladasCO2eEstimadas`, `CreditoCarbono.toneladasCO2e`, `RedContract.emitirCredito` linha ~229) e a emissão compara contra a quantidade **estimada** (`RedContract.java:254`). Isso viola INV-11, INV-12 e o sentido de INV-03. Está registrado aqui como não conformidade conhecida para o builder corrigir; esta especificação não altera código.

## 1. Escopo e papéis

O ledger registra o **resultado verificado e o hash da evidência**. Não recalcula linha de base, adicionalidade nem metodologia (ficam fora do ledger, com o verificador e a norma aplicável).

| Organização (MSP) | Papel | Pode | Não pode |
|---|---|---|---|
| Developer (`DeveloperMSP`) | Proponente do projeto | registrar projeto, submeter MRV do próprio projeto, emitir créditos de relatório VERIFICADO do próprio projeto | verificar qualquer MRV, nem o próprio; emitir sobre relatório de outro proponente |
| Verifier (`VerifierMSP`) | Verificador independente | aprovar ou rejeitar relatório SUBMETIDO, com justificativa e toneladas verificadas | registrar projeto, submeter MRV, emitir, verificar projeto cujo proponente seja o próprio MSP |
| Marketplace (`MarketplaceMSP`) | Participante de mercado | receber, transferir e aposentar crédito de que seja proprietário atual; consultar | criar projeto, relatório ou crédito; verificar; emitir |

Observação: o proprietário inicial de um crédito é o Developer que o emitiu; Developer também pode transferir e aposentar crédito que detenha (INV-06). A identidade vem sempre de `ctx.getClientIdentity().getMSPID()`, nunca de argumento (INV-10).

## 2. Ciclo de vida

```
Projeto REGISTRADO
  -> Relatório MRV SUBMETIDO
       -> VERIFICADO   (terminal para o relatório; abre emissão)
       -> REJEITADO    (terminal para o relatório)
VERIFICADO -> Crédito EMITIDO -> TRANSFERIDO* -> APOSENTADO (terminal)
```

| Passo | Quem pode | Pré-condição | Efeito |
|---|---|---|---|
| Registrar projeto | Developer | identidade Developer; id inédito | projeto REGISTRADO, `proponenteMspId` gravado |
| Submeter MRV | Developer proponente do projeto | projeto ativo; período não sobreposto (INV-04); `hashEvidencia` inédito (INV-13) | relatório SUBMETIDO com `toneladasEstimadas` |
| Verificar MRV | Verifier diferente do proponente | relatório SUBMETIDO; justificativa obrigatória | VERIFICADO com `toneladasVerificadas`, ou REJEITADO |
| Emitir | Developer proponente | relatório VERIFICADO; `emitidas + novo <= verificadas` (INV-03) | crédito EMITIDO, proprietário = proponente |
| Transferir | proprietário atual | crédito não aposentado | proprietário muda; quantidade não muda (INV-15) |
| Aposentar | proprietário atual | crédito não aposentado; motivo e beneficiário informados | APOSENTADO, terminal |

## 3. Invariantes

Formato: ID, enunciado, por que existe, como violar, como testar. INV-01..08 foram mantidas como estavam (texto original), com o motivo acrescentado.

### INV-01 Nenhum crédito sem relatório VERIFICADO
- **Por que existe:** a verificação independente é o que dá lastro ao crédito (MRV). Sem ela, o ledger seria um registro de alegações.
- **Como violar:** emitir sobre relatório SUBMETIDO ou REJEITADO.
- **Como testar:** teste de transição inválida em `emitirCredito` para cada estado diferente de VERIFICADO.

### INV-02 O verificador não é a organização proponente do projeto
- **Por que existe:** conflito de interesse invalida a independência exigida pelos programas.
- **Como violar:** proponente verifica o próprio MRV.
- **Como testar:** MSP do chamador igual a `proponenteMspId` gravado no ledger deve falhar. A comparação é com o dado gravado, não com suposição sobre a topologia atual de MSPs (`RedContract.java:188-200` já faz).

### INV-03 Conservação por relatório
- **Enunciado:** a soma dos créditos emitidos de um relatório não excede as toneladas **verificadas** desse relatório. O acumulado `toneladasEmitidas` é gravado na mesma transação da emissão.
- **Por que existe:** impede inflar o crédito além do que o verificador atestou.
- **Como violar:** duas emissões sobre o mesmo relatório que somam mais que o verificado; emissão contra o valor estimado (não conformidade atual).
- **Como testar:** emitir o total verificado; a emissão seguinte de 1 unidade deve falhar. Emitir em duas parcelas cuja soma é igual ao verificado deve passar. Dois `emitirCredito` concorrentes sobre o mesmo relatório: um deve falhar por conflito de leitura/escrita, não ambos passarem.

### INV-04 Períodos de monitoramento não se sobrepõem
- **Enunciado:** dois relatórios do mesmo projeto não têm períodos que se cruzam. Intervalo `[inicio, fim]` com `inicio <= fim`. Se intervalos fechados ou semiabertos é detalhe a fixar no ADR-0005 (convenção de borda); até lá, tratar fim e início iguais como sobreposição (mais restritivo).
- **Por que existe:** primeira linha de defesa contra dupla contagem temporal (a mesma redução contada em dois relatórios).
- **Como violar:** submeter dois relatórios cujos períodos se cruzam, inclusive um contido no outro, ou com mesma borda.
- **Como testar:** casos: contido, cruzado à esquerda, cruzado à direita, borda igual, adjacente sem tocar (passa). Relatório REJEITADO continua ocupando o período? `a definir com o humano` (ADR-0005); até lá, conservador: ocupa.

### INV-05 Aposentadoria é terminal
- **Enunciado:** crédito APOSENTADO não muda mais de estado; a segunda tentativa falha com erro explícito.
- **Por que existe:** a aposentadoria abate emissão de alguém; se pudesse voltar, a mesma tonelada abateria duas vezes.
- **Como violar:** aposentar duas vezes; transferir depois de aposentar.
- **Como testar:** testes de `aposentarCredito` e `transferirCredito` sobre crédito aposentado.

### INV-06 Só o proprietário atual transfere ou aposenta
- **Por que existe:** posse é a única autoridade de disposição do crédito.
- **Como violar:** terceiro chama `transferirCredito` ou `aposentarCredito`.
- **Como testar:** MSP diferente do `proprietarioAtual` gravado deve falhar, inclusive o MSP do proponente original depois da transferência.

### INV-07 Evento de domínio e histórico em toda mudança de estado
- **Enunciado:** toda mudança de estado gera evento de domínio e entrada no histórico com o timestamp da transação (`getTxTimestamp()`).
- **Por que existe:** auditoria e detecção de dupla contagem dependem de rastro completo e reproduzível.
- **Como violar:** escrita sem `setEvent`; hora do relógio local; ordem não determinística.
- **Como testar:** lint CC-020/CC-001 e teste de determinismo (mesma entrada, mesmo estado, mesmo evento em peers distintos).

### INV-08 Hash da evidência, nunca o documento nem dado pessoal
- **Por que existe:** o ledger é replicado entre organizações; dado pessoal e blob não podem ser apagados e vazam para quem não deveria.
- **Como violar:** colocar blob, CPF/CNPJ de pessoa física ou coordenada que identifique pessoa no estado público.
- **Como testar:** lint CC-014 e revisão do critic. Conteúdo sensível, se houver, vai a coleção privada ou fora do ledger.

### INV-09 Máquina de estados fechada
- **Enunciado:** só existem as transições da seção 2. Relatório VERIFICADO ou REJEITADO é terminal: não se reverifica nem se reabre. Crédito só caminha EMITIDO -> TRANSFERIDO* -> APOSENTADO. Toda função que muda estado valida o estado atual antes de escrever.
- **Por que existe:** estado reabrível permite reescrever o que já foi atestado.
- **Como violar:** verificar duas vezes o mesmo relatório (aprovar depois de rejeitar); emitir sobre relatório REJEITADO; submeter MRV para projeto não ativo.
- **Como testar:** tabela de transições: para cada par (estado, operação) fora da seção 2, esperar falha com mensagem identificável.

### INV-10 Papel vem da identidade da transação, não do argumento
- **Enunciado:** o papel de quem chama deriva do MSP da identidade assinante, conforme a tabela da seção 1. Nenhum parâmetro de função informa "quem sou" ou "meu papel". Marketplace nunca cria projeto, relatório ou crédito.
- **Por que existe:** parâmetro é controlado pelo chamador; identidade assinada não.
- **Como violar:** receber `mspId` ou `papel` por argumento; permitir Marketplace emitir; aceitar Developer verificando.
- **Como testar:** matriz (operação x MSP) 6x3: só as células permitidas passam. Teste que varre a assinatura pública do contrato e falha se algum parâmetro tem nome de identidade do chamador.

### INV-11 Toneladas são inteiros, nunca ponto flutuante
- **Enunciado:** toda quantidade de tCO2e no estado, nos argumentos e nos eventos é inteira (`long` ou `BigInteger`), sempre estritamente positiva nas operações de submissão, verificação e emissão. Nenhum `double`/`float` em campo de toneladas. A **unidade** do inteiro (1 = 1 tCO2e ou 1 = 1 kgCO2e, etc.) é decisão do ADR-0002; `a definir com o humano`.
- **Por que existe:** soma de ponto flutuante quebra igualdade e a comparação de INV-03; o resultado pode divergir entre peers e por isso falhar no endosso.
- **Como violar:** campo `double` (estado atual do código); parse de decimal; divisão que gera fração.
- **Como testar:** teste de propriedade: somar N parcelas inteiras que totalizam o verificado e exigir igualdade exata; lint/grep falha em `double`/`float` nos modelos de toneladas; argumento `0`, negativo ou com ponto decimal falha. Overflow: usar `Math.addExact` (ou `BigInteger`) e falhar em estouro.

### INV-12 Estimado e verificado são grandezas separadas
- **Enunciado:** o relatório guarda `toneladasEstimadas` (do proponente, na submissão) e `toneladasVerificadas` (do verificador, na verificação). A verificação grava o valor verificado e ele é imutável depois. INV-03 e a emissão usam **somente** o verificado. Relação entre os dois (verificado pode exceder o estimado ou não) é lacuna: `a definir com o humano` (ADR-0002); até lá o contrato não impõe `verificado <= estimado`, apenas `verificado > 0` na aprovação e `verificado` ausente na rejeição.
- **Por que existe:** o crédito deriva do que foi atestado, não do que foi prometido.
- **Como violar:** emitir comparando com o estimado; verificador sem informar toneladas; alterar `toneladasVerificadas` depois.
- **Como testar:** relatório com estimado 100 e verificado 60: emitir 61 falha, emitir 60 passa. Tentar reescrever o verificado falha.

### INV-13 Dupla contagem: identidades únicas e evidência única
- **Enunciado:** (a) `idProjeto`, `idRelatorio` e `idCredito` (número de série) são únicos; criar com id existente falha, nunca sobrescreve. (b) Um mesmo `hashEvidencia` não sustenta dois relatórios. (c) Um crédito tem exatamente um proprietário em cada instante; transferência atômica sem estado intermediário. (d) Projeto que declare já estar registrado em outro registro externo guarda o identificador externo e é rejeitado se esse identificador já existe no ledger (ver ADR-0003 para registro nacional e outros registros).
- **Por que existe:** é o risco central. A blockchain deve tornar a dupla contagem detectável: a mesma redução em dois relatórios (INV-04, esta), o mesmo crédito em dois donos (esta, INV-06), o mesmo crédito usado e vendido (INV-05).
- **Como violar:** `putState` sobre chave existente; reusar hash de evidência em relatório de período diferente; duas transferências concorrentes do mesmo crédito.
- **Como testar:** criar duas vezes com o mesmo id (segunda falha); submeter dois relatórios com o mesmo `hashEvidencia` (segunda falha, inclusive entre projetos diferentes); duas transferências concorrentes: uma falha por conflito. Se for confirmado que evidência pode ser legitimamente reutilizada, a decisão é do humano (item 3 das lacunas).

### INV-14 Aposentadoria registra motivo, beneficiário e autor
- **Enunciado:** `aposentarCredito` exige motivo não vazio e identificador do beneficiário (quem abate a emissão) que não seja dado pessoal direto (referência opaca ou hash, INV-08). O crédito grava MSP aposentador e timestamp da transação; o evento `CreditoAposentado` leva esses campos. Crédito aposentado sai de qualquer consulta de saldo disponível e continua consultável por id e histórico. Aposentadoria é do crédito inteiro; aposentar fração exige dividir o crédito, o que depende do ADR-0002 e fica `a definir com o humano`.
- **Por que existe:** a aposentadoria é o ato que dá o uso final ao crédito; sem beneficiário e motivo, o programa de destino (CORSIA, SBCE, voluntário) não consegue conferir a quem foi abatido.
- **Como violar:** aposentar sem motivo; gravar CPF como beneficiário; aposentado ainda aparecer como disponível.
- **Como testar:** motivo vazio ou em branco falha; consulta de disponíveis exclui aposentado; evento contém os três campos.

### INV-15 Transferência preserva identidade e quantidade
- **Enunciado:** `transferirCredito` altera apenas `proprietarioAtual` (e o histórico). Projeto, relatório, quantidade e série não mudam. Destinatário é uma organização conhecida (MSP não vazio e diferente do proprietário atual). Conservação global por projeto, em qualquer instante: `emitido = em circulação + aposentado`, onde cada parcela é soma de inteiros (INV-11).
- **Por que existe:** impede criar ou destruir tonelada por transferência.
- **Como violar:** transferência que reescreve quantidade; destinatário vazio; auto-transferência; crédito que some do estado.
- **Como testar:** propriedade: sequência aleatória de emitir/transferir/aposentar; ao fim, a soma por projeto confere. Auto-transferência falha (o contrato já tem a checagem `novoProprietarioMspId == mspId` em `RedContract.java:300`; falta teste).

### INV-16 Reversão não apaga: é registro novo (condicional ao ADR-0001)
- **Enunciado:** crédito emitido nunca é apagado nem editado retroativamente. Se houver reversão (queimada, perda de floresta), ela entra como evento e estado novos, com autor, motivo e timestamp, e o tratamento (buffer, cancelamento, substituição) é o que o ADR-0001 decidir. Enquanto o ADR-0001 estiver Proposto, o contrato **não implementa** reversão; a ausência é limitação declarada, não omissão.
- **Por que existe:** permanência é risco inerente ao REDD+; programas tratam isso com reserva (buffer) e regras de substituição.
- **Como violar:** `delState` ou sobrescrita de crédito emitido; reversão sem rastro.
- **Como testar:** lint de `delState` em entidades de crédito; após ADR aceito, testes de reversão passam a valer.

### INV-17 Estado de autorização para uso fora do país (condicional ao ADR-0003)
- **Enunciado:** se o crédito puder ser usado fora do país anfitrião (CORSIA, Art. 6), o ledger precisa de um estado de autorização e ajuste correspondente por crédito, e crédito sem a autorização exigida não pode ser aposentado para esse fim. Campos, estados, quem registra e se o ledger o faz ou se é externo: `a definir com o humano` (ADR-0003). Enquanto Proposto, o contrato não afirma nem nega ajuste correspondente; nenhum crédito deve ser descrito como "com ajuste correspondente" pelo sistema.
- **Por que existe:** usar o mesmo crédito para a meta do país anfitrião e para a de outro país é dupla reivindicação, mesmo com o ledger limpo.
- **Como violar:** aposentar para fim internacional sem autorização registrada; rotular crédito como ajustado sem evidência.
- **Como testar:** após ADR aceito: aposentadoria com fim internacional e sem autorização falha. Até lá: nenhuma string "ajuste correspondente" é produzida pelo contrato (grep).

## 4. Mapeamento para normas e programas

> **Aviso de fonte.** Esta tabela liga conceitos a programas pelo que se conhece do desenho deles. **Não cita números de cláusula**, porque o texto das normas não foi consultado nesta revisão. O humano (ou agente com acesso à norma) deve confirmar a cláusula antes de qualquer afirmação de conformidade. Cada linha é hipótese de mapeamento, não declaração de conformidade.

| Conceito do ledger | VCS / Verra | ART-TREES (jurisdicional) | CORSIA | SBCE (Brasil) | INV / ADR |
|---|---|---|---|---|---|
| Verificação por terceiro independente | validação/verificação por organismo credenciado | verificação por organismo independente | unidade elegível exige programa que cumpre critérios do ICAO | a definir (regulamentação do sistema) | INV-01, INV-02 |
| Período de monitoramento sem sobreposição | período de monitoramento e relato por projeto | período de crédito/relato da jurisdição | vintage elegível por fase | a definir | INV-04 |
| Identificação única da unidade (série) | número de série único no registro | número de série único | unidade precisa ser rastreável e cancelada em registro | registro nacional de ativos, a definir | INV-13 |
| Aposentadoria/cancelamento | retirement no registro, irreversível | retirement/cancelamento | cancelamento em registro com indicação do operador | a definir | INV-05, INV-14 |
| Dupla contagem/emissão | evitar dupla emissão entre registros | evitar sobreposição entre projeto e jurisdição | evitar dupla reivindicação | evitar sobreposição com inventário nacional | INV-13, INV-17, ADR-0003 |
| Reversão/permanência | reserva (buffer) de não permanência | reserva (buffer) e regras de reversão | exige tratamento de reversão | a definir | INV-16, ADR-0001 |
| Ajuste correspondente / autorização do país | quando o crédito sai do país: avaliar | tratado no nível da jurisdição | exige autorização e ajuste correspondente | a definir | INV-17, ADR-0003 |
| Tonelada como unidade | 1 crédito = 1 tCO2e | idem | idem | a definir (unidade do ativo) | INV-11, ADR-0002 |

## 5. Lacunas conhecidas (decisão do humano)

| # | Lacuna | ADR (Proposto) |
|---|---|---|
| 1 | Reserva/buffer e reversão: modelar no ledger ou fora; quem arca; critérios de substituição | 0001 |
| 2 | Unidade e representação inteira de toneladas; estimado vs verificado; fracionamento de crédito na aposentadoria | 0002 |
| 3 | Ajuste correspondente (Art. 6), autorização do país anfitrião, integração com registro nacional e outros registros; reuso legítimo de evidência | 0003 |
| 4 | Ordenação: Raft (Fabric atual) ou BFT; define se resultados de BFT do JABS se aplicam ao produto | 0004 |
| 5 | Prazos e limiares por operação (Tb, Pdv, vazão); convenção de borda e tratamento de REJEITADO na sobreposição de períodos; janela entre submissão e verificação | 0005 |

## 6. Requisitos para as métricas do simulador

Métricas: **Tb** (tempo de finalização), **Pdv** (probabilidade de double-spend/reversão sob ataque, BFT empírico), **C_B** (tráfego por nó, com decomposição C_B_total e C_B_consenso conforme ADR-0006), vazão sustentada. Todo limiar abaixo é `a definir com o humano` (ADR-0005) porque nenhuma fonte normativa fixou número. Todo resultado declara semente, versão do simulador e granularidade (regra 6 do projeto).

| ID | Operação | Requisito | Métrica | Limiar | Fonte / ADR |
|---|---|---|---|---|---|
| REQ-01 | Emissão | registro final antes do uso do crédito | Tb | a definir | ADR-0005 |
| REQ-02 | Emissão | nenhuma reversão sob ataque | BFT empírico, Pdv | a definir | ADR-0004, ADR-0005 |
| REQ-03 | Submissão de MRV | rede estável no pico sazonal | Tb sob carga sazonal | a definir | ADR-0005; perfil de carga vem da ponte, medido |
| REQ-04 | Verificação de MRV | decisão VERIFICADO/REJEITADO só vale depois de final, pois destrava emissão (INV-01, INV-09) | Tb | a definir | ADR-0005 |
| REQ-05 | Transferência | duas transferências concorrentes do mesmo crédito: no máximo uma finaliza (INV-13c) | taxa de transações invalidadas por conflito e Pdv | a definir | ADR-0004, ADR-0005 |
| REQ-06 | Aposentadoria | irreversível; registro final antes de a aposentadoria ser comunicada ao programa de destino (INV-05, INV-14) | Tb e Pdv sob ataque | a definir | ADR-0005 |
| REQ-07 | Todas as escritas | tamanho de transação e de bloco por operação, para dimensionar tráfego por nó | C_B (total e consenso, por nó), bytes por transação | a definir | ADR-0006; valores vêm da ponte, nunca estimados |
| REQ-08 | Emissão em lote | vazão sustentada na emissão de muitos créditos de um relatório (depende da unidade, ADR-0002: 1 crédito por tonelada gera muito mais transações que 1 por lote) | transações/s sustentadas e Tb no percentil | a definir | ADR-0002, ADR-0005 |
| REQ-09 | Todas | sistema continua processando com uma organização indisponível (ex.: Verifier fora) até o limite que a política de endosso tolera | taxa de sucesso e Tb com f nós falhos | a definir | ADR-0004, política de endosso |
| REQ-10 | Todos os resultados | resultado de BFT no JABS só é apresentado como alternativa de ordenação, nunca como propriedade do Fabric com Raft (não tolera falha bizantina); cada resultado traz o protocolo equivalente e a distância ao Fabric | metadado obrigatório (não é métrica numérica) | n/a | ADR-0004 |

## 7. Perguntas abertas para o humano

1. Buffer/reversão: no ledger ou fora, e quem define o percentual? (ADR-0001)
2. Unidade do inteiro (tCO2e, kgCO2e) e se o crédito é 1 por tonelada, 1 por lote, ou lote divisível. (ADR-0002)
3. Verificado pode exceder o estimado? Evidência pode ser reutilizada em dois relatórios? (ADR-0002, ADR-0003)
4. O ledger deve registrar autorização e ajuste correspondente, ou isso é externo? Haverá integração com registro nacional? Projeto no Brasil usado no Brasil ou exportado? (ADR-0003)
5. Raft ou BFT para o produto? (ADR-0004)
6. Números: Tb, Pdv, vazão, prazo entre submissão e verificação, borda de intervalo, REJEITADO ocupa período? (ADR-0005)
7. Confirmar cláusulas das normas do mapeamento (seção 4) antes de qualquer afirmação de conformidade.
