package jabs.consensus.algorithm;

import jabs.config.ByzantineConfig;
import jabs.ledgerdata.BlockFactory;
import jabs.ledgerdata.pbft.PBFTBlock;
import jabs.ledgerdata.pbft.PBFTBlockVote;
import jabs.ledgerdata.pbft.PBFTCommitVote;
import jabs.ledgerdata.pbft.PBFTPrePrepareVote;
import jabs.ledgerdata.pbft.PBFTPrepareVote;
import jabs.network.message.Packet;
import jabs.network.message.VoteMessage;
import jabs.network.networks.pbft.PBFTLocalLANNetwork;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.pbft.PBFTNode;
import jabs.simulator.Simulator;
import jabs.simulator.event.Event;
import jabs.simulator.event.PacketDeliveryEvent;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static jabs.network.node.nodes.pbft.PBFTNode.PBFT_GENESIS_BLOCK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Criterio 4 (modelo de falha explicito): os hooks bizantinos do PBFT nao engolem excecao.
 * (1) EQUIVOCATION envia os dois votos conflitantes a metades disjuntas dos vizinhos (contagem de pacotes que
 * saem do no bizantino, observados como {@link PacketDeliveryEvent} no simulador), em COMMIT e em PRE_PREPARE;
 * (2) falha de envio PROPAGA (antes: {@code catch (Exception ignored)} deixava o ataque sumir);
 * (3) rede sem 2 vizinhos ou sem destinatario honesto em cada metade falha alto, em vez de degradar;
 * (4) DOUBLE_SIGN/WITHHOLD/SILENT continuam com a contagem de pacotes esperada.
 */
class PBFTEquivocationTest {

    /** No receptor (r) e demais nos de uma rede PBFT com n nos; r e o no 1, que e o primario da visao 1. */
    private record Alvo(PBFTNode r, List<PBFTNode> todos, Simulator sim, RandomnessEngine rng, int n) {}

    private static final int R = 1;

    private static Alvo novaRede(int n, String ataque, Set<Integer> bizantinos) {
        RandomnessEngine rng = new RandomnessEngine(5);
        Simulator sim = new Simulator();
        PBFTLocalLANNetwork net = new PBFTLocalLANNetwork(rng);
        net.populateNetwork(sim, n, new jabs.consensus.config.PBFTConsensusConfig());
        List<PBFTNode> todos = new ArrayList<>(net.getAllNodes());
        PBFTNode r = todos.get(Math.min(R, n - 1));
        ByzantineConfig cfg = new ByzantineConfig(n, bizantinos, ataque, 5);
        r.getConsensusAlgorithm().setByzantineConfig(cfg);
        return new Alvo(r, todos, sim, rng, n);
    }

    private static PBFTBlock novoBloco(Alvo a) {
        return BlockFactory.samplePBFTBlockWithTx(a.sim(), a.rng(), a.todos().get(0), PBFT_GENESIS_BLOCK, 5);
    }

    private static void entrega(Alvo a, PBFTNode de, PBFTBlockVote<PBFTBlock> voto) {
        a.r().processIncomingPacket(new Packet(de, a.r(), new VoteMessage(voto)));
    }

    /** n votos distintos de nos != r, ate q deles. */
    private static List<PBFTNode> votantes(Alvo a, int q) {
        List<PBFTNode> v = new ArrayList<>();
        for (PBFTNode p : a.todos()) {
            if (p != a.r() && v.size() < q) v.add(p);
        }
        return v;
    }

    private static void prePrepare(Alvo a, PBFTBlock b) {
        PBFTNode criador = a.todos().get(0);
        entrega(a, criador, new PBFTPrePrepareVote<>(criador, b));
    }

    private static void prepares(Alvo a, PBFTBlock b, int quantos) {
        for (PBFTNode p : votantes(a, quantos)) entrega(a, p, new PBFTPrepareVote<>(p, b));
    }

    private static void commits(Alvo a, PBFTBlock b, int quantos) {
        for (PBFTNode p : votantes(a, quantos)) entrega(a, p, new PBFTCommitVote<>(p, b));
    }

    /** Pacotes de voto do tipo dado que SAEM de r, observados ao executar os eventos do simulador. */
    private static List<Packet> pacotesDeR(Alvo a, PBFTBlockVote.VoteType tipo) {
        List<Packet> out = new ArrayList<>();
        int guarda = 0;
        while (a.sim().isThereMoreEvents() && a.sim().getSimulationTime() < 20.0 && guarda++ < 200_000) {
            Event ev = a.sim().peekEvent();
            a.sim().executeNextEvent();
            if (ev instanceof PacketDeliveryEvent pd && pd.packet.getFrom() == a.r()
                    && pd.packet.getMessage() instanceof VoteMessage vm
                    && vm.getVote() instanceof PBFTBlockVote<?> bv && bv.getVoteType() == tipo) {
                out.add(pd.packet);
            }
        }
        return out;
    }

