package jabs.consensus.algorithm.hotstuff;

import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffQuorumCertificate;
import jabs.ledgerdata.hotstuff.HotStuffVote;

import java.util.Optional;

/**
 * Efeito externo que a replica deve executar depois de decidir sobre uma mensagem do lider ou sobre um timeout. O
 * nucleo ({@link ReplicaCore}) so DECIDE; quem tem rede e simulador (o {@code HotStuff}) executa, na ordem devolvida.
 *
 * @param kind o efeito: {@link Kind#VOTE} (enviar voto ao lider da visao), {@link Kind#FINALIZE} (bloco do DECIDE
 *             finalizado), {@link Kind#FINALIZE_ANCESTOR} (ancestral ainda nao finalizado, finalizado ANTES do bloco
 *             do DECIDE, do mais baixo ao mais alto) ou {@link Kind#NEW_VIEW} (a replica terminou a visao
 *             {@code view}: enviar NEW_VIEW com {@code qc} ao lider de {@code view + 1} e armar o timer da nova
 *             visao; vale para DECIDE e para timeout)
 * @param votePhase fase do voto; nulo nos demais
 * @param view visao da mensagem que gerou o efeito (em NEW_VIEW, a visao que TERMINOU)
 * @param block bloco votado ou finalizado; nulo em NEW_VIEW
 * @param qc so em NEW_VIEW: o prepareQC da replica NO MOMENTO em que a visao terminou (fotografia: acoes retidas
 *           que o mesmo {@code onLeaderMessage} drena depois podem avancar o prepareQC, e o NEW_VIEW da visao que
 *           terminou nao pode carrega-lo); vazio = bottom. Vazio nos demais
 */
public record ReplicaAction(Kind kind, HotStuffVote.Phase votePhase, long view, HotStuffBlock block,
                            Optional<HotStuffQuorumCertificate> qc) {
    public enum Kind { VOTE, FINALIZE, FINALIZE_ANCESTOR, NEW_VIEW }

    public ReplicaAction {
        if (kind == null || qc == null) {
            throw new IllegalArgumentException("kind e qc (Optional) obrigatorios");
        }
    }

    public static ReplicaAction vote(HotStuffVote.Phase phase, long view, HotStuffBlock block) {
        return new ReplicaAction(Kind.VOTE, phase, view, block, Optional.empty());
    }

    public static ReplicaAction finalizeBlock(long view, HotStuffBlock block) {
        return new ReplicaAction(Kind.FINALIZE, null, view, block, Optional.empty());
    }

    public static ReplicaAction finalizeAncestor(long view, HotStuffBlock block) {
        return new ReplicaAction(Kind.FINALIZE_ANCESTOR, null, view, block, Optional.empty());
    }

    public static ReplicaAction newView(long endedView, Optional<HotStuffQuorumCertificate> prepareQc) {
        return new ReplicaAction(Kind.NEW_VIEW, null, endedView, null, prepareQc);
    }
}
