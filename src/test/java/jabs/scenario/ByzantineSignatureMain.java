package jabs.scenario;

import jabs.config.ByzantineConfig;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.PeerBlockchainNode;
import jabs.simulator.Simulator;
import jabs.simulator.event.BlockFinalizationEvent;
import jabs.simulator.event.Event;

import java.util.HashSet;
import java.util.Set;

/**
 * Utilitario de nao-regressao (nao e teste JUnit; nome sem sufixo Test), molde: PBFTSignatureMain. Para Casper
 * (MCO2), Parlia (AMBIFY) e Nakamoto (OFFSET, EARTH) imprime UMA linha com a assinatura de uma execucao:
 * hash dos BlockFinalizationEvent (no, tempo, altura, trafego total, trafego de consenso), numero de eventos
 * executados, tempo final e hash dos contadores BFT (honesto/bizantino) de todos os nos.
 *
 * Uso (um caso por JVM, porque EthereumMinerNode.memPool e um HashSet de identidade e o gerador de identity hash
 * avanca entre execucoes na mesma JVM; ver ParliaRunMain):
 *   java -cp target/test-classes:target/classes:$(cat target/cp.txt) jabs.scenario.ByzantineSignatureMain
 *        PROTOCOLO SEMENTE N ATAQUE [DURACAO_NAKAMOTO_S]   (PROTOCOLO: MCO2|AMBIFY|OFFSET|EARTH; ATAQUE: NONE|SILENT|...)
 * O script /tmp/opencode/sig.sh roda a matriz inteira (uma JVM por celula, em paralelo).
 */
public final class ByzantineSignatureMain {

    /** Duracao (s simulados) dos cenarios Nakamoto; sobrescrita pelo 5o argumento (SEGUNDOS). */
    static long durNakamoto = 12000L;

    static String run(String proto, long seed, int n, String attack) throws Exception {
        boolean semAtaque = attack.equals("NONE");
        ByzantineConfig.AttackType tipo = ByzantineConfig.AttackType.valueOf(attack);
        AbstractScenario scenario;
        switch (proto) {
            case "MCO2": {
                int miners = (int) (n * 0.3);
                int stakeholders = (int) (n * 0.7);
                scenario = semAtaque
                        ? new CasperEthereumNetworkScenario("sig", seed, 900.0, 15.0, 14, miners, stakeholders)
                        : new CasperEthereumNetworkScenario("sig", seed, 900.0, 15.0, 14, miners, stakeholders,
                                30.0, tipo);
                break;
            }
            case "AMBIFY": {
                int miners = (int) (n * 0.3);
                int stakeholders = (int) (n * 0.7);
                ParliaBSCNetworkScenario p = new ParliaBSCNetworkScenario("sig", seed, 120.0, 3.0, 3, 21, miners,
                        stakeholders);
                if (!semAtaque) {
                    // Validadores do Parlia = mineradores (ids 0..miners-1): o no 1 e bizantino.
                    p.setByzantineConfig(new ByzantineConfig(n, Set.of(1), attack, seed));
                }
                scenario = p;
                break;
            }
            case "OFFSET":
            case "EARTH": {
                boolean offset = proto.equals("OFFSET");
                scenario = semAtaque
                        ? new BitcoinGlobalNetworkScenario("sig", seed, durNakamoto, offset ? 600.0 : 450.0, n, 0,
                                offset ? 14 : 6, true, 10.0)
                        : new BitcoinGlobalNetworkScenario("sig", seed, durNakamoto, offset ? 600.0 : 450.0, n, 0,
                                offset ? 14 : 6, true, 10.0, 30.0, tipo);
                break;
            }
            default:
                throw new IllegalArgumentException("protocolo: " + proto);
        }
        scenario.createNetwork();
        scenario.insertInitialEvents();
        Simulator sim = scenario.getSimulator();
        long h = 7;
        long events = 0;
        long fin = 0;
        while (sim.isThereMoreEvents() && !scenario.simulationStopCondition()) {
            Event ev = sim.peekEvent();
            sim.executeNextEvent();
            events++;
            if (ev instanceof BlockFinalizationEvent fe) {
                fin++;
                h = h * 31 + fe.getNode().nodeID;
                h = h * 31 + Double.doubleToLongBits(sim.getSimulationTime());
                h = h * 31 + fe.getBlock().getHeight();
                h = h * 31 + fe.getTrafficUntilFinalization();
                h = h * 31 + fe.getConsensusMessageTraffic();
            }
        }
        long bft = 11;
        Set<Node> vistos = new HashSet<>();
        for (Object o : scenario.getNetwork().getAllNodes()) {
            if (o instanceof PeerBlockchainNode<?, ?> pbn && vistos.add(pbn)) {
                bft = bft * 31 + Double.doubleToLongBits(pbn.getConsensusAlgorithm().getByzantineFaultTolerance());
            }
        }
        return "events=" + events + " fin=" + fin + " t=" + Double.doubleToLongBits(sim.getSimulationTime())
                + " hash=" + h + " bft=" + bft;
    }

    public static void main(String[] args) {
        String proto = args[0];
        long seed = Long.parseLong(args[1]);
        int n = Integer.parseInt(args[2]);
        String attack = args[3];
        if (args.length > 4) durNakamoto = Long.parseLong(args[4]);
        final Thread principal = Thread.currentThread();
        Thread vigia = new Thread(() -> {
            try {
                Thread.sleep(Long.getLong("sig.watchdog.ms", 90_000L));
            } catch (InterruptedException e) {
                return;
            }
            System.err.println("WATCHDOG: execucao ainda ativa; pilha da thread principal:");
            for (StackTraceElement el : principal.getStackTrace()) System.err.println("  at " + el);
        }, "sig-watchdog");
        vigia.setDaemon(true);
        vigia.start();
        String out;
        long t0 = System.nanoTime();
        try {
            out = run(proto, seed, n, attack);
        } catch (Exception | StackOverflowError e) {
            out = "THREW " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        System.err.println("elapsed_ms=" + (System.nanoTime() - t0) / 1_000_000);
        System.out.println("SIG " + proto + " seed=" + seed + " n=" + n + " " + attack + " " + out);
    }

    private ByzantineSignatureMain() {}
}