    private static PBFTBlock bloco(Packet p) {
        @SuppressWarnings("unchecked")
        PBFTBlockVote<PBFTBlock> v = (PBFTBlockVote<PBFTBlock>) ((VoteMessage) p.getMessage()).getVote();
        return v.getBlock();
    }

    /** Duas metades disjuntas e cobrindo os n vizinhos: [0, half) recebe um bloco, [half, n) o outro. */
    private static void verificaMetades(Alvo a, List<Packet> pacotes) {
        int n = a.n();
        int half = Math.max(1, n / 2);
        assertEquals(n, pacotes.size(), "um pacote por vizinho (n=" + n + ")");
        Set<Node> destinos = new HashSet<>();
        PBFTBlock blocoA = null;
        PBFTBlock blocoB = null;
        for (Packet p : pacotes) {
            assertTrue(destinos.add(p.getTo()), "destinatario repetido: " + p.getTo().nodeID);
            int idx = a.todos().indexOf(p.getTo());
            PBFTBlock blk = bloco(p);
            if (idx < half) {
                if (blocoA == null) blocoA = blk;
                assertSame(blocoA, blk, "1a metade recebe sempre o mesmo bloco (no " + idx + ")");
            } else {
                if (blocoB == null) blocoB = blk;
                assertSame(blocoB, blk, "2a metade recebe sempre o mesmo bloco (no " + idx + ")");
            }
        }
        assertEquals(n, destinos.size());
        assertNotSame(blocoA, blocoB, "os dois votos sao conflitantes (blocos distintos)");
    }

    // ---------- (1) o ramo EQUIVOCATION envia as duas mensagens conflitantes a subconjuntos distintos ----------

    @Test
    void equivocationEmCommitEPrePrepareEnviaVotosConflitantesAMetadesDisjuntas() {
        for (int n : new int[]{4, 7, 10}) {
            int q = PBFT.quorumSize(n);
            Alvo a = novaRede(n, "EQUIVOCATION", Set.of(R));
            PBFTBlock b = novoBloco(a);
            prePrepare(a, b);
            prepares(a, b, q);          // r fica prepared -> COMMIT equivocado
            commits(a, b, q);           // r finaliza -> visao 1 -> r e o primario -> PRE_PREPARE equivocado
            @SuppressWarnings("unchecked")
            PBFT<PBFTBlock, ?> pbft = (PBFT<PBFTBlock, ?>) a.r().getConsensusAlgorithm();
            assertTrue(pbft.isCommitted(b), "n=" + n);
            assertEquals(1, pbft.getCurrentPrimaryNumber(), "n=" + n + ": r e o primario da visao 1");

            List<Packet> todos = new ArrayList<>();
            // Os dois tipos saem na mesma execucao do simulador; coleta uma vez e separa por tipo.
            int guarda = 0;
            List<Packet> commitsEnviados = new ArrayList<>();
            List<Packet> prePreparesEnviados = new ArrayList<>();
            while (a.sim().isThereMoreEvents() && a.sim().getSimulationTime() < 20.0 && guarda++ < 200_000) {
                Event ev = a.sim().peekEvent();
                a.sim().executeNextEvent();
                if (ev instanceof PacketDeliveryEvent pd && pd.packet.getFrom() == a.r()
                        && pd.packet.getMessage() instanceof VoteMessage vm
                        && vm.getVote() instanceof PBFTBlockVote<?> bv) {
                    if (bv.getVoteType() == PBFTBlockVote.VoteType.COMMIT) commitsEnviados.add(pd.packet);
                    if (bv.getVoteType() == PBFTBlockVote.VoteType.PRE_PREPARE) prePreparesEnviados.add(pd.packet);
                }
            }
            todos.addAll(commitsEnviados);
            verificaMetades(a, commitsEnviados);
            verificaMetades(a, prePreparesEnviados);
            // O voto que cada metade recebe e de fato um VoteMessage com o MESMO remetente (r): equivocacao real.
            for (Packet p : todos) assertSame(a.r(), p.getFrom());
        }
    }

    @Test
    void equivocationSoEmCommitContaNPacotesDeCommit() {
        // Antes da finalizacao so o COMMIT equivoca (nenhum PRE_PREPARE sai de r).
        for (int n : new int[]{4, 7}) {
            Alvo a = novaRede(n, "EQUIVOCATION", Set.of(R));
            PBFTBlock b = novoBloco(a);
            prePrepare(a, b);
            prepares(a, b, PBFT.quorumSize(n));
            List<Packet> c = pacotesDeR(a, PBFTBlockVote.VoteType.COMMIT);
            verificaMetades(a, c);
        }
    }

    // ---------- (2) falha de envio propaga ----------

