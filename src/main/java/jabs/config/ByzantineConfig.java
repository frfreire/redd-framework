package jabs.config;

import jabs.simulator.randengine.RandomnessEngine;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.*;

/**
 * Configuration for Byzantine fault injection in consensus simulations.
 * 
 * Enables testing of protocol resilience to malicious validators.
 * Arq-REDD+ voting-based consensus is designed to tolerate up to f < n/3 Byzantine nodes.
 */
public class ByzantineConfig {
    
    private Set<Integer> byzantineValidatorIds;
    private double byzantinePercentage;
    private int totalValidators;
    private String attackType;
    private long randomSeed;
    
    /**
     * Attack types that Byzantine validators can execute:
     * - WITHHOLD: Refuse to vote on proposals
     * - EQUIVOCATION: Vote for conflicting blocks
     * - SILENT: Don't participate (network partitioned)
     * - DOUBLE_SIGN: Sign multiple conflicting blocks
     */
    public enum AttackType {
        WITHHOLD,
        EQUIVOCATION,
        SILENT,
        DOUBLE_SIGN,
        NONE
    }
    
    public ByzantineConfig(int totalValidators, double byzantinePercentage, 
                          AttackType attackType, long randomSeed) {
        this.totalValidators = totalValidators;
        this.byzantinePercentage = byzantinePercentage;
        this.attackType = validateAttackName(attackType == null ? null : attackType.name());
        this.randomSeed = randomSeed;
        this.byzantineValidatorIds = new HashSet<>();
        
        // Select random validators to be Byzantine
        selectByzantineValidators();
    }
    
    /**
     * Alternative constructor with String attack type
     */
    public ByzantineConfig(int totalValidators, double byzantinePercentage, 
                          String attackType, long randomSeed) {
        this.attackType = validateAttackName(attackType);
        this.totalValidators = totalValidators;
        this.byzantinePercentage = byzantinePercentage;
        this.randomSeed = randomSeed;
        this.byzantineValidatorIds = new HashSet<>();
        
        selectByzantineValidators();
    }

    /**
     * Construct a ByzantineConfig with an explicit set of global node IDs.
     * @param totalNodes total number of nodes in the network (for reporting)
     * @param byzantineIds set of global node IDs which are Byzantine
     * @param attackType attack type string
     * @param randomSeed random seed used (kept for reproducibility)
     */
    public ByzantineConfig(int totalNodes, Set<Integer> byzantineIds,
                           String attackType, long randomSeed) {
        this.attackType = validateAttackName(attackType);
        this.totalValidators = totalNodes;
        this.byzantineValidatorIds = new HashSet<>(byzantineIds == null ? Collections.emptySet() : byzantineIds);
        this.byzantinePercentage = (this.byzantineValidatorIds.size() * 100.0) / (totalNodes == 0 ? 1 : totalNodes);
        this.randomSeed = randomSeed;
    }

    /**
     * Strict validation of an attack name received as text (explicit failure model, bar criterion 4).
     * Accepts EXACTLY the names of {@link AttackType}: SILENT, WITHHOLD, EQUIVOCATION, DOUBLE_SIGN, or
     * NONE ("sem ataque"). No case folding, no trimming, no translation: mapping a scenario-level name
     * (e.g. "equivocacao") to the enum is the job of the config generator. Null and blank are refused too:
     * before, they were stored as-is and every algorithm silently treated the Byzantine nodes as honest.
     *
     * @param name attack name
     * @return the same name, when valid
     * @throws IllegalArgumentException if the name is null, blank or not exactly an enum name
     */
    public static String validateAttackName(String name) {
        if (name != null) {
            for (AttackType t : AttackType.values()) {
                if (t.name().equals(name)) {
                    return name;
                }
            }
        }
        throw new IllegalArgumentException("Invalid Byzantine attack type: "
                + (name == null ? "null" : "'" + name + "'")
                + ". Valid values (exact match, upper case): " + validAttackNames()
                + " (NONE = no attack).");
    }

    /** Comma-separated names accepted by {@link #validateAttackName(String)}. */
    public static String validAttackNames() {
        StringBuilder sb = new StringBuilder();
        for (AttackType t : AttackType.values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(t.name());
        }
        return sb.toString();
    }
    
    /**
     * Seleciona os bizantinos do construtor por PERCENTUAL (caminho legado, para percentual digitado): contagem pelo
     * teto EXATO do {@code double} recebido ({@link #countFromPercent}, sem tolerancia) e conjunto do gerador unico
     * aninhado ({@link #selectNested}) sobre {@code [0, totalValidators)}. Quem TEM o inteiro {@code f} nao deve
     * passar por aqui: use {@link #withExactCount}. Antes: {@code java.util.Random} por protocolo (conjunto
     * diferente do HOTSTUFF para a mesma semente).
     */
    private void selectByzantineValidators() {
        int byzantineCount = countFromPercent(totalValidators, byzantinePercentage);
        if (byzantineCount > 0) { // f=0: nenhum sorteio (nao instancia o fluxo; a saida sem ataque nao muda)
            byzantineValidatorIds.addAll(selectNested(randomSeed, totalValidators, byzantineCount));
        }
    }

