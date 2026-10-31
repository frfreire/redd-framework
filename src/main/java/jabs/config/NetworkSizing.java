package jabs.config;

/**
 * Semantica do parametro {@code n} da linha de comando do {@code MVPComparison} e do {@code ByzantineSweep}
 * (tarefa N1-conversao-n-mvpcomparison; ADR-0011 e auditoria L10).
 *
 * <p><b>O problema.</b> Para Casper (MCO2/TREE) e Parlia (AMBIFY) o {@code n} legado NAO e o numero de nos. A rede
 * e montada com {@code (int)(0,3 n)} mineradores + {@code (int)(0,7 n)} stakeholders (truncamento: n=4 -> 1+2 = 3
 * nos; n=16 -> 4+11 = 15; n=64 -> 19+44 = 63; n=128 -> 38+89 = 127). No {@code ByzantineSweep} (POS/POSA) a rede
 * tem {@code max(1, round(0,3 n))} mineradores + {@code n} stakeholders, ou seja, MAIS que n nos.
 *
 * <p><b>As duas semanticas</b> (flag {@code --n-semantics=legacy|nodes}):
 * <ul>
 *   <li>{@link Semantics#LEGACY} (padrao): reproduz EXATAMENTE as expressoes antigas, inclusive o truncamento.</li>
 *   <li>{@link Semantics#NODES}: {@code n} e o numero total de nos construidos. Regra de decomposicao
 *       (<b>a definir com o humano</b>: a proporcao 30%/70% e herdada do MVPComparison, sem fonte):
 *       {@code mineradores = max(1, round_half_up(0,3 n))}, {@code stakeholders = n - mineradores}.
 *       A soma e exatamente n. O arredondamento e feito em aritmetica inteira
 *       ({@code (3 n + 5) / 10}), sem ponto flutuante.</li>
 * </ul>
 *
 * <p><b>O que NAO esta decidido aqui</b> (perguntas ao humano, relatadas e nao resolvidas): quem e "validador" em
 * PoSA/PoS quando so parte dos nos valida. Esta classe apenas EXPOSE os numeros: no Casper votam todos os nos
 * ({@link Decomposition#casperVoters()}) mas o denominador S exclui os mineradores
 * ({@link Decomposition#casperDenominatorS()}, divida registrada em C1); no Parlia so os mineradores sao
 * validadores ({@link Decomposition#parliaValidators()}).
 */
public final class NetworkSizing {
    /** Nome da flag (sem os dois hifens iniciais). */
    public static final String FLAG = "n-semantics";

    private NetworkSizing() { }

    /** Semantica do {@code n} pedido. */
    public enum Semantics {
        /** {@code n} e o parametro historico (truncado/inflado conforme o protocolo). Padrao. */
        LEGACY("legacy"),
        /** {@code n} e o numero total de nos da rede. */
        NODES("nodes");

        private final String rotulo;

        Semantics(String rotulo) {
            this.rotulo = rotulo;
        }

        public String rotulo() {
            return rotulo;
        }

        /**
         * Valor da flag {@code --n-semantics}. Flag AUSENTE ({@code null}) = {@link #LEGACY} (padrao
         * documentado, comportamento anterior). Flag presente com valor desconhecido, vazio ou com outra
         * grafia (maiusculas, espacos) FALHA ALTO: nao ha cair para o padrao em silencio.
         *
         * @throws IllegalArgumentException valor presente e diferente de {@code legacy} ou {@code nodes}
         */
        public static Semantics fromFlag(String valor) {
            if (valor == null) {
                return LEGACY;
            }
            for (Semantics s : values()) {
                if (s.rotulo.equals(valor)) {
                    return s;
                }
            }
            throw new IllegalArgumentException("--" + FLAG + "='" + valor + "' invalido. Validos: legacy, nodes "
                    + "(sem maiusculas nem espacos). Ausente = legacy.");
        }
    }

    /**
     * Mineradores e stakeholders de uma rede Casper/Parlia.
     *
     * @param semantics  semantica com que {@code requestedN} foi lido
     * @param requestedN o {@code n} recebido na linha de comando
     * @param miners     mineradores construidos (ids 0..miners-1)
     * @param stakeholders nos nao mineradores construidos
     */
    public record Decomposition(Semantics semantics, int requestedN, int miners, int stakeholders) {
        /** Nos construidos na rede (mineradores + stakeholders). */
        public int totalNodes() {
            return miners + stakeholders;
        }

        /** Casper: todos os nos (mineradores e stakeholders) sao EthereumCasper* e votam. */
        public int casperVoters() {
            return totalNodes();
        }

        /** Casper: S configurado (denominador do quorum) = so os stakeholders. Divida C1: nao alterado. */
        public int casperDenominatorS() {
            return stakeholders;
        }

        /** Parlia: so os mineradores sao adicionados como validadores (e so eles votam). */
        public int parliaValidators() {
            return miners;
        }

        /** {@code chave=valor} separados por espaco, para o log e para o cabecalho '#' do CSV. */
        public String describe() {
            return "n_semantics=" + semantics.rotulo() + " n_requested=" + requestedN + " nodes=" + totalNodes()
                    + " miners=" + miners + " stakeholders=" + stakeholders + " casper_voters=" + casperVoters()
                    + " casper_S=" + casperDenominatorS() + " parlia_validators=" + parliaValidators();
        }
    }

    /**
     * MVPComparison, MCO2/TREE/AMBIFY, semantica antiga: {@code (int)(n*0,3)} e {@code (int)(n*0,7)} (mesmas
     * expressoes de ponto flutuante do codigo original; NAO "corrigir").
     */
    public static Decomposition mvpLegacy(int n) {
        return new Decomposition(Semantics.LEGACY, n, (int) (n * 0.3), (int) (n * 0.7));
    }

    /**
     * ByzantineSweep, POS/POSA, semantica antiga: {@code max(1, round(0,3 n))} mineradores e {@code max(1, n)}
     * stakeholders (a rede tem MAIS que n nos).
     */
    public static Decomposition sweepLegacy(int n) {
        int numMiners = (int) Math.max(1, Math.round(n * 0.3));
        int numStakeholders = Math.max(1, n);
        return new Decomposition(Semantics.LEGACY, n, numMiners, numStakeholders);
    }

    /**
     * Semantica {@code nodes}: {@code n} nos no total; mineradores {@code max(1, round_half_up(0,3 n))},
     * stakeholders {@code n - mineradores}. A proporcao 30/70 e <b>a definir com o humano</b>.
     *
     * @throws IllegalArgumentException {@code n < 2} (precisa de ao menos 1 minerador e 1 stakeholder)
     */
    public static Decomposition nodes(int n) {
        if (n < 2) {
            throw new IllegalArgumentException("--" + FLAG + "=nodes exige n >= 2 (1 minerador + 1 stakeholder): n=" + n);
        }
        long arredondado = (3L * n + 5L) / 10L; // floor(0,3 n + 0,5): arredonda meio para cima, sem double
        int miners = (int) Math.max(1L, arredondado);
        return new Decomposition(Semantics.NODES, n, miners, n - miners);
    }

    /** Decomposicao do MVPComparison conforme a semantica. */
    public static Decomposition forMvp(Semantics semantics, int n) {
        return semantics == Semantics.NODES ? nodes(n) : mvpLegacy(n);
    }

    /** Decomposicao do ByzantineSweep (POS/POSA) conforme a semantica. */
    public static Decomposition forSweep(Semantics semantics, int n) {
        return semantics == Semantics.NODES ? nodes(n) : sweepLegacy(n);
    }
}
