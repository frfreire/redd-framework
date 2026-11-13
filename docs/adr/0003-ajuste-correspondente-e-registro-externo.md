# ADR-0003: Ajuste correspondente (Art. 6), autorização do país anfitrião e registro externo

- **Estado:** Aceito (2026-10-25, Fabricio)
- **Data:** 2026-10-25
- **Decisores:** Fabricio (humano). Nenhum agente aceita este ADR.
- **Trava:** INV-17 e INV-13(d) no chaincode; qualquer afirmação de que um crédito tem "ajuste correspondente"; integração com registro nacional ou de outro programa.

## Contexto
Quando um crédito é usado fora do país anfitrião, o Acordo de Paris (Art. 6) prevê ajuste correspondente no inventário do país. CORSIA exige autorização do país e ajuste correspondente para a unidade ser elegível. No Brasil, o SBCE prevê um registro central de ativos. O texto e as cláusulas dessas normas **não foram consultados** na elaboração de `especificacoes/dominio-redd.md`; qualquer cláusula citada deve ser confirmada pelo humano.

Fatos do repositório: o chaincode não tem campo, estado ou evento de autorização ou ajuste correspondente. O projeto está no Brasil (a confirmar pelo humano).

Dupla contagem entre o ledger e outros registros é o risco que o ledger sozinho não elimina: ele só enxerga o que foi registrado nele.

## Opções
1. **Fora do escopo.** O ledger é registro de MRV e créditos, não de autorização. A documentação diz isso e o contrato nunca usa a expressão "ajuste correspondente". Prós: nenhuma regra inventada. Contras: crédito aposentado para fim internacional não é conferido.
2. **Estado de autorização por crédito, preenchido por um papel autorizado.** Novo estado/atributo (ex.: não autorizado / autorizado / ajustado), quem registra (a definir: órgão nacional como organização nova, ou Verifier?) e bloqueio de aposentadoria para fim internacional sem autorização. Prós: INV-17 aplicável. Contras: exige um papel que não está nas 3 organizações atuais, e regra do órgão.
3. **Identificador externo obrigatório (INV-13d).** O projeto declara o número em outro registro; o ledger rejeita repetição. Não resolve ajuste, mas reduz dupla emissão entre registros. Compatível com 1 e 2.

## Decisão
Pendente. Opinião do `redd-dominio`, sem fonte: opção 1 combinada com 3, até haver regra do órgão competente. Não decide quem registra autorização. O humano decide.

- Opção escolhida: a definir com o humano
- Quem registra a autorização do país: a definir com o humano
- Fim da aposentadoria (doméstico vs internacional) como campo do ledger: a definir com o humano
- Integração com registro nacional (SBCE) e outros registros: a definir com o humano
- Reuso legítimo de `hashEvidencia` entre relatórios: a definir com o humano

## Registro do aceite

Aceito pelo humano em 2026-10-25 ("Aceito as ADR que estão pendentes"), adotando as recomendações do `redd-dominio` registradas na seção Decisão. 

- Opção escolhida: **1 combinada com 3**. O ledger não trata autorização nem ajuste correspondente e o contrato nunca usa a expressão "ajuste correspondente"; identificador externo opcional de projeto com unicidade (INV-13d).
- Quem registra autorização, fim da aposentadoria como campo, integração SBCE e reuso de `hashEvidencia`: fora do escopo agora; INV-13 mantém evidência única (sem reuso). Reabrir por novo ADR se houver regra do órgão competente.

## Consequências
- Opção 1: nenhuma mudança no chaincode além de não produzir a expressão "ajuste correspondente".
- Opção 2: nova organização ou papel (afeta `rede-fabric/`, política de endosso e INV-10), novos eventos e testes de INV-17.
- Opção 3: novo campo opcional de identificador externo e índice de unicidade (CouchDB).

## Verificação
- INV-17: grep do contrato sem a expressão "ajuste correspondente" (opção 1); teste de aposentadoria internacional sem autorização falha (opção 2).
- INV-13(d): projeto com identificador externo repetido falha (opção 3).
- Humano registra a opção neste arquivo e muda o estado para Aceito.
