package jabs.example;

import jabs.config.ByzantineConfig;
import jabs.config.ByzantineConfig.AttackType;
import jabs.scenario.AbstractScenario;
import jabs.scenario.BitcoinGlobalNetworkScenario;
import jabs.scenario.CasperEthereumNetworkScenario;
import jabs.scenario.DAGsperNetworkScenario;
import jabs.scenario.HybridNetworkScenario;
import jabs.scenario.ParliaBSCNetworkScenario;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * BYZ-UNICA (ADR-0015, decisao 1, rodada 3): o caminho por CONTAGEM INTEIRA entrega f exato, ponta a ponta no cenario,
 * em todos os protocolos que o ByzantineSweep monta (VOTING/ARQ, POS/Casper, POSA/Parlia, POW/Nakamoto, DAGSPER), em
 * particular no par do defeito (n=7, f=1), onde o caminho legado por percentual (pct = 100.0 * f / n) da 2. Sem simular:
 * so {@code createNetwork}.
 */
class ByzantineContagemInteiraCenariosTest {
    private static void criaRede(AbstractScenario s) throws Exception {
        if (s instanceof jabs.scenario.ArqReddVotingScenario) {
            ((jabs.scenario.ArqReddVotingScenario) s).setMaxFutureHorizon(1000L);
        }
        Method m = AbstractScenario.class.getDeclaredMethod("createNetwork");
        m.setAccessible(true);
        m.invoke(s);
    }

    private static ByzantineConfig config(Object cenario) throws Exception {
        Class<?> c = cenario.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField("byzantineConfig");
                f.setAccessible(true);
                return (ByzantineConfig) f.get(cenario);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException("byzantineConfig");
    }

    @Test
    void voting_arq_f_inteiro_n7_f1_e_exaustivo_pequeno() throws Exception {
        for (int n = 4; n <= 13; n++) {
            for (int f = 0; f <= n / 2; f++) {
                HybridNetworkScenario s = new HybridNetworkScenario("t", 1L, n, 10.0,
                        ByzantineConfig.withExactCount(n, f, AttackType.EQUIVOCATION.name(), 1L));
                criaRede(s);
                assertEquals(f, config(s).getByzantineCount(), "VOTING n=" + n + " f=" + f);
                assertEquals(f, s.getMetrics().getByzantineValidators(), "metrica: n=" + n + " f=" + f);
            }
        }
        // o contraste do defeito: pelo percentual reconstruido o teto exato e 2
        HybridNetworkScenario legado = new HybridNetworkScenario("t", 1L, 7, 10.0, 100.0 / 7, AttackType.EQUIVOCATION);
        assertEquals(2, legado.getByzantineConfig().getByzantineCount());
    }

    @Test
    void pos_posa_pow_dagsper_f_inteiro_n7_e_n10() throws Exception {
        for (int n : new int[] {10, 13}) {
            int miners = Math.max(1, (3 * n + 5) / 10);
            int stake = n - miners;
            for (int f = 1; f <= 3; f++) {
                CasperEthereumNetworkScenario pos = new CasperEthereumNetworkScenario("t", 1L, 10.0, 15.0, 14, miners, stake);
                pos.setByzantineConfig(ByzantineSweep.configFor("POS", n, f, 1L, AttackType.WITHHOLD, new int[] {miners, stake}));
                criaRede(pos);
                assertEquals(f, config(pos).getByzantineCount(), "POS n=" + n + " f=" + f);

                ParliaBSCNetworkScenario posa = new ParliaBSCNetworkScenario("t", 1L, 10.0, 3.0, 3, 21, miners, stake);
                posa.setByzantineConfig(ByzantineSweep.configFor("POSA", n, f, 1L, AttackType.WITHHOLD, new int[] {miners, stake}));
                criaRede(posa);
                assertEquals(f, config(posa).getByzantineCount(), "POSA n=" + n + " f=" + f);

                DAGsperNetworkScenario dag = new DAGsperNetworkScenario("t", 1L, 10.0, 15.0, 14, miners, stake, f,
                        AttackType.WITHHOLD, false, 5.0);
                criaRede(dag);
                assertEquals(f, dag.getByzantineConfig().getByzantineCount(), "DAGSPER n=" + n + " f=" + f);
            }
        }
        for (int n : new int[] {7, 40}) {
            for (int f = 1; f <= 3; f++) {
                BitcoinGlobalNetworkScenario pow = new BitcoinGlobalNetworkScenario("t", 1L, 100L, 600.0, n, 0, 6, false, 10.0);
                pow.setByzantineConfig(ByzantineSweep.configFor("POW", n, f, 1L, AttackType.WITHHOLD, null));
                criaRede(pow);
                assertNotNull(config(pow));
                assertEquals(f, config(pow).getByzantineCount(), "POW n=" + n + " f=" + f);
            }
        }
    }
}
