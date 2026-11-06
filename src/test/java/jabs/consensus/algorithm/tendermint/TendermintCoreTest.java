package jabs.consensus.algorithm.tendermint;

import jabs.consensus.algorithm.tendermint.TendermintAction.Kind;
import jabs.consensus.algorithm.tendermint.TendermintVote.Type;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Um nucleo isolado (n = 4, self = 3), alimentado a mao. Proponente round-robin (h + r) mod 4: na altura 0 os
 * rounds 0, 1, 2, 3 sao propostos por 0, 1, 2, 3. Cada teste cobre uma armadilha da implementacao.
 */
class TendermintCoreTest {

    private static final int N = 4;
    private static final int SELF = 3;

    private static TendermintCore<String> core() {
        return new TendermintCore<>(SELF, N, TendermintCore.roundRobin(N), h -> "fresco-" + h, v -> true);
    }

    private static TendermintProposal<String> prop(long h, int r, String v, int vr) {
        return new TendermintProposal<>(h, r, v, vr, (int) Math.floorMod(h + r, (long) N));
    }

    private static TendermintVote<String> prevote(long h, int r, String v, int from) {
        return new TendermintVote<>(Type.PREVOTE, h, r, v, from);
    }

    private static TendermintVote<String> precommit(long h, int r, String v, int from) {
        return new TendermintVote<>(Type.PRECOMMIT, h, r, v, from);
    }

    private static List<TendermintAction<String>> votes(List<TendermintAction<String>> as, Type type) {
        List<TendermintAction<String>> out = new ArrayList<>();
        for (TendermintAction<String> a : as) {
            if (a.kind() == Kind.BROADCAST_VOTE && a.vote().type() == type) {
                out.add(a);
            }
        }
        return out;
    }

    private static long timeouts(List<TendermintAction<String>> as, TimeoutKind kind) {
        return as.stream().filter(a -> a.kind() == Kind.SCHEDULE_TIMEOUT && a.timeout() == kind).count();
    }

    /**
     * Leva o nucleo ao round r da altura 0 pela regra 55 (f+1 = 2 remetentes de round maior). Usa PRECOMMIT nil
     * para nao mexer na contagem de prevotes do round de destino.
     */
    private static void skipTo(TendermintCore<String> c, int r, List<TendermintAction<String>> sink) {
        int a = (r + 1) % N == SELF ? (r + 2) % N : (r + 1) % N;
        int b = (a + 1) % N == SELF ? (a + 2) % N : (a + 1) % N;
        sink.addAll(c.onVote(precommit(0, r, null, a)));
        sink.addAll(c.onVote(precommit(0, r, null, b)));
        assertEquals(r, c.round());
    }

    @Test
    void naoProponenteAgendaTimeoutDeProposta() {
        TendermintCore<String> c = core();
        List<TendermintAction<String>> as = c.start();
        assertEquals(1, as.size());
        assertEquals(Kind.SCHEDULE_TIMEOUT, as.get(0).kind());
        assertEquals(TimeoutKind.PROPOSE, as.get(0).timeout());
        assertEquals(TendermintStep.PROPOSE, c.step());
    }

    @Test
    void votoNilContaParaTimeoutMasNaoParaPolka() {
        TendermintCore<String> c = core();
        c.start();
        List<TendermintAction<String>> as = c.onProposal(prop(0, 0, "A", -1));
        assertEquals("A", votes(as, Type.PREVOTE).get(0).vote().value());

        as = new ArrayList<>();
        as.addAll(c.onVote(prevote(0, 0, null, 1)));
        as.addAll(c.onVote(prevote(0, 0, null, 2)));
        // 3 prevotes (A, nil, nil): "2f+1 de qualquer coisa" arma o timeout; sem polka e sem 2f+1 nil
        assertEquals(1, timeouts(as, TimeoutKind.PREVOTE));
        assertTrue(votes(as, Type.PRECOMMIT).isEmpty());
        assertEquals(-1, c.lockedRound());
        assertEquals(TendermintStep.PREVOTE, c.step());

        as = c.onVote(prevote(0, 0, null, 0)); // agora 3 nil
        assertEquals(1, votes(as, Type.PRECOMMIT).size());
        assertNull(votes(as, Type.PRECOMMIT).get(0).vote().value());
    }

