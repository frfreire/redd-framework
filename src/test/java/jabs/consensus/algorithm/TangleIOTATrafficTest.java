package jabs.consensus.algorithm;

import jabs.consensus.blockchain.LocalBlockDAG;
import jabs.consensus.config.TangleIOTAConsensusConfig;
import jabs.ledgerdata.Query;
import jabs.ledgerdata.Vote;
import jabs.ledgerdata.tangle.TangleBlock;
import jabs.ledgerdata.tangle.TangleTx;
import jabs.network.message.DataMessage;
import jabs.network.message.InvMessage;
import jabs.network.message.Packet;
import jabs.network.networks.iota.TangleGlobalNetwork;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.PeerDLTNode;
import jabs.network.p2p.BitcoinCoreP2P;
import jabs.network.stats.NodeGlobalNetworkStats;
import jabs.network.stats.sixglobalregions.SixRegions;
import jabs.network.stats.sixglobalregions.iota.IOTANodeDistribution6Region;
import jabs.simulator.Simulator;
import jabs.simulator.event.BlockFinalizationEvent;
import jabs.simulator.event.Event;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0006, tarefa 4 (TangleIOTA): trafego MEDIDO (bytes recebidos por no, packet.getSize()) no hook de
 * PeerDLTNode, no lugar do sintetico (15 x 512 B com ruido gaussiano de Random(42)).
 *
 * LIMITE (T5): o TangleIOTA continua sem cenario executavel. A T5 corrigiu o genesis de IOTANode
 * ({@code parents == null} dava NullPointerException em {@code new IOTANode(...)}; ver IOTANodeGenesisTest), mas
 * nao ha cenario nem entrada no MVPComparison, IOTANode nao gera blocos, ninguem chama confirmBlock e
 * TangleGlobalNetwork#createNewIOTANode usa nodeTypes.get(regiao) (a chave do mapa e o no). Estes testes usam um
 * genesis proprio e um no de teste (PeerDLTNode, como o IOTANode), com o TangleIOTA real. Exercitam o hook e o
 * acumulador; NAO provam execucao em cenario. "Mesma semente / sementes distintas" nao se aplica: o Tangle nao
 * consome mais nenhum numero aleatorio (so o ruido do trafego usava Random(42)).
 */
class TangleIOTATrafficTest {

    /** Genesis proprio: igual ao de IOTANode, mas com lista de pais vazia (o original tem null e quebra o DAG). */
    static final TangleBlock GENESIS = new TangleBlock(0, 0, 0, null, List.of(), 32, new TangleTx(1650, 32), 1, 0);

    static final class Stats extends IOTANodeDistribution6Region implements NodeGlobalNetworkStats<SixRegions> {
        Stats(RandomnessEngine r) {
            super(r);
        }
    }

    /** No de teste: um PeerDLTNode (como o IOTANode) com o consenso injetado. */
    static final class NoTangle extends PeerDLTNode<TangleBlock, TangleTx> {
        NoTangle(Simulator sim, TangleGlobalNetwork<SixRegions> net, int id, TangleIOTA consenso) {
            super(sim, net, id, 10_000_000L, 10_000_000L, new BitcoinCoreP2P(), consenso);
        }

        @Override protected void processNewTx(TangleTx tx, Node from) { }
        @Override protected void processNewBlock(TangleBlock block) { }
        @Override protected void processNewVote(Vote vote) { }
        @Override protected void processNewQuery(Query query) { }
        @Override public void generateNewTransaction() { }
    }

    private final Simulator sim = new Simulator();
    private final RandomnessEngine rng = new RandomnessEngine(1);
    private final TangleGlobalNetwork<SixRegions> net = new TangleGlobalNetwork<>(rng, new Stats(rng));

    private NoTangle novoNo(int id) {
        TangleIOTA consenso = new TangleIOTA(new LocalBlockDAG<>(GENESIS), new TangleIOTAConsensusConfig(1.0));
        NoTangle no = new NoTangle(sim, net, id, consenso);
        // sem isto o RequestDataMessage de resposta ao Inv nao tem processo de envio (NPE no teste do Inv)
        no.getNodeNetworkInterface().connectNetwork(net, rng);
        return no;
    }

    private static TangleBlock bloco(Node criador, int altura, int tamanho) {
        return new TangleBlock(tamanho, altura, 0, criador, List.of(GENESIS), 32,
                new TangleTx(100, 32), 1.0, 0);
    }

