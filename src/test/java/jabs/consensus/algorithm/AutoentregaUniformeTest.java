package jabs.consensus.algorithm;

import jabs.consensus.config.CasperFFGConfig;
import jabs.consensus.config.ChainBasedConsensusConfig;
import jabs.consensus.config.NakamotoConsensusConfig;
import jabs.ledgerdata.bitcoin.BitcoinBlockWithoutTx;
import jabs.ledgerdata.ethereum.EthereumBlock;
import jabs.ledgerdata.ethereum.EthereumTx;
import jabs.ledgerdata.pbft.PBFTBlock;
import jabs.ledgerdata.pbft.PBFTBlockVote;
import jabs.ledgerdata.pbft.PBFTCommitVote;
import jabs.ledgerdata.pbft.PBFTPrePrepareVote;
import jabs.ledgerdata.pbft.PBFTPrepareVote;
import jabs.ledgerdata.BlockFactory;
import jabs.network.message.DataMessage;
import jabs.network.message.Packet;
import jabs.network.message.VoteMessage;
import jabs.network.networks.bitcoin.BitcoinGlobalProofOfWorkNetwork;
import jabs.network.networks.bsc.ParliaGlobalBlockchainNetwork;
import jabs.network.networks.ethereum.CasperFFGGlobalBlockchainNetwork;
import jabs.network.networks.pbft.PBFTLocalLANNetwork;
import jabs.network.node.nodes.bitcoin.BitcoinNode;
import jabs.network.node.nodes.ethereum.EthereumMinerNode;
import jabs.network.node.nodes.pbft.PBFTNode;
import jabs.network.stats.eightysixcountries.bitcoin.BitcoinProofOfWorkGlobalNetworkStats86Countries;
import jabs.network.stats.sixglobalregions.ethereum.EthereumProofOfWorkGlobalNetworkStats6Regions;
import jabs.simulator.Simulator;
import jabs.simulator.event.BlockFinalizationEvent;
import jabs.simulator.randengine.RandomnessEngine;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static jabs.network.node.nodes.pbft.PBFTNode.PBFT_GENESIS_BLOCK;
import static jabs.network.stats.eightysixcountries.bitcoin.BitcoinProofOfWorkGlobalNetworkStats86Countries.BITCOIN_DIFFICULTY_2022;
import static jabs.network.stats.eightysixcountries.ethereum.EthereumProofOfWorkGlobalNetworkStats86Countries.ETHEREUM_DIFFICULTY_2022;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D2 (ADR-0015, registro de 10/11/2026): a AUTOENTREGA (pacote com {@code getFrom() == receptor}) sai do C_B principal em
 * TODOS os protocolos e, onde antes era contada (PBFT, Casper, Parlia, Nakamoto), vai para uma COLUNA PROPRIA. Por
 * protocolo afetado: (1) o C_B principal NAO a conta; (2) a coluna propria tem exatamente o valor da autoentrega;
 * (3) o pacote vindo da rede continua contando no C_B (a regra nao e "nao conta nada"); (4) a sentinela -1 nunca vira zero.
 * Os que ja filtravam (DAGsper/HotStuff/Tendermint) ficam de fora: {@code separatesSelfDelivery()} = false e a saida deles
 * nao muda (provado byte a byte em scripts/jabs/nr_autoentrega.py, caso A).
 */
class AutoentregaUniformeTest {

    // ---------- PBFT ----------

    private static final class CenaPbft {
        final RandomnessEngine rng = new RandomnessEngine(7);
        final Simulator sim = new Simulator();
        final PBFTLocalLANNetwork net = new PBFTLocalLANNetwork(rng);
        final PBFTNode receptor;
        final PBFTNode outro;
        final PBFT<PBFTBlock, ?> pbft;
        final PBFTBlock bloco;

        @SuppressWarnings("unchecked")
        CenaPbft() {
            net.populateNetwork(sim, 4, new jabs.consensus.config.PBFTConsensusConfig(1000L));
            receptor = net.getAllNodes().get(1);
            outro = net.getAllNodes().get(0);
            pbft = (PBFT<PBFTBlock, ?>) receptor.getConsensusAlgorithm();
            bloco = BlockFactory.samplePBFTBlockWithTx(sim, rng, outro, PBFT_GENESIS_BLOCK, 5);
        }
    }

