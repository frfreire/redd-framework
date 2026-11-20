package jabs.consensus.algorithm;

import jabs.config.ByzantineConfig;
import jabs.ledgerdata.BlockFactory;
import jabs.ledgerdata.pbft.PBFTBlock;
import jabs.ledgerdata.pbft.PBFTBlockVote;
import jabs.ledgerdata.pbft.PBFTCommitVote;
import jabs.ledgerdata.pbft.PBFTConflictingBlock;
import jabs.ledgerdata.pbft.PBFTPrePrepareVote;
import jabs.ledgerdata.pbft.PBFTPrepareVote;
import jabs.network.message.Packet;
import jabs.network.message.VoteMessage;
import jabs.network.networks.pbft.PBFTLocalLANNetwork;
import jabs.network.node.nodes.pbft.PBFTNode;
import jabs.simulator.Simulator;
import jabs.simulator.event.BlockFinalizationEvent;
import jabs.simulator.event.Event;
import jabs.simulator.event.PacketDeliveryEvent;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static jabs.network.node.nodes.pbft.PBFTNode.PBFT_GENESIS_BLOCK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PBFT-prepare-equivocation (ADR-0015 B-35 / Q28, lacuna L3): o ataque EQUIVOCATION do PBFT passa a cobrir tambem o
 * PREPARE (alem do PRE_PREPARE do primario e do COMMIT), com a mesma forma do Tendermint: o bloco real A a uma metade
 * dos vizinhos e um bloco conflitante B a outra, mesma particao (metades por indice) e mesma pre-condicao.
 * Os outros ataques NAO mudam (o PREPARE de SILENT/WITHHOLD/DOUBLE_SIGN segue honesto), e o no honesto tambem nao.
 * Cada teste tem uma mutacao correspondente em {@code scripts/jabs/mutar-pbft-prepare-equivocation.py}.
 */
class PBFTPrepareEquivocationTest {

    private record Alvo(PBFTNode r, List<PBFTNode> todos, Simulator sim, RandomnessEngine rng, int n) {}

    private static final int R = 1;

    private static Alvo novaRede(int n, String ataque, Set<Integer> bizantinos) {
        RandomnessEngine rng = new RandomnessEngine(5);
        Simulator sim = new Simulator();
        PBFTLocalLANNetwork net = new PBFTLocalLANNetwork(rng);
        net.populateNetwork(sim, n, new jabs.consensus.config.PBFTConsensusConfig(1000L));
        List<PBFTNode> todos = new ArrayList<>(net.getAllNodes());
        PBFTNode r = todos.get(Math.min(R, n - 1));
        if (ataque != null) {
            r.getConsensusAlgorithm().setByzantineConfig(new ByzantineConfig(n, bizantinos, ataque, 5));
        }
        return new Alvo(r, todos, sim, rng, n);
    }

    private static PBFTBlock novoBloco(Alvo a) {
        return BlockFactory.samplePBFTBlockWithTx(a.sim(), a.rng(), a.todos().get(0), PBFT_GENESIS_BLOCK, 5);
    }

    private static void entrega(Alvo a, PBFTNode de, PBFTBlockVote<PBFTBlock> voto) {
        a.r().processIncomingPacket(new Packet(de, a.r(), new VoteMessage(voto)));
    }

    private static void prePrepare(Alvo a, PBFTBlock b) {
        PBFTNode criador = a.todos().get(0);
        entrega(a, criador, new PBFTPrePrepareVote<>(criador, b));
    }

    private static void prepares(Alvo a, PBFTBlock b, int quantos) {
        int c = 0;
        for (PBFTNode p : a.todos()) {
            if (p != a.r() && c++ < quantos) entrega(a, p, new PBFTPrepareVote<>(p, b));
        }
    }