    // ===== CONTAGEM INTEIRA (ADR-0015, registro de 10/11 e 11/11, decisoes 1 e 2) =====

    /**
     * {@code ceil(universe * num / den)} em aritmetica inteira (sem {@code double}). E a regra unica de {@code f = ceil(phi
     * * n)} para {@code phi = num/den}. {@code universe >= 0}, {@code 0 <= num <= den}, {@code den >= 1}.
     */
    public static int countFromFraction(int universe, long num, long den) {
        if (universe < 0 || den < 1 || num < 0 || num > den) {
            throw new IllegalArgumentException("fracao invalida: universo=" + universe + " num=" + num + " den=" + den
                    + " (exige universo >= 0, den >= 1, 0 <= num <= den)");
        }
        // BigInteger: universe * num e num + den - 1 transbordam long para fracoes validas (den ~ Long.MAX_VALUE).
        // Como num <= den, o teto e <= universe: cabe em int (intValueExact falha alto se esta invariante quebrar).
        BigInteger bigDen = BigInteger.valueOf(den);
        BigInteger produto = BigInteger.valueOf(universe).multiply(BigInteger.valueOf(num));
        return produto.add(bigDen).subtract(BigInteger.ONE).divide(bigDen).intValueExact();
    }

    /**
     * Contagem bizantina {@code f = ceil(pct% * universe)} a partir de um percentual (0 a 100), em aritmetica EXATA e
     * SEM tolerancia: o valor binario do {@code double} recebido entra como {@link BigDecimal} (sem truncar nem
     * arredondar casas) e o teto e feito sobre o produto exato {@code universe * pct / 100}. Nao ha excecao, nem
     * tolerancia em ponto flutuante, nem "absorcao de ruido".
     *
     * <p><b>Contrato do caminho por percentual:</b> o resultado e o teto exato do valor EXATO do {@code double}, nao do
     * literal decimal que o usuario digitou. Um {@code double} que representa um racional (100/7, 0,1) e quase sempre
     * ligeiramente maior ou menor que ele; quando e maior, o teto sobe: {@code countFromPercent(7, 100.0 / 7) == 2}
     * (o double vale 14,2857142857142864..., acima de 100/7, entao {@code 7 * pct / 100} e ligeiramente maior que 1) e
     * {@code countFromPercent(1000, 0.1) == 2} (0,1 em double e 0,1000000000000000055...). Nao reconstrua {@code f} por
     * {@code pct = 100.0 * f / n}: use a contagem inteira ({@link #withExactCount}, {@link #forUniverse},
     * {@link #countFromFraction}). A entrada primaria do estudo e o inteiro {@code f} (ADR-0015, decisao 1); este
     * metodo existe so para um percentual digitado por usuario em caminho legado, e vale o teto exato do double.
     *
     * @throws IllegalArgumentException percentual NaN, negativo ou acima de 100, ou universo negativo
     */
    public static int countFromPercent(int universe, double pct) {
        if (Double.isNaN(pct) || pct < 0.0 || pct > 100.0) {
            throw new IllegalArgumentException("percentual bizantino fora de [0, 100]: " + pct);
        }
        if (universe < 0) {
            throw new IllegalArgumentException("universo negativo: " + universe);
        }
        BigDecimal exato = new BigDecimal(pct).multiply(BigDecimal.valueOf(universe)).movePointLeft(2);
        return exato.setScale(0, RoundingMode.CEILING).intValueExact();
    }

    // ===== FAIXA BFT (ADR-0015 decisao 3 e D3(a)) =====

    /** {@code floor((n-1)/3)}: maior f tolerado com n = 3f+1. Inteiro; {@code floorDiv} para n &lt; 1 nao truncar para 0. */
    public static int bftLimit(int universe) {
        return Math.floorDiv(universe - 1, 3);
    }

    /** {@code f > floor((universe-1)/3)}: "fora da faixa", nunca arredondado em silencio. */
    public static boolean beyondBftThreshold(int universe, int f) {
        return f > bftLimit(universe);
    }