    @Test
    void polkaTravaEPrecommita() {
        TendermintCore<String> c = core();
        c.start();
        c.onProposal(prop(0, 0, "A", -1));
        c.onVote(prevote(0, 0, "A", 0));
        List<TendermintAction<String>> as = c.onVote(prevote(0, 0, "A", 1));
        assertEquals("A", votes(as, Type.PRECOMMIT).get(0).vote().value());
        assertEquals("A", c.lockedValue());
        assertEquals(0, c.lockedRound());
        assertEquals("A", c.validValue());
        assertEquals(0, c.validRound());
        assertEquals(TendermintStep.PRECOMMIT, c.step());
    }

    @Test
    void polkaQueChegaAntesDaPropostaEUsadaQuandoAPropostaChega() {
        TendermintCore<String> c = core();
        c.start();
        c.onVote(prevote(0, 0, "A", 0));
        c.onVote(prevote(0, 0, "A", 1));
        c.onVote(prevote(0, 0, "A", 2));
        // a proposta chega por ultimo: prevote A e, na mesma entrada, a polka ja guardada leva ao precommit
        List<TendermintAction<String>> as = c.onProposal(prop(0, 0, "A", -1));
        assertEquals(1, votes(as, Type.PREVOTE).size());
        assertEquals("A", votes(as, Type.PRECOMMIT).get(0).vote().value());
        assertEquals(0, c.lockedRound());
    }

    @Test
    void noTravadoVotaNilEmValorNovoSemPolka() {
        TendermintCore<String> c = core();
        c.start();
        c.onProposal(prop(0, 0, "A", -1));
        c.onVote(prevote(0, 0, "A", 0));
        c.onVote(prevote(0, 0, "A", 1)); // travado em A no round 0
        List<TendermintAction<String>> sink = new ArrayList<>();
        skipTo(c, 1, sink);
        List<TendermintAction<String>> as = c.onProposal(prop(0, 1, "B", -1));
        assertNull(votes(as, Type.PREVOTE).get(0).vote().value(), "travado em A: B sem polka recebe nil");
        assertEquals("A", c.lockedValue());
    }

    @Test
    void noTravadoAindaVotaNoValorTravado() {
        TendermintCore<String> c = core();
        c.start();
        c.onProposal(prop(0, 0, "A", -1));
        c.onVote(prevote(0, 0, "A", 0));
        c.onVote(prevote(0, 0, "A", 1));
        List<TendermintAction<String>> sink = new ArrayList<>();
        skipTo(c, 1, sink);
        List<TendermintAction<String>> as = c.onProposal(prop(0, 1, "A", 0));
        assertEquals("A", votes(as, Type.PREVOTE).get(0).vote().value());
    }

    @Test
    void destravaComPolkaDeRoundPosteriorAoLock() {
        TendermintCore<String> c = core();
        c.start();
        c.onProposal(prop(0, 0, "A", -1));
        c.onVote(prevote(0, 0, "A", 0));
        c.onVote(prevote(0, 0, "A", 1)); // lock A, r0
        List<TendermintAction<String>> sink = new ArrayList<>();
        skipTo(c, 2, sink);
        // proposta B com vr = 1, ainda sem a polka do round 1: o no ESPERA (nao vota)
        List<TendermintAction<String>> as = c.onProposal(prop(0, 2, "B", 1));
        assertTrue(votes(as, Type.PREVOTE).isEmpty());
        // polka de B no round 1 chega: lockedRound 0 <= vr 1, entao prevote B (linha 29)
        as = new ArrayList<>();
        as.addAll(c.onVote(prevote(0, 1, "B", 0)));
        as.addAll(c.onVote(prevote(0, 1, "B", 1)));
        as.addAll(c.onVote(prevote(0, 1, "B", 2)));
        assertEquals("B", votes(as, Type.PREVOTE).get(0).vote().value());
        assertEquals(2, votes(as, Type.PREVOTE).get(0).vote().round());
        // a polka de um round ANTERIOR nao trava (linha 36 so olha o round corrente)
        assertEquals("A", c.lockedValue());
        assertEquals(0, c.lockedRound());
    }

