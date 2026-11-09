package jabs.network.networks.tendermint;

import jabs.consensus.algorithm.Tendermint;
import jabs.consensus.algorithm.tendermint.TendermintCore;
import jabs.consensus.algorithm.tendermint.TendermintDiscardReason;
import jabs.consensus.algorithm.tendermint.TendermintVote;
import jabs.ledgerdata.BlockFactory;
import jabs.ledgerdata.tendermint.TendermintBlock;
import jabs.ledgerdata.tendermint.TendermintBlockWithTx;
import jabs.ledgerdata.tendermint.TendermintTx;
import jabs.network.message.Packet;
import jabs.network.message.TendermintProposalMessage;
import jabs.network.message.TendermintVoteMessage;
import jabs.network.node.nodes.tendermint.TendermintNode;
import jabs.simulator.Simulator;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static jabs.network.node.nodes.tendermint.TendermintNode.TENDERMINT_GENESIS_BLOCK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code valid(h, v)} do adaptador (Algoritmo 1, Buchman, Kwon e Milosevic, arXiv:1807.04938): o bloco precisa
 * estar conectado ao genesis, ter altura {@code h + 1} e ter como pai a decisao da altura anterior (genesis em
 * {@code h == 0}). O receptor e o no 3 (nao proponente nas alturas 0 e 1: proponentes 0 e 1, round 0); o no 0 faz o
 * papel de proponente bizantino que propoe bloco invalido para a altura.
 */
class TendermintValidityTest {
    private static final int N = 4;
    private static final int RECEPTOR = 3;

    private Simulator sim;
    private TendermintLocalLANNetwork net;
    private TendermintNode receptor;
    private TendermintNode no0;
    private TendermintNode no1;
    private TendermintNode no2;
    private TendermintCore<TendermintBlock> core;

    @BeforeEach
    void montar() {
        sim = new Simulator();
        net = new TendermintLocalLANNetwork(new RandomnessEngine(7));
        net.populateNetwork(sim, N, TendermintTestConfig.config());
        receptor = net.getNode(RECEPTOR);
        no0 = net.getNode(0);
        no1 = net.getNode(1);
        no2 = net.getNode(2);
        @SuppressWarnings("unchecked")
        Tendermint<TendermintBlock, TendermintTx> t =
                (Tendermint<TendermintBlock, TendermintTx>) receptor.getConsensusAlgorithm();
        core = t.getCore();
        receptor.startConsensus();
    }

    private TendermintBlock bloco(TendermintNode criador, int altura, TendermintBlock pai) {
        return new TendermintBlockWithTx(1024, altura, 0, criador, pai, null);
    }

    private void propor(TendermintNode proponente, long altura, int round, TendermintBlock b) {
        receptor.processIncomingPacket(new Packet(proponente, receptor, new TendermintProposalMessage(
                proponente, altura, round, b, -1, TendermintTestConfig.config())));
    }

    private void votar(TendermintNode de, TendermintVote.Type tipo, long altura, int round, TendermintBlock b) {
        receptor.processIncomingPacket(new Packet(de, receptor, new TendermintVoteMessage(
                de, tipo, altura, round, b, TendermintTestConfig.config())));
    }

    private TendermintBlock prevotoDoReceptor(long altura, int round) {
        List<TendermintVote<TendermintBlock>> v =
                core.loggedVotes(altura, round, TendermintVote.Type.PREVOTE, RECEPTOR);
        assertEquals(1, v.size(), "o validador correto prevota exatamente uma vez");
        return v.get(0).value();
    }

    private void precommitsDosOutros(long altura, TendermintBlock b) {
        votar(no0, TendermintVote.Type.PRECOMMIT, altura, 0, b);
        votar(no1, TendermintVote.Type.PRECOMMIT, altura, 0, b);
        votar(no2, TendermintVote.Type.PRECOMMIT, altura, 0, b);
    }

    /** Decide a altura 0 do nucleo com um bloco valido A (altura 1, pai genesis) e devolve A. */
    private TendermintBlock decidirAlturaZero() {
        TendermintBlock a = bloco(no0, 1, TENDERMINT_GENESIS_BLOCK);
        propor(no0, 0, 0, a);
        precommitsDosOutros(0, a);
        assertSame(a, core.decision(0));
        assertEquals(1, core.height());
        return a;
    }

    @Test
    void alturaErradaRecebePrevoteNilENuncaEDecidida() {
        for (int alturaErrada : new int[]{0, 2, 5}) {
            setUpNovo();
            TendermintBlock ruim = bloco(no0, alturaErrada, TENDERMINT_GENESIS_BLOCK);
            propor(no0, 0, 0, ruim);
            assertNull(prevotoDoReceptor(0, 0), "altura de bloco " + alturaErrada + " na altura de consenso 0");
            assertEquals(1, core.discardCount(TendermintDiscardReason.INVALID_VALUE), "contado, nao silencioso");
            // mesmo com os 2f+1 precommits dos outros no bloco invalido, o validador correto nao decide
            precommitsDosOutros(0, ruim);
            assertNull(core.decision(0), "bloco de altura errada nunca e decidido");
            assertEquals(0, core.height());
        }
    }

    private void setUpNovo() {
        montar();
    }

