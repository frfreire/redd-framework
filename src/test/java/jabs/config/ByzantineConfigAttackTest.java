package jabs.config;

import jabs.config.ByzantineConfig.AttackType;
import jabs.consensus.algorithm.AbstractConsensusAlgorithm;
import jabs.consensus.algorithm.CasperFFG;
import jabs.consensus.algorithm.DAGsper;
import jabs.consensus.algorithm.NakamotoConsensus;
import jabs.consensus.algorithm.PBFT;
import jabs.consensus.algorithm.Parlia;
import jabs.consensus.blockchain.LocalBlockTree;
import jabs.consensus.config.CasperFFGConfig;
import jabs.consensus.config.DAGsperConfig;
import jabs.consensus.config.NakamotoConsensusConfig;
import jabs.example.ByzantineSweep;
import jabs.ledgerdata.bitcoin.BitcoinBlockWithoutTx;
import jabs.ledgerdata.ethereum.EthereumBlock;
import jabs.scenario.BitcoinGlobalNetworkScenario;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static jabs.network.node.nodes.pbft.PBFTNode.PBFT_GENESIS_BLOCK;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tarefa B1 (criterio 4, modelo de falha explicito e uniforme): um nome de ataque desconhecido NUNCA vira no
 * honesto em silencio. Camada 1: o ponto de entrada por texto (ByzantineConfig). Camada 2: o protocolo recusa em
 * setByzantineConfig o ataque que nao implementa. Camada 3: ponta a ponta por cenario.
 */
class ByzantineConfigAttackTest {
    private static final long SEED = 1;

    // ---------- camada 1: os tres pontos de entrada por texto ----------

    private static void ctorPercentual(String ataque) {
        new ByzantineConfig(10, 30.0, ataque, SEED);
    }

    private static void ctorIds(String ataque) {
        new ByzantineConfig(10, Set.of(1, 2), ataque, SEED);
    }