    @Test
    void naoDestravaComPolkaAnteriorAoLock() {
        TendermintCore<String> c = core();
        c.start();
        // round 0: este no nao viu a proposta (timeout -> nil), mas os outros tres fizeram polka em B
        c.onTimeout(TimeoutKind.PROPOSE, 0, 0);
        c.onVote(prevote(0, 0, "B", 0));
        c.onVote(prevote(0, 0, "B", 1));
        c.onVote(prevote(0, 0, "B", 2));
        assertEquals(-1, c.lockedRound(), "sem a proposta do round 0 a polka nao trava");
        // round 1: trava em A
        List<TendermintAction<String>> sink = new ArrayList<>();
        skipTo(c, 1, sink);
        c.onProposal(prop(0, 1, "A", -1));
        c.onVote(prevote(0, 1, "A", 0));
        c.onVote(prevote(0, 1, "A", 1));
        assertEquals(1, c.lockedRound());
        assertEquals("A", c.lockedValue());
        // round 2: proponente traz B com vr = 0 (polka real, mas ANTERIOR ao lock)
        skipTo(c, 2, sink);
        List<TendermintAction<String>> as = c.onProposal(prop(0, 2, "B", 0));
        // lockedRound 1 > vr 0 e lockedValue != B: nil (linha 30)
        assertNull(votes(as, Type.PREVOTE).get(0).vote().value());
        assertEquals("A", c.lockedValue());
    }

    @Test
    void proponenteRepropoeOValidValueComSeuValidRound() {
        TendermintCore<String> c = core();
        c.start();
        c.onProposal(prop(0, 0, "A", -1));
        c.onVote(prevote(0, 0, "A", 0));
        c.onVote(prevote(0, 0, "A", 1)); // validValue A, validRound 0
        c.onVote(prevote(0, 3, null, 0));
        List<TendermintAction<String>> as = c.onVote(prevote(0, 3, null, 1)); // salta ao round 3 (proprio no)
        TendermintAction<String> p = as.stream().filter(a -> a.kind() == Kind.BROADCAST_PROPOSAL)
                .findFirst().orElseThrow();
        assertEquals("A", p.proposal().value(), "deve repropor validValue, nao getValue()");
        assertEquals(0, p.proposal().validRound());
    }

    @Test
    void decideComPrecommitsDeRoundAnterior() {
        TendermintCore<String> c = core();
        c.start();
        List<TendermintAction<String>> sink = new ArrayList<>();
        skipTo(c, 2, sink);
        c.onProposal(prop(0, 0, "A", -1));
        c.onVote(precommit(0, 0, "A", 0));
        c.onVote(precommit(0, 0, "A", 1));
        List<TendermintAction<String>> as = c.onVote(precommit(0, 0, "A", 2));
        assertEquals(Kind.DECIDE, as.get(0).kind());
        assertEquals("A", c.decision(0));
        assertEquals(1, c.height());
        assertEquals(0, c.round());
        assertEquals(-1, c.lockedRound(), "lock e zerado na nova altura");
        assertNull(c.validValue());
    }

    @Test
    void precommitsNilNaoDecidem() {
        TendermintCore<String> c = core();
        c.start();
        c.onProposal(prop(0, 0, "A", -1));
        c.onVote(precommit(0, 0, null, 0));
        c.onVote(precommit(0, 0, null, 1));
        List<TendermintAction<String>> as = c.onVote(precommit(0, 0, null, 2));
        assertNull(c.decision(0));
        assertEquals(1, timeouts(as, TimeoutKind.PRECOMMIT), "2f+1 precommits quaisquer armam o timeout");
    }

    @Test
    void mensagensDeAlturaFuturaFicamGuardadasEValem() {
        TendermintCore<String> c = core();
        c.start();
        // altura 1: proponente do round 0 e o no 1
        c.onProposal(prop(1, 0, "X", -1));
        c.onVote(prevote(1, 0, "X", 0));
        c.onVote(prevote(1, 0, "X", 1));
        c.onVote(prevote(1, 0, "X", 2));
        assertEquals(4, c.futureMessages());
        assertEquals(0, c.height());

        c.onProposal(prop(0, 0, "A", -1));
        c.onVote(precommit(0, 0, "A", 0));
        c.onVote(precommit(0, 0, "A", 1));
        List<TendermintAction<String>> as = c.onVote(precommit(0, 0, "A", 2));
        assertEquals(Kind.DECIDE, as.get(0).kind());
        assertEquals("X", votes(as, Type.PREVOTE).get(0).vote().value());
        assertEquals("X", votes(as, Type.PRECOMMIT).get(0).vote().value());
        assertEquals(1, c.height());
        assertEquals(0, c.lockedRound());
        assertEquals(4, c.peakFutureMessages());
        assertEquals(0, c.futureMessages());
    }

