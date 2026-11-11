package jabs.consensus.algorithm;

import jabs.config.ByzantineConfig;
import jabs.consensus.algorithm.hotstuff.DiscardReason;
import jabs.consensus.config.HotStuffConsensusConfig;
import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffTx;
import jabs.ledgerdata.hotstuff.HotStuffVote;
import jabs.network.message.HotStuffProposalMessage;
import jabs.network.message.Packet;
import jabs.network.message.VoteMessage;
import jabs.network.networks.hotstuff.HotStuffLocalLANNetwork;
import jabs.network.networks.hotstuff.HotStuffTestConfig;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.hotstuff.HotStuffNode;
import jabs.scenario.HotStuffLANScenario;
import jabs.simulator.Simulator;
import jabs.simulator.event.Event;
import jabs.simulator.event.PacketDeliveryEvent;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3a-2a-2d-1: ataque EQUIVOCATION no HotStuff (ADR-0010 PI-12). Particao (metades por indice dos vizinhos),
 * abrangencia (PREPARE do lider + votos da replica) e ausencia de coordenacao sao PROVISORIAS (ADR-0015 Q28); estes
 * testes fixam o comportamento provisorio. Sao testes de COMPORTAMENTO, nao resultado de pesquisa. Todos os numeros
 * (n, f, sementes, duracoes, timeout de visao) sao fixtures de teste.
 */
class HotStuffEquivocationTest {

    private static final HotStuffConsensusConfig CFG = HotStuffTestConfig.config();
    /** FIXTURE: janela curta, bem menor que o timeout de visao (10 s) da config de teste: so a visao 1 do lider 1. */
    private static final double JANELA_VISAO_1 = 3.0;

    // ------------------------------------------------------------------------------------------------ montagem

    private static ByzantineConfig equivoca(int n, Set<Integer> ids, long seed) {
        return new ByzantineConfig(n, ids, "EQUIVOCATION", seed);
    }

    private static HotStuffLocalLANNetwork rede(Simulator sim, int n, long seed, ByzantineConfig byz) {
        HotStuffLocalLANNetwork net = new HotStuffLocalLANNetwork(new RandomnessEngine(seed));
        net.populateNetwork(sim, n, CFG);
        if (byz != null) {
            net.applyByzantineConfig(byz);
        }
        return net;
    }

    @SuppressWarnings("unchecked")
    private static HotStuff<HotStuffBlock, HotStuffTx> hs(HotStuffNode node) {
        return (HotStuff<HotStuffBlock, HotStuffTx>) node.getConsensusAlgorithm();
    }

    /** Executa eventos ate {@code stop}; devolve os pacotes que SAEM de {@code from}. */
    private static List<Packet> rodarColetando(Simulator sim, double stop, Node from) {
        List<Packet> out = new ArrayList<>();
        while (sim.isThereMoreEvents() && sim.peekEventTime() <= stop) {
            Event ev = sim.peekEvent();
            sim.executeNextEvent();
            if (ev instanceof PacketDeliveryEvent pd && pd.packet.getFrom() == from) {
                out.add(pd.packet);
            }
        }
        return out;
    }

    private static void rodar(Simulator sim, double stop) {
        while (sim.isThereMoreEvents() && sim.peekEventTime() <= stop) {
            sim.executeNextEvent();
        }
    }

    private static List<Packet> prepares(List<Packet> pacotes, long view) {
        List<Packet> out = new ArrayList<>();
        for (Packet p : pacotes) {
            if (p.getMessage() instanceof HotStuffProposalMessage m
                    && m.getPhase() == HotStuffProposalMessage.Phase.PREPARE && m.getView() == view) {
                out.add(p);
            }
        }
        return out;
    }

    private static List<Packet> votos(List<Packet> pacotes, HotStuffVote.Phase fase, long view) {
        List<Packet> out = new ArrayList<>();
        for (Packet p : pacotes) {
            if (p.getMessage() instanceof VoteMessage m && m.getVote() instanceof HotStuffVote v && v.getPhase() == fase
                    && v.getView() == view) {
                out.add(p);
            }
        }
        return out;
    }

    private static HotStuffBlock blocoDe(Packet p) {
        return p.getMessage() instanceof HotStuffProposalMessage m ? m.getBlock()
                : ((HotStuffVote) ((VoteMessage) p.getMessage()).getVote()).getBlock();
    }