    @Test
    void pbftAutoentregaSaiDoCbPrincipalEVaiParaAColunaPropria() {
        CenaPbft c = new CenaPbft();
        Packet propria = new Packet(c.receptor, c.receptor, new VoteMessage(new PBFTPrePrepareVote<>(c.receptor, c.bloco)));
        c.receptor.processIncomingPacket(propria);
        assertEquals(0L, c.pbft.getBlockTraffic(c.bloco), "C_B_total principal NAO conta a autoentrega");
        assertEquals(0L, c.pbft.getConsensusMessageTraffic(c.bloco), "C_B_consenso principal NAO conta a autoentrega");
        assertEquals(propria.getSize(), c.pbft.getSelfDeliveryTraffic(c.bloco), "coluna propria: pacote inteiro");
        assertEquals(PBFTBlockVote.PBFT_VOTE_SIZE_OVERHEAD, c.pbft.getSelfDeliveryConsensusTraffic(c.bloco),
                "coluna propria so-consenso: o PRE_PREPARE so conta o overhead fixo");
        assertEquals(0L, c.pbft.getAccountedVoteBytes(), "livro do C_B vazio");
        assertEquals(propria.getSize(), c.pbft.getAccountedSelfDeliveryBytes(), "livro da autoentrega fecha");
    }

    @Test
    void pbftPacoteDaRedeContinuaNoCbEAutoentregaNaoSeMistura() {
        CenaPbft c = new CenaPbft();
        Packet rede = new Packet(c.outro, c.receptor, new VoteMessage(new PBFTPrepareVote<>(c.outro, c.bloco)));
        Packet propria = new Packet(c.receptor, c.receptor, new VoteMessage(new PBFTPrepareVote<>(c.receptor, c.bloco)));
        c.receptor.processIncomingPacket(rede);
        c.receptor.processIncomingPacket(propria);
        assertEquals(rede.getSize(), c.pbft.getBlockTraffic(c.bloco), "so o pacote da rede e C_B");
        assertEquals(rede.getSize(), c.pbft.getConsensusMessageTraffic(c.bloco));
        assertEquals(propria.getSize(), c.pbft.getSelfDeliveryTraffic(c.bloco));
        assertEquals(propria.getSize(), c.pbft.getSelfDeliveryConsensusTraffic(c.bloco));
        Packet commit = new Packet(c.receptor, c.receptor, new VoteMessage(new PBFTCommitVote<>(c.receptor, c.bloco)));
        c.receptor.processIncomingPacket(commit);
        assertEquals(rede.getSize(), c.pbft.getBlockTraffic(c.bloco), "COMMIT proprio tambem nao e C_B");
        assertEquals(propria.getSize() + commit.getSize(), c.pbft.getSelfDeliveryTraffic(c.bloco));
    }

    @Test
    void pbftNoEventoOCbPrincipalNaoTemAutoentregaEAColunaTemOValor() {
        // Rodada curta ponta a ponta: o evento de finalizacao carrega C_B principal (so rede) e autoentrega (medida).
        RandomnessEngine rng = new RandomnessEngine(1);
        Simulator sim = new Simulator();
        PBFTLocalLANNetwork net = new PBFTLocalLANNetwork(rng);
        net.populateNetwork(sim, 7, new jabs.consensus.config.PBFTConsensusConfig(1000L));
        net.startConsensusRound(sim, 30.0);
        int vistos = 0;
        long total = 0;
        while (sim.isThereMoreEvents() && sim.getSimulationTime() <= 30.0) {
            jabs.simulator.event.Event ev = sim.peekEvent();
            sim.executeNextEvent();
            if (ev instanceof BlockFinalizationEvent fe) {
                vistos++;
                assertTrue(fe.getSelfDeliveryTraffic() >= 0, "PBFT mede a autoentrega: nunca sentinela (zero e zero medido)");
                assertTrue(fe.getSelfDeliveryConsensusTraffic() >= 0);
                total += fe.getSelfDeliveryTraffic();
                // consenso e parte do total: o PRE_PREPARE so conta o overhead, PREPARE/COMMIT inteiros
                assertTrue(fe.getSelfDeliveryConsensusTraffic() <= fe.getSelfDeliveryTraffic());
                // zero medido e legitimo por bloco (no que finaliza antes de o proprio COMMIT voltar pela rede: o byte vai
                // para "posterior"); por isso nao se exige > 0 por evento, so na rodada
            }
        }
        assertTrue(vistos > 0, "houve finalizacoes");
        assertTrue(total > 0, "o broadcast do PBFTP2P inclui o remetente: ha autoentrega medida na rodada");
    }

