package jabs.config;

import jabs.simulator.randengine.RandomnessEngine;

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
     * Randomly select Byzantine validators based on percentage
     */
    private void selectByzantineValidators() {
        int byzantineCount = (int) Math.ceil(totalValidators * (byzantinePercentage / 100.0));
        Random random = new Random(randomSeed);
        
        while (byzantineValidatorIds.size() < byzantineCount) {
            int validatorId = random.nextInt(totalValidators);
            byzantineValidatorIds.add(validatorId);
        }
    }

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
     * Config com EXATAMENTE {@code count} bizantinos entre {@code totalValidators}, para o caminho do HOTSTUFF
     * (ADR-0015 B-37/B-38). Sorteio: {@code nextInt(totalValidators)} no {@link #byzantineStream(long)} da semente
     * ate o conjunto ter {@code count} ids; para a mesma semente o conjunto de {@code f} e prefixo do de {@code f+1}
     * (aninhado entre valores de f) e a mesma semente da o mesmo conjunto. NAO usa {@code java.util.Random} e NAO e
     * o laco do construtor por percentual (que segue intacto, com {@code java.util.Random}, para nao mudar o
     * conjunto de ARQ/Parlia/Casper): os dois caminhos NAO produzem o mesmo conjunto para a mesma semente.
     *
     * <p>A regra de arredondamento de fracao para contagem (ADR-0015 B-33/Q34) segue {@code a definir com o humano}
     * e nao e decidida aqui; o universo do sorteio (todos os nos x so validadores, B-37/Q30) tambem: aqui e
     * {@code [0, totalValidators)}.
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
        RandomnessEngine stream = byzantineStream(randomSeed);
        Set<Integer> ids = new HashSet<>();
        while (ids.size() < count) {
            ids.add(stream.nextInt(totalValidators));
        }
        return new ByzantineConfig(totalValidators, ids, attackType, randomSeed);
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