    /** Verifica: um pacote por vizinho; [0,half) recebe um unico bloco A, [half,size) um unico B != A. Devolve {A, B}. */
    private static HotStuffBlock[] verificaMetades(HotStuffNode origem, List<Packet> pacotes, String rotulo) {
        List<Node> vizinhos = origem.getP2pConnections().getNeighbors();
        int size = vizinhos.size();
        int half = Math.max(1, size / 2);
        assertEquals(size, pacotes.size(), rotulo + ": um pacote por vizinho, nenhum para si");
        HotStuffBlock a = null;
        HotStuffBlock b = null;
        Set<Integer> vistos = new HashSet<>();
        for (Packet p : pacotes) {
            assertTrue(vistos.add(p.getTo().getNodeID()), rotulo + ": destinatario repetido " + p.getTo().getNodeID());
            int idx = vizinhos.indexOf(p.getTo());
            assertTrue(idx >= 0, rotulo + ": destinatario fora dos vizinhos");
            if (idx < half) {
                if (a == null) {
                    a = blocoDe(p);
                }
                assertSame(a, blocoDe(p), rotulo + ": 1a metade recebe um unico bloco");
            } else {
                if (b == null) {
                    b = blocoDe(p);
                }
                assertSame(b, blocoDe(p), rotulo + ": 2a metade recebe um unico bloco");
            }
        }
        assertNotNull(a);
        assertNotNull(b);
        assertNotSame(a, b, rotulo + ": blocos conflitantes sao objetos distintos");
        assertNotSame(a.getHash(), b.getHash(), rotulo + ": hash distinto");
        assertSame(a.getFixedParent(), b.getFixedParent(), rotulo + ": mesmo pai (conflito na mesma altura)");
        assertEquals(a.getHeight(), b.getHeight(), rotulo + ": mesma altura");
        assertEquals(a.getSize(), b.getSize(), rotulo + ": mesmo tamanho");
        return new HotStuffBlock[]{a, b};
    }

    // ----------------------------------------------------- (d) supportedAttacks / ataques nao suportados recusados

