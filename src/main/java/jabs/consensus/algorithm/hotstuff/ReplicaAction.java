package jabs.consensus.algorithm.hotstuff;

import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffVote;

/**
 * Efeito externo que a replica deve executar depois de decidir sobre uma mensagem do lider. O nucleo
 * ({@link ReplicaCore}) so DECIDE; quem tem rede e simulador (o {@code HotStuff}) executa, na ordem devolvida.
 *
 * @param kind {@link Kind#VOTE} (enviar voto ao lider da visao) ou {@link Kind#FINALIZE} (bloco finalizado)
 * @param votePhase fase do voto; nulo em FINALIZE
 * @param view visao da mensagem que gerou o efeito
 * @param block bloco votado ou finalizado
 */
public record ReplicaAction(Kind kind, HotStuffVote.Phase votePhase, long view, HotStuffBlock block) {
    public enum Kind { VOTE, FINALIZE }

    public static ReplicaAction vote(HotStuffVote.Phase phase, long view, HotStuffBlock block) {
        return new ReplicaAction(Kind.VOTE, phase, view, block);
    }

    public static ReplicaAction finalizeBlock(long view, HotStuffBlock block) {
        return new ReplicaAction(Kind.FINALIZE, null, view, block);
    }
}
