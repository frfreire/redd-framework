# ADR-0008: auditabilidade do ator em `aposentadoPorId` (referência opaca vs. identificar quem aposentou)

- **Estado:** Proposto
- **Data:** 2026-10-27
- **Atualizações:** 2026-11-09, só a questão (d): consulta parcial de fontes normativas, análise de efeito sobre A1-A6 e sugestão do orquestrador (seção "Para (d)"). Nenhuma opção escolhida; o Estado não mudou.
- **Atualizações (2):** 2026-11-09, segunda rodada de (d): leitura do **texto extraído dos PDFs oficiais** (Verra VCS v5.0, Registry Terms of Use jul/2026 e User Guide; ART TREES 3.0 e Registry Terms of Use v3; Decretos 12.677/2025 e 12.768/2025). Acrescentada a seção "Rodada 2" dentro de "Para (d)"; as linhas 11 e 14 da Tabela 1 foram anotadas, não apagadas. Nenhuma opção escolhida; o Estado não mudou.
- **Decisores:** Fabricio (humano). Nenhum agente aceita este ADR.
- **Trava:** qualquer tarefa que altere o cálculo ou o formato de `aposentadoPorId` em `chaincode/`; qualquer endpoint de consulta de auditoria na `api-gateway/` que precise resolver a referência ao ator; qualquer coleção privada nova para este fim (`rede-fabric/`). Não trava a L1-T5 já entregue.
- **Origem:** decisão tomada na L1-T5 para satisfazer INV-08 sem perder a rastreabilidade de INV-14. Este ADR registra o que a L1-T5 deixou em aberto. **Não escolhe opção.**

## Contexto (verificado em 2026-10-27 no código; nenhuma norma consultada)

1. `RedContract.aposentarCredito` (`RedContract.java:439-465`) lê `ctx.getClientIdentity().getMSPID()` e `getId()`, e grava `aposentadoPorMspId = <MSP em claro>` e `aposentadoPorId = referenciaOpacaDoAutor(mspId, autorId)`.
2. `referenciaOpacaDoAutor` (`RedContract.java:832-844`) devolve `"sha256:"` + hex minúsculo de SHA-256 sobre `redd.aposentadoPorId.v1|<MSP>|<ClientIdentity.getId()>`. A constante `SAL_REFERENCIA_AUTOR` é fixa e pública no código. O javadoc (`:829-830`) já registra que ela é separação de domínio e **não protege contra dicionário sobre DN adivinhável**.
3. Motivo da mudança: `ClientIdentity.getId()` é derivado do DN do certificado e pode conter CN/nome de pessoa (`CreditoCarbono.java:51-54`). O estado é replicado entre organizações e não pode ser apagado; ver INV-08 em `especificacoes/dominio-redd.md`. O teste que fixa o comportamento está em `RedContractTest.java:606-607`.
4. O mesmo valor é determinístico: o mesmo ator gera sempre a mesma referência, para qualquer crédito. Consequência dupla: dá correlação ("quem aposentou o quê") a qualquer leitor do canal, e permite a quem **já possui** o certificado candidato recomputar e conferir.
5. O evento `CreditoAposentado` serializa o objeto inteiro do crédito (`RedContract.java:470`; `emitirEvento` em `:785-786` faz `setEvent(nome, toJson(payload))`), portanto leva a mesma referência a todo ouvinte de eventos.
6. INV-14 no texto atual exige motivo, beneficiário não pessoal e "MSP aposentador e timestamp". **Não exige** o id do ator. O campo `aposentadoPorId` e seu formato vêm da L1-T5, não da especificação. Se a decisão tornar o campo obrigatório, `dominio-redd.md` precisa ser atualizado (fora deste ADR).
7. O contrato **não valida** o digest do beneficiário (só o formato); o cálculo é do chamador (`RedContract.java:419-426`). O de `aposentadoPorId`, ao contrário, é feito dentro do contrato a partir da identidade assinante (INV-10).
8. A seção 4 de `dominio-redd.md` mapeia aposentadoria a "cancelamento em registro com indicação do operador" (CORSIA) como **hipótese sem cláusula**. Nenhuma norma foi consultada **na data deste item (2026-10-27)**. Em 2026-11-09 houve consulta parcial, registrada na seção "Para (d)"; a seção 4 de `dominio-redd.md` continua hipótese porque os documentos normativos principais não foram lidos (ver lista de "não acessado"). **Atualização 2026-11-09 (rodada 2):** os documentos de Verra e ART foram lidos em texto; a linha "Aposentadoria/cancelamento" da seção 4 ganhou cláusulas para esses dois programas. Para CORSIA (ICAO) e SBCE (ato do órgão gestor) continua hipótese.
9. Não verificado: se existe alguma rede com créditos aposentados que não sejam de teste. Isso decide o peso da questão (c).

## Questões em aberto para o humano

**(a) Como um auditor resolve a referência de volta ao ator?** Hoje não há caminho previsto: o contrato grava só o digest, e não há registro nem API de resolução.

**(b) O sal deve ser secreto?** Com sal fixo e público, quem conhece ou adivinha o DN calcula o digest e confirma a identidade, e o MSP já é público. Sal secreto muda o modelo de ameaça, mas muda quem pode auditar.

**(c) O que fazer com créditos já aposentados com o ID bruto?** O ledger é imutável. A base em que isso ocorreu e o volume são desconhecidos (contexto 9).

**(d) Que norma (Verra/ART-TREES/CORSIA/SBCE) exige identificar quem aposentou, e em que nível?** Pessoa física, organização, MSP, ou só o beneficiário final? Sem fonte, é `a definir com o humano`; não se infere a exigência a partir do nome do programa.

## Opções

Opções de (a) e (b) se combinam; (c) e (d) são independentes. Sem recomendação.

### Para (a) e (b): como o ator é referenciado e resolvido

- **A1. Manter como está (digest com sal fixo público), resolução por recomputação.** O auditor que já tem o certificado/DN do ator recalcula e compara.
  - Prós: nada a construir; verificável por qualquer um com o candidato; sem custódia de segredo.
  - Contras: não há resolução se o auditor não tem o candidato; dicionário sobre DN previsível identifica o ator (questão b); correlação entre créditos visível a todo o canal.
- **A2. Registro off-chain mantido pela organização que aposenta (ou pela `api-gateway`), mapeando referência -> ator.**
  - Prós: o ledger não muda; o acesso ao registro é controlável e pode ser apagado quando exigido.
  - Contras: confiança fora do ledger (o registro pode ser alterado ou perdido sem rastro on-chain); precisa de política de retenção, backup e quem responde ao auditor; cria um alvo de dado pessoal fora do ledger.
- **A3. Coleção privada (PDC) entre a organização aposentadora e os auditores autorizados, com a referência pública apontando para o registro privado.**
  - Prós: o hash do dado privado fica ancorado no ledger e é verificável; o acesso é restrito por membership; `blockToLive` permite expirar o dado privado, se a norma assim pedir.
  - Contras: exige definição de membership e política de coleção em `rede-fabric/`, que hoje não existe para este fim; o auditor externo à rede precisa entrar como membro ou receber o dado fora de banda; aumenta a complexidade de endosso e de teste; a expiração por `blockToLive` conflita com eventual exigência de guarda longa (depende de (d)).
