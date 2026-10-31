package jabs.consensus.algorithm.hotstuff;

import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffQuorumCertificate;
import jabs.ledgerdata.hotstuff.HotStuffVote;
import jabs.network.message.Packet;
import jabs.network.networks.Network;
import jabs.network.node.nodes.Node;
import jabs.network.p2p.AbstractP2PConnections;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Apoio dos testes da logica pura do HotStuff: nos stub, config, cadeias de blocos e QCs. */
final class Fixtures {

    private Fixtures() { }

    private static final class StubP2P extends AbstractP2PConnections {
        @Override public void connectToNetwork(Network network) { }
        @Override public boolean requestConnection(Node node) { return false; }
    }

    static final class StubNode extends Node {
        StubNode(int id) { super(null, null, id, 1, 1, new StubP2P()); }
        @Override public void processIncomingPacket(Packet packet) { }
        @Override public void generateNewTransaction() { }
    }

    static HotStuffConsensusConfig cfg() {
        return HotStuffConsensusConfig.builder()
                .voterEncoding(HotStuffConsensusConfig.VoterEncoding.BITMAP)
                .viewTimeoutSeconds(10.0).batchSize(HotStuffConsensusConfig.batchSizeFor(1024, 512))
                .individualSignatureSizeBytes(64).aggregatedSignatureSizeBytes(96)
                .aggregatedShareSizeBytes(96).build();
    }

    /** Arvore local de teste: pai por identidade, sem tocar em {@code getParents}. */
    static final class Tree implements ParentLookup {
        private final Map<HotStuffBlock, HotStuffBlock> parents = new IdentityHashMap<>();

        HotStuffBlock genesis() {
            return new HotStuffBlock(1000, 0, 0.0, null, null);
        }

        HotStuffBlock child(HotStuffBlock parent) {
            HotStuffBlock b = new HotStuffBlock(1000, parent.getHeight() + 1, 0.0, null, parent);
            parents.put(b, parent);
            return b;
        }

        /** Cadeia de {@code len} filhos a partir de {@code from}; devolve todos, em ordem de altura. */
        List<HotStuffBlock> chain(HotStuffBlock from, int len) {
            List<HotStuffBlock> out = new ArrayList<>();
            HotStuffBlock cur = from;
            for (int i = 0; i < len; i++) {
                cur = child(cur);
                out.add(cur);
            }
            return out;
        }

        /** Esquece o pai (simula arvore local incompleta). */
        void forget(HotStuffBlock b) {
            parents.remove(b);
        }

        /** Registra um pai arbitrario (para cenarios invalidos). */
        void force(HotStuffBlock b, HotStuffBlock parent) {
            parents.put(b, parent);
        }

        @Override
        public Optional<HotStuffBlock> parentOf(HotStuffBlock block) {
            return Optional.ofNullable(parents.get(block));
        }
    }

    static List<Node> nodes(int from, int count) {
        List<Node> l = new ArrayList<>();
        for (int i = 0; i < count; i++) l.add(new StubNode(from + i));
        return l;
    }

    /** QC valido numa rede de n nos, com os primeiros 2f+1 ids como votantes. */
    static HotStuffQuorumCertificate qc(HotStuffVote.Phase phase, long view, HotStuffBlock block, int n) {
        return new HotStuffQuorumCertificate(phase, view, block,
                nodes(0, HotStuffConsensusConfig.quorumSize(n)), n, cfg());
    }

    static HotStuffQuorumCertificate qc(HotStuffVote.Phase phase, long view, HotStuffBlock block) {
        return qc(phase, view, block, 4);
    }

    static HotStuffVote vote(int voterId, HotStuffVote.Phase phase, long view, HotStuffBlock block) {
        return new HotStuffVote(new StubNode(voterId), phase, view, block, cfg());
    }
}