    @Test
    void paiQueNaoEADecisaoAnteriorRecebePrevoteNil() {
        TendermintBlock a = decidirAlturaZero();
        // A' e irmao de A: valido na altura 0 (admitido pela proposta bizantina equivocada), mas nao e a decisao.
        TendermintBlock alternativo = bloco(no0, 1, TENDERMINT_GENESIS_BLOCK);
        propor(no0, 0, 0, alternativo);
        assertSame(a, core.decision(0), "equivocacao posterior nao altera a decisao");

        // Altura de consenso 1 (proponente: no 1). Altura do bloco e 2 (correta), mas o pai e A', nao A.
        TendermintBlock filhoDoAlternativo = bloco(no1, 2, alternativo);
        propor(no1, 1, 0, filhoDoAlternativo);
        assertNull(prevotoDoReceptor(1, 0), "pai != decisao da altura anterior");
        assertEquals(1, core.discardCount(TendermintDiscardReason.INVALID_VALUE));
        precommitsDosOutros1(filhoDoAlternativo);
        assertNull(core.decision(1), "bloco com pai errado nunca e decidido");
    }

    private void precommitsDosOutros1(TendermintBlock b) {
        votar(no0, TendermintVote.Type.PRECOMMIT, 1, 0, b);
        votar(no1, TendermintVote.Type.PRECOMMIT, 1, 0, b);
        votar(no2, TendermintVote.Type.PRECOMMIT, 1, 0, b);
    }

    @Test
    void paiGenesisNaAlturaUmDeConsensoEInvalido() {
        decidirAlturaZero();
        // Altura de bloco 2 mas pai = genesis: altura incoerente com o pai e pai diferente da decisao.
        TendermintBlock ruim = bloco(no1, 2, TENDERMINT_GENESIS_BLOCK);
        propor(no1, 1, 0, ruim);
        assertNull(prevotoDoReceptor(1, 0));
        assertEquals(1, core.discardCount(TendermintDiscardReason.INVALID_VALUE));
    }

    @Test
    void propostaValidaEPrevotadaEDecidida() {
        TendermintBlock a = bloco(no0, 1, TENDERMINT_GENESIS_BLOCK);
        propor(no0, 0, 0, a);
        assertSame(a, prevotoDoReceptor(0, 0), "bloco altura 1, pai genesis, na altura 0: prevote no bloco");
        assertEquals(0, core.discardCount(TendermintDiscardReason.INVALID_VALUE));
        precommitsDosOutros(0, a);
        assertSame(a, core.decision(0));

        TendermintBlock b = bloco(no1, 2, a);
        propor(no1, 1, 0, b);
        assertSame(b, prevotoDoReceptor(1, 0), "altura 2, pai = decisao da altura 0");
        precommitsDosOutros1(b);
        assertSame(b, core.decision(1));
        assertEquals(0, core.discardCount(TendermintDiscardReason.INVALID_VALUE));
    }

    @Test
    void blocoDesconectadoDoGenesisContinuaInvalido() {
        // Pai desconhecido do receptor (altura 0, mas nao e o genesis): bloco nao entra conectado na arvore.
        TendermintBlock paiForaDaArvore = bloco(no0, 0, null);
        TendermintBlock ruim = bloco(no0, 1, paiForaDaArvore);
        propor(no0, 0, 0, ruim);
        assertNull(prevotoDoReceptor(0, 0));
        assertEquals(1, core.discardCount(TendermintDiscardReason.INVALID_VALUE));
        precommitsDosOutros(0, ruim);
        assertNull(core.decision(0));
    }

    @Test
    void proponenteCorretoPassaNoPropriePredicadoEmTodasAsAlturas() {
        // Rede honesta completa: toda proposta de um correto (inclusive a propria) e valida em todos os nos.
        Simulator s = new Simulator();
        TendermintLocalLANNetwork rede = new TendermintLocalLANNetwork(new RandomnessEngine(7));
        rede.populateNetwork(s, N, TendermintTestConfig.config());
        rede.startConsensusRound(s, 20.0);
        while (s.isThereMoreEvents() && s.peekEventTime() <= 20.0) {
            s.executeNextEvent();
        }
        for (TendermintNode node : rede.getAllNodes()) {
            @SuppressWarnings("unchecked")
            Tendermint<TendermintBlock, TendermintTx> t =
                    (Tendermint<TendermintBlock, TendermintTx>) node.getConsensusAlgorithm();
            assertEquals(0, t.getCore().discardCount(TendermintDiscardReason.INVALID_VALUE),
                    "no " + node.getNodeID() + ": nenhum valor honesto foi tratado como invalido");
            assertTrue(t.getHeight() >= 3, "progresso honesto preservado: altura " + t.getHeight());
            // o bloco decidido em h foi proposto (e passou no predicado de todos) pelo proponente do round 0
            for (long h = 0; h < t.getHeight(); h++) {
                TendermintBlock decidido = t.getDecision(h);
                assertNotNull(decidido, "decisao na altura " + h);
                assertEquals(h + 1, decidido.getHeight());
                assertEquals((int) (h % N), decidido.getCreator().getNodeID(), "proponente round-robin, altura " + h);
                assertSame(h == 0 ? TendermintNode.TENDERMINT_GENESIS_BLOCK : t.getDecision(h - 1),
                        decidido.getFixedParent());
            }
        }
    }

    @Test
    void blocoDeFabricaDoProponenteTemAlturaEPaiEsperados() {
        TendermintBlock a = BlockFactory.sampleTendermintBlockWithTx(sim, no0, TENDERMINT_GENESIS_BLOCK,
                TendermintTestConfig.config());
        assertEquals(1, a.getHeight());
        assertSame(TENDERMINT_GENESIS_BLOCK, a.getFixedParent());
        assertNull(TENDERMINT_GENESIS_BLOCK.getFixedParent());
    }
}
