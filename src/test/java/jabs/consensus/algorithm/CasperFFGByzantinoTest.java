package jabs.consensus.algorithm;

import jabs.config.ByzantineConfig;
import jabs.consensus.blockchain.LocalBlockTree;
import jabs.consensus.config.CasperFFGConfig;
import jabs.ledgerdata.casper.CasperFFGLink;
import jabs.ledgerdata.casper.CasperFFGVote;
import jabs.ledgerdata.ethereum.EthereumBlock;
import jabs.ledgerdata.ethereum.EthereumTx;
import jabs.network.message.Packet;
import jabs.network.message.VoteMessage;
import jabs.network.networks.ethereum.CasperFFGGlobalBlockchainNetwork;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.ethereum.EthereumNode;
import jabs.network.stats.sixglobalregions.ethereum.EthereumProofOfWorkGlobalNetworkStats6Regions;
import jabs.simulator.Simulator;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static jabs.network.stats.eightysixcountries.ethereum.EthereumProofOfWorkGlobalNetworkStats86Countries.ETHEREUM_DIFFICULTY_2022;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Criterio 4 (modelo de falha explicito) no CasperFFG: o hook bizantino de voto e a atribuicao honesto/bizantino na
 * finalizacao nao engolem excecao (antes: {@code catch (Exception ignored)} em ~7 pontos). Cada teste injeta uma
 * falha tipada ({@link FalhaInjetada}) ou um estado invalido e exige que ela ATRAVESSE o hook.
 * Tambem fixa a semantica que NAO mudou: contagem de pacotes de EQUIVOCATION (metades), DOUBLE_SIGN, SILENT/WITHHOLD.
 */
class CasperFFGByzantinoTest {
    private static final int CHECKPOINT = 2;
    private static final int STAKEHOLDERS = 7;

    /** Rede Casper de 10 nos (3 mineradores + 7 stakeholders), no observado r e cadeia genesis->b1->b2->b3. */
    private static final class Cena {
        final Simulator sim = new Simulator();
        final RandomnessEngine rng = new RandomnessEngine(7);
        final CasperFFGGlobalBlockchainNetwork<?> net;
        final EthereumNode r;
        final CasperFFG<EthereumBlock, EthereumTx> casper;
        final EnvioDeTeste envio;
        final EthereumBlock g, b1, b2, b3;

        @SuppressWarnings({"unchecked", "rawtypes"})
        Cena(String ataque) {
            net = new CasperFFGGlobalBlockchainNetwork<>(rng, CHECKPOINT,
                    new EthereumProofOfWorkGlobalNetworkStats6Regions(rng));
            net.populateNetwork(sim, 3, STAKEHOLDERS, new CasperFFGConfig(
                    EthereumBlock.generateGenesisBlock(ETHEREUM_DIFFICULTY_2022), 15.0, CHECKPOINT, STAKEHOLDERS));
            r = net.getAllNodes().get(5);
            casper = (CasperFFG<EthereumBlock, EthereumTx>) r.getConsensusAlgorithm();
            if (ataque != null) {
                casper.setByzantineConfig(new ByzantineConfig(10, Set.of(r.nodeID), ataque, 7));
            }
            envio = EnvioDeTeste.instala(r, sim, net, rng);
            LocalBlockTree<EthereumBlock> tree = casper.getLocalBlockTree();
            g = tree.getGenesisBlock();
            b1 = bloco(1, g);
            b2 = bloco(2, b1);
            b3 = bloco(3, b2);
        }

        private static EthereumBlock bloco(int altura, EthereumBlock pai) {
            return new EthereumBlock(100, altura, altura, null, pai, new HashSet<>(), 1.0, 1.0);
        }

        /** Entrega b1..b3: no b3 a altura passa do checkpoint e o CasperFFG.updateChain vota. */
        void alimenta() {
            for (EthereumBlock b : List.of(b1, b2, b3)) {
                casper.getLocalBlockTree().add(b);
                casper.newIncomingBlock(b);
            }
        }