- **A4. Sal/chave secreto por organização (HMAC em vez de SHA-256 com sal público), guardado fora do ledger.**
  - Prós: elimina o ataque de dicionário por terceiros sem a chave.
  - Contras: o contrato precisaria da chave dentro do peer (como a recebe sem entrar no ledger nem em argumento? não definido; mandar por argumento viola INV-10 porque o chamador controlaria o valor) ou o cálculo sai do contrato e perde a garantia de que o digest corresponde à identidade assinante; só quem tem a chave audita (custódia, rotação e perda de chave quebram a rastreabilidade de referências antigas); chave não pode ir ao git, ao contexto nem à memória (regra 7 do projeto).
- **A5. Compromisso com nonce por aposentadoria (digest de DN + nonce aleatório), nonce guardado no registro privado ou off-chain.**
  - Prós: dicionário não funciona, e não há correlação entre créditos.
  - Contras: perde a rastreabilidade determinística (mesmo ator, referências diferentes); o nonce precisa vir de fora do contrato (aleatoriedade em chaincode é não determinística) e mesmo assim não é verificável contra a identidade assinante; a auditoria depende inteiramente do guardião do nonce.
- **A6. Gravar só o MSP e remover o ator individual do estado.** É o que INV-14 literal exige hoje (contexto 6).
  - Prós: nenhum dado derivado de pessoa no estado; sem problema de dicionário.
  - Contras: perde a granularidade "qual identidade da organização aposentou"; a organização passa a ser a unidade de responsabilidade; muda o modelo e o teste da L1-T5; depende de (d) para saber se basta.

### Para (c): créditos já aposentados com o ID bruto

- **C1. Aceitar o histórico como está e rotular.** Documentar que o intervalo de blocos anterior à L1-T5 contém o ID bruto.
  - Prós: nenhuma intervenção; ledger íntegro.
  - Contras: o dado permanece legível a todo peer e a todo backup; pode ser inaceitável conforme o enquadramento jurídico (não analisado aqui).
- **C2. Migrar para um canal ou ledger novo, sem o dado bruto, mantendo o antigo fora de uso.**
  - Prós: elimina o dado do ledger ativo.
  - Contras: custo alto; o antigo continua existindo onde estiver replicado; quebra a continuidade do histórico e da série de créditos (INV-13) se não for feito com cuidado; exige decisão sobre o que acontece com os créditos em circulação.
- **C3. Se for só ambiente de desenvolvimento ou teste, reinicializar o ledger.**
  - Prós: simples e sem resíduo.
  - Contras: só é válido se (contexto 9) confirmar que não há dado real; não vale depois de qualquer operação real.
- **C4. Registrar, num evento ou registro novo, uma anotação de que o ID bruto anterior está sob tratamento.** Não apaga nem altera o antigo (INV-16 em espírito: correção não reescreve).
  - Prós: deixa rastro auditável do problema.
  - Contras: não remove a exposição; aumenta o dado no ledger.

Análise jurídica (privacidade, eliminação de dados) e qualquer afirmação de conformidade: **a definir com o humano**; não foi feita aqui.

### Para (d): nível de identificação exigido

(d) não tem opções próprias: é um fato a levantar nas normas. **Estado da consulta em 2026-11-09, rodada 1 (páginas web): parcial; (d) continuava aberta. Rodada 2 (texto dos PDFs, mesmo dia): (d) fecha para Verra e ART e continua aberta para CORSIA/ICAO e SBCE; ver "Rodada 2" abaixo.** O texto da rodada 1 foi mantido; onde a leitura dos PDFs o corrige ou completa, há anotação "(R2: ...)".

Rótulos: **[FONTE]** li o texto citado (acesso por `webfetch` em 2026-11-09); **[HIPÓTESE]** inferência sem cláusula; **[NÃO ACESSADO]** tentei e não consegui ler, ou não tentei. Nada foi preenchido de memória.

#### Resultado

Nenhuma fonte lida exige identificar a **pessoa física** que executou a aposentadoria. As fontes lidas exigem (ou mostram) rastrear o **evento** de cancelamento/retirada e identificar o **titular** da conta, do perfil ou o operador (organização). Os documentos normativos principais de Verra, ART e CORSIA **não foram lidos** (PDF grande demais ou devolvido em binário), e os atos infralegais do SBCE não foram localizados. **Ausência de cláusula na amostra lida não é ausência de exigência.**

#### Tabela 1: norma -> exigência de identificação na aposentadoria -> nível -> fonte

