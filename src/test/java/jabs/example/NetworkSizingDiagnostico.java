package jabs.example;

import jabs.config.NetworkSizing;
import jabs.consensus.algorithm.CasperFFG;
import jabs.consensus.algorithm.Parlia;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.PeerBlockchainNode;
import jabs.scenario.AbstractScenario;
import jabs.scenario.CasperEthereumNetworkScenario;
import jabs.scenario.ParliaBSCNetworkScenario;

/**
 * Diagnostico EXECUTADO da N1 (NAO e teste): CONSTROI a rede (createNetwork, sem simular) para MCO2 e AMBIFY e
 * imprime o que foi realmente construido, pelos DOIS caminhos (MVPComparison e ByzantineSweep) e pelas duas
 * semanticas, ao lado do que a formula preve. Fonte da tabela "executado" de
 * {@code src/test/resources/nao-regressao/n1-conversao-n/README.txt}.
 *
 * <p>Uso: {@code java -cp "target/classes:target/test-classes:$(cat target/cp.txt)" jabs.example.NetworkSizingDiagnostico
 * [n ...]} (padrao: 4 16 64 128 256 400 800).
 */
public final class NetworkSizingDiagnostico {
    private NetworkSizingDiagnostico() { }

    public static void main(String[] args) {
        int[] ns = args.length == 0 ? new int[]{4, 16, 64, 128, 256, 400, 800} : parse(args);
        System.out.println("caminho protocolo semantica n_pedido | nos_construidos mineradores_construidos "
                + "stakeholders_construidos | efetivo_que_vota | previsto_nos | confere");
        for (int n : ns) {
            for (NetworkSizing.Semantics sem : NetworkSizing.Semantics.values()) {
                linha("MVPComparison", "MCO2", sem, n, NetworkSizing.forMvp(sem, n), false);
                linha("MVPComparison", "AMBIFY", sem, n, NetworkSizing.forMvp(sem, n), true);
                linha("ByzantineSweep", "POS", sem, n, NetworkSizing.forSweep(sem, n), false);
                linha("ByzantineSweep", "POSA", sem, n, NetworkSizing.forSweep(sem, n), true);
            }
            arq(n);
        }
    }

    /** PBFT/ARQ: n e o numero de nos e todos validam (MVPComparison "ARQ" e ByzantineSweep "VOTING"). */
    private static void arq(int n) {
        jabs.scenario.HybridNetworkScenario sc = new jabs.scenario.HybridNetworkScenario("diag", 1L, n, 60.0);
        sc.createNetwork();
        int nos = sc.getNetwork().getAllNodes().size();
        System.out.printf("MVPComparison+ByzantineSweep ARQ/VOTING (igual nas duas semanticas) n=%d | nos=%d "
                + "| validadores_pbft_que_votam=%d | previsto=%d | %s%n", n, nos, nos, n, nos == n ? "OK" : "DIVERGE");
    }

    private static int[] parse(String[] a) {
        int[] r = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = Integer.parseInt(a[i]);
        }
        return r;
    }

    private static void linha(String caminho, String proto, NetworkSizing.Semantics sem, int n,
            NetworkSizing.Decomposition d, boolean parlia) {
        AbstractScenario sc = parlia
                ? new ParliaBSCNetworkScenario("diag", 1L, 60.0, 3.0, 3, 21, d.miners(), d.stakeholders())
                : new CasperEthereumNetworkScenario("diag", 1L, 60.0, 15.0, 14, d.miners(), d.stakeholders());
        if (parlia) {
            ((ParliaBSCNetworkScenario) sc).createNetwork();
        } else {
            ((CasperEthereumNetworkScenario) sc).createNetwork();
        }
        int nos = sc.getNetwork().getAllNodes().size();
        int mineradores = ((jabs.network.networks.GlobalProofOfWorkNetwork<?, ?, ?, ?>) sc.getNetwork())
                .getAllMiners().size();
        String efetivo;
        if (parlia) {
            int validadores = -1;
            for (Object o : sc.getNetwork().getAllNodes()) {
                Node x = (Node) o;
                if (x instanceof PeerBlockchainNode
                        && ((PeerBlockchainNode<?, ?>) x).getConsensusAlgorithm() instanceof Parlia) {
                    validadores = ((Parlia<?, ?>) ((PeerBlockchainNode<?, ?>) x).getConsensusAlgorithm())
                            .getNumOfStakeholders();
                    break;
                }
            }
            efetivo = "validadores_parlia_que_votam=" + validadores;
        } else {
            int votantes = 0;
            int limiar = -1;
            for (Object o : sc.getNetwork().getAllNodes()) {
                PeerBlockchainNode<?, ?> x = (PeerBlockchainNode<?, ?>) o;
                if (x.getConsensusAlgorithm() instanceof CasperFFG) {
                    votantes++;
                    limiar = ((CasperFFG<?, ?>) x.getConsensusAlgorithm()).getRequiredVotes();
                }
            }
            efetivo = "nos_casper_que_votam=" + votantes + " S_denominador=" + d.stakeholders()
                    + " limiar_votos=" + limiar;
        }
        System.out.printf("%s %s %s n=%d | nos=%d mineradores=%d stakeholders=%d | %s | previsto=%d | %s%n",
                caminho, proto, sem.rotulo(), n, nos, mineradores, nos - mineradores, efetivo, d.totalNodes(),
                nos == d.totalNodes() && mineradores == d.miners() ? "OK" : "DIVERGE");
    }
}
