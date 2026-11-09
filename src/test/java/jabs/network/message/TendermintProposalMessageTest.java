package jabs.network.message;

import jabs.consensus.algorithm.tendermint.TendermintTimeouts;
import jabs.consensus.algorithm.tendermint.TendermintVote;
import jabs.consensus.config.TendermintConsensusConfig;
import jabs.ledgerdata.BlockFactory;
import jabs.ledgerdata.tendermint.TendermintBlock;
import jabs.ledgerdata.tendermint.TendermintBlockWithTx;
import jabs.network.networks.tendermint.TendermintLocalLANNetwork;
import jabs.network.node.nodes.tendermint.TendermintNode;
import jabs.simulator.Simulator;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.Test;

import static jabs.network.node.nodes.tendermint.TendermintNode.TENDERMINT_GENESIS_BLOCK;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tamanho da proposta = hash + messageOverheadBytes + proposerSignatureSizeBytes + payload do bloco; a componente de
 * consenso exclui o payload. Assinaturas abaixo: so de teste, nao sao valores de pesquisa.
 */
class TendermintProposalMessageTest {
    private static TendermintConsensusConfig config(int overhead, int voteSig, int proposerSig) {
        return TendermintConsensusConfig.builder()
                .timeouts(new TendermintTimeouts(1.0, 0.5, 1.0, 0.5, 1.0, 0.5))
                .batchSize(20)
                .txSizeBytes(512)
                .messageOverheadBytes(overhead)
                .signatureSizeBytes(voteSig)
                .proposerSignatureSizeBytes(proposerSig)
                .build();
    }

    private static TendermintProposalMessage proposta(TendermintConsensusConfig cfg) {
        Simulator sim = new Simulator();
        TendermintLocalLANNetwork net = new TendermintLocalLANNetwork(new RandomnessEngine(1));
        net.populateNetwork(sim, 4, cfg);
        TendermintNode sender = net.getNode(0);
        TendermintBlockWithTx bloco = BlockFactory.sampleTendermintBlockWithTx(sim, sender, TENDERMINT_GENESIS_BLOCK, cfg);
        return new TendermintProposalMessage(sender, 0, 0, bloco, -1, cfg);
    }

    private static int voto(TendermintConsensusConfig cfg) {
        Simulator sim = new Simulator();
        TendermintLocalLANNetwork net = new TendermintLocalLANNetwork(new RandomnessEngine(1));
        net.populateNetwork(sim, 4, cfg);
        return new TendermintVoteMessage(net.getNode(0), TendermintVote.Type.PREVOTE, 0, 0, null, cfg).getSize();
    }

    @Test
    void tamanhoEHashMaisOverheadMaisAssinaturaDoProponenteMaisPayloadParaDoisValores() {
        int hash = TendermintBlock.TENDERMINT_BLOCK_HASH_SIZE;
        for (int assinatura : new int[] {40, 100}) {
            TendermintConsensusConfig cfg = config(10, 64, assinatura);
            TendermintProposalMessage m = proposta(cfg);
            long payload = m.getBlock().getSize();
            assertEquals(hash + 10 + assinatura + payload, m.getSize());
            assertEquals(hash + 10 + assinatura, m.getConsensusSize(), "consenso exclui o payload do bloco");
            assertEquals(m.getSize(), m.getTotalSize());
            assertEquals(payload, m.getTotalSize() - m.getConsensusSize());
        }
        assertEquals(100 - 40, proposta(config(10, 64, 100)).getSize() - proposta(config(10, 64, 40)).getSize(),
                "a diferenca entre dois valores e exatamente a diferenca das assinaturas");
    }

    @Test
    void assinaturaDoVotoNaoAfetaAPropostaEViceVersa() {
        TendermintMessageSizes a = new TendermintMessageSizes(config(10, 64, 80));
        TendermintMessageSizes votoDiferente = new TendermintMessageSizes(config(10, 200, 80));
        TendermintMessageSizes propDiferente = new TendermintMessageSizes(config(10, 64, 300));

        assertEquals(a.proposta, votoDiferente.proposta, "assinatura do voto nao entra na proposta");
        assertEquals(a.consensoProposta, votoDiferente.consensoProposta);
        assertEquals(a.voto + 136, votoDiferente.voto);

        assertEquals(a.voto, propDiferente.voto, "assinatura do proponente nao entra no voto");
        assertEquals(a.proposta + 220, propDiferente.proposta);
    }

    private static final class TendermintMessageSizes {
        final int proposta;
        final int consensoProposta;
        final int voto;

        TendermintMessageSizes(TendermintConsensusConfig cfg) {
            TendermintProposalMessage m = proposta(cfg);
            this.proposta = m.getSize();
            this.consensoProposta = m.getConsensusSize();
            this.voto = voto(cfg);
        }
    }
}