        int vizinhos() {
            return r.getP2pConnections().getNeighbors().size();
        }
    }

    private static boolean ehVoto(Packet p) {
        return p.getMessage() instanceof VoteMessage;
    }

    @SuppressWarnings("unchecked")
    private static CasperFFGLink<EthereumBlock> link(Packet p) {
        return ((CasperFFGVote<EthereumBlock>) ((VoteMessage) p.getMessage()).getVote()).getLink();
    }

    // ---------- semantica preservada: contagem de pacotes por ataque ----------

    @Test
    void equivocationEnviaDoisLinksConflitantesAMetadesDosVizinhos() {
        Cena c = new Cena("EQUIVOCATION");
        c.alimenta();
        assertSame(c.b3, c.casper.getCanonicalChainHead(), "sanidade: a cadeia chegou ao b3 e disparou o checkpoint");
        int n = c.vizinhos();
        assertTrue(n >= 2, "n=" + n);
        int metade = Math.max(1, n / 2);
        List<Packet> votos = c.envio.doTipo(VoteMessage.class);
        // O hook enfileira exatamente um voto por vizinho, na ordem de vizinhanca; depois o voto local re-propaga
        // (EthereumNode#processNewVote), por isso a lista tem mais que n pacotes: o hook sao os n primeiros.
        List<Node> vizinhos = c.r.getP2pConnections().getNeighbors();
        for (int i = 0; i < n; i++) {
            Packet p = votos.get(i);
            assertSame(vizinhos.get(i), p.getTo(), "destinatario " + i);
            // 1a metade: link original (justifica o checkpoint b2); 2a metade: link alternativo (justifica o genesis)
            assertSame(i < metade ? c.b2 : c.g, link(p).getToBeJustified(), "link do vizinho " + i);
            assertSame(c.g, link(p).getToBeFinalized(), "ambos finalizam o genesis");
        }
        assertEquals(2 * n, votos.size(), "n do hook + n da re-propagacao do voto local");
    }

    @Test
    void doubleSignEnviaOMesmoVotoDuasVezesPorVizinho() {
        Cena c = new Cena("DOUBLE_SIGN");
        c.alimenta();
        int n = c.vizinhos();
        List<Packet> votos = c.envio.doTipo(VoteMessage.class);
        assertEquals(3 * n, votos.size(), "2 broadcasts do hook + 1 re-propagacao do voto local");
        for (int i = 0; i < 2 * n; i++) {
            assertSame(votos.get(0).getMessage(), votos.get(i).getMessage(), "mesmo VoteMessage nos 2 broadcasts");
        }
    }

    @Test
    void silentEWithholdNaoEnviamVotoMesmoComEnvioQuebrado() {
        for (String ataque : new String[]{"SILENT", "WITHHOLD"}) {
            Cena c = new Cena(ataque);
            c.envio.falharQuando = CasperFFGByzantinoTest::ehVoto; // se o hook tentasse enviar, a falha apareceria
            c.alimenta();
            assertEquals(0, c.envio.doTipo(VoteMessage.class).size(), ataque);
        }
    }

    @Test
    void votoHonestoEnviaUmaVezPorVizinhoMaisARepropagacaoLocal() {
        Cena c = new Cena(null);
        c.alimenta();
        assertEquals(2 * c.vizinhos(), c.envio.doTipo(VoteMessage.class).size());
    }

    // ---------- (criterio 4) falha de envio atravessa o hook ----------

    @Test
    void falhaDeEnvioNoVotoEquivocadoPropaga() {
        Cena c = new Cena("EQUIVOCATION");
        c.envio.falharQuando = CasperFFGByzantinoTest::ehVoto;
        assertThrows(FalhaInjetada.class, c::alimenta);
    }

    @Test
    void falhaDeEnvioNoDoubleSignPropaga() {
        Cena c = new Cena("DOUBLE_SIGN");
        c.envio.falharQuando = CasperFFGByzantinoTest::ehVoto;
        assertThrows(FalhaInjetada.class, c::alimenta);
    }