    /**
     * Guarda das portas de entrada (CLI) "fora da faixa" ({@code f > floor((universe-1)/3)}): sem {@code allowBeyond} falha ALTO
     * citando n, f e o limite; com a flag devolve {@code true} (o chamador DEVE rotular a saida). Dentro da faixa devolve
     * {@code false} e nao exige nada, com ou sem a flag. {@code f > universe} falha sempre.
     *
     * @param what descricao do universo para a mensagem (ex.: "validadores")
     */
    public static boolean requireWithinBftThreshold(String what, int universe, int f, boolean allowBeyond) {
        if (f < 0 || f > universe) {
            throw new IllegalArgumentException("contagem bizantina f=" + f + " fora de [0, " + universe + "] (" + what + ")");
        }
        if (!beyondBftThreshold(universe, f)) {
            return false;
        }
        if (!allowBeyond) {
            throw new IllegalArgumentException("contagem bizantina fora da faixa BFT (f > floor((n-1)/3)): n=" + universe + " f=" + f
                    + " limite floor((n-1)/3)=" + bftLimit(universe) + " (" + what + "). Modo explicito e rotulado so com --"
                    + ALLOW_BEYOND_BFT_THRESHOLD + "=true (ADR-0015 decisao 3 e D3(a)); nada e arredondado em silencio");
        }
        return true;
    }

    /** Linha '#' padrao que rotula um n fora da faixa (mesmo texto do HOTSTUFF, 76932b9). */
    public static String beyondThresholdLine(int universe, int f) {
        return new StringBuilder("# fora_da_faixa=true n=").append(universe).append(" byzantine_count=").append(f)
                .append(" limite_bft=").append(bftLimit(universe))
                .append(" (limite = floor((n-1)/3); modo explicito --").append(ALLOW_BEYOND_BFT_THRESHOLD)
                .append("=true; ADR-0015 decisao 3 e D3(a); resultado fora da garantia BFT do protocolo)").toString();
    }

    /** Nome da flag que libera, rotulado, o modo fora da faixa (HOTSTUFF desde 76932b9; agora tambem o ByzantineSweep). */
    public static final String ALLOW_BEYOND_BFT_THRESHOLD = "allow-beyond-bft-threshold";

    /** {@code true}/{@code false} exatos; ausente ({@code null}) = {@code false}; outro valor falha alto. */
    public static boolean parseAllowBeyondBftThreshold(String valor) {
        if (valor == null || valor.equals("false")) {
            return false;
        }
        if (valor.equals("true")) {
            return true;
        }
        throw new IllegalArgumentException("--" + ALLOW_BEYOND_BFT_THRESHOLD + " aceita 'true' ou 'false' (recebido '" + valor + "')");
    }

    // ===== GERADOR UNICO (ADR-0015 decisao 4 de 11/11) =====

    /**
     * Sal XOR-ado a semente do cenario para obter a semente do fluxo DEDICADO do sorteio bizantino de
     * {@link #withExactCount}. Constante fixa (nao e parametro de pesquisa): so separa o fluxo do sorteio do fluxo
     * da simulacao, que usa a semente "crua" no {@code RandomnessEngine} do cenario.
     */
    public static final long BYZANTINE_STREAM_SALT = 0x425A414E54494E45L;

    /**
     * Fluxo de aleatoriedade DEDICADO ao sorteio bizantino: um {@link RandomnessEngine} proprio, semeado por
     * {@code seed ^ BYZANTINE_STREAM_SALT}. E independente do engine do cenario (rede, blocos, transacoes): criar e
     * consumir este fluxo NAO desloca a sequencia da simulacao, entao a saida sem ataque segue identica e a mesma
     * semente produz a mesma simulacao para qualquer {@code f}.
     */
    public static RandomnessEngine byzantineStream(long seed) {
        return new RandomnessEngine(seed ^ BYZANTINE_STREAM_SALT);
    }