| # | Norma / documento (versão, seção) | Trecho curto | Exigência sobre identificar quem aposentou | Nível | Rótulo |
|---|---|---|---|---|---|
| 1 | SBCE, Lei 15.042/2024, Art. 5º, VIII (texto em `planalto.gov.br/ccivil_03/_ato2023-2026/2024/lei/l15042.htm`; alterações posteriores não verificadas) | "garantia da rastreabilidade eletrônica da emissão, da detenção, da transferência e do cancelamento das CBEs e dos CRVEs" | Exige rastreabilidade do **cancelamento** do ativo. Não diz de quem nem em que nível. | não especificado: a definir com o humano | [FONTE] o texto. Que a rastreabilidade inclua o autor: [HIPÓTESE] |
| 2 | Idem, Art. 23, II e III (Registro Central) | "assegurar contabilidade precisa da concessão, da aquisição, da detenção, da transferência e do cancelamento de ativos integrantes do SBCE" | Contabilidade do ativo. Nada sobre o autor do ato. | não especificado | [FONTE] |
| 3 | Idem, Art. 2º, II e XXI | cancelamento: "anulação de [CBE] ou de [CRVE] detido por operador para fins de comprovação dos compromissos ambientais"; operador: "pessoa física ou jurídica" | No conceito de conciliação, quem cancela é o **operador** (detentor do ativo). | operador titular (PF ou PJ) | [FONTE] |
| 4 | Idem, Art. 13 | "Ato do órgão gestor do SBCE disciplinará a transferência de titularidade e o cancelamento de operações sobre os ativos integrantes do SBCE" | Remete o procedimento (e provavelmente os dados registrados) a ato infralegal. | a definir: ato não localizado | [FONTE] a remissão. Conteúdo do ato: [NÃO ACESSADO] |
| 5 | Idem, Art. 45 | "A eventual utilização dos ativos integrantes do SBCE para fins de compensação voluntária de emissões de GEE de pessoas físicas e jurídicas ensejará seu cancelamento no Registro Central do SBCE." | Liga o cancelamento a quem **compensa** (PF ou PJ). Não fala do executor. | beneficiário final (PF ou PJ) | [FONTE] o texto. Que o registro identifique o beneficiário: [HIPÓTESE] |
| 6 | Idem, Art. 24, VI | o Registro Central permitirá "a divulgação de informações em formato de dados abertos" | Publicidade possível. A lei não diz que o cancelamento por ator é divulgado. | n/a | [FONTE]. Efeito sobre dado de pessoa: [HIPÓTESE]; análise jurídica a definir com o humano |
| 7 | Idem, Art. 18 | cancelamento "por pessoa jurídica com apuração no lucro real" permite dedução de gastos | O vínculo fiscal do cancelamento é com a pessoa jurídica. | organização (PJ) | [FONTE]. Implicação de prova/retenção: [HIPÓTESE] |
| 8 | SBCE, atos infralegais: Ministério da Fazenda, "Marco Legal" (`gov.br/fazenda/.../mercado-de-carbono/entenda-o-sbce/marco-legal`) | lista Decreto 12.677/2025 (cria a SEMC, órgão gestor), Decreto 12.768/2025 (CTCP), Portarias e Resoluções CTCP/SBCE 1 a 4/2026 | Nenhum item da lista é descrito como disciplinando cancelamento ou Registro Central. | a definir | [FONTE] a listagem. Texto dos atos: [NÃO ACESSADO] |
| 9 | ART, página "ART Registry" (`artredd.org/art-registry/`; metadado da página: modificada em 2026-10-13) | registry registra "issuance, transfer, and retirement or cancellation of serialized verified credits"; "entities that will take ownership of credits must have a profile"; perfil Transaction: "purchase, hold, transfer, retire, and/or cancel credits"; Designated Signatory: "individual who has the appropriate authority to legally bind the profile holder organization"; relatórios públicos de "retirement, cancellation" | A retirada é feita por um **perfil titular** (entidade). O indivíduo aparece como signatário designado na **abertura do perfil**, não no evento de retirada. | organização titular do perfil; PF só como signatário na abertura | [FONTE]. Campos dos relatórios públicos e do evento: [NÃO ACESSADO] |
| 10 | ART, "CORSIA Cancellation Report" (`greentrace.ice.com/art/disclosures/corsia-cancellation-report`) | "satisfies the public disclosure requirement under ICAO Annex 16, Volume IV, Table A5-7, Field 5. It provides consolidated identifying information for all ART emissions units cancelled" | Declaração **da ART** de que o Annex 16 Vol IV tem exigência de divulgação pública do cancelamento. | n/a | [FONTE] a declaração da ART. Tabela A5-7 campo 5: [NÃO ACESSADO]. "identifying information" referir-se às unidades e não ao autor: [HIPÓTESE]. **(R2: TREES 3.0, Anexo A.2 item 4, p.102, diz que, na cancelação para CORSIA, "the cancellation information will specify the aeroplane operator [...] and the calendar year": ver R2-20. É a descrição da ART do que o CORSIA pede, não o texto da ICAO, que segue NÃO ACESSADO.)** |
| 11 | ART, TREES 3.0 (`TREES-3.0-Final-Version-English.pdf`, jun/2026) e ART Registry Terms of Use v3 (mar/2026) | n/a | n/a | n/a | [NÃO ACESSADO]: `webfetch` devolveu o PDF binário, sem texto extraído. **(R2: LIDO em texto em 2026-11-09; ver R2-13 a R2-20. Confirma "organização titular"; acrescenta beneficiário final obrigatório nas retiradas por conta de terceiros e dispensa nome de pessoa física.)** |
| 12 | Verra, página do programa VCS (`verra.org/programs/verified-carbon-standard/`) | "The Verra Registry also tracks the generation and retirement of all VCUs"; "Verra only retires credits per the instruction of the project proponent" | O registro rastreia a retirada; a instrução vem do proponente (entidade). | titular/proponente (entidade); autor individual: sem referência | [FONTE] |
| 13 | Verra, "Registry Overview" (`verra.org/registry/`) e VCS Program Details (seção Verra Registry) | "Any entity wishing to register projects or issue, retire, or transfer credits must have an active Verra Registry account"; "strict 'Know-Your-Customer' background checks"; o registry lista "issued and retired units" | Identifica-se a **entidade titular da conta** na abertura (KYC). A retirada aparece publicamente como unidade retirada. | organização titular da conta | [FONTE] |
| 14 | Verra, VCS Standard v5.0, VCS Program Guide v5.0 e Registration and Issuance Process v5.0 (todos de 16/12/2025); Program Guide v4.4; Registry Terms of Use (jul/2026) | n/a | n/a | n/a | [NÃO ACESSADO]: v5.0 acima de 5 MB (`webfetch` recusou); v4.4 veio em PDF binário; os Terms of Use só aparecem listados. **(R2: v5.0 (três documentos) e Terms of Use jul/2026 LIDOS em texto; ver R2-1 a R2-12. Program Guide v4.4 continua não obtido, mas a v5.0 declara que substitui a v4: ver "Documentos por status".)** |
| 15 | CORSIA, ICAO "CORSIA Eligible Emissions Units" (`icao.int/CORSIA/corsia-eligible-emissions-units`; documento de unidades elegíveis edição abr/2026; critérios mar/2019) | "the aeroplane operator shall meet its offsetting requirements [...] by cancelling CORSIA Eligible Emissions Units in a quantity equal to its total final offsetting requirements" | Quem cancela é o **operador aéreo** (organização). | organização (operador aéreo) | [FONTE] |
| 16 | CORSIA, Annex 16 Vol IV (2ª ed., aplicável desde 1/1/2024), ICAO Document 09 (critérios), documento de unidades elegíveis, ETM Vol IV | n/a | n/a | n/a | [NÃO ACESSADO]: a página do Annex só lista links; o Doc 09 veio em PDF binário; os demais não tentados. **(R2: continua NÃO ACESSADO: HTTP 403 do servidor da ICAO. Correção de descrição: os arquivos guardados (`icao-doc09.bin`, `annex16-page.bin`) são HTML de desafio anti-bot ("Just a moment..."), não PDF binário. Annex 16 Vol IV Tabela A5-7 campo 5 e Doc 09 seguem sem leitura; nada foi preenchido de memória.)** |

#### Tabela 2: o que as fontes lidas dizem, por nível

| Nível | Evidência na amostra lida | Rótulo |
|---|---|---|
| Pessoa física que executou o ato | Nenhuma exigência encontrada (linhas 1 a 15). TREES 3.0, VCS v5.0, Annex 16 e atos do SBCE não lidos. | [FONTE] para "não encontrei"; [NÃO ACESSADO] para o resto |
| Organização titular (conta, perfil, operador) | Linhas 3, 7, 9, 12, 13, 15. Sempre sobre quem **detém** a conta ou o ativo, não sobre quem clicou. | [FONTE] |
| MSP (conceito do Fabric) | Sem equivalente normativo. Aqui o MSP é a organização do consórcio que detém o crédito no ledger; mapeá-lo ao "titular" dos registros dos programas é interpretação. | [HIPÓTESE] |
| Beneficiário final | SBCE Art. 45 (PF ou PJ que compensa); CORSIA (operador aéreo). O contrato já exige referência opaca do beneficiário (INV-14). | [FONTE] |

#### Ressalvas

