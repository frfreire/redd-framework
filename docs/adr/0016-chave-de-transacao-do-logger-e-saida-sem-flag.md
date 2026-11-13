# ADR-0016: chave de transação do logger por id sequencial e saída sem `-XX:hashCode=2`

- **Estado:** Aceito (decisão do humano em 13/11/2026)
- **Data:** 2026-11-13
- **Decisores:** Fabricio (humano).
- **Trava:** a aprovação da tarefa `LOGGER-chave-txid-sequencial` (loop 2). Não trava a bateria do HotStuff.

## Contexto
- `-XX:hashCode=2` é exigido por `--load-record=true` e pela `GarantiaHashCode` (M1a). Com ela, `identityHashCode` é constante. [FONTE] `experimentos/piloto-timeout-hotstuff/RELATORIO.md` §9.2.
- O piloto atribuiu o custo (4 a 104 vezes, pior em L=500) a `HashSet<HotStuffTx>`. O `jabs-builder` refutou isso por medição (HOTSTUFF ROTATING n=4 L=500 30 s semente 16346): o gargalo é `jabs.ledgerdata.Hash` sem `hashCode/equals` por valor, usado como chave no `EnhancedBlockFinalizationLogger` e no `DoubleSpendTracker`. HEAD: 58,67 s com a flag. Só `HotStuffTx` corrigido: 44,85 s. Só `Hash` corrigido: 1,57 s. [FONTE] relatório do builder de 13/11; script `scripts/jabs/medir-hashcode-hotstuff.py`.
- A correção menos invasiva (id sequencial por simulação no logger) deixa idênticas, com a flag, as saídas de 12 células e o determinismo de HOTSTUFF, PBFT, ARQ, TENDERMINT, MCO2, AMBIFY, TREE, EARTH e DAGSPER. Sem a flag, mudam AMBIFY (n=10 e 25), MCO2 n=50 e OFFSET n=40, porque a nova versão consome mais `identityHashCode` da thread principal por evento. [FONTE] relatório do builder; [NÃO VERIFICADO] contra o HEAD atual `d6daa3c` (gap do critic, critério 7).

## Opções
1. Aceitar a mudança sem a flag nos protocolos Ethereum, com identidade garantida com a flag.
2. Restaurar a sequência de `identityHashCode` (invasivo, sem causa isolada).
3. Corrigir `Hash` por valor (muda a ordem de iteração em todos os protocolos).

## Decisão
Opção 1. As baterias e a `GarantiaHashCode` exigem a flag, e o HEAD sem a flag já diverge dela nessas células. A opção 3 fica para uma tarefa posterior, só se esta não bastar.

## Consequências
- Baselines antigas de MCO2, AMBIFY e OFFSET **sem flag** deixam de servir (`nao_regressao.py` falha contra elas). Devem ser regeradas com a flag.
- Contagem de transações, Tt e double spend não podem mudar.

## Verificação
Comparação reproduzível contra build do HEAD `d6daa3c`: com a flag, saída idêntica; sem a flag, mudança só em protocolos Ethereum; determinismo e `mvn test` verdes.