    /**
     * GERADOR UNICO de conjuntos bizantinos: {@code (semente, universo, f) -> conjunto}, usado por TODOS os protocolos
     * (ARQ, Casper/MCO2/TREE, Parlia/AMBIFY, Nakamoto, DAGsper, Tendermint, HotStuff). Sorteio: {@code nextInt(|universo|)}
     * no {@link #byzantineStream(long)} da semente, indice -&gt; id do universo, ate o conjunto ter {@code f} ids. Para a mesma
     * semente e o mesmo universo o conjunto de {@code f} e prefixo, na ordem de sorteio, do de {@code f+1}: ANINHADO entre
     * valores de f. Devolve os ids em ordem de sorteio. Nao usa {@code java.util.Random} nem consome o fluxo da simulacao.
     *
     * <p>O universo e o dos validadores/votantes do protocolo (ADR-0015 Q30, opcao A; Q32, opcao A): quem chama o
     * monta (Casper: todos os nos; Parlia: so os mineradores; demais: os n nos). Universos diferentes dao conjuntos
     * diferentes para a mesma semente (o fluxo depende de {@code |universo|}).
     *
     * @param universeIds ids elegiveis, sem repeticao, em ordem estavel
     * @throws IllegalArgumentException universo vazio ou com repeticao, ou {@code f} fora de [0, |universo|]
     */
    public static Set<Integer> selectNested(long seed, List<Integer> universeIds, int f) {
        if (universeIds == null || universeIds.isEmpty()) {
            throw new IllegalArgumentException("universo bizantino vazio");
        }
        if (new HashSet<>(universeIds).size() != universeIds.size()) {
            throw new IllegalArgumentException("universo bizantino com id repetido: " + universeIds);
        }
        if (f < 0 || f > universeIds.size()) {
            throw new IllegalArgumentException("contagem bizantina fora de [0, " + universeIds.size() + "]: " + f);
        }
        Set<Integer> ids = new LinkedHashSet<>();
        if (f == 0) {
            return ids; // nada a sortear: nao instancia o fluxo
        }
        RandomnessEngine stream = byzantineStream(seed);
        while (ids.size() < f) {
            ids.add(universeIds.get(stream.nextInt(universeIds.size())));
        }
        return ids;
    }

    /** Universo {@code [0, universeSize)}: caso dos protocolos em que todos os nos sao validadores (ARQ, Tendermint, HotStuff...). */
    public static Set<Integer> selectNested(long seed, int universeSize, int f) {
        if (universeSize < 1) {
            throw new IllegalArgumentException("totalValidators deve ser >= 1: " + universeSize);
        }
        return selectNested(seed, range(0, universeSize), f);
    }

    /** Ids {@code first .. first+count-1}, em ordem. */
    public static List<Integer> range(int first, int count) {
        List<Integer> ids = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            ids.add(first + i);
        }
        return ids;
    }

    /**
     * Config com EXATAMENTE {@code f} bizantinos sorteados no {@code universeIds} pelo gerador unico
     * ({@link #selectNested}). {@code totalNodes} e o numero de nos da rede (so para relatorio e conferencia);
     * pode ser maior que o universo (Casper: todos votam; Parlia: so os mineradores).
     */
    public static ByzantineConfig forUniverse(int totalNodes, List<Integer> universeIds, int f, String attackType,
            long randomSeed) {
        Set<Integer> ids = selectNested(randomSeed, universeIds, f);
        return new ByzantineConfig(totalNodes, ids, attackType, randomSeed);
    }

    /**
     * Config com EXATAMENTE {@code count} bizantinos entre {@code totalValidators}, para o caminho do HOTSTUFF
     * (ADR-0015 B-37/B-38). Universo {@code [0, totalValidators)} no gerador unico {@link #selectNested}: o conjunto de
     * {@code f} e prefixo do de {@code f+1} (aninhado) e a mesma semente da o mesmo conjunto. Os mesmos conjuntos de
     * antes da unificacao (testes de pino em {@code MVPComparisonHotStuffTest}).
     *
     * @throws IllegalArgumentException se {@code totalValidators < 1}, {@code count} fora de [0, totalValidators]
     *                                  ou o nome do ataque for invalido
     */
    public static ByzantineConfig withExactCount(int totalValidators, int count, String attackType, long randomSeed) {
        if (totalValidators < 1) {
            throw new IllegalArgumentException("totalValidators deve ser >= 1: " + totalValidators);
        }
        if (count < 0 || count > totalValidators) {
            throw new IllegalArgumentException("contagem bizantina fora de [0, " + totalValidators + "]: " + count);
        }
        return forUniverse(totalValidators, range(0, totalValidators), count, attackType, randomSeed);
    }
    
    /**
     * Check if a validator ID is Byzantine
     */
    public boolean isByzantine(int validatorId) {
        return byzantineValidatorIds.contains(validatorId);
    }
    
    // ===== GETTERS =====
    
    public Set<Integer> getByzantineValidatorIds() {
        return Collections.unmodifiableSet(byzantineValidatorIds);
    }
    
    public int getByzantineCount() {
        return byzantineValidatorIds.size();
    }
    
    public double getByzantinePercentage() {
        return byzantinePercentage;
    }
    
    public int getTotalValidators() {
        return totalValidators;
    }
    
    public String getAttackType() {
        return attackType;
    }
    
    public long getRandomSeed() {
        return randomSeed;
    }
    
    @Override
    public String toString() {
        return String.format("ByzantineConfig{validators=%d, byzantine=%.1f%% (%d nodes), " +
                           "attack=%s, seed=%d, threshold=%s, safetyMargin=%.2f%%}",
            totalValidators, byzantinePercentage, getByzantineCount(), attackType, randomSeed,
            "N/A", 0.0);
    }
}