1. **Escopo do SBCE.** A Lei trata de CBE e CRVE; um crédito só é CRVE se atender ao Art. 44 (metodologia credenciada, verificação independente, "inscritos no Registro Central do SBCE") [FONTE]. Se os créditos deste ledger são ou serão CRVE depende do ADR-0003 e do registro nacional; não afirmo nada sobre isso.
2. **Ausência não é inexistência.** As linhas 11, 14 e 16 são os documentos que mais provavelmente trazem campos obrigatórios do evento de retirada. Sem lê-los, (d) não fecha. *(R2: as linhas 11 e 14 foram lidas em 2026-11-09; restam a 16 (ICAO, HTTP 403) e o ato do órgão gestor do SBCE (linha 4). Ver "Rodada 2".)*
3. **Análise jurídica** (proteção de dados, prazo de guarda, eliminação): não feita. A definir com o humano.

#### Rodada 2 (2026-11-09): leitura do texto extraído dos PDFs oficiais

Rótulos como acima. Texto obtido por `pdftotext -layout` dos PDFs baixados em 2026-11-09 (extração do orquestrador em `/tmp/opencode/pdf/`, fora do repositório). Número de página = rodapé impresso do documento; número de cláusula = o que o texto extraído mostra (nos Terms of Use da Verra a numeração de alguns parágrafos se perdeu na extração; nesses casos cito só a cláusula-mãe). Citações em inglês são literais.

##### Documentos por status

| Documento | Versão / data | Status |
|---|---|---|
| Verra, VCS Standard | v5.0, 16/12/2025, 93 p. | [FONTE] lido |
| Verra, VCS Program Guide | v5.0, 16/12/2025, 27 p. | [FONTE] lido |
| Verra, Registration and Issuance Process | v5.0, 16/12/2025, 46 p. | [FONTE] lido |
| Verra Registry, Terms of Use | jul/2026, 34 p. | [FONTE] lido |
| Verra Registry, User Guide | v1.0, 27/07/2026 | [FONTE] lido |
| ART, TREES | v3.0, jun/2026, 108 p. | [FONTE] lido |
| ART Registry, Terms of Use | v3, mar/2026 | [FONTE] lido |
| Decreto 12.677/2025 | 15/10/2025 | [FONTE] lido; contagem de "cancelament", "baixa de ativ", "aposent" = 0 (confirmada por grep) |
| Decreto 12.768/2025 | 05/12/2025 | [FONTE] lido; mesma contagem = 0 (trata só do Comitê Técnico Consultivo Permanente) |
| ICAO Document 09 (critérios de elegibilidade CORSIA) | n/a | **NÃO ACESSADO: HTTP 403 do servidor da ICAO.** O arquivo guardado é HTML de desafio anti-bot ("Just a moment..."), não PDF. Nada preenchido de memória |
| ICAO Annex 16 Vol IV, Tabela A5-7 campo 5 | 2ª ed. | **NÃO ACESSADO: HTTP 403 do servidor da ICAO** (idem) |
| Verra, VCS Program Guide | v4.4 | Não obtido. **Dispensável para a regra vigente:** o Program Guide v5.0, §1.1, p.1, diz "VCS Version 5 was released in December 2025 and replaces VCS Version 4 as the applicable version of the VCS Program" [FONTE]. Ressalva [HIPÓTESE]: o mesmo §1.1 diz que as datas de vigência são definidas por requisito; não verifiquei a data de vigência das regras de retirada da v5.0. A v4.4 só importaria para unidades aposentadas sob a v4 |
| Verra, VCS Program Definitions (define retirement, cancellation, authorized representative) | n/a | **Pendente de busca** (remetido por R&I v5.0, nota 1 e §4.6) |
| ART, Registry Operating Procedures (definem os campos do evento de retirada; ART ToU §9(b)(iii)) | n/a | **Pendente de busca** (lacuna nova desta rodada) |
| Verra Registry e ART Registry, relatórios públicos (colunas efetivas) | n/a | **Pendente**: os documentos lidos dizem que existem, não listam todas as colunas |
| SBCE: ato do órgão gestor sobre cancelamento e Registro Central (Lei 15.042/2024, Art. 13) | n/a | **Pendente de busca**; os dois decretos não o contêm |
| Lei 15.042/2024 | n/a | Lida só na rodada 1 (página web); não relida em texto nesta rodada |

##### Tabela R2: o que os PDFs dizem sobre quem retira/cancela e sobre o que é público