    @Test
    void mensagemDeAlturaPassadaEContada() {
        TendermintCore<String> c = core();
        c.start();
        c.onProposal(prop(0, 0, "A", -1));
        c.onVote(precommit(0, 0, "A", 0));
        c.onVote(precommit(0, 0, "A", 1));
        c.onVote(precommit(0, 0, "A", 2));
        assertTrue(c.onVote(prevote(0, 0, "A", 0)).isEmpty());
        assertEquals(1, c.discardCount(TendermintDiscardReason.OLD_HEIGHT));
    }

    @Test
    void saltaParaOMaiorRoundComFMais1() {
        TendermintCore<String> c = core();
        c.start();
        c.onVote(prevote(0, 5, null, 0));
        assertEquals(0, c.round(), "um remetente so nao basta (f+1 = 2)");
        c.onVote(prevote(0, 2, null, 1));
        c.onVote(precommit(0, 5, null, 2));
        // rounds 2 e 5: o 5 tem {0, 2}; o 2 tem so {1}
        assertEquals(5, c.round());
    }

    @Test
    void mensagensDoMesmoRemetenteContamUmaVezParaOSalto() {
        TendermintCore<String> c = core();
        c.start();
        c.onVote(prevote(0, 4, null, 1));
        c.onVote(precommit(0, 4, null, 1));
        assertEquals(0, c.round());
    }

    @Test
    void timeoutObsoletoNaoTemEfeito() {
        TendermintCore<String> c = core();
        c.start();
        List<TendermintAction<String>> sink = new ArrayList<>();
        skipTo(c, 1, sink);
        assertTrue(c.onTimeout(TimeoutKind.PROPOSE, 0, 0).isEmpty());
        assertTrue(c.onTimeout(TimeoutKind.PRECOMMIT, 0, 0).isEmpty());
        assertEquals(1, c.round());
        assertEquals(2, c.discardCount(TendermintDiscardReason.STALE_TIMEOUT));
        // o do round corrente vale
        List<TendermintAction<String>> as = c.onTimeout(TimeoutKind.PROPOSE, 0, 1);
        assertNull(votes(as, Type.PREVOTE).get(0).vote().value());
    }

    @Test
    void timeoutDePrecommitAvancaORound() {
        TendermintCore<String> c = core();
        c.start();
        List<TendermintAction<String>> as = c.onTimeout(TimeoutKind.PRECOMMIT, 0, 0);
        assertEquals(1, c.round());
        assertEquals(TendermintStep.PROPOSE, c.step());
        assertEquals(1, timeouts(as, TimeoutKind.PROPOSE));
    }

    @Test
    void timeoutDePrevoteEArmadoUmaVezPorRound() {
        TendermintCore<String> c = core();
        c.start();
        List<TendermintAction<String>> as = new ArrayList<>(c.onProposal(prop(0, 0, "A", -1)));
        as.addAll(c.onVote(prevote(0, 0, null, 1)));
        as.addAll(c.onVote(prevote(0, 0, "B", 2)));
        as.addAll(c.onVote(prevote(0, 0, null, 0)));
        assertEquals(1, timeouts(as, TimeoutKind.PREVOTE));
        assertEquals(TendermintStep.PREVOTE, c.step(), "nem polka nem 2f+1 nil: espera o timeout");
    }

    @Test
    void duplicataNaoContaDuasVezes() {
        TendermintCore<String> c = core();
        c.start();
        c.onProposal(prop(0, 0, "A", -1));
        c.onVote(prevote(0, 0, "A", 1));
        c.onVote(prevote(0, 0, "A", 1));
        assertEquals(1, c.discardCount(TendermintDiscardReason.DUPLICATE));
        assertEquals(-1, c.lockedRound(), "2 votos A (o proprio e o do no 1) nao sao quorum");
        c.onVote(prevote(0, 0, "A", 2));
        assertEquals(0, c.lockedRound());
    }

    /** Equivocacao conta para o valor que assina, mas o remetente conta uma vez so nos limiares "de qualquer valor". */
    @Test
    void votoEquivocadoContaParaCadaValorQueAssina() {
        TendermintCore<String> c = core();
        c.start();
        c.onProposal(prop(0, 0, "A", -1)); // proprio voto: A
        c.onVote(prevote(0, 0, "B", 0));   // bizantino 0 vota B...
        c.onVote(prevote(0, 0, "A", 1));
        assertEquals(-1, c.lockedRound());
        List<TendermintAction<String>> as = c.onVote(prevote(0, 0, "A", 0)); // ...e tambem A
        assertEquals(1, c.equivocationsObserved());
        assertEquals(0, c.lockedRound(), "o voto A do bizantino completa a polka (3 remetentes em A)");
        assertEquals("A", votes(as, Type.PRECOMMIT).get(0).vote().value());
        assertEquals(0, timeouts(as, TimeoutKind.PREVOTE), "3 remetentes distintos, nao 4 mensagens");
    }

