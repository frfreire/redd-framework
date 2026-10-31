package jabs.consensus.algorithm.hotstuff;

import jabs.ledgerdata.hotstuff.HotStuffBlock;

import java.util.Optional;

/**
 * Vista IMUTAVEL da arvore local de blocos de uma replica: responde "quem e o pai de {@code block}?" sem usar
 * {@code Block#getParents()} (lista mutavel, divida da base) nem {@code SingleParentBlock#getParent()} (que le essa
 * mesma lista).
 *
 * <p>Por que existe: {@link HotStuffBlock} so expoe de forma imutavel a altura ({@code getHeight()}, campo
 * {@code final}) e a identidade da referencia; o pai so existe na lista mutavel. Quem chama {@link SafetyRule}
 * fornece o pai a partir da propria arvore local (a camada de rede da tarefa 2c-2 a mantem), e o resultado de
 * {@code safeNode} deixa de depender de um objeto que qualquer codigo poderia alterar.
 *
 * <p>Contrato: {@code parentOf(b)} devolve {@link Optional#empty()} para o genesis ou para um bloco cujo pai a
 * replica ainda nao conhece; o pai devolvido tem altura ESTRITAMENTE menor que a de {@code b}
 * ({@link SafetyRule} lanca {@link IllegalArgumentException} se isso for violado, o que tambem garante que a
 * subida termina). Blocos sao comparados por identidade de referencia: {@code HotStuffBlock} nao define
 * {@code equals/hashCode} e o simulador compartilha a instancia.
 */
@FunctionalInterface
public interface ParentLookup {
    /**
     * Pai de {@code block} na arvore local.
     *
     * @param block bloco (nao nulo)
     * @return pai, ou vazio se desconhecido/genesis
     */
    Optional<HotStuffBlock> parentOf(HotStuffBlock block);
}