| # | Documento, seção, página | Trecho curto | O que diz sobre quem retira e o que é público | Rótulo |
|---|---|---|---|---|
| R2-1 | VCS Program Guide v5.0, §4, p.15 | "full transparency on project and program documentation, together with information on the project and jurisdictional proponents, VCU issuance and retirement" | A retirada é tema público; os campos não são listados aqui. | [FONTE] |
| R2-2 | Registration and Issuance v5.0, §4.6.2 a 4.6.3, p.24; Diagrama 9 | "All and any VCU retirements shall be initiated by the registry account holder."; "The Verra Registry records the details of all VCU retirements."; diagrama: "Account holder or authorized representative" executa | Quem retira é o **titular da conta** (ou representante autorizado). O registro grava os detalhes (não diz quais). | [FONTE]. Que "authorized representative" seja pessoa física, e o que a definição exige: [HIPÓTESE]; Program Definitions não obtido |
| R2-3 | Idem, §4.6.7 e 4.6.10, p.25 | "The registry account holder, the other GHG program in which the registry account holder is participating, or Verra may initiate a VCU cancellation." | Cancelamento (distinto de retirada) pode partir do titular, de outro programa ou da Verra. | [FONTE] |
| R2-4 | VCS Standard v5.0, §3.21, p.68-69 | Zero ocorrências de "retire"/"retirement"/"beneficiar" no documento; "cancel" só trata de créditos de **outros** programas | O Standard não regula quem aposenta. | [FONTE] (busca textual) |
| R2-5 | Verra Registry ToU jul/2026, cláusula 4.2 (a) e (c), p.5 | "the applicant is not an individual (being a natural person)"; "sufficient identification and other information to satisfy any relevant Know-Your-Client (KYC)" | Titular de conta **não pode ser pessoa física**; KYC na abertura e continuado (4.4). | [FONTE] |
| R2-6 | Idem, cláusulas 1.5 e 1.6, p.3-4 | "retiring or cancelling Instruments on behalf of third parties" exige "prior written notice to Verra each time"; "Know-Your-Client checks have been conducted on each of the customers it is undertaking the business activities on behalf of" | Retirar por conta de terceiros exige aviso prévio à Verra e KYC do cliente, feito por quem retira. | [FONTE] |
| R2-7 | Idem, cláusula 8 (8.1 a 8.4), p.8-9 | "any instruction ... to cancel or retire Instruments ... is irrevocable"; "Account Holders may request minor amendments to retirement details (e.g. typos or incorrect spelling) ... within a 6-month period" | A ordem é do titular. Irrevogável, **mas** a Verra aceita correção de "retirement details" em até 6 meses, a critério dela. | [FONTE]. Contraste com INV-05/INV-16 (correção não reescreve): [HIPÓTESE] |
| R2-8 | Idem, cláusula 16 (parágrafo sobre cancelamento/retirada; sem número na extração), p.24 | "the following information ... shall be subject to public disclosure": "(a) the number ... (b) the vintage and serial numbers ... (c) the date ... (d) the name, type and identification number of the Project Activity and the location ... (e) if applicable, and voluntarily disclosed by the Account Holder to Verra, a statement to the effect that the cancellation or retirement of the Instrument was on behalf of another person or organization and the reason" | **Piso público**: quantidade, vintage/séries, data, projeto. **Fora da lista**: nome do titular, do usuário executor, do beneficiário. Beneficiário (declaração "on behalf of") e motivo só se o titular divulgar **voluntariamente**. | [FONTE]. Que relatórios públicos mostrem mais (nome do titular, por exemplo): [NÃO ACESSADO] (colunas não vistas) |
| R2-9 | Idem, cláusula 11, covenants (a), p.13 | "responsible under these Terms of Use for all acts and omissions of any User accessing the Verra Registry through the Account Holder's Verra Registry Account" | A responsabilidade pelo ato de qualquer usuário é do **titular (organização)**. | [FONTE] |
| R2-10 | Idem, cláusula 18 (18.1-18.2), p.25-26 | titular garante "all necessary consents and provided all required notices" para dados pessoais de indivíduos, conforme "applicable data protection laws" | Dado pessoal de indivíduo é ônus do titular; nenhuma exigência de publicar. | [FONTE] |
| R2-11 | Verra Registry User Guide v1.0, §12.3, p.83-87 | "The Beneficial Owner is the individual or corporation claiming the ultimate Environmental Benefit(s) from the retirement of a unit."; "Fill the Beneficial Owner, Retirement Reason, and Retirement Reason Details fields. Use the available toggles to indicate whether you want to make this information public."; "Retirement is an irreversible process ... In exceptional circumstances, the registry may allow retirement reversal" | O fluxo pede beneficiário (pessoa ou empresa), motivo e **escolha de publicidade por campo**. Não pede o usuário que clica. | [FONTE]. Se o preenchimento do beneficiário é obrigatório (o ToU 16 diz "voluntarily"): o guia não diz; [HIPÓTESE] de que o campo é de preenchimento e a publicidade é opcional |
| R2-12 | Idem, §3.1.5 (p.12), §3.2.2 (p.22), §7 (p.40-41) | Retail Aggregator: "recommended for organizations that purchase units to retire them on behalf of third parties"; KYC: "Account Manager identification document (valid government-issued photo ID)"; papéis Administrator/Contributor/Reader; "the Action Log provides a complete record of all actions taken on the account" | O indivíduo é identificado no **KYC da abertura** (gerente da conta). Há trilha por ação dentro da conta (Action Log); nada diz que seja pública. | [FONTE] |
| R2-13 | Idem, §8.3.2, p.52; §12, p.70 | "The Public Reports tab provides publicly available information for projects, issuances, holdings, retirements, cancellations, and Buffers"; "Users may request cancellation of active Units by contacting the Verra Registry Administration Team" | Há relatório público de retiradas e cancelamentos; cancelamento é por pedido à administração. | [FONTE]; colunas: [NÃO ACESSADO] |
| R2-14 | (síntese Verra) | n/a | Nenhum documento Verra lido exige identificar a **pessoa física executora** no evento de retirada, nem proíbe divulgar o nome do titular; o piso público (R2-8) não inclui nome algum. | [FONTE] para "não encontrei" nos sete documentos Verra; ressalva: Program Definitions e colunas dos relatórios públicos não vistos |
| R2-15 | ART Registry ToU v3, §9(a), p.10 | "Registry Participant will only hold or retire in its accounts TREES Credits ... for which it is the sole holder of all legal Title and all Beneficial Ownership Rights"; não pode reter/retirar "on behalf of one or more third parties" | Regra geral: o participante (organização com perfil) só retira o que é seu. | [FONTE] |
| R2-16 | Idem, §9(b)(ii)-(iv), p.10-11 (retirada "omnibus") | "retirement or cancellation is effected only in the manner set forth in the ART Registry Operating Procedures, inclusive of identifying (A) any Indirect Owner making an environmental claim and (B) the purpose of retirement"; "solely on behalf of the applicable Indirect Owner who is itself the final beneficiary" | Retirada por conta de terceiro: **identificar o beneficiário final e o propósito é exigência**. Campos exatos: nos Operating Procedures. | [FONTE]. Operating Procedures: [NÃO ACESSADO] |
| R2-17 | Idem, §9(d)(i) e (v), p.12 (agregador de varejo) | "(i) ... an active, public profile, resulting in the entity's name being available on the public report of Registry Participants"; "(v) ... identifying (A) any Retail Purchaser making an environmental claim and (B) the purpose of retirement (for the avoidance of doubt, for privacy reasons, individual persons do not need to be identified by name)" | **Dispensa expressa de nomear pessoas físicas**, por privacidade. Nome público: só o da entidade agregadora/custodial. | [FONTE] |
| R2-18 | Idem, §9(f), p.14; §9(c)(iv), p.11 | "maintain sufficient auditable records matching specific Indirect Owners or Retail Purchasers ... to the carbon credits and quantities transacted, cancelled, or retired and the dates those actions were effected on the Registry, which will be made available to Administrator upon request"; "auditable customer identification program ... to verify the identity of any individual(s) or organization(s) on whose behalf Registry Participant is holding" | Registro **privado** do participante, ligando beneficiário a créditos, quantidades e datas, entregue ao administrador **a pedido**. É o padrão da exigência: identidade fora do relatório público, resolvível sob demanda. | [FONTE] |
| R2-19 | Idem, §7(b) e definições "Users" e "Designated Signatory", p.6; §19(b)(iii)-(iv), p.26 | "Designated Signatory" = "the individual within the Registry Participant's organization who is authorized to denote the Registry Participant's agreement to these Terms of Use"; Users = "all individuals ... accessing the Registry via a login ID associated with the Registry profile"; informação confidencial inclui "Total number of ... Credits in a Registry Participant's account" e "The amount and timing of specific ... transfers" | Indivíduos existem como signatário (abertura) e como Users com login. Saldo e transferências são confidenciais; retirada não consta da lista de confidencial e é pública (R2-20). | [FONTE] |
| R2-20 | TREES 3.0: §13.3 p.85; §15.1 p.88; Glossário "Cancel" p.93 e "Retire" p.97; Anexo A.2 itens 3-4 p.102; A.3 item 3 p.106 | "All TREES Credit retirements and cancellations will be transparently recorded in public reports on the ART Registry."; "A retired credit may be applied toward the emission reduction target of the ART Account Holder ... or on behalf of a third party"; "For cancellations of units for the CORSIA, the cancellation information will specify the aeroplane operator for which the offset credits were cancelled and the calendar year"; "Quantity of CORSIA Eligible credits cancelled by aeroplane operator for each CORSIA compliance period" | Retiradas e cancelamentos são **públicos em relatórios**. No CORSIA, a informação de cancelamento identifica o **operador aéreo** (organização) e o ano. Em nenhum ponto o executor individual. | [FONTE]; é a descrição da ART do que o CORSIA pede, não o texto da ICAO |
| R2-21 | Decreto 12.677/2025, Art. 4º (acrescenta Art. 60-C, III e Art. 60-A ao Decreto 11.907) e Art. 6º §1º; Decreto 12.768/2025 | "criar, manter e gerir o Registro Central do SBCE" (competência da Subsecretaria de Implementação); a SEMC exerce, de forma temporária, competências do órgão gestor do Art. 8º da Lei | Atribui competência sobre o Registro Central; **não** disciplina cancelamento nem dados do cancelamento. 0 ocorrências de "cancelament", "baixa de ativ", "aposent". | [FONTE]. Se algum inciso do Art. 8º da Lei citado nos decretos cobre a regulação do cancelamento: [NÃO VERIFICADO] (Lei não relida) |

