package jabs.consensus.algorithm;

import jabs.consensus.config.DAGsperConfig;
import jabs.ledgerdata.Block;
import jabs.ledgerdata.dagsper.DAGsperVote;
import jabs.ledgerdata.ethereum.EthereumBlock;
import jabs.network.message.DataMessage;
import jabs.network.message.Packet;
import jabs.network.message.VoteMessage;
import jabs.network.networks.ethereum.DAGsperGlobalBlockchainNetwork;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.PeerBlockchainNode;
import jabs.network.node.nodes.ethereum.EthereumDAGsperMiner;
import jabs.network.node.nodes.ethereum.EthereumDAGsperNode;
import jabs.network.stats.sixglobalregions.SixRegions;
import jabs.network.stats.sixglobalregions.ethereum.EthereumProofOfWorkGlobalNetworkStats6Regions;
import jabs.simulator.Simulator;
import jabs.simulator.event.BlockFinalizationEvent;
import jabs.simulator.event.Event;
import jabs.simulator.randengine.RandomnessEngine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Auxiliar dos testes do DAGsper (ADR-0006, tarefa 4).
 *
 * Nao existe cenario nem entrada no MVPComparison para DAGsper, e
 * {@code DAGsperGlobalBlockchainNetwork.populateNetwork} nao o instancia (nao sobrescreve
 * createSampleNode/createSampleMiner: cria EthereumNode com GhostProtocol). Este harness monta a rede
 * chamando direto os construtores de EthereumDAGsperMiner/EthereumDAGsperNode, sem tocar na rede de producao.
 *
 * Os nos sao subclasses que registram TODO pacote entregue a processIncomingPacket. Os que chegam pela rede
 * (from != no) entram na soma esperada de packet.getSize() por (no, bloco), calculada de forma independente do
 * acumulador do DAGsper; as entregas locais (bloco minerado e voto que o proprio no entrega a si mesmo) so sao
 * contadas a parte, porque nao sao trafego.
 */
final class DAGsperHarness {
    /** Uma finalizacao observada. */
    record Fin(int nodeId, int height, long total, long consensus, double time) {}

    /** Soma esperada por no e bloco, calculada so com packet.getSize() e a regra "ate a finalizacao local". */
    static final class Esperado {
        final Map<Node, Map<Block<?>, long[]>> porNo = new HashMap<>(); // [total, consenso]
        int pacotesDeBloco;
        int pacotesDeVoto;
        /** Entregas locais (from == no) de voto e de bloco: observadas, mas NAO somam bytes (nao passam pela rede). */
        int votosLocais;
        int blocosLocais;

        @SuppressWarnings({"unchecked", "rawtypes"})
        void antes(PeerBlockchainNode<?, ?> no, Packet p) {
            DAGsper dag = (DAGsper) no.getConsensusAlgorithm();
            if (p.getFrom() == no) {
                // Convencao: so pacote que chegou pela rede simulada e trafego recebido.
                if (p.getMessage() instanceof VoteMessage) {
                    votosLocais++;
                } else if (p.getMessage() instanceof DataMessage) {
                    blocosLocais++;
                }
                return;
            }
            Block<?> chave = null;
            boolean voto = false;
            if (p.getMessage() instanceof DataMessage dm && dm.getData() instanceof Block<?> b) {
                chave = b;
                pacotesDeBloco++;
            } else if (p.getMessage() instanceof VoteMessage vm && vm.getVote() instanceof DAGsperVote<?> v) {
                chave = v.toBeJustified;
                voto = true;
                pacotesDeVoto++;
            }
            if (chave == null || dag.isBlockFinalized((EthereumBlock) chave)) {
                return;
            }
            long[] acc = porNo.computeIfAbsent(no, k -> new HashMap<>()).computeIfAbsent(chave, k -> new long[2]);
            acc[0] += p.getSize();
            if (voto) {
                acc[1] += p.getSize();
            }
        }

        long[] get(Node no, Block<?> bloco) {
            Map<Block<?>, long[]> m = porNo.get(no);
            return m == null || m.get(bloco) == null ? new long[2] : m.get(bloco);
        }
    }

    static final class NoRegistrador extends EthereumDAGsperNode {
        private final Esperado esperado;

        NoRegistrador(Simulator sim, DAGsperGlobalBlockchainNetwork<?> net, int id, long down, long up,
                      EthereumBlock genesis, DAGsperConfig<EthereumBlock> cfg, Esperado esperado) {
            super(sim, net, id, down, up, genesis, cfg);
            this.esperado = esperado;
        }

        @Override
        public void processIncomingPacket(Packet packet) {
            esperado.antes(this, packet);
            super.processIncomingPacket(packet);
        }
    }

    static final class MineradorRegistrador extends EthereumDAGsperMiner {
        private final Esperado esperado;

