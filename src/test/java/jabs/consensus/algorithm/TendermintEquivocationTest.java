package jabs.consensus.algorithm;

import jabs.config.ByzantineConfig;
import jabs.consensus.algorithm.tendermint.TendermintVote;
import jabs.consensus.config.TendermintConsensusConfig;
import jabs.ledgerdata.tendermint.TendermintBlock;
import jabs.ledgerdata.tendermint.TendermintTx;
import jabs.network.message.Packet;
import jabs.network.message.TendermintProposalMessage;
import jabs.network.message.TendermintVoteMessage;
import jabs.network.networks.tendermint.TendermintLocalLANNetwork;
import jabs.network.networks.tendermint.TendermintTestConfig;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.tendermint.TendermintNode;
import jabs.scenario.TendermintLANScenario;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 * D3b-tendermint-equivocation: ataque EQUIVOCATION no Tendermint (ADR-0010 PI-12). A particao (metades por indice dos
 * vizinhos), a abrangencia (proposta + prevote + precommit) e a ausencia de coordenacao sao PROVISORIAS ate a
 * resposta da Q28 (ADR-0015); estes testes fixam o comportamento provisorio, que e o mesmo do PBFT.
 *
 * <p>Relato de liveness observado (semente 1, 7, 8, 42; 30 s simulados; config de teste; sem gossip):
 * n=7 (f=2) e n=10 (f=3) progridem para TODOS os conjuntos de f bizantinos (minimo observado: 22 e 11 alturas);
 * n=4 (f=1) NAO progride quando o bizantino e proponente da altura em curso: os rounds avancam por timeout, mas nenhuma
 * altura e decidida. Causa provavel (consistente com o log de votos por no, nao provada por teste): o voto B do
 * bizantino chega so a uma metade e, sem repasse, os honestos da outra nunca veem a polka de B que os travados exigem. E a limitacao do broadcast direto que o javadoc do nucleo ja declara; nao foi "corrigida" aqui.
 * Safety nunca foi violada em nenhuma execucao com f.
 */
class TendermintEquivocationTest {

    private static final TendermintConsensusConfig CFG = TendermintTestConfig.config();
    private static final int PROPOSAL_BYTES = 32 + 10 + 80 + 20 * 512;
    private static final int VOTE_BYTES = 32 + 10 + 64;

    // ------------------------------------------------------------------------------------------------ montagem

    private static ByzantineConfig equivoca(int n, Set<Integer> ids, long seed) {
        return new ByzantineConfig(n, ids, "EQUIVOCATION", seed);
    }

    private static TendermintLocalLANNetwork rede(Simulator sim, int n, long seed, ByzantineConfig byz) {
        TendermintLocalLANNetwork net = new TendermintLocalLANNetwork(new RandomnessEngine(seed));
        net.populateNetwork(sim, n, CFG);
        if (byz != null) {
            for (TendermintNode node : net.getAllNodes()) {
                node.getConsensusAlgorithm().setByzantineConfig(byz);
            }
        }
        return net;
    }

    @SuppressWarnings("unchecked")
    private static Tendermint<TendermintBlock, TendermintTx> alg(TendermintNode node) {
        return (Tendermint<TendermintBlock, TendermintTx>) node.getConsensusAlgorithm();
    }

    /** Executa eventos ate {@code stop}; devolve os pacotes que SAEM de {@code from} (observados antes de executar). */
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

    private static List<Packet> propostas(List<Packet> pacotes, long height, int round) {
        List<Packet> out = new ArrayList<>();
        for (Packet p : pacotes) {
            if (p.getMessage() instanceof TendermintProposalMessage m && m.getHeight() == height && m.getRound() == round) {
                out.add(p);
            }
        }
        return out;
    }

    private static List<Packet> votos(List<Packet> pacotes, TendermintVote.Type tipo, long height, int round) {
        List<Packet> out = new ArrayList<>();
        for (Packet p : pacotes) {
            if (p.getMessage() instanceof TendermintVoteMessage m && m.getType() == tipo && m.getHeight() == height
                    && m.getRound() == round) {
                out.add(p);
            }
        }
        return out;
    }