##### O que a leitura dos PDFs mudou em relação à rodada 1 (páginas web)

1. **Confirmado:** o nível da exigência é a organização titular (Verra: conta de não indivíduo, R2-5; ART: perfil, R2-15). Nenhum documento lido exige a pessoa física que executou. A rodada 1 dizia "não encontrei" sem ter lido os documentos que mais provavelmente a exigiriam (linhas 11 e 14 da Tabela 1); agora foram lidos e a ausência se mantém.
2. **Novo, sobre divulgação do nome (pergunta especial):** (i) o piso público da Verra não inclui nome de titular, de usuário nem de beneficiário, e a declaração "on behalf of" é voluntária (R2-8); (ii) a ART **dispensa expressamente nomear pessoas físicas**, por privacidade (R2-17). **Nenhuma fonte lida exige divulgar publicamente o nome do retirante pessoa física, e nenhuma o proíbe nominalmente.** As duas empurram para o mínimo (confidencialidade em R2-19, dados pessoais em R2-10). Ressalvas: a ART torna públicos retiradas e cancelamentos (R2-20), com campos nos Operating Procedures não lidos; e CORSIA/ICAO e SBCE não foram lidos.
3. **Novo, sobre o beneficiário:** a ART exige **identificar o beneficiário final e o propósito** na retirada por terceiros, e exige que o participante guarde registro auditável que ligue o beneficiário ao crédito, entregue ao administrador a pedido (R2-16, R2-18). Isto não estava na rodada 1. Efeito sobre INV-14: o beneficiário opaco (hash) só é compatível com esse modelo se existir um registro resolvível fora do relatório público [HIPÓTESE].
4. **Corrigido:** a rodada 1 tratava como [HIPÓTESE] que "identifying information" do CORSIA Cancellation Report se referisse às unidades e não ao autor. O TREES 3.0, Anexo A.2 item 4, diz que a informação de cancelamento especifica o **operador aéreo** (R2-20). Para cancelamento CORSIA, portanto, a organização é identificada. Continua a ressalva: é a ART descrevendo o CORSIA; o texto da ICAO não foi lido.
5. **Novo:** a Verra admite correção de "retirement details" em 6 meses e, em circunstâncias excepcionais, reversão de retirada (R2-7, R2-11). O ledger deste projeto tem INV-05 (terminal) e INV-16 (correção é registro novo). Não é conflito de norma, mas o humano deve saber que o programa-modelo não é imutável no detalhe.
6. **Novo, responsabilidade:** o titular responde por qualquer usuário da conta (R2-9). A unidade de responsabilidade normativa é a organização, não o indivíduo.
7. **Sem efeito:** os decretos (R2-21) não mudam nada para o SBCE; a linha 4 da Tabela 1 (ato do órgão gestor) segue pendente.

##### Reavaliação de (d)

**(d) fecha para o escopo Verra e ART, no nível "organização titular da conta/perfil". Não fecha para CORSIA (texto da ICAO inacessível) nem para o SBCE (ato do órgão gestor não localizado).** Cláusulas:

| Programa | Nível exigido para quem aposentou | Beneficiário final | Pessoa física executora | Público | Cláusulas |
|---|---|---|---|---|---|
| Verra VCS | Organização titular da conta (conta de pessoa física é vedada) | Informado no fluxo; divulgação pública **voluntária** | Não exigida | Piso: quantidade, vintage/séries, data, projeto | ToU 4.2(a), 1.5-1.6, 8, 11 (covenants), 16; R&I v5.0 §4.6.2-4.6.3; User Guide §12.3 |
| ART TREES | Organização titular do perfil | **Obrigatório** identificar beneficiário final e propósito quando retira por terceiros; pessoa física não precisa ser nomeada | Não exigida (Users com login existem; signatário só na abertura) | Retiradas e cancelamentos em relatório público; saldo e transferências confidenciais | ToU v3 §9(a), 9(b), 9(d)(v), 9(f), 19(b); TREES 3.0 §13.3, Anexo A.2 |
| CORSIA | Operador aéreo (via descrição da ART) | n/a | Não exigida pelo que a ART descreve | Informado por operador e ano | TREES 3.0 Anexo A.2 item 4 e A.3 item 3. **Texto ICAO NÃO ACESSADO** |
| SBCE | A definir | A definir (Lei Art. 45: quem compensa, rodada 1) | A definir | A definir | Lei Art. 5º VIII, 13, 24 VI (rodada 1); ato do órgão gestor **pendente** |

Se o humano aceitar fechar por escopo (Verra e ART), o nível é **organização**. Se o produto é voltado ao SBCE (créditos no Brasil), (d) continua **aberta** até o ato do órgão gestor. A escolha de escopo é do humano.

Ressalvas que **limitam** o uso de "organização" como resposta: (1) o MSP deste projeto só corresponde ao "titular" quando a organização aposenta crédito **próprio**; quando um MSP (por exemplo o Marketplace) aposenta por clientes, o paralelo é o agregador/custodial da ART (R2-17, R2-18) ou o Retail Aggregator da Verra (R2-12), e o que a norma exige passa a ser a identificação do **beneficiário** com registro resolvível, não a do MSP [HIPÓTESE]; (2) análise de proteção de dados (LGPD ou outra) e de prazo de guarda: **não feita**; nenhum documento lido fixa prazo de retenção do registro de retirada (ART ToU §22(c), p.30, só diz "data backup and retention procedures"; Verra 6.7 fixa seis anos para a obrigação de compensar sobre-emissão, que é outra coisa).

#### Análise: o que o resultado parcial de (d) elimina ou favorece em A1-A6

> **Isto é análise, não decisão.** Todas as linhas são [HIPÓTESE] sobre um resultado parcial. O humano decide (a) e (b).

Nenhuma opção é **eliminada** por fonte. O resultado desloca pesos:

