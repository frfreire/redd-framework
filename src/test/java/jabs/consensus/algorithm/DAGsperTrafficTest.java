package jabs.consensus.algorithm;

import jabs.consensus.algorithm.DAGsperHarness.Fin;
import jabs.ledgerdata.dagsper.DAGsperVote;
import jabs.ledgerdata.ethereum.EthereumBlock;
import jabs.network.message.DataMessage;
import jabs.network.message.InvMessage;
import jabs.network.message.Packet;
import jabs.network.message.VoteMessage;
import jabs.network.node.nodes.PeerBlockchainNode;
import jabs.network.node.nodes.ethereum.EthereumMinerNode;
import jabs.simulator.event.BlockFinalizationEvent;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0006, tarefa 4 (DAGsper): trafego MEDIDO (bytes recebidos por no, packet.getSize()) pelo hook de
 * PeerBlockchainNode, com C_B_total (bloco + votos) e C_B_consenso (so votos).
 *
 * O DAGsper nao tem cenario nem entrada no MVPComparison: estes testes usam {@link DAGsperHarness}, que monta a
 * rede direto. Fica de fora o que depende de cenario (ex.: coluna do CSV do logger).
 */
class DAGsperTrafficTest {
    private static final int MINERS = 5;
    private static final int OTHERS = 10;
    private static final int CHECKPOINT_SPACE = 3;
    private static final double INTERVAL = 15;
    private static final double STOP = 400;

    private static List<Fin> simular(long seed) {
        return new DAGsperHarness(seed, MINERS, OTHERS, CHECKPOINT_SPACE, INTERVAL).rodar(STOP);
    }

    // ---------- determinismo por semente ----------

    @Test
    void mesmaSementeMesmaSaida() {
        List<Fin> a = simular(1);
        List<Fin> b = simular(1);
        assertFalse(a.isEmpty(), "DAGsper deve finalizar blocos em " + STOP + " s");
        assertEquals(a, b, "mesma semente => mesmas finalizacoes e mesmo trafego");
    }

    @Test
    void sementesDistintasSaidasDistintas() {
        List<Fin> a = simular(1);
        List<Fin> b = simular(2);
        assertFalse(a.isEmpty());
        assertFalse(b.isEmpty());
        assertNotEquals(a, b, "sementes distintas => saidas distintas");
    }

    // ---------- tamanho medido == soma de packet.getSize() ----------

    @Test
    void trafegoMedidoIgualASomaDosPacotesRecebidos() {
        DAGsperHarness h = new DAGsperHarness(1, MINERS, OTHERS, CHECKPOINT_SPACE, INTERVAL);
        List<Fin> fins = new ArrayList<>();
        List<String> divergencias = h.rodarConferindo(STOP, fins);

        assertFalse(fins.isEmpty(), "deve haver finalizacoes");
        assertTrue(divergencias.isEmpty(), "evento != soma independente de packet.getSize(): " + divergencias);
        assertTrue(h.esperado.pacotesDeBloco > 0, "o cenario precisa entregar pacotes de bloco");
        assertTrue(h.esperado.pacotesDeVoto > 0, "o cenario precisa entregar pacotes de voto");
        assertTrue(h.esperado.votosLocais > 0, "o cenario precisa ter votos que o no entrega a si mesmo");
        assertTrue(h.esperado.blocosLocais > 0, "o cenario precisa ter blocos que o minerador entrega a si mesmo");
        for (Fin f : fins) {
            assertTrue(f.total() > 0, "C_B_total medido > 0 (no " + f.nodeId() + " altura " + f.height() + ")");
            assertTrue(f.consensus() >= 0, "C_B_consenso medido (nao a sentinela -1)");
            assertTrue(f.consensus() <= f.total(), "consenso e parte do total");
        }
        assertTrue(fins.stream().anyMatch(f -> f.consensus() > 0), "votos foram contados no consenso");
        assertTrue(fins.stream().anyMatch(f -> f.consensus() < f.total()), "a carga do bloco conta so no total");
    }

    // ---------- hook: bytes do pacote, no no que recebeu ----------

