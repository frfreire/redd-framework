package jabs.example;

import java.util.Arrays;

/**
 * Auxiliar de DIAGNOSTICO (NAO e teste JUnit). Mede em que posicao da sequencia de {@link System#identityHashCode}
 * da thread principal a JVM esta ao fim de {@link MVPComparison#main}.
 *
 * <p>Modos: {@code ref N} imprime os N primeiros valores {@code identityHashCode(new Object())} de uma JVM nova (a
 * sequencia e deterministica por JVM/flags); {@code run K args...} chama K vezes identityHashCode, roda a
 * MVPComparison e imprime o proximo valor da sequencia ({@code SONDA=valor}). Procurar o valor em {@code ref}
 * da o numero de hashes de identidade consumidos pela thread principal (incluindo a classe auxiliar, igual nos
 * dois lados). Ver {@code diagnostico-hash.txt}.
 */
public final class SondaHash {
    private SondaHash() { }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("ref")) {
            int n = Integer.parseInt(args[1]);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                sb.append(System.identityHashCode(new Object())).append('\n');
                if (sb.length() > 1 << 16) {
                    System.out.print(sb);
                    sb.setLength(0);
                }
            }
            System.out.print(sb);
            return;
        }
        int k = Integer.parseInt(args[1]);
        for (int i = 0; i < k; i++) {
            System.identityHashCode(new Object());
        }
        MVPComparison.main(Arrays.copyOfRange(args, 2, args.length));
        System.out.println("SONDA=" + System.identityHashCode(new Object()));
    }
}