| Opção | Efeito do resultado parcial |
|---|---|
| A1 (digest, sal fixo público, recomputação) | Não eliminada. Serve como estado de espera: nada lido exige mais do que o contrato já grava (MSP em claro + digest), e mantém a informação caso (d) feche em "pessoa física". Mantém a limitação (b) e a correlação (contexto 4). |
| A2 (registro off-chain) | Não eliminada; desfavorecida. Cria alvo de dado pessoal e confiança fora do ledger sem exigência encontrada que a justifique. Se a "rastreabilidade do cancelamento" (SBCE Art. 5º, VIII) exigir o autor no registro, o off-chain como única prova seria fraco; depende de ler o ato do órgão gestor (linha 4). |
| A3 (coleção privada) | Não eliminada. O ponto frágil é `blockToLive` contra eventual guarda longa. Nenhuma fonte lida fixa prazo de guarda, então não há base para eliminar nem escolher; prazo: a definir com o humano (regra 8 do projeto). |
| A4 (HMAC, segredo por organização) e A5 (nonce por aposentadoria) | Não eliminadas; as mais desfavorecidas. Custódia de chave ou nonce fora do contrato só se justifica se a auditoria em nível de pessoa física for exigida, e nenhuma fonte lida exige. A5 ainda tira a rastreabilidade determinística, o que iria contra uma leitura forte do Art. 5º, VIII. |
| A6 (gravar só o MSP) | Favorecida **se** (d) fechar em "a organização basta", que é o que a amostra lida indica (linhas 3, 9, 12, 13, 15) e é o que INV-14 literal já pede (contexto 6). Resolve (b) por construção. Duas ressalvas: a amostra é incompleta, e a escolha é irreversível para o que deixar de ser gravado (aposentadorias futuras sem o digest não podem ser reconstituídas). |

#### Análise R2: o que a leitura dos PDFs muda em A1-A6

> **Isto é análise, não decisão.** Tudo que não for citação direta da seção anterior é [HIPÓTESE]. A tabela da rodada 1 acima foi mantida; esta a atualiza.

| Opção | Mudança em relação à rodada 1 |
|---|---|
| A1 (digest, sal fixo público, recomputação) | **Mais desfavorecida.** Nenhuma fonte lida exige o ator individual (R2-14, R2-20), e as que tratam de divulgação vão ao mínimo (R2-8, R2-17). A1 grava, para todo o canal, um identificador **determinístico** de pessoa (correlação entre créditos, contexto 4) que nenhuma norma lida pede. Não é proibida: nenhuma fonte veda gravar. O argumento contra A1 é de minimização, não de ilegalidade. O padrão das duas fontes é identidade fora do relatório público e resolvível sob demanda (R2-18); A1 coloca no canal um identificador recomputável por quem tenha o DN. O canal Fabric não é "público" no sentido das normas (R2-8), mas o evento `CreditoAposentado` leva o campo a todo ouvinte (contexto 5). |
| A2 (registro off-chain) | **Menos desfavorecida do que na rodada 1, para o beneficiário.** A ART faz isto: o participante guarda registro auditável fora do relatório público e o entrega ao administrador a pedido (R2-18). A objeção "off-chain como única prova é fraca" continua valendo. Para o **ator individual**, ainda não há exigência que justifique criar o alvo de dado pessoal. |
| A3 (coleção privada) | **Mais favorecida para o beneficiário, neutra para o ator.** O padrão da ART (R2-18: registro ligando beneficiário a crédito, quantidade e data, acessível ao administrador) é o que uma PDC com o auditor como membro reproduz, com âncora de hash no ledger. Sobre `blockToLive`: nenhuma fonte lida fixa prazo de guarda, então o conflito com "guarda longa" segue **sem número**; prazo: `a definir com o humano`. |
| A4 (HMAC) e A5 (nonce) | **Sem mudança; continuam as mais desfavorecidas.** Custódia de chave ou nonce por pessoa física só se justificaria com exigência de auditoria individual, que não foi achada. |
| A6 (gravar só o MSP) | **Mais favorecida, dentro do escopo Verra/ART.** O nível exigido é a organização, e a responsabilidade pelo ato de qualquer usuário é dela (R2-9, R2-5, R2-15); isso é o que INV-14 literal já grava. Resolve (b) por construção **apenas no estado**. Limites: (i) o MSP é o "titular" só quando aposenta crédito próprio (ressalva 1 de "Reavaliação de (d)"); (ii) o MSP não resolve o **beneficiário** que a ART exige identificar em retirada por terceiros, que é matéria de INV-14/(a), não do ator; (iii) a irreversibilidade (aposentadorias sem digest não se reconstituem) continua. |

**Ponto técnico a verificar, que afeta A6 e (b) (não vem dos PDFs):** [HIPÓTESE] o envelope de toda transação Fabric grava o certificado do criador da transação no bloco. Se for assim, mesmo sem `aposentadoPorId` no estado, a identidade individual continua recuperável por quem lê os blocos do canal, e "A6 resolve (b) por construção" valeria só para o estado, não para o ledger. Não verifiquei isto aqui; confirmar com `fabric-rede` e o critic antes de usar o argumento. Nenhuma das opções A1-A6 altera esse fato.

#### Sugestão do orquestrador (registrada para o humano avaliar; não é decisão)

- **S1.** Manter A1 provisoriamente (referência `sha256:` com sal fixo e público; gateway só valida formato) até (d) fechar.
- **S2.** Se (d) mostrar que basta identificar a organização, adotar A6 (gravar só o MSP).

Avaliação do domínio (hipótese, a confirmar pelo humano):

1. S1 é coerente com a análise acima: é o comportamento atual (seção Consequências), tem custo zero e preserva informação. O custo é manter a limitação (b) e a correlação durante a espera.
2. **Precisão sobre S1:** "gateway só valida formato" vale para o **beneficiário** (`api-gateway/.../dto/LimitesContrato.java:36`, `sha256:[0-9a-f]{64}`). O `aposentadoPorId` não passa pelo gateway: é calculado dentro do contrato a partir da identidade assinante (contexto 7).
3. A ordem S1 -> S2 é a que perde menos: guardar o digest e depois parar é possível; parar e depois querer voltar não recupera o intervalo sem digest.
4. S2 só é bem fundada depois de ler os documentos das linhas 4, 11, 14 e 16. *(R2: 11 e 14 lidos; restam 4 (ato do SBCE) e 16 (ICAO, HTTP 403).)* Se adotada, muda `RedContract.aposentarCredito`, o teste `RedContractTest.java:606-607`, o campo `aposentadoPorId` e o evento; passa pelo loop 1; e reabre (c) (convivência com créditos já aposentados).

Atualização das sugestões à luz da rodada 2 (hipótese, a confirmar pelo humano):