    @Test
    void hookContaBytesDoPacoteNoNoQueRecebeu() {
        DAGsperHarness h = new DAGsperHarness(7, 3, 9, CHECKPOINT_SPACE, INTERVAL);
        PeerBlockchainNode<?, ?> emissor = h.todos.get(0);
        PeerBlockchainNode<?, ?> receptor = h.todos.get(5);
        DAGsper<EthereumBlock, ?> dag = (DAGsper<EthereumBlock, ?>) receptor.getConsensusAlgorithm();
        EthereumBlock genesis = (EthereumBlock) receptor.getConsensusAlgorithm().getLocalBlockDAG().getGenesisBlock();
        EthereumBlock bloco = new EthereumBlock(2_000, 1, 0, (EthereumMinerNode) emissor, genesis,
                new HashSet<>(), 2097, 1.0);

        Packet pb = new Packet(emissor, receptor, new DataMessage(bloco));
        receptor.processIncomingPacket(pb);
        assertEquals(pb.getSize(), dag.getBlockTraffic(bloco), "bloco: total = pacote inteiro");
        assertEquals(0, dag.getConsensusMessageTraffic(bloco), "bloco nao e mensagem de consenso");

        DAGsperVote<EthereumBlock> voto = new DAGsperVote<>(emissor, 0, 0, bloco, new HashSet<>());
        Packet pv = new Packet(emissor, receptor, new VoteMessage(voto));
        receptor.processIncomingPacket(pv);
        assertEquals(pb.getSize() + pv.getSize(), dag.getBlockTraffic(bloco));
        assertEquals(pv.getSize(), dag.getConsensusMessageTraffic(bloco), "voto conta no consenso");

        // Duplicata tambem consumiu banda (contada antes do filtro alreadySeenVotes / alreadySeenBlocks).
        receptor.processIncomingPacket(pv);
        receptor.processIncomingPacket(pb);
        assertEquals(2L * (pb.getSize() + pv.getSize()), dag.getBlockTraffic(bloco));
        assertEquals(2L * pv.getSize(), dag.getConsensusMessageTraffic(bloco));

        // Quem nao recebeu nao acumula: o emissor nunca recebeu esses pacotes.
        assertEquals(0, ((DAGsper<EthereumBlock, ?>) emissor.getConsensusAlgorithm()).getBlockTraffic(bloco));

        // InvMessage (so o hash) nao carrega o bloco: nao entra, como nos demais protocolos.
        Packet inv = new Packet(emissor, receptor, new InvMessage(bloco.getHash().getSize(), bloco.getHash()));
        long antes = dag.getBlockTraffic(bloco);
        receptor.processIncomingPacket(inv);
        assertEquals(antes, dag.getBlockTraffic(bloco));
    }

    // ---------- convencao: so conta o que chegou pela rede simulada ----------