    private static BlockFinalizationEvent ultimoEvento(Simulator sim) {
        BlockFinalizationEvent achado = null;
        while (sim.isThereMoreEvents()) {
            Event ev = sim.peekEvent();
            sim.executeNextEvent();
            if (ev instanceof BlockFinalizationEvent fe) {
                achado = fe;
            }
        }
        return achado;
    }

    @Test
    void hookContaBytesDoPacoteNoNoQueRecebeu() {
        NoTangle emissor = novoNo(0);
        NoTangle receptor = novoNo(1);
        TangleIOTA tangle = (TangleIOTA) receptor.getConsensusAlgorithm();
        TangleBlock b = bloco(emissor, 1, 4_321);

        Packet p = new Packet(emissor, receptor, new DataMessage(b));
        receptor.processIncomingPacket(p);
        assertEquals(p.getSize(), tangle.getBlockTraffic(b), "total = packet.getSize()");
        assertTrue(p.getSize() >= 4_321, "o pacote carrega o bloco inteiro");

        // Duplicata tambem consumiu banda (contada antes do filtro de bloco ja visto).
        receptor.processIncomingPacket(p);
        assertEquals(2L * p.getSize(), tangle.getBlockTraffic(b));

        // So quem recebeu acumula.
        assertEquals(0, ((TangleIOTA) emissor.getConsensusAlgorithm()).getBlockTraffic(b));

        // InvMessage (so o hash) nao carrega o bloco: nao entra, como nos demais protocolos.
        long antes = tangle.getBlockTraffic(b);
        receptor.processIncomingPacket(new Packet(emissor, receptor, new InvMessage(32, b.getHash())));
        assertEquals(antes, tangle.getBlockTraffic(b));
    }

    @Test
    void confirmacaoEmiteTrafegoMedidoSemConsensoESemRuido() {
        NoTangle emissor = novoNo(0);
        NoTangle receptor = novoNo(1);
        TangleIOTA tangle = (TangleIOTA) receptor.getConsensusAlgorithm();
        TangleBlock b = bloco(emissor, 1, 3_000);
        Packet p = new Packet(emissor, receptor, new DataMessage(b));
        receptor.processIncomingPacket(p);
        receptor.processIncomingPacket(p);

        tangle.confirmBlock(b);
        BlockFinalizationEvent fe = ultimoEvento(sim);
        assertNotNull(fe, "confirmBlock emite BlockFinalizationEvent");
        assertEquals(2L * p.getSize(), fe.getTrafficUntilFinalization(),
                "evento = soma medida de packet.getSize(), nao 15 x 512 com ruido");
        assertTrue(fe.getTrafficUntilFinalization() > 0);
        assertEquals(BlockFinalizationEvent.NOT_MEASURED, fe.getConsensusMessageTraffic(),
                "Tangle nao tem fase de consenso separavel: -1, nao 0");

        // Encerrada a contagem: pacote tardio nao entra e nao ha acumulo sem fim.
        receptor.processIncomingPacket(p);
        assertEquals(0, tangle.getBlockTraffic(b));
    }

    @Test
    void blocoSemPacoteConfirmaComZeroMedido() {
        NoTangle no = novoNo(0);
        TangleBlock b = bloco(no, 1, 1_000);
        ((TangleIOTA) no.getConsensusAlgorithm()).confirmBlock(b);
        BlockFinalizationEvent fe = ultimoEvento(sim);
        assertNotNull(fe);
        assertEquals(0, fe.getTrafficUntilFinalization(), "nada recebido: 0 medido, nao a estimativa antiga");
        assertNotEquals7680(fe.getTrafficUntilFinalization());
    }

    private static void assertNotEquals7680(long v) {
        assertFalse(v == 15L * 512L, "15 * 512 era a estimativa sintetica");
    }

    @Test
    void tangleNaoTemFormulaNemRandomFixo() throws Exception {
        String codigo = DAGsperTrafficTest.semComentarios("src/main/java/jabs/consensus/algorithm/TangleIOTA.java");
        assertFalse(codigo.contains("Random(42)"));
        assertFalse(codigo.contains("new Random(") || codigo.contains("new java.util.Random("));
        assertFalse(codigo.contains("Math.random"));
        assertFalse(codigo.contains("nextGaussian"));
        assertFalse(codigo.contains("estimatedDagMessages"));
    }
}
