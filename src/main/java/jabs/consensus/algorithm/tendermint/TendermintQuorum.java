package jabs.consensus.algorithm.tendermint;

/**
 * Limiares de votacao com poder de voto IGUAL para todos os validadores (suposicao declarada: o artigo admite pesos;
 * aqui todos pesam 1, como no PBFT e no HotStuff do simulador).
 *
 * <ul>
 *   <li>{@link #strong()}: "2f+1" do artigo, lido como MAIS DE 2/3 dos n: {@code floor(2n/3) + 1}. E o que garante
 *       que dois quoruns se intersectam em pelo menos um no honesto para QUALQUER n, nao so n = 3f+1;</li>
 *   <li>{@link #weak()}: "f+1" (linha 55), lido como MAIS DE 1/3: {@code floor(n/3) + 1}, ou seja, pelo menos um no
 *       honesto.</li>
 * </ul>
 *
 * <p><b>Divergencia com PBFT e HotStuff do simulador (a decidir com o humano):</b> ambos usam {@code 2f+1} com
 * {@code f = floor((n-1)/3)}. Os dois criterios coincidem quando {@code n = 3f+1} (n = 4, 7, 10, ...), mas para os
 * demais n o {@code 2f+1} fica abaixo de 2/3 (n=5: 3 de 5; n=50: 33 de 50), e dois quoruns podem se intersectar so em
 * nos bizantinos. Ver {@link #legacyTwoFPlusOne(int)} para comparacao.
 *
 * @param n numero de validadores (&gt;= 1)
 */
public record TendermintQuorum(int n) {

    public TendermintQuorum {
        if (n < 1) {
            throw new IllegalArgumentException("n deve ser >= 1: " + n);
        }
    }

    /** Mais de 2/3 de n. */
    public int strong() {
        return (2 * n) / 3 + 1;
    }

    /** Mais de 1/3 de n. */
    public int weak() {
        return n / 3 + 1;
    }

    /** Maximo de bizantinos tolerados: maior f com {@code 3f < n}. */
    public int maxFaulty() {
        return (n - 1) / 3;
    }

    /** O {@code 2f+1} usado hoje por PBFT/HotStuff, so para comparacao e testes. */
    public static int legacyTwoFPlusOne(int n) {
        return 2 * ((n - 1) / 3) + 1;
    }
}
