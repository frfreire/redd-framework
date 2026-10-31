package jabs.network.p2p;

import java.util.List;

/**
 * Utilitario (nao e teste JUnit; sem sufixo Test) para o CA-03 do ADR-0011: imprime, uma linha por no,
 * a lista de ids dos vizinhos (em ordem) para (familia, n, semente). Roda em JVM nova, com
 * {@code -XX:hashCode=...} variando, e a saida e comparada byte a byte.
 * Uso: P2PVizinhosMain {geth|bitcoin} n semente
 */
public class P2PVizinhosMain {
    public static void main(String[] args) {
        var familia = args[0].equals("geth") ? RedeP2PDeTeste.GETH : RedeP2PDeTeste.BITCOIN;
        int n = Integer.parseInt(args[1]);
        long semente = Long.parseLong(args[2]);
        RedeP2PDeTeste rede = RedeP2PDeTeste.criar(familia, n, semente);
        rede.conectarTodos();
        List<List<Integer>> viz = rede.vizinhos();
        for (int i = 0; i < viz.size(); i++) {
            System.out.println(i + ":" + viz.get(i));
        }
    }
}