    // ---------- Casper (MCO2/TREE) ----------

    @Test
    void casperBlocoQueOMineradorEntregaASiMesmoNaoEhCb() {
        Simulator sim = new Simulator();
        RandomnessEngine rng = new RandomnessEngine(11);
        CasperFFGGlobalBlockchainNetwork<?> net = new CasperFFGGlobalBlockchainNetwork<>(rng, 5,
                new EthereumProofOfWorkGlobalNetworkStats6Regions(rng));
        net.populateNetwork(sim, 4, 6, new CasperFFGConfig(
                EthereumBlock.generateGenesisBlock(ETHEREUM_DIFFICULTY_2022), 15.0, 5, 6));
        EthereumMinerNode no = (EthereumMinerNode) net.getAllNodes().get(0); // minerador: o unico que entrega bloco a si mesmo
        EthereumMinerNode outro = (EthereumMinerNode) net.getAllNodes().get(1);
        @SuppressWarnings("unchecked")
        CasperFFG<EthereumBlock, EthereumTx> casper = (CasperFFG<EthereumBlock, EthereumTx>) no.getConsensusAlgorithm();
        assertTrue(casper.separatesSelfDelivery());
        EthereumBlock genesis = casper.getLocalBlockTree().getGenesisBlock();
        EthereumBlock propio = new EthereumBlock(1000, 1, 1.0, no, genesis, new HashSet<>(), 1.0, 1.0);
        EthereumBlock alheio = new EthereumBlock(2000, 1, 1.0, outro, genesis, new HashSet<>(), 1.0, 1.0);
        Packet p1 = new Packet(no, no, new DataMessage(propio));
        Packet p2 = new Packet(outro, no, new DataMessage(alheio));
        no.processIncomingPacket(p1);
        no.processIncomingPacket(p2);
        assertEquals(0L, casper.getBlockTraffic(propio), "autoentrega do bloco nao e C_B");
        assertEquals(p1.getSize(), casper.getSelfDeliveryTraffic(propio), "coluna propria = tamanho do pacote");
        assertEquals(p2.getSize(), casper.getBlockTraffic(alheio), "bloco vindo da rede continua C_B");
        assertEquals(0L, casper.getSelfDeliveryTraffic(alheio), "zero medido: o bloco alheio nao tem autoentrega");
    }

    // ---------- Parlia (AMBIFY) ----------

    @Test
    void parliaBlocoQueOProdutorEntregaASiMesmoNaoEhCb() {
        RandomnessEngine rng = new RandomnessEngine(11);
        Simulator sim = new Simulator();
        ParliaGlobalBlockchainNetwork<?> net = new ParliaGlobalBlockchainNetwork<>(rng,
                new EthereumProofOfWorkGlobalNetworkStats6Regions(rng), 3, 21);
        EthereumBlock genesis = EthereumBlock.generateGenesisBlock(ETHEREUM_DIFFICULTY_2022);
        net.populateNetwork(sim, 4, 6, new ChainBasedConsensusConfig(genesis, 3.0));
        EthereumMinerNode no = (EthereumMinerNode) net.getAllMiners().get(0);
        EthereumMinerNode outro = (EthereumMinerNode) net.getAllMiners().get(1);
        @SuppressWarnings("unchecked")
        Parlia<EthereumBlock, EthereumTx> parlia = (Parlia<EthereumBlock, EthereumTx>) no.getConsensusAlgorithm();
        assertTrue(parlia.separatesSelfDelivery());
        EthereumBlock propio = new EthereumBlock(1000, 1, 1.0, no, genesis, new HashSet<>(), 1.0, 1.0);
        EthereumBlock alheio = new EthereumBlock(2000, 1, 1.0, outro, genesis, new HashSet<>(), 1.0, 1.0);
        Packet p1 = new Packet(no, no, new DataMessage(propio));
        Packet p2 = new Packet(outro, no, new DataMessage(alheio));
        no.processIncomingPacket(p1);
        no.processIncomingPacket(p2);
        assertEquals(0L, parlia.getBlockTraffic(propio), "autoentrega do bloco nao e C_B");
        assertEquals(p1.getSize(), parlia.getSelfDeliveryTraffic(propio));
        assertEquals(p2.getSize(), parlia.getBlockTraffic(alheio), "bloco vindo da rede continua C_B");
        assertEquals(0L, parlia.getSelfDeliveryTraffic(alheio));
    }