    @Test
    void falhaDeEnvioNoVotoHonestoPropaga() {
        Cena c = new Cena(null);
        c.envio.falharQuando = CasperFFGByzantinoTest::ehVoto;
        assertThrows(FalhaInjetada.class, c::alimenta);
    }

    @Test
    void falhaNaRepropagacaoLocalDoVotoEquivocadoPropaga() {
        // Os n envios do hook passam; so a re-propagacao do voto local (depois do laco) falha. Antes o catch
        // externo engolia isto tambem.
        Cena c = new Cena("EQUIVOCATION");
        int n = c.vizinhos();
        int[] visto = {0};
        c.envio.falharQuando = p -> ehVoto(p) && ++visto[0] > n;
        assertThrows(FalhaInjetada.class, c::alimenta);
        assertEquals(n, c.envio.doTipo(VoteMessage.class).size(), "os n envios conflitantes aconteceram antes da falha");
    }

    // ---------- (criterio 4) atribuicao honesto/bizantino na finalizacao ----------

    /** Votos no link (b1 a finalizar, b2 a justificar). Com 7 stakeholders o limiar e ceil(14/3)=5 votos (>=), C1-casper-quorum-correcao. */
    private void votaNoLink(Cena c, List<Node> votantes, boolean comVotanteNulo) {
        CasperFFGLink<EthereumBlock> l = new CasperFFGLink<>(c.b1, c.b2);
        c.casper.getLocalBlockTree().add(c.b1);
        c.casper.getLocalBlockTree().add(c.b2);
        for (Node v : votantes) c.casper.newIncomingVote(new CasperFFGVote<>(v, l));
        if (comVotanteNulo) c.casper.newIncomingVote(new CasperFFGVote<>(null, l));
    }

    @Test
    void votoSemVotanteNoQuorumFalhaAltoEmVezDeContarComoHonesto() {
        Cena c = new Cena(null);
        // 4 votantes validos + 1 nulo = 5 = limiar exato (S=7): o voto nulo e o que atinge o quorum e dispara a atribuicao
        List<Node> quatro = List.<Node>copyOf(c.net.getAllNodes().subList(0, 4));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> votaNoLink(c, quatro, true));
        assertTrue(e.getMessage().contains("sem votante"), e.getMessage());
    }

    @Test
    void controleQuorumDeVotantesValidosFinalizaSemFalha() {
        Cena c = new Cena(null);
        List<Node> seis = List.<Node>copyOf(c.net.getAllNodes().subList(0, 6));
        votaNoLink(c, seis, false);
        assertTrue(c.casper.isBlockFinalized(c.b1));
        assertEquals(100.0, c.casper.getByzantineFaultTolerance(), "6 honestos: sem bloco aceito de bizantinos");
    }

    @Test
    void maioriaBizantinaNosVotosContaComoBlocoBizantino() {
        Cena c = new Cena(null);
        List<Node> seis = List.<Node>copyOf(c.net.getAllNodes().subList(0, 6));
        Set<Integer> byz = Set.of(seis.get(0).nodeID, seis.get(1).nodeID, seis.get(2).nodeID, seis.get(3).nodeID);
        c.casper.setByzantineConfig(new ByzantineConfig(10, byz, "WITHHOLD", 7));
        votaNoLink(c, seis, false);
        assertTrue(c.casper.isBlockFinalized(c.b1));
        assertEquals(0.0, c.casper.getByzantineFaultTolerance(), "4 bizantinos > 2 honestos: bloco bizantino");
        assertNotEquals(100.0, c.casper.getByzantineFaultTolerance());
    }

    // ---------- guarda estatica ----------

    @Test
    void casperNaoTemCatchDeExceptionVazioNemIgnored() throws java.io.IOException {
        GuardaCatch.exigeSemCatchEngolido(java.nio.file.Path.of("src/main/java/jabs/consensus/algorithm/CasperFFG.java"));
    }
}