    private static TendermintBlock blocoDe(Packet p) {
        return p.getMessage() instanceof TendermintProposalMessage m ? m.getBlock()
                : ((TendermintVoteMessage) p.getMessage()).getBlock();
    }

    /**
     * Verifica a particao provisoria: os vizinhos de {@code byz} em ordem crescente de id (malha completa sem si);
     * indices [0, half) recebem um MESMO bloco A e [half, size) um MESMO bloco B; A e B conflitantes (identidade e
     * hash distintos, mesma altura e mesmo pai); um pacote por vizinho; nenhum para si.
     */
    private static TendermintBlock[] verificaMetades(int n, int byz, List<Packet> pacotes) {
        int size = n - 1;
        int half = Math.max(1, size / 2);
        assertEquals(size, pacotes.size(), "um pacote por vizinho");
        TendermintBlock a = null;
        TendermintBlock b = null;
        Set<Integer> destinos = new HashSet<>();
        for (Packet p : pacotes) {
            assertNotSame(p.getFrom(), p.getTo(), "sem autoentrega");
            assertTrue(destinos.add(p.getTo().nodeID), "destinatario repetido " + p.getTo().nodeID);
            List<Integer> vizinhos = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (i != byz) {
                    vizinhos.add(i);
                }
            }
            int idx = vizinhos.indexOf(p.getTo().nodeID);
            TendermintBlock blk = blocoDe(p);
            if (idx < half) {
                a = a == null ? blk : a;
                assertSame(a, blk, "1a metade recebe sempre o mesmo bloco (no " + p.getTo().nodeID + ")");
            } else {
                b = b == null ? blk : b;
                assertSame(b, blk, "2a metade recebe sempre o mesmo bloco (no " + p.getTo().nodeID + ")");
            }
        }
        assertNotNull(a);
        assertNotNull(b);
        assertNotSame(a, b, "os dois valores sao conflitantes (identidade distinta)");
        assertNotSame(a.getHash(), b.getHash(), "hash distinto");
        assertEquals(a.getHeight(), b.getHeight(), "mesma altura");
        assertSame(a.getFixedParent(), b.getFixedParent(), "mesmo pai");
        assertEquals(a.getSize(), b.getSize(), "mesmo tamanho");
        return new TendermintBlock[]{a, b};
    }

    // --------------------------------------------------------------------- (1) supportedAttacks / setByzantineConfig

    @Test
    void equivocationAceitoOutrosAtaquesFalhamAlto() {
        TendermintLocalLANNetwork net = rede(new Simulator(), 4, 1, null);
        Tendermint<TendermintBlock, TendermintTx> t = alg(net.getNode(0));
        t.setByzantineConfig(equivoca(4, Set.of(1), 1));
        assertTrue(t.isByzantineValidator(1));
        assertEquals("EQUIVOCATION", t.getByzantineAttackType());
        for (String ataque : new String[]{"SILENT", "WITHHOLD", "DOUBLE_SIGN"}) {
            Tendermint<TendermintBlock, TendermintTx> outro = alg(net.getNode(1));
            UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                    () -> outro.setByzantineConfig(new ByzantineConfig(4, Set.of(1), ataque, 1)), ataque);
            assertTrue(e.getMessage().contains(ataque) && e.getMessage().contains("EQUIVOCATION"), e.getMessage());
            assertNull(outro.getByzantineConfig(), "config recusada nao pode ficar gravada: " + ataque);
        }
        // NONE ("sem ataque") e null continuam aceitos.
        t.setByzantineConfig(new ByzantineConfig(4, Set.of(1), "NONE", 1));
        t.setByzantineConfig(null);
        assertNull(t.getByzantineConfig());
    }

    @Test
    void cenarioFalhaAltoParaAtaqueNaoSuportado() {
        for (String ataque : new String[]{"SILENT", "WITHHOLD", "DOUBLE_SIGN"}) {
            TendermintLANScenario sc = new TendermintLANScenario("t", 1, 4, 2.0, CFG,
                    new ByzantineConfig(4, Set.of(1), ataque, 1));
            assertThrows(UnsupportedOperationException.class, sc::run, ataque);
        }
    }

    @Test
    void cenarioRecusaConfigIncoerente() {
        assertThrows(IllegalArgumentException.class, () -> new TendermintLANScenario("t", 1, 4, 2.0, CFG,
                equivoca(7, Set.of(1), 1)));
        assertThrows(IllegalArgumentException.class, () -> new TendermintLANScenario("t", 1, 4, 2.0, CFG,
                equivoca(4, Set.of(9), 1)));
    }

    // ------------------------------------------------------------------ (2) proposta conflitante do proponente bizantino

    @Test
    void propostaConflitanteAMetadesDisjuntasENenhumHonestoRecebeAsDuas() {
        for (int n : new int[]{4, 7, 10}) {
            Simulator sim = new Simulator();
            Set<Integer> byz = Set.of(0); // (h + r) % n == 0 em h=0, r=0: o no 0 e o proponente
            TendermintLocalLANNetwork net = rede(sim, n, 7, equivoca(n, byz, 7));
            net.startConsensusRound(sim, 5.0);
            List<Packet> saidos = rodarColetando(sim, 0.3, net.getNode(0));

            List<Packet> props = propostas(saidos, 0, 0);
            TendermintBlock[] ab = verificaMetades(n, 0, props);
            for (Packet p : props) {
                assertEquals(PROPOSAL_BYTES, p.getSize(), "proposta: hash+overhead+assinatura do proponente+payload");
            }

            // Nenhum no recebe duas propostas conflitantes do bizantino para o mesmo (h, r): pelo mapa e pelo nucleo.
            Map<Integer, Set<TendermintBlock>> porDestino = new HashMap<>();
            for (Packet p : saidos) {
                if (p.getMessage() instanceof TendermintProposalMessage m) {
                    porDestino.computeIfAbsent(p.getTo().nodeID, k -> new HashSet<>()).add(m.getBlock());
                }
            }
            // (h = 0 e r = 0 no unico round em que o no 0 propoe nesta janela)
            for (Map.Entry<Integer, Set<TendermintBlock>> e : porDestino.entrySet()) {
                assertEquals(1, e.getValue().size(), "no " + e.getKey() + " recebeu propostas conflitantes");
            }
            for (TendermintNode node : net.getAllNodes()) {
                if (node.nodeID != 0) {
                    assertEquals(0, alg(node).getCore().equivocationsObserved(),
                            "nenhum honesto viu as duas mensagens do bizantino (n=" + n + ", no " + node.nodeID + ")");
                }
            }

            // B e VALIDO para a altura: os honestos da 2a metade o aceitam e prevotam nele (nao nil, nao rejeitado).
            int half = Math.max(1, (n - 1) / 2);
            for (int id = 1; id < n; id++) {
                List<TendermintVote<TendermintBlock>> pv = alg(net.getNode(id)).getCore()
                        .loggedVotes(0, 0, TendermintVote.Type.PREVOTE, id);
                assertEquals(1, pv.size(), "no " + id);
                assertSame((id - 1) < half ? ab[0] : ab[1], pv.get(0).value(),
                        "no " + id + " prevota no bloco que recebeu (A na 1a metade, B na 2a)");
                assertEquals(0, alg(net.getNode(id)).getCore()
                        .discardCount(jabs.consensus.algorithm.tendermint.TendermintDiscardReason.INVALID_VALUE),
                        "B nao pode ser rejeitado por valid(h, v)");
            }
        }
    }

    @Test
    void trafegoDaPropostaConflitanteEContadoPeloHookNormal() {
        int n = 7;
        Simulator sim = new Simulator();
        TendermintLocalLANNetwork net = rede(sim, n, 7, equivoca(n, Set.of(0), 7));
        net.startConsensusRound(sim, 5.0);
        List<Packet> saidos = rodarColetando(sim, 0.3, net.getNode(0));
        List<Packet> props = propostas(saidos, 0, 0);
        TendermintBlock[] ab = verificaMetades(n, 0, props);
        // O bizantino so injeta (n-1) pacotes de proposta, cada um com o tamanho correto; nenhum para si.
        long bytesEnviados = 0;
        for (Packet p : props) {
            bytesEnviados += p.getSize();
        }
        assertEquals((long) (n - 1) * PROPOSAL_BYTES, bytesEnviados);
        // O no 4 e da 2a metade (vizinhos 1 2 3 | 4 5 6): o hook normal soma packet.getSize() de tudo que ele recebeu
        // sobre B (a proposta e, depois, votos em B), e o consenso e o total menos a carga do bloco (uma vez).
        Tendermint<TendermintBlock, TendermintTx> receptor = alg(net.getNode(4));
        long total = receptor.getBlockTraffic(ab[1]);
        long consenso = receptor.getConsensusMessageTraffic(ab[1]);
        assertTrue(total >= PROPOSAL_BYTES, "recebeu a proposta B: " + total);
        assertEquals(0, (total - PROPOSAL_BYTES) % VOTE_BYTES, "o resto sao votos inteiros: " + total);
        assertEquals(total - 20L * 512, consenso, "consenso = total - carga do bloco (cabecalho + votos)");
        // O no 1 e da 1a metade: nunca recebeu a proposta B (a de A e outro bloco).
        for (Packet p : props) {
            if (p.getTo().nodeID == 1) {
                assertSame(ab[0], blocoDe(p));
            }
        }
    }

    // ----------------------------------------------------------- (3) votos (prevote e precommit) conflitantes

    @Test
    void prevoteEPrecommitConflitantesAMetadesDisjuntas() {
        for (int n : new int[]{4, 7, 10}) {
            int byz = 2; // nao e proponente em (0, 0): so vota
            Simulator sim = new Simulator();
            TendermintLocalLANNetwork net = rede(sim, n, 7, equivoca(n, Set.of(byz), 7));
            net.startConsensusRound(sim, 5.0);
            List<Packet> saidos = rodarColetando(sim, 0.3, net.getNode(byz));

            assertTrue(propostas(saidos, 0, 0).isEmpty(), "o bizantino nao propoe em (0, 0)");
            List<Packet> prevotes = votos(saidos, TendermintVote.Type.PREVOTE, 0, 0);
            List<Packet> precommits = votos(saidos, TendermintVote.Type.PRECOMMIT, 0, 0);
            TendermintBlock[] pv = verificaMetades(n, byz, prevotes);
            TendermintBlock[] pc = verificaMetades(n, byz, precommits);
            // O mesmo par (A, B) serve as duas fases do round.
            assertSame(pv[0], pc[0]);
            assertSame(pv[1], pc[1]);
            // A e o bloco realmente proposto pelo no 0 (honesto); B foi fabricado pelo bizantino e ninguem o propos.
            assertEquals(0, pv[0].getCreator().nodeID);
            assertEquals(byz, pv[1].getCreator().nodeID);
            for (Packet p : prevotes) {
                assertEquals(VOTE_BYTES, p.getSize(), "voto: hash+overhead+assinatura");
            }
            for (Packet p : precommits) {
                assertEquals(VOTE_BYTES, p.getSize(), "voto: hash+overhead+assinatura");
            }
            // Cada destinatario recebeu UM voto do bizantino por (tipo, h, r), nunca dois conflitantes: pacotes e nucleo.
            Map<String, Set<Integer>> vistos = new HashMap<>();
            for (Packet p : saidos) {
                if (p.getMessage() instanceof TendermintVoteMessage m && !m.isNil()) {
                    String chave = m.getType() + "/" + m.getHeight() + "/" + m.getRound();
                    assertTrue(vistos.computeIfAbsent(chave, k -> new HashSet<>()).add(p.getTo().nodeID),
                            "no " + p.getTo().nodeID + " recebeu dois votos " + chave + " do bizantino");
                }
            }
            for (TendermintNode node : net.getAllNodes()) {
                if (node.nodeID != byz) {
                    assertEquals(0, alg(node).getCore().equivocationsObserved(),
                            "nenhum honesto viu as duas mensagens do bizantino (n=" + n + ", no " + node.nodeID + ")");
                }
            }
        }
    }

    @Test
    void trafegoDoVotoConflitanteEContadoPeloHookNormal() {
        int n = 7;
        int byz = 2;
        Simulator sim = new Simulator();
        TendermintLocalLANNetwork net = rede(sim, n, 7, equivoca(n, Set.of(byz), 7));
        net.startConsensusRound(sim, 5.0);
        List<Packet> saidos = rodarColetando(sim, 0.3, net.getNode(byz));
        TendermintBlock[] ab = verificaMetades(n, byz, votos(saidos, TendermintVote.Type.PREVOTE, 0, 0));
        // vizinhos de 2: 0 1 3 | 4 5 6. O no 4 (2a metade) recebeu prevote e precommit de B: 2 x 106 bytes, tudo
        // consenso. B nunca e proposto nem finalizado, entao o contador nao e zerado. O no 1 (1a metade) nao viu B.
        Tendermint<TendermintBlock, TendermintTx> r4 = alg(net.getNode(4));
        assertEquals(2L * VOTE_BYTES, r4.getBlockTraffic(ab[1]));
        assertEquals(2L * VOTE_BYTES, r4.getConsensusMessageTraffic(ab[1]));
        assertEquals(0, alg(net.getNode(1)).getBlockTraffic(ab[1]));
    }

    @Test
    void votoNilNaoEEquivocado() {
        // Voto nil nao carrega valor: o bizantino o envia a TODOS os vizinhos, uma unica versao. A semente 8 com
        // o no 3 bizantino produz rounds com nil nesta janela (verificado abaixo, para o teste nao ser vazio).
        int n = 7;
        int byz = 3;
        Simulator sim = new Simulator();
        TendermintLocalLANNetwork net = rede(sim, n, 8, equivoca(n, Set.of(byz), 8));
        net.startConsensusRound(sim, 20.0);
        List<Packet> saidos = rodarColetando(sim, 20.0, net.getNode(byz));
        Map<String, Integer> porChave = new HashMap<>();
        for (Packet p : saidos) {
            if (p.getMessage() instanceof TendermintVoteMessage m && m.isNil()) {
                porChave.merge(m.getType() + "/" + m.getHeight() + "/" + m.getRound(), 1, Integer::sum);
                assertEquals(VOTE_BYTES, p.getSize());
            }
        }
        assertFalse(porChave.isEmpty(), "a janela deve conter voto nil do bizantino");
        for (Map.Entry<String, Integer> e : porChave.entrySet()) {
            assertEquals(n - 1, e.getValue(), "voto nil " + e.getKey() + " vai uma vez a cada vizinho");
        }
    }

    // ------------------------------------------------------------------------------ (4) safety e liveness com <= f

    private record Resultado(boolean diverge, int minDecisoes, int maxDecisoes, int maxRound, boolean precondicao) { }

    private static Resultado simular(int n, Set<Integer> byz, long seed, double stop) {
        Simulator sim = new Simulator();
        TendermintLocalLANNetwork net = rede(sim, n, seed, equivoca(n, byz, seed));
        try {
            net.startConsensusRound(sim, stop);
            while (sim.isThereMoreEvents() && sim.peekEventTime() <= stop) {
                sim.executeNextEvent();
            }
        } catch (IllegalStateException e) {
            // Unica falha esperada: pre-condicao do ataque (documentada). Qualquer outra mensagem e bug e propaga.
            assertTrue(e.getMessage().contains("EQUIVOCATION") && e.getMessage().contains("destinatario honesto"),
                    e.getMessage());
            return new Resultado(false, 0, 0, 0, true);
        }
        boolean diverge = false;
        for (long h = 0; ; h++) {
            Set<TendermintBlock> decididos = new HashSet<>();
            for (TendermintNode node : net.getAllNodes()) {
                if (!byz.contains(node.nodeID)) {
                    TendermintBlock b = alg(node).getDecision(h);
                    if (b != null) {
                        decididos.add(b);
                    }
                }
            }
            if (decididos.isEmpty()) {
                break;
            }
            diverge |= decididos.size() > 1;
        }
        int min = Integer.MAX_VALUE;
        int max = 0;
        int maxRound = 0;
        for (TendermintNode node : net.getAllNodes()) {
            if (!byz.contains(node.nodeID)) {
                int d = alg(node).getCore().decisions().size();
                min = Math.min(min, d);
                max = Math.max(max, d);
                maxRound = Math.max(maxRound, alg(node).getRound());
            }
        }
        return new Resultado(diverge, min, max, maxRound, false);
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
    void safetyComAteFBizantinosEquivocandoParaTodosOsConjuntos() {
        for (int n : new int[]{4, 7, 10}) {
            int f = (n - 1) / 3;
            List<Set<Integer>> conjuntos = new ArrayList<>();
            combinacoes(n, f, 0, new int[f], 0, conjuntos);
            for (long seed : new long[]{1, 7}) {
                for (Set<Integer> byz : conjuntos) {
                    Resultado r = simular(n, byz, seed, 30.0);
                    assertFalse(r.precondicao(), "com f bizantinos a pre-condicao sempre vale: n=" + n + " " + byz);
                    assertFalse(r.diverge(), "SAFETY violada: honestos decidiram blocos diferentes na mesma altura"
                            + " (n=" + n + ", f=" + f + ", byz=" + byz + ", seed=" + seed + ")");
                    // honestos nunca ficam mais de uma altura afastados ao fim (nenhum decide a frente "no vazio")
                    assertTrue(r.maxDecisoes() - r.minDecisoes() <= 1,
                            "honestos desalinhados: " + r + " n=" + n + " " + byz);
                }
            }
        }
    }

    @Test
    void livenessComAteFBizantinosEmN7EN10() {
        for (int n : new int[]{7, 10}) {
            int f = (n - 1) / 3;
            List<Set<Integer>> conjuntos = new ArrayList<>();
            combinacoes(n, f, 0, new int[f], 0, conjuntos);
            for (long seed : new long[]{1, 7}) {
                for (Set<Integer> byz : conjuntos) {
                    Resultado r = simular(n, byz, seed, 30.0);
                    assertTrue(r.minDecisoes() >= 5, "rede deve continuar progredindo: " + r + " n=" + n + " " + byz
                            + " seed=" + seed);
                }
            }
        }
    }

    @Test
    void emN4ComBizantinoProponenteOsRoundsAvancamMasNaoHaDecisaoSemGossip() {
        // Observacao, nao promessa: o limite de liveness do broadcast direto. Asserimos so o que o protocolo
        // garante aqui: rounds avancam por timeout (nao trava no round 0) e safety continua valendo.
        for (int id = 0; id < 4; id++) {
            Resultado r = simular(4, Set.of(id), 7, 30.0);
            assertFalse(r.diverge(), "byz=" + id);
            assertTrue(r.maxRound() > 0 || r.minDecisoes() > 0, "round deve avancar por timeout; byz=" + id + " " + r);
        }
    }

    // --------------------------------------------------------------- (5) acima do limiar: so observar, sem promessa

    @Test
    void acimaDoLimiarSoObservaSemAfirmarSafety() {
        int execucoes = 0;
        int precondicao = 0;
        int divergentes = 0;
        for (int[] cfg : new int[][]{{7, 3}, {10, 4}}) {
            int n = cfg[0];
            List<Set<Integer>> conjuntos = new ArrayList<>();
            combinacoes(n, cfg[1], 0, new int[cfg[1]], 0, conjuntos);
            int passo = Math.max(1, conjuntos.size() / 20);
            for (int i = 0; i < conjuntos.size(); i += passo) {
                Resultado r = simular(n, conjuntos.get(i), 7, 20.0);
                execucoes++;
                if (r.precondicao()) {
                    precondicao++;
                } else if (r.diverge()) {
                    divergentes++;
                }
            }
        }
        // Sem asserir safety: acima de floor((n-1)/3) o protocolo nao a promete. Divergencias observadas (se houver)
        // so sao contadas e impressas. Com o ataque NAO coordenado e provisorio nao se observou divergencia, mas isso
        // NAO e garantia: um atacante coordenado acima do limiar pode violar a safety.
        System.out.println("[TendermintEquivocationTest] acima do limiar: execucoes=" + execucoes
                + " precondicao_violada=" + precondicao + " divergentes=" + divergentes);
        assertTrue(execucoes > 0);
    }

    // ---------------------------------------------------------------- (6) pre-condicao violada falha alto, claro

    @Test
    void menosDeDoisVizinhosFalhaAlto() {
        Simulator sim = new Simulator();
        TendermintLocalLANNetwork net = rede(sim, 4, 1, equivoca(4, Set.of(0), 1));
        List<Node> vizinhos = net.getNode(0).getP2pConnections().getNeighbors();
        while (vizinhos.size() > 1) {
            vizinhos.remove(vizinhos.size() - 1);
        }
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> net.startConsensusRound(sim, 1.0));
        assertTrue(e.getMessage().contains("EQUIVOCATION") && e.getMessage().contains("2 vizinhos"), e.getMessage());
    }

    @Test
    void metadeSemHonestoNaPropostaFalhaAlto() {
        // n=4, no 0 propoe; vizinhos 1 | 2 3. Com 1 bizantino, a 1a metade nao tem honesto.
        Simulator sim = new Simulator();
        TendermintLocalLANNetwork net = rede(sim, 4, 1, equivoca(4, Set.of(0, 1), 1));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> net.startConsensusRound(sim, 1.0));
        assertTrue(e.getMessage().contains("PROPOSAL") && e.getMessage().contains("destinatario honesto")
                && e.getMessage().contains("1a metade=0"), e.getMessage());

        Simulator sim2 = new Simulator();
        TendermintLocalLANNetwork net2 = rede(sim2, 4, 1, equivoca(4, Set.of(0, 2, 3), 1));
        IllegalStateException e2 = assertThrows(IllegalStateException.class, () -> net2.startConsensusRound(sim2, 1.0));
        assertTrue(e2.getMessage().contains("2a=0"), e2.getMessage());
    }

    @Test
    void metadeSemHonestoNoVotoFalhaAlto() {
        // n=4, byz {1,2,3}: o no 0 (honesto) propoe; o no 1 e bizantino e seus vizinhos sao 0 | 2 3, onde a 2a metade
        // so tem bizantinos. A falha acontece quando o no 1 prevota (nao na proposta, que e do no 0).
        Simulator sim = new Simulator();
        TendermintLocalLANNetwork net = rede(sim, 4, 1, equivoca(4, Set.of(1, 2, 3), 1));
        net.startConsensusRound(sim, 5.0);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> {
            while (sim.isThereMoreEvents() && sim.peekEventTime() <= 5.0) {
                sim.executeNextEvent();
            }
        });
        assertTrue(e.getMessage().contains("PREVOTE") && e.getMessage().contains("destinatario honesto"),
                e.getMessage());
    }

    // ---------------------------------------- (7) e (8) saida sem ataque identica; determinismo com ataque

    /** CSV da simulacao sem a coluna de hash (hashCode de objeto varia por execucao) e sem comentarios. */
    private static List<String> csv(Path dir, String nome, long seed, int n, double stop, ByzantineConfig byz,
                                    boolean passaConfig) throws IOException {
        return csv(dir, nome, seed, n, stop, byz, passaConfig, Set.of());
    }

    private static List<String> csv(Path dir, String nome, long seed, int n, double stop, ByzantineConfig byz,
                                    boolean passaConfig, Set<String> colunasIgnoradas) throws IOException {
        TendermintLANScenario sc = passaConfig
                ? new TendermintLANScenario("tm-eq", seed, n, stop, CFG, byz)
                : new TendermintLANScenario("tm-eq", seed, n, stop, CFG);
        Path arq = dir.resolve(nome + ".csv");
        sc.addMetricsLogger(arq.toString());
        sc.run();
        List<String> linhas = Files.readAllLines(arq);
        String cab = null;
        Set<Integer> ignoradas = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String l : linhas) {
            if (l.startsWith("#")) {
                continue;
            }
            String[] cols = l.split(",", -1);
            if (cab == null) {
                cab = l;
                int col = List.of(cols).indexOf("BlockHashCode");
                assertTrue(col >= 0, "coluna BlockHashCode");
                ignoradas.add(col);
                for (String nomeCol : colunasIgnoradas) {
                    int c = List.of(cols).indexOf(nomeCol);
                    assertTrue(c >= 0, "coluna " + nomeCol);
                    ignoradas.add(c);
                }
                out.add(l);
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cols.length; i++) {
                if (!ignoradas.contains(i)) {
                    sb.append(cols[i]).append(',');
                }
            }
            out.add(sb.toString());
        }
        assertTrue(out.size() > 10, "deve haver finalizacoes: " + out.size());
        return out;
    }

    @Test
    void semAtaqueASaidaEIdenticaComSemOuComConfigNulaNoneOuVazia(@TempDir Path dir) throws IOException {
        int n = 4;
        List<String> sem = csv(dir, "sem", 7, n, 5.0, null, false);
        assertEquals(sem, csv(dir, "nulo", 7, n, 5.0, null, true), "ByzantineConfig nulo");
        assertEquals(sem, csv(dir, "vazio", 7, n, 5.0, equivoca(n, Set.of(), 7), true),
                "EQUIVOCATION sem nenhum bizantino");
        assertEquals(sem, csv(dir, "none-vazio", 7, n, 5.0, new ByzantineConfig(n, Set.of(), "NONE", 7), true),
                "NONE sem bizantinos");
        assertEquals(sem, csv(dir, "sem2", 7, n, 5.0, null, false), "determinismo por semente sem ataque");
        assertFalse(sem.equals(csv(dir, "outra", 8, n, 5.0, null, false)), "outra semente muda a saida");

        // NONE com ids: nenhum no age como bizantino, entao tudo e identico EXCETO a coluna de BFT, que a base calcula
        // pelo conjunto de ids (o proponente "bizantino" entra na contagem) mesmo sem ataque. Comportamento anterior,
        // igual nos outros protocolos; aqui so e declarado, nao alterado.
        Set<String> semBft = Set.of("BFT_ByzantineTolerance_pct");
        assertEquals(csv(dir, "sem-b", 7, n, 5.0, null, false, semBft),
                csv(dir, "none-ids", 7, n, 5.0, new ByzantineConfig(n, Set.of(1), "NONE", 7), true, semBft),
                "NONE com ids, sem a coluna de BFT");
    }

    @Test
    void comAtaqueMesmaSementeEMesmoConjuntoDaCsvIdentico(@TempDir Path dir) throws IOException {
        int n = 7;
        Set<Integer> byz = Set.of(0, 4);
        List<String> a = csv(dir, "a", 7, n, 8.0, equivoca(n, byz, 7), true);
        List<String> b = csv(dir, "b", 7, n, 8.0, equivoca(n, byz, 7), true);
        assertEquals(a, b, "mesma semente e mesmo conjunto bizantino");
        List<String> honesto = csv(dir, "h", 7, n, 8.0, null, false);
        assertFalse(a.equals(honesto), "o ataque tem efeito observavel (a comparacao nao e vazia)");
        List<String> outroConjunto = csv(dir, "c", 7, n, 8.0, equivoca(n, Set.of(1, 5), 7), true);
        assertFalse(a.equals(outroConjunto), "outro conjunto bizantino muda a saida");
    }

    // ------------------------------------------------------------------------------------ guarda estatica

    @Test
    void tendermintNaoTemCatchDeExceptionVazio() throws IOException {
        GuardaCatch.exigeSemCatchEngolido(Path.of("src/main/java/jabs/consensus/algorithm/Tendermint.java"));
        GuardaCatch.exigeSemCatchEngolido(Path.of("src/main/java/jabs/scenario/TendermintLANScenario.java"));
    }
}
