# ADR-0016: chave de transação do logger por id sequencial e saída sem `-XX:hashCode=2`

- **Estado:** Aceito (decisão do humano em 13/11/2026)
- **Data:** 2026-11-13
- **Decisores:** Fabricio (humano).
- **Trava:** a aprovação da tarefa `LOGGER-chave-txid-sequencial` (loop 2). Não trava a bateria do HotStuff.

## Contexto
- `-XX:hashCode=2` é exigido por `--load-record=true` e pela `GarantiaHashCode` (M1a). Com ela, `identityHashCode` é constante. [FONTE] `experimentos/piloto-timeout-hotstuff/RELATORIO.md` §9.2.
- O piloto atribuiu o custo (4 a 104 vezes, pior em L=500) a `HashSet<HotStuffTx>`. O `jabs-builder` refutou isso por medição (HOTSTUFF ROTATING n=4 L=500 30 s semente 16346): o gargalo é `jabs.ledgerdata.Hash` sem `hashCode/equals` por valor, usado como chave no `EnhancedBlockFinalizationLogger` e no `DoubleSpendTracker`. HEAD: 58,67 s com a flag. Só `HotStuffTx` corrigido: 44,85 s. Só `Hash` corrigido: 1,57 s. [FONTE] relatório do builder de 13/11; script `scripts/jabs/medir-hashcode-hotstuff.py`.
- A correção menos invasiva (id sequencial por simulação no logger) deixa idênticas, com a flag, as saídas de 12 células e o determinismo de HOTSTUFF, PBFT, ARQ, TENDERMINT, MCO2, AMBIFY, TREE, EARTH e DAGSPER. Sem a flag, mudam AMBIFY (n=10 e 25), MCO2 n=50 e OFFSET n=40, porque a nova versão consome mais `identityHashCode` da thread principal por evento (evento 1: (1,1,1) no HEAD, (2,3,1) na versão nova). [FONTE] relatório do builder; comparação reproduzível contra o build do HEAD `819931e` (git archive), 13 células, com e sem flag, semente 16346: com a flag 13/13 idênticas; sem a flag só as quatro células Ethereum diferem; o controle base×base2 (HEAD sem flag duas vezes) é idêntico em todas. [FONTE] relatório do builder, rodada 2.
- **Correção (13/11/2026).** A versão anterior deste ADR dizia que o HEAD sem a flag "já diverge" entre execuções. Isso foi refutado pelo controle base×base2: o HEAD sem flag é estável entre execuções. O que difere é a saída sem flag entre HEAD e código novo, e o HEAD sem flag em relação ao HEAD com flag nas quatro células Ethereum. Também dizia que Tt não pode mudar; isso só vale **com** a flag (ver Consequências).

## Opções
1. Aceitar a mudança sem a flag nos protocolos Ethereum, com identidade garantida com a flag.
2. Restaurar a sequência de `identityHashCode` (invasivo, sem causa isolada).
3. Corrigir `Hash` por valor (muda a ordem de iteração em todos os protocolos).

## Decisão
Opção 1. As baterias e a `GarantiaHashCode` exigem a flag, e com a flag a saída é idêntica ao HEAD. Sem a flag o HEAD já difere de si mesmo com flag nas quatro células Ethereum, então a saída sem flag desses protocolos não é referência de pesquisa. A opção 3 fica para uma tarefa posterior, só se esta não bastar.

## Consequências
- Baselines antigas de MCO2, AMBIFY e OFFSET **sem flag** deixam de servir (`nao_regressao.py` falha contra elas). Devem ser regeradas com a flag.
- **Com a flag:** contagem de transações, Tt, Tb, Cb e double spend não mudam (identidade de todos os CSVs).
- **Sem a flag, nos protocolos Ethereum, Tt, Tb, Cb e o número de linhas por bloco mudam**, e não por pouco: AMBIFY n=10 Tt 4,42 -> 6,21; AMBIFY n=25 85,37 -> 58,27; MCO2 n=50 400,55 -> 444,62; OFFSET n=40 9545 -> 11196 (`mvp-results.csv`, semente 16346). Nenhum resultado de pesquisa pode vir de execução sem a flag nesses protocolos.
- Fora dos protocolos Ethereum, nada muda, com ou sem a flag.

## Verificação
`python3 scripts/jabs/comparar-chave-tx.py --base <build do HEAD> --commit <hash>` (script recusa base que não seja o HEAD): com a flag, saída idêntica; sem a flag, mudança só em AMBIFY, MCO2 e OFFSET; determinismo e `mvn test` verdes.
