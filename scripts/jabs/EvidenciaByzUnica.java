// Evidencia de BYZ-UNICA (ADR-0015): contagem EFETIVA e conjunto EFETIVO de bizantinos por protocolo, para o HEAD e para a
// arvore atual, SEM simular (so createNetwork). Roda como arquivo-fonte unico (JDK 11+), sem compilar o repositorio:
//
//   java -cp "<raiz>/target/classes:$(cat <raiz>/target/cp.txt)" scripts/jabs/EvidenciaByzUnica.java <modo> [seed]
//
// modo "pct"   : constroi pelos construtores por PERCENTUAL (o caminho que o ByzantineSweep antigo usava, com
//                pct = 100.0*f/n). Existe no HEAD e na arvore atual.
// modo "sweep" : so na arvore atual: chama ByzantineSweep.configFor (f inteiro direto, gerador unico) por reflexao.
// Saida: uma linha por (tipo, n, f, semente): "tipo n=.. f_pedido=.. semente=.. contagem=.. ids=[..]". A comparacao
// HEAD x atual e feita por diff dos dois textos (ver scripts/jabs/nr_byz_unica.py). Nao toca em nada do repositorio.
import jabs.config.ByzantineConfig;
import jabs.config.ByzantineConfig.AttackType;
import jabs.scenario.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

public class EvidenciaByzUnica {
    static Object campo(Object o, String nome) throws Exception {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(nome);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(nome);
    }

    static void criaRede(AbstractScenario s) throws Exception {
        Class<?> c = s.getClass();
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod("createNetwork");
                m.setAccessible(true);
                m.invoke(s);
                return;
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchMethodException("createNetwork");
    }

    static String linha(String tipo, int n, int f, long seed, ByzantineConfig c) {
        List<Integer> ids = new ArrayList<>(c == null ? Set.<Integer>of() : c.getByzantineValidatorIds());
        Collections.sort(ids);
        return String.format("%-7s n=%-3d f_pedido=%-2d semente=%-6d contagem=%-2d ids=%s", tipo, n, f, seed,
                ids.size(), ids);
    }

    /** Percentual como o ByzantineSweep antigo: 100.0 * f / n. */
    static double pct(int n, int f) {
        return n == 0 ? 0.0 : 100.0 * f / n;
    }

    @SuppressWarnings("unchecked")
    static ByzantineConfig viaSweep(String tipo, int n, int f, long seed, AttackType a, int[] sizing) throws Exception {
        Class<?> c = Class.forName("jabs.example.ByzantineSweep");
        Method m = c.getDeclaredMethod("configFor", String.class, int.class, int.class, long.class, AttackType.class, int[].class);
        m.setAccessible(true);
        return (ByzantineConfig) m.invoke(null, tipo, n, f, seed, a, sizing);
    }

    public static void main(String[] args) throws Exception {
        String modo = args.length > 0 ? args[0] : "pct";
        long[] sementes = args.length > 1 ? new long[] {Long.parseLong(args[1])} : new long[] {1L, 2L, 3L};
        boolean sweep = modo.equals("sweep");
        // (tipo, n, fmax): n pequeno e os pares do defeito (n=7 f=1, n=7 f=2, n=14 f=1, n=9 f=3)
        Object[][] casos = {{"VOTING", 7, 3}, {"VOTING", 14, 2}, {"VOTING", 9, 3}, {"VOTING", 10, 4},
                {"POS", 10, 3}, {"POS", 13, 4}, {"POSA", 10, 3}, {"POSA", 13, 4}, {"POW", 40, 3}, {"DAGSPER", 10, 3}};
        for (Object[] caso : casos) {
            String tipo = (String) caso[0];
            int n = (Integer) caso[1];
            int fmax = (Integer) caso[2];
            for (long seed : sementes) {
                for (int f = 1; f <= fmax; f++) {
                    ByzantineConfig cfg;
                    switch (tipo) {
                        case "VOTING": {
                            AttackType a = AttackType.EQUIVOCATION;
                            if (sweep) {
                                cfg = viaSweep("VOTING", n, f, seed, a, null);
                            } else {
                                HybridNetworkScenario s = new HybridNetworkScenario("e", seed, n, 10.0, pct(n, f), a);
                                criaRede(s);
                                cfg = s.getByzantineConfig();
                            }
                            break;
                        }
                        case "POS": { // sizing 'nodes': 30% mineradores arredondado (3n+5)/10
                            int miners = Math.max(1, (3 * n + 5) / 10);
                            int stake = n - miners;
                            AttackType a = AttackType.WITHHOLD;
                            if (sweep) {
                                cfg = viaSweep("POS", n, f, seed, a, new int[] {miners, stake});
                            } else {
                                // o ByzantineSweep antigo passava pct = 100*f/n e o cenario fazia ceil(stakeholders*pct/100.0)
                                CasperEthereumNetworkScenario s = new CasperEthereumNetworkScenario("e", seed, 10.0, 15.0, 14,
                                        miners, stake, pct(n, f), a);
                                criaRede(s);
                                cfg = (ByzantineConfig) campo(s, "byzantineConfig");
                            }
                            break;
                        }
                        case "POSA": {
                            int miners = Math.max(1, (3 * n + 5) / 10);
                            int stake = n - miners;
                            AttackType a = AttackType.WITHHOLD;
                            ParliaBSCNetworkScenario s = new ParliaBSCNetworkScenario("e", seed, 10.0, 3.0, 3, 21, miners, stake);
                            if (sweep) {
                                cfg = viaSweep("POSA", n, f, seed, a, new int[] {miners, stake});
                            } else {
                                cfg = new ByzantineConfig(n, pct(n, f), a, seed);
                            }
                            s.setByzantineConfig(cfg);
                            criaRede(s);
                            break;
                        }
                        case "POW": {
                            AttackType a = AttackType.WITHHOLD;
                            BitcoinGlobalNetworkScenario s;
                            if (sweep) {
                                s = new BitcoinGlobalNetworkScenario("e", seed, 100L, 600.0, n, 0, 6, false, 10.0);
                                s.setByzantineConfig(viaSweep("POW", n, f, seed, a, null));
                            } else {
                                s = new BitcoinGlobalNetworkScenario("e", seed, 100L, 600.0, n, 0, 6, false, 10.0, pct(n, f), a);
                            }
                            criaRede(s);
                            cfg = (ByzantineConfig) campo(s, "byzantineConfig");
                            break;
                        }
                        default: { // DAGSPER: f inteiro ja era a entrada; muda so o gerador do conjunto
                            int miners = Math.max(1, (int) Math.round(n * 0.3));
                            DAGsperNetworkScenario s = new DAGsperNetworkScenario("e", seed, 10.0, 15.0, 14, miners, n - miners, f,
                                    AttackType.WITHHOLD, false, 5.0);
                            criaRede(s);
                            cfg = s.getByzantineConfig();
                        }
                    }
                    System.out.println(linha(tipo, n, f, seed, cfg));
                }
            }
        }
    }
}