    @Test
    void equivocationAceitoOutrosAtaquesEDesconhecidosFalhamAlto() {
        HotStuffLocalLANNetwork net = rede(new Simulator(), 4, 1, null);
        HotStuff<HotStuffBlock, HotStuffTx> h = hs(net.getNode(0));
        h.setByzantineConfig(equivoca(4, Set.of(1), 1));
        assertTrue(h.isByzantineValidator(1));
        assertEquals("EQUIVOCATION", h.getByzantineAttackType());
        for (String ataque : new String[]{"SILENT", "WITHHOLD", "DOUBLE_SIGN"}) {
            HotStuff<HotStuffBlock, HotStuffTx> outro = hs(net.getNode(1));
            UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                    () -> outro.setByzantineConfig(new ByzantineConfig(4, Set.of(1), ataque, 1)), ataque);
            assertTrue(e.getMessage().contains(ataque) && e.getMessage().contains("EQUIVOCATION"), e.getMessage());
            assertNull(outro.getByzantineConfig(), "config recusada nao pode ficar gravada: " + ataque);
        }
        // nome desconhecido nem chega a existir como config (ByzantineConfig recusa)
        for (String desconhecido : new String[]{"equivocation", "EQUIVOCACAO", "", "FOO"}) {
            assertThrows(IllegalArgumentException.class, () -> new ByzantineConfig(4, Set.of(1), desconhecido, 1),
                    desconhecido);
        }
        // NONE ("sem ataque") e null continuam aceitos.
        h.setByzantineConfig(new ByzantineConfig(4, Set.of(1), "NONE", 1));
        h.setByzantineConfig(null);
        assertNull(h.getByzantineConfig());
    }

    @Test
    void redeECenarioFalhamAltoParaAtaqueNaoSuportadoECenarioRecusaConfigIncoerente() {
        for (String ataque : new String[]{"SILENT", "WITHHOLD", "DOUBLE_SIGN"}) {
            HotStuffLocalLANNetwork net = rede(new Simulator(), 4, 1, null);
            ByzantineConfig cfg = new ByzantineConfig(4, Set.of(1), ataque, 1);
            assertThrows(UnsupportedOperationException.class, () -> net.applyByzantineConfig(cfg), ataque);
            HotStuffLANScenario sc = new HotStuffLANScenario("hs", 1, 4, 2.0, CFG, Set.of(), cfg);
            assertThrows(UnsupportedOperationException.class, sc::run, ataque);
        }
        assertThrows(IllegalArgumentException.class,
                () -> new HotStuffLANScenario("hs", 1, 4, 2.0, CFG, Set.of(), equivoca(7, Set.of(1), 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new HotStuffLANScenario("hs", 1, 4, 2.0, CFG, Set.of(), equivoca(4, Set.of(9), 1)));
        HotStuffLocalLANNetwork net = rede(new Simulator(), 4, 1, null);
        assertThrows(IllegalArgumentException.class, () -> net.applyByzantineConfig(null));
        assertThrows(IllegalArgumentException.class, () -> net.applyByzantineConfig(equivoca(7, Set.of(1), 1)));
    }

    // ------------------------------------------- (a) lider bizantino emite duas propostas a metades disjuntas

    @Test
    void liderBizantinoEmiteDoisPreparesConflitantesAMetadesDisjuntas() {
        for (int n : new int[]{4, 7, 10}) {
            Simulator sim = new Simulator();
            int lider = (int) (HotStuff.INITIAL_VIEW % n); // ROTATING: leaderOf(1) = 1
            HotStuffLocalLANNetwork net = rede(sim, n, 7, equivoca(n, Set.of(lider), 7));
            net.startConsensusRound(sim, 5.0);
            List<Packet> saidos = rodarColetando(sim, JANELA_VISAO_1, net.getNode(lider));
            String rot = "PREPARE n=" + n;

            List<Packet> props = prepares(saidos, HotStuff.INITIAL_VIEW);
            HotStuffBlock[] ab = verificaMetades(net.getNode(lider), props, rot);
            assertEquals(1L, hs(net.getNode(lider)).getEquivocatedProposals(), rot);
            // A e o bloco que o LeaderCore realmente propos (curProposal); B nao e
            assertSame(hs(net.getNode(lider)).getLeaderCore().curProposal(HotStuff.INITIAL_VIEW).get(), ab[0], rot);
            for (Packet p : props) {
                HotStuffProposalMessage m = (HotStuffProposalMessage) p.getMessage();
                assertEquals(m.getBlock().getSize() + m.getConsensusSize(), p.getSize(), rot + ": tamanho = total");
            }

            // trafego MEDIDO pelo hook normal no receptor: cada destinatario conta so o PREPARE que recebeu
            List<Node> vizinhos = net.getNode(lider).getP2pConnections().getNeighbors();
            int half = Math.max(1, vizinhos.size() / 2);
            for (int i = 0; i < vizinhos.size(); i++) {
                HotStuffNode dest = (HotStuffNode) vizinhos.get(i);
                HotStuffBlock recebido = i < half ? ab[0] : ab[1];
                HotStuffBlock outro = i < half ? ab[1] : ab[0];
                assertEquals(props.get(0).getSize(), hs(dest).getBlockTraffic(recebido), rot + " no " + dest.getNodeID());
                assertEquals(0L, hs(dest).getBlockTraffic(outro), rot + ": nenhum honesto recebe as duas propostas");
            }
            // B e VALIDO para a visao: os honestos da 2a metade o aceitaram e votaram nele; esses votos chegam ao
            // lider e sao descartados como bloco errado (nao agregam): A fica com half + 1 < n - f votos
            assertEquals(vizinhos.size() - half, hs(net.getNode(lider)).getDiscardCount(DiscardReason.VOTE_WRONG_BLOCK),
                    rot + ": votos em B descartados no lider");
            assertEquals(0L, hs(net.getNode(lider)).getUnequivocatedLeaderPhases(), rot + ": nenhuma fase com QC saiu");
            assertEquals(0, hs(net.getNode(lider)).getNumOfConfirmedBlocks(), rot + ": visao do lider equivocador nao decide");
        }
    }

    // ------------------------------------------- (b) replica bizantina emite votos conflitantes

    @Test
    void replicaBizantinaEmiteVotosConflitantesAMetadesDisjuntas() {
        // leaderOf(1) = 1 honesto. n=4: byz=2 tem vizinhos [0 | 1 3] (lider na 2a metade), byz=0 tem [1 | 2 3]
        // (lider na 1a metade): as duas posicoes do lider em relacao a particao.
        for (int n : new int[]{4, 7}) {
            for (int byz : new int[]{0, 2}) {
                Simulator sim = new Simulator();
                HotStuffLocalLANNetwork net = rede(sim, n, 7, equivoca(n, Set.of(byz), 7));
                net.startConsensusRound(sim, 5.0);
                List<Packet> saidos = rodarColetando(sim, JANELA_VISAO_1, net.getNode(byz));
                String rot = "n=" + n + " byz=" + byz;
                long v1 = HotStuff.INITIAL_VIEW;

                List<Packet> v = votos(saidos, HotStuffVote.Phase.PREPARE, v1);
                HotStuffBlock[] ab = verificaMetades(net.getNode(byz), v, "voto PREPARE " + rot);
                HotStuffBlock real = hs(net.getNode(1)).getLeaderCore().curProposal(v1).get();
                assertSame(real, ab[0], rot + ": a 1a metade recebe o voto no bloco real");
                // PRE_COMMIT e COMMIT tambem sao equivocados, com o MESMO B da visao
                HotStuffBlock[] ab2 = verificaMetades(net.getNode(byz),
                        votos(saidos, HotStuffVote.Phase.PRE_COMMIT, v1), "voto PRE_COMMIT " + rot);
                HotStuffBlock[] ab3 = verificaMetades(net.getNode(byz),
                        votos(saidos, HotStuffVote.Phase.COMMIT, v1), "voto COMMIT " + rot);
                assertSame(ab[1], ab2[1], rot + ": mesmo B em todas as fases da visao");
                assertSame(ab[1], ab3[1], rot + ": mesmo B em todas as fases da visao");
                assertTrue(hs(net.getNode(byz)).getEquivocatedVotes() >= 3, rot);
                for (Packet p : v) {
                    assertEquals(32 + 10 + 64, p.getSize(), rot + ": voto = hash + overhead + assinatura");
                }
                // na visao 1 o bizantino e so replica: nao envia PREPARE nela (a janela pode incluir visoes futuras em
                // que ele lidera, p.ex. a visao 4 com byz=0; la ele equivoca o PREPARE, e isso e esperado)
                assertTrue(prepares(saidos, v1).isEmpty(), rot + ": replica nao propoe na visao 1");
                assertEquals(0L, hs(net.getNode(byz)).getUnequivocatedLeaderPhases(), rot);

                // trafego MEDIDO: cada vizinho conta o voto recebido (total == consenso: voto e 100% protocolo)
                List<Node> vizinhos = net.getNode(byz).getP2pConnections().getNeighbors();
                int half = Math.max(1, vizinhos.size() / 2);
                for (int i = 0; i < vizinhos.size(); i++) {
                    HotStuffNode dest = (HotStuffNode) vizinhos.get(i);
                    HotStuffBlock recebido = i < half ? ab[0] : ab[1];
                    // o destinatario pode ter finalizado o bloco real e zerado o contador (takeTraffic); o de B nunca
                    // e finalizado, entao o trafego do voto conflitante persiste e e igual nos dois contadores
                    if (i >= half && dest.getNodeID() != 1) {
                        assertEquals(hs(dest).getBlockTraffic(recebido), hs(dest).getConsensusMessageTraffic(recebido),
                                rot + " no " + dest.getNodeID());
                    }
                }
            }
        }
    }

    @Test
    void votoConflitanteDeReplicaBizantinaChegaAoLiderComoBlocoErradoEOVotoRealAgrega() {
        // n=4, byz=2, vizinhos [0 | 1 3]: o lider (1) esta na 2a metade e recebe o voto em B (descartado), nao em A.
        // Os votos reais de 0, 3 e do proprio lider (3 = n - f) formam o QC mesmo assim.
        Simulator sim = new Simulator();
        HotStuffLocalLANNetwork net = rede(sim, 4, 7, equivoca(4, Set.of(2), 7));
        net.startConsensusRound(sim, 5.0);
        rodar(sim, JANELA_VISAO_1);
        HotStuff<HotStuffBlock, HotStuffTx> lider = hs(net.getNode(1));
        assertTrue(lider.getDiscardCount(DiscardReason.VOTE_WRONG_BLOCK) >= 1, "voto em B descartado no lider");
        assertTrue(lider.getNumOfConfirmedBlocks() >= 1, "com f=1 a visao do lider honesto ainda decide");
    }

    // ------------------------------------------------------------- (c) pre-condicao falha alto

    @Test
    void menosDeDoisVizinhosFalhaAlto() {
        Simulator sim = new Simulator();
        HotStuffLocalLANNetwork net = rede(sim, 4, 1, equivoca(4, Set.of(1), 1));
        List<Node> vizinhos = net.getNode(1).getP2pConnections().getNeighbors();
        while (vizinhos.size() > 1) {
            vizinhos.remove(vizinhos.size() - 1);
        }
        net.startConsensusRound(sim, 5.0);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> rodar(sim, 5.0));
        assertTrue(e.getMessage().contains("EQUIVOCATION") && e.getMessage().contains("2 vizinhos"), e.getMessage());
    }

    @Test
    void metadeSemHonestoNoPrepareFalhaAltoAntesDeFabricarOuEnviar() {
        // n=4, lider 1, vizinhos 0 | 2 3. byz {1,0}: 1a metade sem honesto. byz {1,2,3}: 2a metade sem honesto.
        for (Set<Integer> byz : List.of(Set.of(1, 0), Set.of(1, 2, 3))) {
            Simulator sim = new Simulator();
            HotStuffLocalLANNetwork net = rede(sim, 4, 1, equivoca(4, byz, 1));
            net.startConsensusRound(sim, 5.0);
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> rodar(sim, 5.0), byz.toString());
            assertTrue(e.getMessage().contains("EQUIVOCATION") && e.getMessage().contains("PREPARE")
                    && e.getMessage().contains("destinatario honesto"), e.getMessage());
            assertTrue(e.getMessage().contains(byz.contains(0) ? "1a metade=0" : "2a=0"), e.getMessage());
            assertEquals(0L, hs(net.getNode(1)).getEquivocatedProposals(), "nada foi enviado antes da falha");
        }
    }

    @Test
    void metadeSemHonestoNoVotoFalhaAlto() {
        // n=4, lider 1 honesto; replica 2 bizantina com vizinhos 0 | 1 3; byz {2,0}: 1a metade sem honesto.
        Simulator sim = new Simulator();
        HotStuffLocalLANNetwork net = rede(sim, 4, 1, equivoca(4, Set.of(2, 0), 1));
        net.startConsensusRound(sim, 5.0);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> rodar(sim, 5.0));
        assertTrue(e.getMessage().contains("PREPARE") && e.getMessage().contains("destinatario honesto")
                && e.getMessage().contains("no 2"), e.getMessage());
        assertEquals(0L, hs(net.getNode(2)).getEquivocatedVotes());
    }

    // ------------------------------------------------------------ (e) safety e liveness com <= f

    /** Cadeia finalizada de um no: do bloco mais alto finalizado ate o genesis (por pai fixo), do genesis para cima. */
    private static List<HotStuffBlock> cadeia(HotStuffNode node) {
        List<HotStuffBlock> out = new ArrayList<>();
        HotStuffBlock b = hs(node).getReplicaState().getHighestFinalized().orElse(null);
        while (b != null && b.getFixedParent() != null) {
            out.add(0, b);
            b = b.getFixedParent();
        }
        return out;
    }

    private record Resultado(boolean diverge, int minDecisoes, int maxDecisoes, long abandonadas, long fasesSemEquivocar,
                             long visaoMax) { }

    private static Resultado simular(int n, Set<Integer> byz, long seed, double stop) {
        Simulator sim = new Simulator();
        HotStuffLocalLANNetwork net = rede(sim, n, seed, equivoca(n, byz, seed));
        net.startConsensusRound(sim, stop);
        rodar(sim, stop);
        int min = Integer.MAX_VALUE;
        int max = 0;
        long abandonadas = 0;
        long semEquivocar = 0;
        long visaoMax = 0;
        List<List<HotStuffBlock>> cadeias = new ArrayList<>();
        for (HotStuffNode node : net.getAllNodes()) {
            abandonadas += hs(node).getLeaderActionsOnAbandonedView();
            semEquivocar += hs(node).getUnequivocatedLeaderPhases();
            if (byz.contains(node.getNodeID())) {
                continue; // so as decisoes dos HONESTOS entram na safety
            }
            List<HotStuffBlock> c = cadeia(node);
            cadeias.add(c);
            min = Math.min(min, c.size());
            max = Math.max(max, c.size());
            visaoMax = Math.max(visaoMax, hs(node).getCurrentView());
        }
        boolean diverge = false;
        for (List<HotStuffBlock> x : cadeias) {
            for (List<HotStuffBlock> y : cadeias) {
                for (int i = 0; i < Math.min(x.size(), y.size()); i++) {
                    diverge |= x.get(i) != y.get(i); // mesma altura, blocos diferentes decididos por dois honestos
                }
            }
        }
        return new Resultado(diverge, min, max, abandonadas, semEquivocar, visaoMax);
    }

    private static void combinacoes(int n, int k, int inicio, int[] atual, int fundo, List<Set<Integer>> out) {
        if (fundo == k) {
            Set<Integer> s = new TreeSet<>();
            for (int x : atual) {
                s.add(x);
            }
            out.add(s);
            return;
        }
        for (int i = inicio; i < n; i++) {
            atual[fundo] = i;
            combinacoes(n, k, i + 1, atual, fundo + 1, out);
        }
    }

    @Test
    void safetyComAteFBizantinosEquivocandoParaTodosOsConjuntosEmN4EN7() {
        for (int n : new int[]{4, 7}) {
            int f = (n - 1) / 3;
            List<Set<Integer>> conjuntos = new ArrayList<>();
            combinacoes(n, f, 0, new int[f], 0, conjuntos);
            for (long seed : new long[]{1, 7}) {
                for (Set<Integer> byz : conjuntos) {
                    Resultado r = simular(n, byz, seed, 40.0);
                    String ctx = " (n=" + n + ", f=" + f + ", byz=" + byz + ", seed=" + seed + ") " + r;
                    assertFalse(r.diverge(), "SAFETY violada: honestos decidiram blocos diferentes na mesma altura" + ctx);
                    assertEquals(0L, r.abandonadas(), "o ataque nao pode fazer o lider agir em visao abandonada" + ctx);
                    assertEquals(0L, r.fasesSemEquivocar(), "fase com QC de lider equivocador e inalcancavel" + ctx);
                }
            }
        }
    }

    /**
     * LIVENESS observada (fixture: config de teste, timeout de visao 10 s, 40 s simulados, semente 1), SEM promessa
     * fora destas condicoes. Mecanismo: nas visoes cujo lider e bizantino o PREPARE vai em duas versoes, A chega a
     * {@code floor((n-1)/2) + 1} votos (< n - f) e B nao tem QC; a visao nao decide e as replicas honestas saem dela
     * por TIMEOUT (10 s); as visoes de lider honesto decidem (com f replica bizantina o quorum n - f ainda fecha com
     * os honestos). Assere-se so o piso: cada honesto decide ao menos 1 bloco, e nenhuma divergencia. O numero
     * decidido por execucao e impresso, nao asserido. Diferente do Tendermint, nao se observou n=4 sem decisao: o
     * lider rotativo muda a cada visao, entao o bizantino bloqueia so as proprias visoes.
     */
    @Test
    void livenessObservadaComUmBizantinoEmN4EN7() {
        for (int n : new int[]{4, 7}) {
            for (int id = 0; id < n; id++) {
                Resultado r = simular(n, Set.of(id), 1, 40.0);
                assertFalse(r.diverge(), "n=" + n + " byz=" + id);
                assertTrue(r.minDecisoes() >= 1, "honestos devem decidir ao menos 1 bloco em 40 s: " + r + " n=" + n
                        + " byz=" + id);
                System.out.println("[HotStuffEquivocationTest] liveness n=" + n + " byz=" + id + " " + r);
            }
        }
    }

    // ------------------------------------- regra 4: descarte de visao continua valendo para mensagem conflitante

    @Test
    void propostaConflitanteReentregueEmVisaoAbandonadaEDescartadaPorOldView() {
        int n = 4;
        Simulator sim = new Simulator();
        HotStuffLocalLANNetwork net = rede(sim, n, 7, equivoca(n, Set.of(1), 7));
        net.startConsensusRound(sim, 25.0);
        List<Packet> saidos = rodarColetando(sim, 25.0, net.getNode(1));
        Packet prepareB = null;
        for (Packet p : prepares(saidos, HotStuff.INITIAL_VIEW)) {
            if (p.getTo().getNodeID() == 2) {
                prepareB = p; // vizinhos de 1: [0 | 2 3]; o no 2 esta na 2a metade e recebeu B
            }
        }
        assertNotNull(prepareB);
        HotStuffNode honesto = net.getNode(2);
        assertTrue(hs(honesto).getCurrentView() > HotStuff.INITIAL_VIEW, "o honesto ja saiu da visao 1 por timeout");
        long antes = hs(honesto).getDiscardCount(DiscardReason.OLD_VIEW);
        hs(honesto).onProposal((HotStuffProposalMessage) prepareB.getMessage());
        assertEquals(antes + 1, hs(honesto).getDiscardCount(DiscardReason.OLD_VIEW),
                "proposta conflitante de visao abandonada passa pelo ReplicaCore e e contada, nao processada");
    }

    // ---------------------------------------- determinismo com ataque; sem ataque a saida nao muda

    /** CSV do cenario sem a coluna de hash de identidade (varia por execucao) e sem comentarios. */
    private static List<String> csv(Path dir, String nome, long seed, int n, double stop, ByzantineConfig byz,
                                    boolean passaConfig) throws IOException {
        HotStuffLANScenario sc = passaConfig
                ? new HotStuffLANScenario("hs-eq", seed, n, stop, CFG, Set.of(), byz)
                : new HotStuffLANScenario("hs-eq", seed, n, stop, CFG);
        Path arq = dir.resolve(nome + ".csv");
        sc.addMetricsLogger(arq.toString());
        sc.run();
        List<String> out = new ArrayList<>();
        int col = -1;
        for (String l : Files.readAllLines(arq)) {
            if (l.startsWith("#")) {
                continue;
            }
            String[] cols = l.split(",", -1);
            if (col < 0) {
                col = List.of(cols).indexOf("BlockHashCode");
                assertTrue(col >= 0, "coluna BlockHashCode");
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cols.length; i++) {
                if (i != col) {
                    sb.append(cols[i]).append(',');
                }
            }
            out.add(sb.toString());
        }
        assertTrue(out.size() > 2, "deve haver finalizacoes: " + out.size());
        return out;
    }

    @Test
    void comAtaqueMesmaSementeEMesmoConjuntoDaCsvIdentico(@TempDir Path dir) throws IOException {
        int n = 7;
        Set<Integer> byz = Set.of(0, 4);
        List<String> a = csv(dir, "a", 7, n, 30.0, equivoca(n, byz, 7), true);
        List<String> b = csv(dir, "b", 7, n, 30.0, equivoca(n, byz, 7), true);
        assertEquals(a, b, "mesma semente e mesmo conjunto bizantino");
        List<String> honesto = csv(dir, "h", 7, n, 30.0, null, false);
        assertFalse(a.equals(honesto), "o ataque tem efeito observavel (a comparacao nao e vazia)");
    }

    @Test
    void semAtaqueASaidaEIdenticaComSemOuComConfigNulaNoneOuVazia(@TempDir Path dir) throws IOException {
        int n = 4;
        List<String> sem = csv(dir, "sem", 7, n, 20.0, null, false);
        assertEquals(sem, csv(dir, "nulo", 7, n, 20.0, null, true), "ByzantineConfig nulo");
        assertEquals(sem, csv(dir, "vazio", 7, n, 20.0, equivoca(n, Set.of(), 7), true), "EQUIVOCATION sem bizantinos");
        assertEquals(sem, csv(dir, "none-vazio", 7, n, 20.0, new ByzantineConfig(n, Set.of(), "NONE", 7), true),
                "NONE sem bizantinos");
    }

    @Test
    void hotStuffNaoTemCatchDeExceptionVazio() throws IOException {
        GuardaCatch.exigeSemCatchEngolido(Path.of("src/main/java/jabs/consensus/algorithm/HotStuff.java"));
        GuardaCatch.exigeSemCatchEngolido(Path.of("src/main/java/jabs/scenario/HotStuffLANScenario.java"));
        GuardaCatch.exigeSemCatchEngolido(
                Path.of("src/main/java/jabs/network/networks/hotstuff/HotStuffLocalLANNetwork.java"));
    }
}