        MineradorRegistrador(Simulator sim, DAGsperGlobalBlockchainNetwork<?> net, int id, long down, long up,
                             double hashPower, EthereumBlock genesis, DAGsperConfig<EthereumBlock> cfg,
                             Esperado esperado) {
            super(sim, net, id, down, up, hashPower, genesis, cfg);
            this.esperado = esperado;
        }

        @Override
        public void processIncomingPacket(Packet packet) {
            esperado.antes(this, packet);
            super.processIncomingPacket(packet);
        }
    }

    static final int MIN_NOS = 10;

    final Simulator sim = new Simulator();
    final RandomnessEngine rng;
    final DAGsperGlobalBlockchainNetwork<SixRegions> net;
    final List<PeerBlockchainNode<?, ?>> todos = new ArrayList<>();
    final Esperado esperado = new Esperado();

    @SuppressWarnings("unchecked")
    DAGsperHarness(long seed, int miners, int others, int checkpointSpace, double blockInterval) {
        if (miners + others < MIN_NOS) {
            // EthereumGethP2P.connectToNetwork repete ate achar 3 vizinhos de saida distintos; com rede pequena
            // nao termina (laco infinito, nao lentidao). Falhar alto em vez de travar o teste.
            throw new IllegalArgumentException("DAGsper/Geth P2P exige n >= " + MIN_NOS + " (recebido "
                    + (miners + others) + "): o setup da rede nao termina com n pequeno");
        }
        this.rng = new RandomnessEngine(seed);
        EthereumProofOfWorkGlobalNetworkStats6Regions stats = new EthereumProofOfWorkGlobalNetworkStats6Regions(rng);
        this.net = new DAGsperGlobalBlockchainNetwork<>(rng, checkpointSpace, stats);
        int n = miners + others;
        EthereumBlock genesis = EthereumBlock.generateGenesisBlock(2097);
        DAGsperConfig<EthereumBlock> cfg = new DAGsperConfig<>(genesis, blockInterval, checkpointSpace, n);
        for (int i = 0; i < miners; i++) {
            SixRegions r = net.sampleMinerRegion();
            // hashPower bruto (nao escalado como em populateNetwork): intervalo por minerador ~ dificuldade/hashPower.
            net.addMiner(new MineradorRegistrador(sim, net, i, net.sampleDownloadBandwidth(r),
                    net.sampleUploadBandwidth(r), stats.sampleMinerHashPower(), genesis, cfg, esperado));
        }
        for (int i = 0; i < others; i++) {
            SixRegions r = net.sampleRegion();
            net.addNode(new NoRegistrador(sim, net, miners + i, net.sampleDownloadBandwidth(r),
                    net.sampleUploadBandwidth(r), genesis, cfg, esperado));
        }
        for (Object o : net.getAllNodes()) {
            ((Node) o).getP2pConnections().connectToNetwork(net);
            todos.add((PeerBlockchainNode<?, ?>) o);
        }
        net.startAllMiningProcesses();
    }

    /** Roda ate `stop` s e devolve as finalizacoes observadas, em ordem de execucao. */
    List<Fin> rodar(double stop) {
        List<Fin> out = new ArrayList<>();
        while (sim.isThereMoreEvents() && sim.getSimulationTime() <= stop) {
            Event ev = sim.peekEvent();
            sim.executeNextEvent();
            if (ev instanceof BlockFinalizationEvent fe) {
                out.add(new Fin(fe.getNode().nodeID, fe.getBlock().getHeight(),
                        fe.getTrafficUntilFinalization(), fe.getConsensusMessageTraffic(), sim.getSimulationTime()));
            }
        }
        return out;
    }

    /** Roda ate `stop` s conferindo cada finalizacao contra a soma esperada de packet.getSize(). */
    List<String> rodarConferindo(double stop, List<Fin> saida) {
        List<String> erros = new ArrayList<>();
        while (sim.isThereMoreEvents() && sim.getSimulationTime() <= stop) {
            Event ev = sim.peekEvent();
            sim.executeNextEvent();
            if (ev instanceof BlockFinalizationEvent fe) {
                long[] esp = esperado.get(fe.getNode(), fe.getBlock());
                if (esp[0] != fe.getTrafficUntilFinalization() || esp[1] != fe.getConsensusMessageTraffic()) {
                    erros.add("no " + fe.getNode().nodeID + " altura " + fe.getBlock().getHeight()
                            + ": esperado total/consenso " + esp[0] + "/" + esp[1] + ", evento "
                            + fe.getTrafficUntilFinalization() + "/" + fe.getConsensusMessageTraffic());
                }
                saida.add(new Fin(fe.getNode().nodeID, fe.getBlock().getHeight(),
                        fe.getTrafficUntilFinalization(), fe.getConsensusMessageTraffic(), sim.getSimulationTime()));
            }
        }
        return erros;
    }

    DAGsper<?, ?> dagsper(int i) {
        return (DAGsper<?, ?>) todos.get(i).getConsensusAlgorithm();
    }
}