    @Test
    void equivocacaoNaoDaPassagemDeRoundSozinha() {
        TendermintCore<String> c = core();
        c.start();
        c.onVote(prevote(0, 6, null, 1));
        c.onVote(prevote(0, 6, "X", 1));
        c.onVote(precommit(0, 6, "Y", 1));
        assertEquals(0, c.round(), "um remetente, varias mensagens: nao e f+1");
    }

    @Test
    void decideNaSegundaPropostaDeUmProponenteBizantino() {
        TendermintCore<String> c = core();
        c.start();
        c.onProposal(prop(0, 0, "A", -1)); // vota A
        c.onProposal(prop(0, 0, "B", -1)); // equivocacao do proponente 0: guardada
        assertEquals(1, c.equivocationsObserved());
        c.onVote(precommit(0, 0, "B", 0));
        c.onVote(precommit(0, 0, "B", 1));
        c.onVote(precommit(0, 0, "B", 2));
        assertEquals("B", c.decision(0), "a decisao dos outros em B precisa ser reconhecida");
    }

    @Test
    void propostaDeQuemNaoEProponenteEDescartada() {
        TendermintCore<String> c = core();
        c.start();
        assertTrue(c.onProposal(new TendermintProposal<>(0, 0, "A", -1, 1)).isEmpty());
        assertEquals(1, c.discardCount(TendermintDiscardReason.WRONG_PROPOSER));
        assertEquals(TendermintStep.PROPOSE, c.step());
    }

    @Test
    void segundaPropostaIgualEDuplicata() {
        TendermintCore<String> c = core();
        c.start();
        c.onProposal(prop(0, 0, "A", -1));
        c.onProposal(prop(0, 0, "A", -1));
        assertEquals(1, c.discardCount(TendermintDiscardReason.DUPLICATE));
        assertEquals(0, c.equivocationsObserved());
    }

    @Test
    void valorInvalidoRecebeNil() {
        TendermintCore<String> c = new TendermintCore<>(SELF, N, TendermintCore.roundRobin(N), h -> "ok",
                v -> !v.startsWith("ruim"));
        c.start();
        List<TendermintAction<String>> as = c.onProposal(prop(0, 0, "ruim", -1));
        assertNull(votes(as, Type.PREVOTE).get(0).vote().value());
        c.onVote(prevote(0, 0, "ruim", 0));
        c.onVote(prevote(0, 0, "ruim", 1));
        c.onVote(prevote(0, 0, "ruim", 2));
        assertEquals(-1, c.lockedRound(), "polka de valor invalido nao trava");
    }

    @Test
    void mensagemRecebidaDoProprioNoFalhaAlto() {
        TendermintCore<String> c = core();
        c.start();
        assertThrows(IllegalStateException.class, () -> c.onVote(prevote(0, 0, "A", SELF)));
    }

    @Test
    void remetenteForaDaFaixaEContado() {
        TendermintCore<String> c = core();
        c.start();
        c.onVote(prevote(0, 0, "A", 7));
        assertEquals(1, c.discardCount(TendermintDiscardReason.SENDER_OUT_OF_RANGE));
    }

    @Test
    void mensagensAntesDoStartSaoUsadasNoStart() {
        TendermintCore<String> c = core();
        c.onProposal(prop(0, 0, "A", -1));
        assertFalse(c.isStarted());
        List<TendermintAction<String>> as = c.start();
        assertEquals("A", votes(as, Type.PREVOTE).get(0).vote().value());
    }

    @Test
    void startDuasVezesFalha() {
        TendermintCore<String> c = core();
        c.start();
        assertThrows(IllegalStateException.class, c::start);
        TendermintCore<String> d = core();
        assertThrows(IllegalStateException.class, () -> d.onTimeout(TimeoutKind.PROPOSE, 0, 0));
    }

    @Test
    void argumentosInvalidos() {
        assertThrows(IllegalArgumentException.class, () -> new TendermintCore<String>(4, 4,
                TendermintCore.roundRobin(4), h -> "v", v -> true));
        assertThrows(IllegalArgumentException.class, () -> new TendermintProposal<>(0, 1, "A", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new TendermintProposal<>(0, 1, null, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new TendermintVote<>(null, 0, 0, "A", 1));
    }
}
