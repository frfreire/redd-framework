package jabs.network.message;

import jabs.consensus.algorithm.tendermint.TendermintTimeouts;
import jabs.consensus.algorithm.tendermint.TendermintVote;
import jabs.consensus.config.TendermintConsensusConfig;
import jabs.ledgerdata.tendermint.TendermintBlock;
import jabs.network.networks.tendermint.TendermintLocalLANNetwork;
import jabs.network.node.nodes.tendermint.TendermintNode;
import jabs.simulator.Simulator;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tamanho do voto = hash + messageOverheadBytes + signatureSizeBytes. Assinaturas abaixo: so de teste. */
class TendermintVoteMessageTest {
    private static TendermintConsensusConfig config(int overhead, int signature) {
        return TendermintConsensusConfig.builder()
                .timeouts(new TendermintTimeouts(1.0, 0.5, 1.0, 0.5, 1.0, 0.5))
                .batchSize(20)
                .txSizeBytes(512)
                .messageOverheadBytes(overhead)
                .signatureSizeBytes(signature)
                .proposerSignatureSizeBytes(77) // FIXTURE somente de teste, nao e valor de pesquisa; nao afeta o voto
                .build();
    }

    private static int tamanho(TendermintConsensusConfig cfg) {
        Simulator sim = new Simulator();
        TendermintLocalLANNetwork net = new TendermintLocalLANNetwork(new RandomnessEngine(1));
        net.populateNetwork(sim, 4, cfg);
        TendermintNode sender = net.getNode(0);
        return new TendermintVoteMessage(sender, TendermintVote.Type.PREVOTE, 0, 0, null, cfg).getSize();
    }

    @Test
    void tamanhoEHashMaisOverheadMaisAssinaturaParaDoisValores() {
        int hash = TendermintBlock.TENDERMINT_BLOCK_HASH_SIZE;
        assertEquals(hash + 10 + 40, tamanho(config(10, 40)));
        assertEquals(hash + 10 + 100, tamanho(config(10, 100)));
        assertEquals(hash + 7 + 100, tamanho(config(7, 100)));
    }
}