    private static void commits(Alvo a, PBFTBlock b, int quantos) {
        int c = 0;
        for (PBFTNode p : a.todos()) {
            if (p != a.r() && c++ < quantos) entrega(a, p, new PBFTCommitVote<>(p, b));
        }
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

    @SuppressWarnings("unchecked")
    private static PBFTBlock bloco(Packet p) {
        return ((PBFTBlockVote<PBFTBlock>) ((VoteMessage) p.getMessage()).getVote()).getBlock();
    }

    /** Uma metade [0, half) recebe sempre o MESMO bloco e [half, n) sempre o outro; devolve {primeiro, segundo}. */
    private static PBFTBlock[] verificaMetades(Alvo a, List<Packet> pacotes) {
        int n = a.n();
        int half = Math.max(1, n / 2);
        assertEquals(n, pacotes.size(), "um pacote por vizinho (n=" + n + ")");
        Set<Object> destinos = new HashSet<>();
        PBFTBlock primeiro = null;
        PBFTBlock segundo = null;
        for (Packet p : pacotes) {
            assertTrue(destinos.add(p.getTo()), "destinatario repetido: " + p.getTo().nodeID);
            int idx = a.todos().indexOf(p.getTo());
            PBFTBlock blk = bloco(p);
            if (idx < half) {
                if (primeiro == null) primeiro = blk;
                assertSame(primeiro, blk, "1a metade recebe sempre o mesmo bloco (no " + idx + ")");
            } else {
                if (segundo == null) segundo = blk;
                assertSame(segundo, blk, "2a metade recebe sempre o mesmo bloco (no " + idx + ")");
            }
        }
        return new PBFTBlock[] {primeiro, segundo};
    }

    // ---------- (1) EQUIVOCATION: PREPARE conflitante a metades disjuntas ----------

    @Test
    void equivocationEnviaPrepareRealAUmaMetadeEConflitanteAOutra() {
        for (int n : new int[] {4, 7, 10}) {
            Alvo a = novaRede(n, "EQUIVOCATION", Set.of(R));
            PBFTBlock real = novoBloco(a);
            prePrepare(a, real);
            List<Packet> prepares = pacotesDeR(a, PBFTBlockVote.VoteType.PREPARE);
            PBFTBlock[] m = verificaMetades(a, prepares);
            assertSame(real, m[0], "n=" + n + ": a 1a metade recebe o bloco REAL (aceito no PRE_PREPARE)");
            assertNotSame(real, m[1], "n=" + n + ": a 2a metade recebe um bloco conflitante");
            assertTrue(m[1] instanceof PBFTConflictingBlock,
                    "n=" + n + ": o B do ataque e um PBFTConflictingBlock (marca que evita reter seus PREPAREs)");
            assertFalse(real instanceof PBFTConflictingBlock, "o bloco real nunca e conflitante");
            for (Packet p : prepares) assertSame(a.r(), p.getFrom());
        }
    }

    @Test
    void blocoConflitanteTemMesmoPaiAlturaETamanhoEHashDistinto() {
        Alvo a = novaRede(7, "EQUIVOCATION", Set.of(R));
        PBFTBlock real = novoBloco(a);
        prePrepare(a, real);
        PBFTBlock alt = verificaMetades(a, pacotesDeR(a, PBFTBlockVote.VoteType.PREPARE))[1];
        assertSame(real.getParents().get(0), alt.getParents().get(0), "mesmo pai: conflito na mesma altura");
        assertEquals(real.getHeight(), alt.getHeight());
        assertEquals(real.getSize(), alt.getSize());
        assertNotSame(real.getHash(), alt.getHash(), "hash distinto: e outro bloco");
        assertNotEquals(real, alt);
    }

    @Test
    void mesmoBlocoConflitanteEmPreparesRepetidosEIgualPorValorSemSerGuardado() {
        Alvo a = novaRede(7, "EQUIVOCATION", Set.of(R));
        PBFTBlock real = novoBloco(a);
        prePrepare(a, real);
        // 2o PRE_PREPARE do mesmo bloco (voto novo, passa pelo alreadySeenVotes): outro round de PREPARE equivocado
        prePrepare(a, real);
        assertEquals(0, conflitantesRetidos(pbftDe(a.r())), "o no nao guarda B (rodada 4: funcao pura de A)");
        List<Packet> todos = pacotesDeR(a, PBFTBlockVote.VoteType.PREPARE);
        assertEquals(2 * a.n(), todos.size());
        PBFTBlock b1 = null;
        int deB = 0;
        for (Packet p : todos) {
            if (p.getTo() == a.todos().get(a.n() - 1)) {
                if (b1 == null) b1 = bloco(p);
                // dois rounds = dois objetos Java, mas o MESMO bloco logico: equals/hashCode por valor
                assertEquals(b1, bloco(p), "o mesmo B (por valor) serve a todos os PREPAREs equivocados de A");
                assertEquals(b1.hashCode(), bloco(p).hashCode());
                deB++;
            }
        }
        assertEquals(2, deB);
    }

    @Test
    void blocoConflitanteEFuncaoPuraDeAEDoCriador() {
        Alvo a = novaRede(7, "EQUIVOCATION", Set.of(R));
        PBFTBlock real = novoBloco(a);
        PBFTBlock x = conflitanteDe(a, real);
        PBFTBlock y = conflitanteDe(a, real);
        assertNotSame(x, y);
        assertEquals(x, y, "mesmo A, mesmo criador: igual por valor");
        assertEquals(x.hashCode(), y.hashCode());
        assertEquals(new HashSet<>(List.of(x)), new HashSet<>(List.of(y)), "e se comporta como a mesma chave de conjunto");
        assertEquals(real.getCreationTime(), x.getCreationTime(), "sem relogio: nada que varie entre chamadas");
        // outro criador (outro bizantino) ou outro A: outro bloco (sem coordenacao entre bizantinos)
        assertNotEquals(x, new PBFTConflictingBlock(real, a.todos().get(3)));
        assertNotEquals(x, new PBFTConflictingBlock(novoBloco(a), a.todos().get(2)));
        assertNotEquals(real, x);
        assertNotEquals(x, real);
    }

    @Test
    void nadaFicaRetidoQuandoABlocoFinaliza() {
        Alvo a = novaRede(4, "EQUIVOCATION", Set.of(R));
        PBFTBlock real = novoBloco(a);
        prePrepare(a, real);
        assertEquals(0, conflitantesRetidos(pbftDe(a.r())));
        prepares(a, real, PBFT.quorumSize(4));
        commits(a, real, PBFT.quorumSize(4));
        assertTrue(pbftDe(a.r()).isCommitted(real));
        assertEquals(0, conflitantesRetidos(pbftDe(a.r())));
    }

    /**
     * ACUMULACAO (rodada 4, criterio 8): muitos blocos A que NUNCA finalizam neste no, cada um com PREPARE equivocado.
     * A versao anterior guardava um B por A (mapa A -> B podado so quando A finalizava): crescia sem limite aqui.
     * Agora o no nao retem nenhum B, nem por A nem por qualquer outra colecao. Varre por reflexao TODAS as colecoes do
     * PBFT do no (ate 3 niveis, chaves, valores e o bloco de cada voto) em busca de PBFTConflictingBlock.
     */
    @Test
    void muitosABlocosQueNuncaFinalizamNaoAcumulamBlocosConflitantes() {
        Alvo a = novaRede(7, "EQUIVOCATION", Set.of(R));
        PBFT<PBFTBlock, ?> pbft = pbftDe(a.r());
        assertEquals(0, conflitantesRetidos(pbft));
        final int blocos = 2000;
        for (int i = 0; i < blocos; i++) {
            prePrepare(a, novoBloco(a)); // aceito, PREPARE equivocado emitido, jamais chega quorum: A nunca finaliza
            if (i == 9 || i == blocos - 1) {
                assertEquals(0, conflitantesRetidos(pbft), "apos " + (i + 1) + " A nao finalizados: retencao de B = 0");
            }
        }
        // sanidade: o ataque foi aplicado de fato (um B por A, na 2a metade dos vizinhos) e nada finalizou
        List<Packet> prepares = pacotesDeR(a, PBFTBlockVote.VoteType.PREPARE);
        int half = Math.max(1, a.n() / 2);
        long deB = prepares.stream().filter(p -> bloco(p) instanceof PBFTConflictingBlock).count();
        assertEquals((long) blocos * (a.n() - half), deB, "2000 A, cada um com B enviado a segunda metade");
        for (Packet p : prepares) {
            assertFalse(pbft.isCommitted(bloco(p)), "nenhum A finalizou neste no");
        }
    }

    /** Conta referencias a PBFTConflictingBlock em toda colecao/mapa (ate 3 niveis) campo do PBFT do no. */
    private static int conflitantesRetidos(PBFT<?, ?> pbft) {
        int total = 0;
        for (java.lang.reflect.Field f : PBFT.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            try {
                total += conta(f.get(pbft), 3);
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        return total;
    }

    private static int conta(Object o, int nivel) {
        if (o == null) return 0;
        if (o instanceof PBFTConflictingBlock) return 1;
        if (o instanceof PBFTBlockVote<?> v) return conta(v.getBlock(), nivel);
        if (nivel == 0) return 0;
        int c = 0;
        if (o instanceof java.util.Map<?, ?> m) {
            for (java.util.Map.Entry<?, ?> e : m.entrySet()) c += conta(e.getKey(), nivel - 1) + conta(e.getValue(), nivel - 1);
        } else if (o instanceof java.util.Collection<?> col) {
            for (Object x : col) c += conta(x, nivel - 1);
        }
        return c;
    }

    // ---------- (2) o que NAO muda ----------

    @Test
    void noHonestoEnviaOMesmoPrepareATodos() {
        for (String ataque : new String[] {null, "NONE"}) {
            Alvo a = novaRede(7, ataque, Set.of(R));
            PBFTBlock real = novoBloco(a);
            prePrepare(a, real);
            List<Packet> prepares = pacotesDeR(a, PBFTBlockVote.VoteType.PREPARE);
            assertEquals(7, prepares.size(), String.valueOf(ataque));
            for (Packet p : prepares) assertSame(real, bloco(p), String.valueOf(ataque));
        }
    }

    @Test
    void naoBizantinoComConfigDeEquivocationEnviaPrepareHonesto() {
        // a config existe (EQUIVOCATION) mas este no NAO esta no conjunto bizantino
        Alvo a = novaRede(7, "EQUIVOCATION", Set.of(2, 3));
        PBFTBlock real = novoBloco(a);
        prePrepare(a, real);
        List<Packet> prepares = pacotesDeR(a, PBFTBlockVote.VoteType.PREPARE);
        assertEquals(7, prepares.size());
        for (Packet p : prepares) assertSame(real, bloco(p));
    }

    @Test
    void outrosAtaquesMantemOPrepareHonesto() {
        for (String ataque : new String[] {"DOUBLE_SIGN", "WITHHOLD", "SILENT"}) {
            Alvo a = novaRede(7, ataque, Set.of(R));
            PBFTBlock real = novoBloco(a);
            prePrepare(a, real);
            List<Packet> prepares = pacotesDeR(a, PBFTBlockVote.VoteType.PREPARE);
            assertEquals(7, prepares.size(), ataque + ": PREPARE segue um por vizinho");
            for (Packet p : prepares) assertSame(real, bloco(p), ataque + ": PREPARE segue honesto (nao ampliado)");
        }
    }

    @Test
    void commitEPrePrepareEquivocadosContinuamIguais() {
        Alvo a = novaRede(7, "EQUIVOCATION", Set.of(R));
        PBFTBlock real = novoBloco(a);
        prePrepare(a, real);
        prepares(a, real, PBFT.quorumSize(7));
        List<Packet> commits = pacotesDeR(a, PBFTBlockVote.VoteType.COMMIT);
        PBFTBlock[] m = verificaMetades(a, commits);
        assertNotSame(m[0], m[1]);
    }

    // ---------- (3) falha alto ----------

    @Test
    void umUnicoVizinhoFalhaAltoNoPrepare() {
        Alvo a = novaRede(1, "EQUIVOCATION", Set.of(0));
        PBFTNode r = a.r();
        PBFTBlock b = BlockFactory.samplePBFTBlockWithTx(a.sim(), a.rng(), r, PBFT_GENESIS_BLOCK, 5);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> entrega(a, r, new PBFTPrePrepareVote<>(r, b)));
        assertTrue(e.getMessage().contains("EQUIVOCATION em PREPARE") && e.getMessage().contains("2 vizinhos"),
                e.getMessage());
    }

    @Test
    void metadeSemNoHonestoFalhaAltoNoPrepareEmVezDeDegradar() {
        // n=4: metades [0,1] e [2,3]; com 2 e 3 bizantinos a 2a metade nao tem destinatario honesto
        Alvo a = novaRede(4, "EQUIVOCATION", Set.of(R, 2, 3));
        PBFTBlock b = novoBloco(a);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> prePrepare(a, b));
        assertTrue(e.getMessage().contains("EQUIVOCATION em PREPARE") && e.getMessage().contains("2a=0"),
                e.getMessage());
        assertEquals(0, pacotesDeR(a, PBFTBlockVote.VoteType.PREPARE).size(),
                "a checagem vem antes de qualquer envio: nada escapou");
    }

    @Test
    void falhaDeEnvioNoPrepareEquivocadoPropaga() {
        Alvo a = novaRede(4, "EQUIVOCATION", Set.of(R));
        a.r().getNodeNetworkInterface().messageSendingProcess = null;
        PBFTBlock b = novoBloco(a);
        assertThrows(NullPointerException.class, () -> prePrepare(a, b));
    }

    // ---------- (4) ponta a ponta: determinismo e liveness sob ataque ----------

    private static String assinatura(long semente, int n, Set<Integer> bizantinos) {
        RandomnessEngine rng = new RandomnessEngine(semente);
        Simulator sim = new Simulator();
        PBFTLocalLANNetwork net = new PBFTLocalLANNetwork(rng);
        net.populateNetwork(sim, n, new jabs.consensus.config.PBFTConsensusConfig(1000L));
        ByzantineConfig cfg = new ByzantineConfig(n, bizantinos, "EQUIVOCATION", semente);
        for (int id : bizantinos) {
            net.getAllNodes().get(id).getConsensusAlgorithm().setByzantineConfig(cfg);
        }
        net.startConsensusRound(sim, 60.0);
        long h = 7;
        int eventos = 0;
        while (sim.isThereMoreEvents() && sim.getSimulationTime() <= 60.0) {
            Event ev = sim.peekEvent();
            sim.executeNextEvent();
            if (ev instanceof BlockFinalizationEvent fe) {
                eventos++;
                h = h * 31 + fe.getNode().nodeID;
                h = h * 31 + Double.doubleToLongBits(sim.getSimulationTime());
                h = h * 31 + fe.getTrafficUntilFinalization();
                h = h * 31 + fe.getConsensusMessageTraffic();
            }
        }
        return eventos + ":" + h;
    }

    @Test
    void mesmaSementeMesmaSaidaSobAtaqueEFinalizaComHonestosSuficientes() {
        String s1 = assinatura(1, 7, Set.of(1, 2));
        assertEquals(s1, assinatura(1, 7, Set.of(1, 2)), "mesma semente, mesma saida");
        assertFalse(s1.startsWith("0:"), "com f=2 de n=7 os honestos ainda finalizam: " + s1);
        assertNotEquals(s1, assinatura(42, 7, Set.of(1, 2)), "sementes distintas, saidas distintas");
    }

    // ---------- (5) retencao: PREPAREs do bloco conflitante B nao ficam retidos apos A finalizar ----------

    @SuppressWarnings("unchecked")
    private static PBFT<PBFTBlock, ?> pbftDe(PBFTNode no) {
        return (PBFT<PBFTBlock, ?>) no.getConsensusAlgorithm();
    }

    private static void finaliza(Alvo a, PBFTBlock real) {
        prePrepare(a, real);
        prepares(a, real, PBFT.quorumSize(a.n()));
        commits(a, real, PBFT.quorumSize(a.n()));
        assertTrue(pbftDe(a.r()).isCommitted(real));
    }

    /** B como o no bizantino o fabrica (mesmo pai, altura e tamanho; nunca recebe PRE_PREPARE). */
    private static PBFTBlock conflitanteDe(Alvo a, PBFTBlock real) {
        return new PBFTConflictingBlock(real, a.todos().get(2));
    }

    @Test
    void prepareDoConflitanteSemPrePrepareNaoERetido() {
        Alvo a = novaRede(4, null, Set.of());
        PBFT<PBFTBlock, ?> pbft = pbftDe(a.r());
        PBFTBlock real = novoBloco(a);
        PBFTBlock b = conflitanteDe(a, real);
        prepares(a, b, 3);
        assertEquals(0, pbft.getPendingVoteCount(b), "PREPARE de B nunca poderia ser contado: nao retem");
        assertFalse(pbft.isPrepared(b));
        assertFalse(pbft.hasAcceptedPrePrepare(b));
    }

    @Test
    void trafegoDoConflitanteNaoAbreEntradaPorBlocoMasEMedidoNoAgregado() {
        Alvo a = novaRede(4, null, Set.of());
        PBFT<PBFTBlock, ?> pbft = pbftDe(a.r());
        PBFTBlock real = novoBloco(a);
        PBFTBlock b = conflitanteDe(a, real);
        prepares(a, b, 3); // passa por processIncomingPacket: hook de trafego e depois o voto
        assertEquals(0L, pbft.getBlockTraffic(b), "sem entrada por bloco para B (retencao O(1))");
        assertEquals(0L, pbft.getConsensusMessageTraffic(b));
        assertEquals(0, pbft.getOpenTrafficEntryCount(), "nenhum mapa por bloco foi aberto");
        // mas os bytes de B foram MEDIDOS: 3 pacotes de PREPARE (42 B cada), no agregado
        assertEquals(3L * 42, pbft.getConflictingBlockTraffic());
        assertEquals(3L * 42, pbft.getConflictingBlockConsensusTraffic());
        assertEquals(3L * 42, pbft.getAccountedVoteBytes(), "livro fecha: tudo o que chegou esta contabilizado");
        // controle: o mesmo hook segue medindo o bloco real, por bloco
        prepares(a, real, 3);
        assertTrue(pbft.getBlockTraffic(real) > 0);
        assertTrue(pbft.getConsensusMessageTraffic(real) > 0);
        assertEquals(6L * 42, pbft.getAccountedVoteBytes());
        assertEquals(3L * 42, pbft.getConflictingBlockTraffic(), "o trafego do real nao vai ao agregado de B");
    }

    @Test
    void memoriaDeRetencaoEO1EmBMesmoComMilConflitantes() {
        Alvo a = novaRede(4, null, Set.of());
        PBFT<PBFTBlock, ?> pbft = pbftDe(a.r());
        PBFTBlock real = novoBloco(a);
        int antes = pbft.getOpenTrafficEntryCount();
        for (int i = 0; i < 1000; i++) {
            PBFTBlock b = conflitanteDe(a, novoBloco(a)); // 1000 blocos B distintos (um por A distinto)
            entrega(a, a.todos().get(2), new PBFTPrepareVote<>(a.todos().get(2), b));
        }
        assertEquals(antes, pbft.getOpenTrafficEntryCount(), "entradas por bloco nao crescem com B");
        assertEquals(0, pbft.getPendingConflictingPrepareBlockCount());
        assertEquals(1000L * 42, pbft.getConflictingBlockTraffic(), "e nenhum byte se perdeu");
        assertEquals(1000L * 42, pbft.getAccountedVoteBytes());
    }

    @Test
    void trafegoPosteriorAFinalizacaoTambemFechaOLivro() {
        Alvo a = novaRede(4, null, Set.of());
        PBFT<PBFTBlock, ?> pbft = pbftDe(a.r());
        PBFTBlock real = novoBloco(a);
        finaliza(a, real);
        long antes = pbft.getAccountedVoteBytes();
        assertEquals(0L, pbft.getAfterFinalizationTraffic());
        commits(a, real, 1); // chega depois do commit local
        assertEquals(42L, pbft.getAfterFinalizationTraffic());
        assertEquals(antes + 42, pbft.getAccountedVoteBytes());
        assertEquals(0L, pbft.getBlockTraffic(real), "o evento ja leu e removeu a entrada; nada reabriu");
    }

    /**
     * Fecha o livro contra a REDE: soma de bytes que o enlace entregou ao no (LinkLoadCounters.recvBytes, contado em
     * PacketReceivingProcess, independente de PBFT) == bytes que o PBFT do no contabilizou (atribuido + posterior +
     * conflitante). Vale para C_B_total; C_B_consenso soma o pacote inteiro exceto o PRE_PREPARE (so o overhead).
     */
    private static void livroFechaComAEnlace(int n, Set<Integer> bizantinos, String ataque, long semente,
                                             boolean exigeConflitante) {
        RandomnessEngine rng = new RandomnessEngine(semente);
        Simulator sim = new Simulator();
        PBFTLocalLANNetwork net = new PBFTLocalLANNetwork(rng);
        net.populateNetwork(sim, n, new jabs.consensus.config.PBFTConsensusConfig(1000L));
        if (ataque != null) {
            ByzantineConfig cfg = new ByzantineConfig(n, bizantinos, ataque, semente);
            for (int id : bizantinos) net.getAllNodes().get(id).getConsensusAlgorithm().setByzantineConfig(cfg);
        }
        for (PBFTNode no : net.getAllNodes()) no.getNodeNetworkInterface().enableLoadRecording();
        net.startConsensusRound(sim, 30.0);
        while (sim.isThereMoreEvents() && sim.getSimulationTime() <= 30.0) {
            sim.executeNextEvent();
        }
        long conflitanteTotal = 0;
        long entregueTotal = 0;
        for (PBFTNode no : net.getAllNodes()) {
            long entregue = no.getNodeNetworkInterface().getLoadCounters().getRecvBytes();
            PBFT<PBFTBlock, ?> pbft = pbftDe(no);
            assertTrue(entregue > 0, "no " + no.nodeID + " recebeu algo");
            // D2 (ADR-0015): a autoentrega (o broadcast do PBFTP2P inclui o remetente; o pacote percorre o enlace) sai do C_B
            // e vai para o livro proprio; o livro fecha SOMANDO os dois: nenhum byte some.
            assertEquals(entregue, pbft.getAccountedVoteBytes() + pbft.getAccountedSelfDeliveryBytes(),
                    "no " + no.nodeID + " (" + ataque + "): bytes entregues pela rede != C_B + autoentrega contabilizados");
            assertTrue(pbft.getAccountedSelfDeliveryBytes() > 0, "no " + no.nodeID + ": o proprio voto chegou pelo enlace");
            assertEquals(entregue, pbft.getAttributedTraffic() + pbft.getAfterFinalizationTraffic()
                    + pbft.getConflictingBlockTraffic() + pbft.getAccountedSelfDeliveryBytes());
            conflitanteTotal += pbft.getConflictingBlockTraffic();
            entregueTotal += entregue;
            assertEquals(0, pbft.getPendingConflictingPrepareBlockCount());
            assertEquals(0L, pbft.getConflictingBlockTraffic() % 42, "so PREPAREs (42 B) nomeiam B");
        }
        if (exigeConflitante) {
            assertTrue(conflitanteTotal > 0, "sob EQUIVOCATION houve trafego de B para contabilizar");
        } else {
            assertEquals(0L, conflitanteTotal, "sem EQUIVOCATION de PREPARE nao ha B");
        }
        assertTrue(entregueTotal > conflitanteTotal);
        System.out.println("[livro] n=" + n + " ataque=" + ataque + " seed=" + semente + " entregue(enlace)=" + entregueTotal
                + " B(conflitante)=" + conflitanteTotal + " fracao=" + String.format("%.4f", (double) conflitanteTotal / entregueTotal));
    }

    @Test
    void sobEquivocationBytesEntreguesPelaRedeSaoIguaisAosContabilizadosPorNo() {
        livroFechaComAEnlace(7, Set.of(1, 2), "EQUIVOCATION", 1, true);
        livroFechaComAEnlace(10, Set.of(1, 4, 7), "EQUIVOCATION", 42, true);
    }

    @Test
    void semAtaqueOLivroTambemFechaESemTrafegoConflitante() {
        livroFechaComAEnlace(7, Set.of(), null, 1, false);
        livroFechaComAEnlace(7, Set.of(1, 2), "DOUBLE_SIGN", 1, false);
    }

    @Test
    void prepareDeBlocoHonestoSemPrePrepareContinuaRetidoComoAntes() {
        // o que NAO muda: voto adiantado de bloco comum (nao conflitante) segue retido e e reavaliado depois
        Alvo a = novaRede(4, null, Set.of());
        PBFT<PBFTBlock, ?> pbft = pbftDe(a.r());
        PBFTBlock irmaoHonesto = novoBloco(a);
        prepares(a, irmaoHonesto, 2);
        assertEquals(2, pbft.getPendingVoteCount(irmaoHonesto), "so PBFTConflictingBlock deixa de ser retido");
        prePrepare(a, irmaoHonesto);
        assertEquals(0, pbft.getPendingVoteCount(irmaoHonesto), "reavaliado quando o PRE_PREPARE chega");
        assertTrue(pbft.hasAcceptedPrePrepare(irmaoHonesto));
    }

    @Test
    void sobEquivocationNenhumConflitanteFicaRetidoAoLongoDaSimulacao() {
        int n = 7;
        Set<Integer> bizantinos = Set.of(1, 2);
        RandomnessEngine rng = new RandomnessEngine(1);
        Simulator sim = new Simulator();
        PBFTLocalLANNetwork net = new PBFTLocalLANNetwork(rng);
        net.populateNetwork(sim, n, new jabs.consensus.config.PBFTConsensusConfig(1000L));
        ByzantineConfig cfg = new ByzantineConfig(n, bizantinos, "EQUIVOCATION", 1);
        for (int id : bizantinos) net.getAllNodes().get(id).getConsensusAlgorithm().setByzantineConfig(cfg);
        net.startConsensusRound(sim, 60.0);
        int finalizacoes = 0;
        while (sim.isThereMoreEvents() && sim.getSimulationTime() <= 60.0) {
            Event ev = sim.peekEvent();
            sim.executeNextEvent();
            if (ev instanceof BlockFinalizationEvent) finalizacoes++;
        }
        // T6 / ADR-0007: o primario rotaciona por altura e, SEM view change, o primeiro primario bizantino que equivoca
        // (no 1, altura 2) trava a cadeia; antes, a proposta periodica do genesis mantinha a corrida viva. O que o teste
        // guarda (nenhum conflitante retido) vale para as alturas que rodaram.
        assertTrue(finalizacoes >= 1, "rodou ao menos uma altura: " + finalizacoes);
        for (PBFTNode no : net.getAllNodes()) {
            // sem a correcao: um B retido por equivocacao (24 por no em 60 s, n=7, sementes 1); com ela, nenhum
            assertEquals(0, pbftDe(no).getPendingConflictingPrepareBlockCount(),
                    "no " + no.nodeID + ": conflitantes retidos");
        }
    }

    @Test
    void semAtaqueNenhumConflitanteExisteEOCaminhoHonestoNaoMudou() {
        Alvo a = novaRede(4, null, Set.of());
        PBFT<PBFTBlock, ?> pbft = pbftDe(a.r());
        finaliza(a, novoBloco(a));
        assertEquals(0, pbft.getPendingConflictingPrepareBlockCount());
        assertEquals(0, conflitantesRetidos(pbft));
    }
}