    // ---------- Nakamoto (OFFSET/EARTH) ----------

    @Test
    void nakamotoBlocoQueOMineradorEntregaASiMesmoNaoEhCb() {
        RandomnessEngine rng = new RandomnessEngine(3);
        Simulator sim = new Simulator();
        BitcoinGlobalProofOfWorkNetwork<?> net = new BitcoinGlobalProofOfWorkNetwork<>(rng,
                new BitcoinProofOfWorkGlobalNetworkStats86Countries(rng));
        BitcoinBlockWithoutTx genesis = BitcoinBlockWithoutTx.generateGenesisBlock(BITCOIN_DIFFICULTY_2022);
        net.populateNetwork(sim, 24, 2, new NakamotoConsensusConfig(genesis, 600.0, 14));
        BitcoinNode no = (BitcoinNode) net.getAllNodes().get(5);
        BitcoinNode outro = (BitcoinNode) net.getAllNodes().get(6);
        @SuppressWarnings("unchecked")
        NakamotoConsensus<BitcoinBlockWithoutTx, ?> nak = (NakamotoConsensus<BitcoinBlockWithoutTx, ?>) no.getConsensusAlgorithm();
        assertTrue(nak.separatesSelfDelivery());
        BitcoinBlockWithoutTx propio = new BitcoinBlockWithoutTx(1000, 1, 1.0, no, genesis, BITCOIN_DIFFICULTY_2022, 1.0);
        BitcoinBlockWithoutTx alheio = new BitcoinBlockWithoutTx(2000, 1, 1.0, outro, genesis, BITCOIN_DIFFICULTY_2022, 1.0);
        Packet p1 = new Packet(no, no, new DataMessage(propio));
        Packet p2 = new Packet(outro, no, new DataMessage(alheio));
        no.processIncomingPacket(p1);
        no.processIncomingPacket(p2);
        assertEquals(0L, nak.getBlockTraffic(propio), "autoentrega do bloco nao e C_B");
        assertEquals(p1.getSize(), nak.getSelfDeliveryTraffic(propio));
        assertEquals(p2.getSize(), nak.getBlockTraffic(alheio), "bloco vindo da rede continua C_B");
        assertEquals(0L, nak.getSelfDeliveryTraffic(alheio));
    }

    // ---------- o EVENTO de finalizacao carrega o C_B (so rede) e a autoentrega (medida) ----------

    /** Coleta, numa rodada curta, os eventos de finalizacao e confere: autoentrega NAO esta no C_B e a coluna tem o valor. */
    private static void conferirEventosNaRodada(Simulator sim, double fim, int minEventos, String rotulo) {
        int vistos = 0;
        long somaAuto = 0;
        while (sim.isThereMoreEvents() && sim.getSimulationTime() <= fim) {
            jabs.simulator.event.Event ev = sim.peekEvent();
            sim.executeNextEvent();
            if (ev instanceof BlockFinalizationEvent fe) {
                vistos++;
                assertTrue(fe.getSelfDeliveryTraffic() >= 0, rotulo + ": protocolo que separa mede (nunca sentinela)");
                assertEquals(BlockFinalizationEvent.NOT_MEASURED, fe.getSelfDeliveryConsensusTraffic(),
                        rotulo + ": a parte so-consenso nao e decomposta neste protocolo: -1, nunca zero");
                // O no que finaliza o bloco que ELE produziu tem C_B (so rede) menor que o tamanho do bloco: o bloco que o
                // proprio no entregou a si mesmo esta na coluna propria, nao no C_B.
                if (fe.getBlock().getCreator() == fe.getNode()) {
                    assertTrue(fe.getSelfDeliveryTraffic() >= fe.getBlock().getSize(),
                            rotulo + ": no produtor: a autoentrega carrega pelo menos o bloco (" + fe.getSelfDeliveryTraffic()
                                    + " vs " + fe.getBlock().getSize() + ")");
                }
                somaAuto += fe.getSelfDeliveryTraffic();
            }
        }
        assertTrue(vistos >= minEventos, rotulo + ": eventos de finalizacao vistos = " + vistos);
        assertTrue(somaAuto > 0, rotulo + ": houve autoentrega medida na rodada");
    }