    @Test
    void falhaDeEnvioNoCommitEquivocadoPropaga() {
        int n = 4;
        Alvo a = novaRede(n, "EQUIVOCATION", Set.of(R));
        PBFTBlock b = novoBloco(a);
        prePrepare(a, b);
        prepares(a, b, PBFT.quorumSize(n) - 1);
        // Sem processo de envio, addToUpLinkQueue lanca NPE. Antes, o catch (Exception ignored) engolia e o
        // ataque simplesmente nao acontecia; agora a simulacao falha alto.
        a.r().getNodeNetworkInterface().messageSendingProcess = null;
        assertThrows(NullPointerException.class, () -> prepares(a, b, PBFT.quorumSize(n)));
    }

    @Test
    void falhaDeEnvioNoPrePrepareEquivocadoPropaga() {
        int n = 4;
        Alvo a = novaRede(n, "EQUIVOCATION", Set.of(R));
        PBFTBlock b = novoBloco(a);
        prePrepare(a, b);
        prepares(a, b, PBFT.quorumSize(n));
        // r esta prepared (COMMIT equivocado ja enfileirado). Quebra o envio e entrega o quorum de COMMITs:
        // a finalizacao dispara o PRE_PREPARE equivocado, que tem de propagar a falha.
        a.r().getNodeNetworkInterface().messageSendingProcess = null;
        assertThrows(NullPointerException.class, () -> commits(a, b, PBFT.quorumSize(n)));
    }

    // ---------- (3) rede sem destinatarios honestos disjuntos falha alto ----------

    @Test
    void redeComUmUnicoVizinhoFalhaAltoComMensagemClara() {
        Alvo a = novaRede(1, "EQUIVOCATION", Set.of(0));
        PBFTNode r = a.r();
        PBFTBlock b = BlockFactory.samplePBFTBlockWithTx(a.sim(), a.rng(), r, PBFT_GENESIS_BLOCK, 5);
        entrega(a, r, new PBFTPrePrepareVote<>(r, b));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> entrega(a, r, new PBFTPrepareVote<>(r, b)));
        assertTrue(e.getMessage().contains("EQUIVOCATION") && e.getMessage().contains("2 vizinhos"), e.getMessage());
    }

    @Test
    void metadeSemNoHonestoFalhaAltoEmVezDeDegradar() {
        // n=4: metades [0,1] e [2,3]. Com 2 e 3 bizantinos, a 2a metade nao tem destinatario honesto.
        int n = 4;
        Alvo a = novaRede(n, "EQUIVOCATION", Set.of(R, 2, 3));
        PBFTBlock b = novoBloco(a);
        prePrepare(a, b);
        prepares(a, b, PBFT.quorumSize(n) - 1);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> prepares(a, b, PBFT.quorumSize(n)));
        assertTrue(e.getMessage().contains("destinatario honesto") && e.getMessage().contains("2a=0"),
                e.getMessage());
        assertFalse(a.r().getConsensusAlgorithm().isByzantineValidator(0), "controle: o no 0 e honesto");
    }

    // ---------- (4) os outros hooks bizantinos mantem a contagem de pacotes ----------

    @Test
    void doubleSignEnviaDoisCommitsPorVizinho() {
        int n = 4;
        Alvo a = novaRede(n, "DOUBLE_SIGN", Set.of(R));
        PBFTBlock b = novoBloco(a);
        prePrepare(a, b);
        prepares(a, b, PBFT.quorumSize(n));
        assertEquals(2 * n, pacotesDeR(a, PBFTBlockVote.VoteType.COMMIT).size());
    }

    @Test
    void withholdESilentNaoEnviamCommit() {
        for (String ataque : new String[]{"WITHHOLD", "SILENT"}) {
            int n = 4;
            Alvo a = novaRede(n, ataque, Set.of(R));
            PBFTBlock b = novoBloco(a);
            prePrepare(a, b);
            prepares(a, b, PBFT.quorumSize(n));
            assertEquals(0, pacotesDeR(a, PBFTBlockVote.VoteType.COMMIT).size(), ataque);
        }
    }

    // ---------- guarda estatica: nenhum catch vazio de Exception no PBFT ----------

    @Test
    void pbftNaoTemCatchDeExceptionVazio() throws IOException {
        String fonte = Files.readString(Path.of("src/main/java/jabs/consensus/algorithm/PBFT.java"));
        assertFalse(fonte.matches("(?s).*catch\\s*\\(\\s*(final\\s+)?(java\\.lang\\.)?(Exception|Throwable)\\s+\\w+\\s*\\)\\s*\\{\\s*(//[^\\n]*)?\\s*\\}.*"),
                "PBFT.java nao pode engolir Exception (lint JB-009)");
        assertFalse(fonte.contains("ignored"), "PBFT.java nao pode ter excecao 'ignored'");
    }
}
