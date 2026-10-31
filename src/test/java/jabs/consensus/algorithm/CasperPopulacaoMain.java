package jabs.consensus.algorithm;

import jabs.consensus.config.CasperFFGConfig;
import jabs.ledgerdata.casper.CasperFFGLink;
import jabs.ledgerdata.casper.CasperFFGVote;
import jabs.ledgerdata.ethereum.EthereumBlock;
import jabs.network.node.nodes.Node;
import jabs.network.node.nodes.PeerBlockchainNode;
import jabs.scenario.CasperEthereumNetworkScenario;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.TreeSet;

/**
 * Diagnostico (NAO e teste; tarefa C1-casper-quorum-correcao, item (a) do reporte): para os n do MVPComparison/MCO2
 * (mineradores = (int)(0,3n), stakeholders S = (int)(0,7n)), imprime o limiar antigo e o novo sobre o denominador
 * configurado S, e roda o cenario para observar QUEM de fato vota (ids de votantes distintos por link, por
 * reflexao no mapa privado {@code votes}), comparando com S.
 *
 * <p>Uso: {@code java -cp "target/classes:target/test-classes:$(cat target/cp.txt)"
 * jabs.consensus.algorithm.CasperPopulacaoMain [n:duracao:semente ...]} (padrao: tabela de limiares so).
 */
public final class CasperPopulacaoMain {
    private CasperPopulacaoMain() { }

    static int antigo(int s) {
        return (((s / 3) * 2) + 1) + 1;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("# n miners S  limiar_antigo limiar_novo  S%3  (limiar/n_votantes_potenciais: antigo novo)");
        for (int n : new int[]{10, 20, 30, 40, 50, 60, 100}) {
            int m = (int) (n * 0.3);
            int s = (int) (n * 0.7);
            int novo = CasperFFG.supermajorityThreshold(s);
            System.out.printf("%d %d %d %d %d %d  %.3f %.3f%n", n, m, s, antigo(s), novo, s % 3,
                    antigo(s) / (double) (m + s), novo / (double) (m + s));
        }
        for (String a : args) {
            String[] p = a.split(":");
            int n = Integer.parseInt(p[0]);
            double dur = Double.parseDouble(p[1]);
            int semente = Integer.parseInt(p[2]);
            int m = (int) (n * 0.3);
            int s = (int) (n * 0.7);
            // mesma construcao do MVPComparison.runMCO2Scenario (seed = n*1000+semente)
            CasperEthereumNetworkScenario sc = new CasperEthereumNetworkScenario("diag", n * 1000 + semente, dur,
                    15.0, 14, m, s);
            sc.run();
            Field f = CasperFFG.class.getDeclaredField("votes");
            f.setAccessible(true);
            TreeSet<Integer> votantes = new TreeSet<>();
            int links = 0;
            for (Object o : sc.getNetwork().getAllNodes()) {
                PeerBlockchainNode<?, ?> node = (PeerBlockchainNode<?, ?>) o;
                @SuppressWarnings("unchecked")
                Map<CasperFFGLink<EthereumBlock>, Map<Node, CasperFFGVote<EthereumBlock>>> v =
                        (Map<CasperFFGLink<EthereumBlock>, Map<Node, CasperFFGVote<EthereumBlock>>>)
                                f.get(node.getConsensusAlgorithm());
                links += v.size();
                for (Map<Node, CasperFFGVote<EthereumBlock>> mm : v.values()) {
                    for (Node vt : mm.keySet()) votantes.add(vt.nodeID);
                }
            }
            long mineradoresVotando = votantes.stream().filter(id -> id < m).count();
            long stakeholdersVotando = votantes.stream().filter(id -> id >= m).count();
            System.out.printf("run n=%d dur=%.0f semente=%d: nos=%d (miners=%d, S=%d) votantes distintos=%d "
                            + "(entre mineradores=%d, entre stakeholders=%d) links(soma dos nos)=%d%n",
                    n, dur, semente, sc.getNetwork().getAllNodes().size(), m, s, votantes.size(),
                    mineradoresVotando, stakeholdersVotando, links);
        }
    }
}