5. **S1 (manter A1 provisoriamente) continua defensável só pelo custo de mudar agora, não por exigência normativa.** A rodada 2 tirou o apoio que A1 tinha na rodada 1 ("mantém a informação caso (d) feche em pessoa física"): nos documentos lidos, (d) não fecha em pessoa física. Resta o argumento operacional (reversibilidade: guardar o digest e depois parar é possível; o inverso não) e o fato de que nenhuma fonte veda gravá-lo.
6. **S2 (adotar A6 se bastar a organização) passa a ter fonte para Verra e ART** (quadro "Reavaliação de (d)"). O gatilho "se (d) mostrar que basta a organização" só se cumpre integralmente se o humano aceitar fechar por escopo; para SBCE e CORSIA não foi verificado.
7. **Ponto novo, fora de S1/S2:** o que a norma-modelo mais exige (ART §9(b), §9(d)(v), §9(f)) é identificar o **beneficiário** e guardar registro resolvível, não o ator. Isto pesa sobre (a) para o beneficiário: o digest do beneficiário que o contrato recebe (INV-14) hoje não tem caminho de resolução (contexto 7). Para seguir o padrão da ART, A2/A3 se aplicariam ao beneficiário, mesmo que o ator vá para A6. Não escolho; registro como dependência entre (a) e INV-14.

#### Para fechar (d)

Precisa de ferramenta que extraia texto de PDF (o `webfetch` devolveu binário). Documentos a ler, com os endereços obtidos nesta consulta:

- Verra: `https://verra.org/documents/vcs-standard-v5-0/`, `https://verra.org/documents/vcs-program-guide-v5-0/`, `https://verra.org/documents/registration-and-issuance-process-v5-0/`; Registry Terms of Use (jul/2026), listado em `https://verra.org/registry/` (endereço direto não obtido).
- ART: `https://www.artredd.org/wp-content/uploads/2026/06/TREES-3.0-Final-Version-English.pdf` e `https://artredd.org/wp-content/uploads/2026/03/art-registry-terms-of-use-v3.pdf`.
- CORSIA: Annex 16 Vol IV (2ª ed.) via `https://www.icao.int/CORSIA/sarps-annex-16-volume-iv`, em particular Tabela A5-7 campo 5; `https://www.icao.int/sites/default/files/sp-files/environmental-protection/CORSIA/Documents/ICAO_Document_09.pdf`.
- SBCE: ato do órgão gestor sobre cancelamento e Registro Central (Art. 13 e Art. 23, parágrafo único); não localizado.

*(R2, 2026-11-09: a lista acima foi cumprida para Verra e ART, salvo os satélites abaixo. Resta, em ordem de peso para (d): (1) ato do órgão gestor do SBCE sobre cancelamento e Registro Central; (2) ICAO Annex 16 Vol IV Tabela A5-7 campo 5 e Doc 09, que exigem outro meio de acesso (HTTP 403), por exemplo download manual pelo humano; (3) ART Registry Operating Procedures (campos exatos do evento de retirada); (4) VCS Program Definitions (alcance de "authorized representative"); (5) colunas efetivas dos relatórios públicos de Verra e ART; (6) texto atual da Lei 15.042/2024 em PDF.)*

Em cada um, anotar: (i) quais campos do evento de retirada/cancelamento são obrigatórios; (ii) quais aparecem em relatório público; (iii) se o usuário individual ou só a conta/perfil. Distinguir o que o **registro** exige de **terceiros** (beneficiário final) do que a **organização** precisa reter internamente. A seção 4 de `dominio-redd.md` permanece hipótese até lá.

## Decisão

Pendente. Nenhuma opção escolhida. Sem fonte normativa, qualquer escolha hoje seria opinião.

- Opção para (a) e (b): a definir com o humano
- Opção para (c): a definir com o humano
- Nível de identificação exigido por norma (d): a definir com o humano. Consulta de 2026-11-09, rodada 2: nível **organização titular** para Verra e ART (cláusulas em "Reavaliação de (d)"); CORSIA (texto ICAO) e SBCE (ato do órgão gestor) **não fechados**. O humano decide se fecha por escopo.
- O ator em `aposentadoPorId` deve ser obrigatório na especificação (INV-14)? A definir com o humano

## Consequências

- Até a decisão, o comportamento da L1-T5 continua em vigor: referência `sha256:` com sal fixo e público, determinística, sem caminho de resolução.
- Toda afirmação de auditabilidade do ator deve citar este ADR e a limitação (b): a referência não impede identificação por dicionário e, por si só, não permite resolver o ator.
- A1: nenhuma mudança. A2/A3: afetam `api-gateway/` e/ou `rede-fabric/`; A3 exige também mudança no chaincode. A4/A5/A6: alteram o cálculo e o teste de `referenciaOpacaDoAutor` (`RedContractTest.java:606-607`); todo crédito aposentado depois da mudança terá referência de outro formato, então a versão do sal (`v1`) precisa mudar e a convivência de versões precisa de regra.
- Em qualquer opção que mude o formato: passa pelo loop 1 (critic) e atualiza INV-14 e `dominio-redd.md`.
- Não afeta o simulador nem as métricas.

## Verificação

Para quando houver decisão (nada disto foi executado):

- Teste que fixa o formato escolhido para `aposentadoPorId` (substitui `RedContractTest.java:606-607`).
- Para A1-A3: procedimento de auditoria com um caso real de teste: dado um `aposentadoPorId`, o auditor autorizado chega ao ator; um não autorizado não chega.
- Para A4/A5: demonstração de que um dicionário de DNs conhecidos não recupera a referência sem o segredo.
- Para (c): inventário dos créditos aposentados antes da L1-T5 (quantidade e rede), registrado neste ADR.
- `python3 scripts/lint/lint-chaincode.py chaincode/src/main/java` (CC-014) e revisão do critic.
- O humano registra a opção e muda o estado para Aceito.


## Atualização 09/11/2026: hipótese do creator no bloco VERIFICADA (fabric-rede)

Medido na rede de desenvolvimento (canal `redchannel`, chaincode `redd-creditos` v1.0): **o certificado X.509 de quem submeteu a transação fica gravado no bloco, em texto recuperável.** Campos: `payload.header.signature_header.creator` (`mspid` e `id_bytes`, PEM de cerca de 843 bytes, sem cifra nem hash), repetido em `actions[].header.creator`; mais o certificado de cada peer endossante em `endorsements[].endorser` (2 por transação). O CN aparece no subject (formato `Admin@marketplace.redd.network` nas identidades de teste do cryptogen; em produção seria o CN real). O bloco é imutável e replicado nas 3 organizações. O campo `aposentadoPorId` do estado guarda só o digest opaco.

**Consequência (análise, não decisão):** a opção A6 (gravar só o MSP no estado) limpa o estado, mas **não** apaga o certificado/CN do ledger; vale também para A1 e as demais, pois o envelope sempre existe. Resolver exigiria identidades de transação pseudonimizadas (CN opaco, emitidas pela CA da organização) ou aceitar que o ator é recuperável por quem lê o bloco. Isso desloca o problema de "o que o contrato grava" para "que identidade a organização usa para assinar", o que pertence a `fabric-rede`/CA e ao jurídico. Atenção: o que a leitura dos PDFs mostrou (Verra/ART exigem a organização titular, não a pessoa física) é coerente com identidades de transação **organizacionais** (CN de serviço, não de pessoa), o que reduziria o problema sem mudar o contrato. Decisão: a definir com o humano.
