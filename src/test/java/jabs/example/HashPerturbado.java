package jabs.example;

import java.util.Arrays;

/**
 * Auxiliar de DIAGNOSTICO (NAO e teste JUnit): desloca a sequencia de {@link System#identityHashCode} da thread
 * principal chamando-o {@code k} vezes ANTES de delegar a {@link MVPComparison#main}. Serve para testar se a saida
 * da simulacao depende de quantas vezes o hash de identidade foi consumido (HashSet/HashMap com chaves sem
 * equals/hashCode, ADR-0006 debitos 2 e 5). Ver {@code src/test/resources/nao-regressao/n1-conversao-n/diagnostico-hash.txt}.
 *
 * <p>Uso: {@code java -cp <classes>:<cp>:<test-classes> jabs.example.HashPerturbado K --protocols=... }
 * (a MVPComparison vem da primeira entrada do classpath que a contenha; test-classes NAO a contem).
 */
public final class HashPerturbado {
    private HashPerturbado() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("uso: HashPerturbado K [args da MVPComparison]");
        }
        int k = Integer.parseInt(args[0]);
        if (k < 0) {
            throw new IllegalArgumentException("k >= 0");
        }
        int acumulador = 0;
        for (int i = 0; i < k; i++) {
            acumulador ^= System.identityHashCode(new Object());
        }
        // o valor nao e usado; a leitura abaixo so impede qualquer otimizacao hipotetica da chamada
        if (acumulador == 0x7fffffff && k > 0) {
            System.err.println("acumulador=" + acumulador);
        }
        MVPComparison.main(Arrays.copyOfRange(args, 1, args.length));
    }
}
