package jabs.consensus.algorithm.hotstuff;

import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffQuorumCertificate;
import jabs.network.message.HotStuffProposalMessage.Phase;

import java.util.Optional;

/**
 * Efeito externo que o lider deve executar depois de decidir: DIFUNDIR a mensagem da fase {@code phase} da visao
 * {@code view} sobre {@code block}, com o QC indicado. O {@link LeaderCore} so DECIDE; quem tem rede e simulador
 * (o {@code HotStuff}) monta a mensagem e envia. Equivale ao {@link LeaderMessage} que as replicas receberao.
 *
 * <ul>
 *   <li>PREPARE: {@code block} = curProposal, {@code qc} = highQC (vazio = bottom);</li>
 *   <li>PRE_COMMIT: {@code qc} = prepareQC da visao;</li>
 *   <li>COMMIT: {@code qc} = precommitQC da visao;</li>
 *   <li>DECIDE: {@code qc} = commitQC da visao.</li>
 * </ul>
 * O difundir inclui o proprio lider (ele e replica tambem): a entrega a si mesmo e LOCAL, sem pacote e sem trafego
 * (ver {@link LeaderCore}, "Voto do lider").
 *
 * @param phase fase a difundir
 * @param view visao
 * @param block bloco da proposta (curProposal)
 * @param qc QC carregado; vazio so em PREPARE sem highQC
 */
public record LeaderAction(Phase phase, long view, HotStuffBlock block, Optional<HotStuffQuorumCertificate> qc) {
    public LeaderAction {
        if (phase == null || block == null || qc == null || view < 0) {
            throw new IllegalArgumentException("phase, block, qc (Optional) obrigatorios e view >= 0");
        }
        if (phase != Phase.PREPARE && qc.isEmpty()) {
            throw new IllegalArgumentException(phase + " exige QC");
        }
    }

    public static LeaderAction prepare(long view, HotStuffBlock block, Optional<HotStuffQuorumCertificate> highQc) {
        return new LeaderAction(Phase.PREPARE, view, block, highQc);
    }

    public static LeaderAction preCommit(long view, HotStuffBlock block, HotStuffQuorumCertificate prepareQc) {
        return new LeaderAction(Phase.PRE_COMMIT, view, block, Optional.of(prepareQc));
    }

    public static LeaderAction commit(long view, HotStuffBlock block, HotStuffQuorumCertificate precommitQc) {
        return new LeaderAction(Phase.COMMIT, view, block, Optional.of(precommitQc));
    }

    public static LeaderAction decide(long view, HotStuffBlock block, HotStuffQuorumCertificate commitQc) {
        return new LeaderAction(Phase.DECIDE, view, block, Optional.of(commitQc));
    }
}