    /** Voto que o no entrega a si mesmo (Packet(self, self), como em DAGsper#updateChain) nao soma bytes. */
    @Test
    void votoDoProprioNoNaoSomaBytes() {
        DAGsperHarness h = new DAGsperHarness(7, 3, 9, CHECKPOINT_SPACE, INTERVAL);
        PeerBlockchainNode<?, ?> emissor = h.todos.get(0);
        PeerBlockchainNode<?, ?> receptor = h.todos.get(5);
        DAGsper<EthereumBlock, ?> dag = (DAGsper<EthereumBlock, ?>) receptor.getConsensusAlgorithm();
        EthereumBlock genesis = (EthereumBlock) receptor.getConsensusAlgorithm().getLocalBlockDAG().getGenesisBlock();
        EthereumBlock bloco = new EthereumBlock(2_000, 1, 0, (EthereumMinerNode) emissor, genesis,
                new HashSet<>(), 2097, 1.0);

        DAGsperVote<EthereumBlock> proprio = new DAGsperVote<>(receptor, 0, 0, bloco, new HashSet<>());
        Packet local = new Packet(receptor, receptor, new VoteMessage(proprio));
        assertTrue(local.getSize() > 0, "o voto tem tamanho; o que o exclui e a origem, nao o tamanho");
        receptor.processIncomingPacket(local);
        assertEquals(0, dag.getBlockTraffic(bloco), "voto proprio: C_B_total nao muda");
        assertEquals(0, dag.getConsensusMessageTraffic(bloco), "voto proprio: C_B_consenso nao muda");

        // Bloco entregue pelo proprio minerador a si mesmo tambem nao e trafego.
        Packet blocoLocal = new Packet(receptor, receptor, new DataMessage(bloco));
        receptor.processIncomingPacket(blocoLocal);
        assertEquals(0, dag.getBlockTraffic(bloco), "bloco proprio nao e trafego");

        // Voto de OUTRO no, para o mesmo bloco, soma exatamente packet.getSize() (C_B_total e C_B_consenso).
        DAGsperVote<EthereumBlock> alheio = new DAGsperVote<>(emissor, 0, 0, bloco, new HashSet<>());
        Packet rede = new Packet(emissor, receptor, new VoteMessage(alheio));
        receptor.processIncomingPacket(rede);
        assertEquals(rede.getSize(), dag.getBlockTraffic(bloco), "voto de outro no soma packet.getSize()");
        assertEquals(rede.getSize(), dag.getConsensusMessageTraffic(bloco), "C_B_consenso idem");

        // Depois de contado um voto de rede, outro voto proprio continua sem somar.
        DAGsperVote<EthereumBlock> proprio2 = new DAGsperVote<>(receptor, 1, 0, bloco, new HashSet<>());
        receptor.processIncomingPacket(new Packet(receptor, receptor, new VoteMessage(proprio2)));
        assertEquals(rede.getSize(), dag.getBlockTraffic(bloco));
        assertEquals(rede.getSize(), dag.getConsensusMessageTraffic(bloco));
    }

    /**
     * Fim a fim: o voto de broadcast que o no envia para os OUTROS nos chega pela rede simulada e e contado por
     * eles. O harness confere cada finalizacao contra a soma independente dos pacotes com from != no.
     */
    @Test
    void broadcastParaOutrosNosContinuaContado() {
        DAGsperHarness h = new DAGsperHarness(1, MINERS, OTHERS, CHECKPOINT_SPACE, INTERVAL);
        List<Fin> fins = new ArrayList<>();
        List<String> divergencias = h.rodarConferindo(STOP, fins);
        assertTrue(divergencias.isEmpty(), divergencias.toString());
        assertTrue(h.esperado.pacotesDeVoto > 0, "ha votos de rede (from != no)");
        assertTrue(fins.stream().anyMatch(f -> f.consensus() > 0), "C_B_consenso > 0 so pode vir de votos de rede");
    }

    // ---------- no silencioso ----------

    @Test
    void noSilenciosoNaoRecebeNemFinaliza() {
        DAGsperHarness h = new DAGsperHarness(3, MINERS, OTHERS, CHECKPOINT_SPACE, INTERVAL);
        int silencioso = MINERS + 2;
        h.todos.get(silencioso).crash();
        List<Fin> fins = h.rodar(STOP);
        assertFalse(fins.isEmpty(), "os demais ainda finalizam");
        Set<Integer> quem = new HashSet<>();
        fins.forEach(f -> quem.add(f.nodeId()));
        assertFalse(quem.contains(silencioso), "no fora do ar nao recebe pacote, logo nao finaliza nem acumula");
    }

    // ---------- higiene do codigo ----------

    @Test
    void dagsperNaoTemFormulaNemAleatoriedadeForaDoEngine() throws Exception {
        String codigo = semComentarios("src/main/java/jabs/consensus/algorithm/DAGsper.java");
        assertFalse(codigo.contains("Math.random"));
        assertFalse(codigo.contains("new Random(") || codigo.contains("new java.util.Random("));
        assertFalse(codigo.contains("nextGaussian"));
        assertFalse(codigo.contains("* 1024"));
    }

    @Test
    void sentinelaNaoMedidoPermaneceParaProtocolosSemDecomposicao() {
        BlockFinalizationEvent e = new BlockFinalizationEvent(0.0, null, null, 55L);
        assertEquals(-1L, e.getConsensusMessageTraffic());
    }

    static String semComentarios(String caminho) throws Exception {
        String src = Files.readString(Paths.get(caminho));
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }
}
