package jabs.ledgerdata.pbft;

import jabs.network.node.nodes.Node;

/**
 * Bloco CONFLITANTE fabricado por um no bizantino ao equivocar o PREPARE (PBFT-prepare-equivocation, ADR-0015 B-35):
 * mesmo pai, altura, tamanho e instante de criacao do bloco real, identidade e hash distintos, sem transacoes.
 * Nenhum primario o propoe e ele nunca recebe PRE_PREPARE.
 *
 * <p>FUNCAO PURA de ({@code conflictsWith}, {@code creator}) (rodada 4, criterio 8): nenhum campo vem do relogio nem de
 * sorteio, e {@link #equals}/{@link #hashCode} sao por valor, entao o B de um mesmo A fabricado por um mesmo no e
 * sempre o mesmo objeto logico, mesmo que o objeto Java seja novo. Por isso o no bizantino NAO precisa guardar o B de
 * cada A (retencao O(A) eliminada): recria-o a cada PREPARE equivocado. Dois bizantinos distintos continuam fabricando
 * B distintos para o mesmo A (sem coordenacao, como antes). Blocos honestos nunca sao desta classe, entao o caminho sem
 * ataque nao e tocado.
 *
 * <p>Obs.: o {@code Hash} do bloco (campo de {@code Data}) e instanciado por objeto e nao participa de equals/hashCode;
 * nenhum caminho do PBFT indexa bloco conflitante por esse hash.
 */
public class PBFTConflictingBlock extends PBFTBlockWithTx {
    private final PBFTBlock conflictsWith;

    /** @param real bloco A cujo PREPARE foi equivocado; precisa ter pai (nao pode ser o genesis) */
    public PBFTConflictingBlock(PBFTBlock real, Node creator) {
        super(real.getSize(), real.getHeight(), real.getCreationTime(), creator, real.getParents().get(0), null);
        this.conflictsWith = real;
    }

    /** O bloco real A que este bloco contradiz. */
    public PBFTBlock getConflictsWith() {
        return conflictsWith;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PBFTConflictingBlock)) {
            return false;
        }
        PBFTConflictingBlock other = (PBFTConflictingBlock) o;
        return conflictsWith == other.conflictsWith && getCreator() == other.getCreator();
    }

    /** Deterministico entre execucoes (nao usa identityHashCode): altura, tamanho e id do criador. */
    @Override
    public int hashCode() {
        int h = 17;
        h = 31 * h + getHeight();
        h = 31 * h + getSize();
        h = 31 * h + (getCreator() == null ? 0 : getCreator().nodeID);
        return h;
    }
}