    @Test
    void casperNoEventoAAutoentregaEhOBlocoQueOProdutorEntregouASiMesmo() {
        RandomnessEngine rng = new RandomnessEngine(5);
        Simulator sim = new Simulator();
        CasperFFGGlobalBlockchainNetwork<?> net = new CasperFFGGlobalBlockchainNetwork<>(rng, 14,
                new EthereumProofOfWorkGlobalNetworkStats6Regions(rng));
        net.populateNetwork(sim, 3, 7, new CasperFFGConfig(
                EthereumBlock.generateGenesisBlock(ETHEREUM_DIFFICULTY_2022), 15.0, 14, 7));
        for (int i = 0; i < 3; i++) {
            ((jabs.network.node.nodes.MinerNode) net.getAllNodes().get(i)).startMining();
        }
        conferirEventosNaRodada(sim, 3000.0, 1, "Casper");
    }

    @Test
    void nakamotoNoEventoAAutoentregaEhOBlocoQueOMineradorEntregouASiMesmo() {
        RandomnessEngine rng = new RandomnessEngine(5);
        Simulator sim = new Simulator();
        BitcoinGlobalProofOfWorkNetwork<?> net = new BitcoinGlobalProofOfWorkNetwork<>(rng,
                new BitcoinProofOfWorkGlobalNetworkStats86Countries(rng));
        BitcoinBlockWithoutTx genesis = BitcoinBlockWithoutTx.generateGenesisBlock(BITCOIN_DIFFICULTY_2022);
        net.populateNetwork(sim, 30, 0, new NakamotoConsensusConfig(genesis, 600.0, 3));
        for (jabs.network.node.nodes.MinerNode m : net.getAllMiners()) {
            m.startMining();
        }
        conferirEventosNaRodada(sim, 20000.0, 1, "Nakamoto");
    }

    // ---------- sentinela e quem nao muda ----------

    @Test
    void sentinelaNuncaViraZeroEEventoAntigoNaoTemColunaDeAutoentrega() {
        BlockFinalizationEvent semColuna = new BlockFinalizationEvent(0.0, null, null, 123L);
        assertEquals(BlockFinalizationEvent.NOT_MEASURED, semColuna.getSelfDeliveryTraffic());
        assertEquals(-1L, semColuna.getSelfDeliveryConsensusTraffic());
        BlockFinalizationEvent comConsenso = new BlockFinalizationEvent(0.0, null, null, 123L, 45L);
        assertEquals(-1L, comConsenso.getSelfDeliveryTraffic(), "construtor antigo: nao medido, nunca zero");
        BlockFinalizationEvent medidoZero = new BlockFinalizationEvent(0.0, null, null, 123L, 45L, 0L, 0L);
        assertEquals(0L, medidoZero.getSelfDeliveryTraffic(), "zero MEDIDO continua zero");
        assertEquals(0L, medidoZero.getSelfDeliveryConsensusTraffic());
    }

    @Test
    void soPbftCasperParliaENakamotoSeparamAAutoentrega() {
        // DAGsper, HotStuff e Tendermint ja filtravam (isLocalSelfDelivery): sem coluna, saida inalterada.
        for (Class<?> c : new Class<?>[] {DAGsper.class, HotStuff.class, Tendermint.class, GhostProtocol.class}) {
            assertFalse(AbstractChainBasedConsensus.class.isAssignableFrom(c) && declaraSeparacao(c),
                    c.getSimpleName() + " nao deve sobrescrever separatesSelfDelivery()");
        }
        for (Class<?> c : new Class<?>[] {PBFT.class, Parlia.class, CasperFFG.class, NakamotoConsensus.class}) {
            assertTrue(declaraSeparacao(c), c.getSimpleName() + " separa a autoentrega");
        }
    }

    private static boolean declaraSeparacao(Class<?> c) {
        try {
            return c.getDeclaredMethod("separatesSelfDelivery") != null;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