    @Test
    void recusaNomeEmPortuguesDoYamlComMensagemListandoOsValidos() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ctorPercentual("equivocacao"));
        String m = e.getMessage();
        assertTrue(m.contains("'equivocacao'"), m);
        for (String v : new String[] {"SILENT", "WITHHOLD", "EQUIVOCATION", "DOUBLE_SIGN", "NONE"}) {
            assertTrue(m.contains(v), "mensagem deve listar " + v + ": " + m);
        }
        assertThrows(IllegalArgumentException.class, () -> ctorIds("equivocacao"));
    }

    @Test
    void naoNormalizaCaixaNemEspacos() {
        for (String ruim : new String[] {"equivocation", "Equivocation", "silent", "Withhold", "double_sign",
                " SILENT", "SILENT ", "SILENT\n", "none", "sem ataque", "DOUBLE-SIGN", "EQUIVOCACAO"}) {
            assertThrows(IllegalArgumentException.class, () -> ctorPercentual(ruim), "percentual: '" + ruim + "'");
            assertThrows(IllegalArgumentException.class, () -> ctorIds(ruim), "ids: '" + ruim + "'");
        }
    }

    /**
     * Antes da tarefa B1, null e "" eram aceitos pelos construtores de texto e armazenados como estavam; todo
     * algoritmo os tratava como "sem ataque" (os if/else comparam com literais e caem no ramo honesto; o DAGsper
     * mapeava null para ""). Era justamente o buraco. Agora sao recusados. "Sem ataque" tem uma unica grafia: NONE.
     * Nenhum chamador do repositorio passa null/vazio (todos usam AttackType.name()); o construtor por ENUM com
     * null lancava NullPointerException e agora lanca IllegalArgumentException.
     */
    @Test
    void recusaNuloEVazio() {
        for (String ruim : new String[] {null, "", "   "}) {
            assertThrows(IllegalArgumentException.class, () -> ctorPercentual(ruim));
            assertThrows(IllegalArgumentException.class, () -> ctorIds(ruim));
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ctorPercentual(null));
        assertTrue(e.getMessage().contains("null") && e.getMessage().contains("SILENT"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new ByzantineConfig(10, 30.0, (AttackType) null, SEED));
    }

    @Test
    void aceitaOsQuatroValidosEOSemAtaque() {
        for (AttackType t : AttackType.values()) {
            ByzantineConfig a = new ByzantineConfig(10, 30.0, t.name(), SEED);
            ByzantineConfig b = new ByzantineConfig(10, Set.of(1, 2, 3), t.name(), SEED);
            ByzantineConfig c = new ByzantineConfig(10, 30.0, t, SEED);
            assertEquals(t.name(), a.getAttackType());
            assertEquals(t.name(), b.getAttackType());
            assertEquals(t.name(), c.getAttackType());
            assertEquals(3, a.getByzantineCount());
        }
        assertEquals(5, AttackType.values().length, "SILENT, WITHHOLD, EQUIVOCATION, DOUBLE_SIGN e NONE");
    }

    @Test
    void escolhaDosNosNaoMudouComAValidacao() {
        // mesma semente e mesmos parametros: o conjunto bizantino vem do mesmo sorteio de antes
        ByzantineConfig a = new ByzantineConfig(20, 25.0, "SILENT", 7);
        ByzantineConfig b = new ByzantineConfig(20, 25.0, AttackType.SILENT, 7);
        assertEquals(a.getByzantineValidatorIds(), b.getByzantineValidatorIds());
        assertEquals(5, a.getByzantineCount());
    }

    // ---------- ByzantineSweep: Enum.valueOf ja falha; confirmado e mantido ----------

    @Test
    void sweepRecusaNomeDesconhecidoAntesDeRodarQualquerCelula() {
        for (String ruim : new String[] {"equivocacao", "xyz", ""}) {
            assertThrows(IllegalArgumentException.class,
                    () -> ByzantineSweep.main(new String[] {"--attack=" + ruim, "--out=/tmp/opencode/sweep-recusado.csv"}),
                    "--attack=" + ruim);
        }
        assertThrows(IllegalArgumentException.class, () -> AttackType.valueOf("equivocacao"));
        assertThrows(IllegalArgumentException.class, () -> AttackType.valueOf("equivocation"));
    }

    // ---------- camada 2: o protocolo recusa o ataque que nao implementa ----------

    /** Instancias isoladas (sem rede) de cada algoritmo que le o tipo de ataque. */
    private static Map<String, AbstractConsensusAlgorithm<?, ?>> protocolos() {
        EthereumBlock gEth = EthereumBlock.generateGenesisBlock(2097);
        BitcoinBlockWithoutTx gBtc = BitcoinBlockWithoutTx.generateGenesisBlock(225);
        Map<String, AbstractConsensusAlgorithm<?, ?>> m = new java.util.LinkedHashMap<>();
        m.put("PBFT", new PBFT<>(new LocalBlockTree<>(PBFT_GENESIS_BLOCK), 4));
        m.put("CasperFFG", new CasperFFG<>(new LocalBlockTree<>(gEth), new CasperFFGConfig<>(gEth, 15, 14, 10)));
        m.put("Parlia", new Parlia<>(new LocalBlockTree<>(gEth), 3, 21));
        m.put("Nakamoto", new NakamotoConsensus<>(new LocalBlockTree<>(gBtc),
                new NakamotoConsensusConfig<>(gBtc, 600, 6)));
        m.put("DAGsper", new DAGsper<>(new LocalBlockTree<>(gEth), new DAGsperConfig<>(gEth, 15, 14, 10)));
        return m;
    }

    /** Tabela esperada protocolo x ataque (a mesma do relatorio). true = aceito (tem ramo), false = recusado. */
    private static Map<String, Map<AttackType, Boolean>> esperado() {
        Map<String, Map<AttackType, Boolean>> t = new java.util.LinkedHashMap<>();
        t.put("PBFT", linha(true, true, true, true));
        t.put("CasperFFG", linha(true, true, true, true));
        t.put("Parlia", linha(true, true, true, true));
        t.put("Nakamoto", linha(false, true, true, false));
        t.put("DAGsper", linha(true, true, false, false));
        return t;
    }

    private static Map<AttackType, Boolean> linha(boolean silent, boolean withhold, boolean equivocation,
                                                  boolean doubleSign) {
        Map<AttackType, Boolean> m = new EnumMap<>(AttackType.class);
        m.put(AttackType.SILENT, silent);
        m.put(AttackType.WITHHOLD, withhold);
        m.put(AttackType.EQUIVOCATION, equivocation);
        m.put(AttackType.DOUBLE_SIGN, doubleSign);
        return m;
    }

    @Test
    void tabelaProtocoloXAtaque() {
        Map<String, Map<AttackType, Boolean>> esp = esperado();
        for (String nome : esp.keySet()) {
            for (AttackType t : new AttackType[] {AttackType.SILENT, AttackType.WITHHOLD, AttackType.EQUIVOCATION,
                    AttackType.DOUBLE_SIGN}) {
                // instancia nova por celula: uma config aceita antes nao pode mascarar a recusa
                AbstractConsensusAlgorithm<?, ?> alg = protocolos().get(nome);
                ByzantineConfig cfg = new ByzantineConfig(10, Set.of(1), t.name(), SEED);
                String rotulo = nome + " x " + t;
                if (esp.get(nome).get(t)) {
                    assertDoesNotThrow(() -> alg.setByzantineConfig(cfg), rotulo);
                    assertEquals(t.name(), alg.getByzantineAttackType(), rotulo);
                } else {
                    UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                            () -> alg.setByzantineConfig(cfg), rotulo);
                    assertTrue(e.getMessage().contains(t.name()), e.getMessage());
                    assertNull(alg.getByzantineAttackType(), "config recusada nao pode ficar instalada: " + rotulo);
                }
            }
        }
    }

    @Test
    void semAtaqueENuloSempreAceitosPeloProtocolo() {
        ByzantineConfig none = new ByzantineConfig(10, Set.of(1), "NONE", SEED);
        for (Map.Entry<String, AbstractConsensusAlgorithm<?, ?>> p : protocolos().entrySet()) {
            assertDoesNotThrow(() -> p.getValue().setByzantineConfig(none), p.getKey());
            assertDoesNotThrow(() -> p.getValue().setByzantineConfig(null), p.getKey());
        }
    }

    // ---------- camada 3: ponta a ponta, o cenario nao engole a recusa ----------

    @Test
    void cenarioNakamotoComSilentFalhaAltoEmVezDeRodarHonesto() {
        BitcoinGlobalNetworkScenario s = new BitcoinGlobalNetworkScenario("b1-silent-nakamoto", SEED, 100, 600.0,
                40, 0, 6, 25.0, AttackType.SILENT);
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class, s::run);
        assertTrue(e.getMessage().contains("SILENT"), e.getMessage());
    }
}
