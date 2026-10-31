package jabs.consensus.config;

/**
 * Configuracao do HotStuff (somente dados e funcoes puras; o algoritmo vem em outra tarefa).
 *
 * <p>Nada aqui e fixo no algoritmo: politica de lider, modo de assinatura, timeout de visao,
 * tamanho de lote e tamanhos em bytes sao parametros do {@link Builder}.
 *
 * <p>Parametros SEM base normativa nao tem padrao: quem constroi o config precisa escreve-los no
 * {@link Builder} (o {@code build()} falha com {@link IllegalStateException} se faltar algum). Sao eles:
 * <ul>
 *   <li>{@code viewTimeoutSeconds}: a definir com o humano (o artigo nao fixa valor).</li>
 *   <li>{@code batchSize}: derivavel do spec oficial por {@link #batchSizeFor(int, int)}
 *       (.experimento/bateria1.yaml: tamanho_bloco_kb=1024, tamanho_tx_bytes=512 -&gt; 2048);
 *       o valor final continua sendo escolha explicita de quem constroi.</li>
 *   <li>{@code individualSignatureSizeBytes}, {@code aggregatedSignatureSizeBytes},
 *       {@code aggregatedShareSizeBytes}: suposicoes (ex.: 64 B ECDSA/Ed25519, 96 B BLS12-381 G2),
 *       a definir com o humano para o esquema efetivo do Fabric; nao derivam do spec.
 *       Esses tamanhos cobrem so a ASSINATURA; os identificadores dos votantes de um QC sao contados a parte
 *       (ver {@code voterEncoding}) e valem igualmente nos dois modos de assinatura.</li>
 *   <li>{@code voterEncoding}: codificacao dos identificadores dos votantes num QC. A definir com o humano
 *       (codificacao dos votantes): so {@link VoterEncoding#BITMAP} esta implementado (decisao humana desta
 *       rodada: {@code ceil(n/8)} bytes em INDIVIDUAL e em AGGREGATED, de modo que a vantagem do agregado fique
 *       somente na assinatura). O enum existe para admitir lista de ids depois sem refatorar.</li>
 * </ul>
 *
 * <p>Padroes que TEM base e continuam:
 * <ul>
 *   <li>{@code leaderPolicy = ROTATING}: HotStuff do artigo (Yin et al., PODC 2019) troca o lider a cada visao.
 *       {@code STABLE} e a ablacao do protocolo experimental (.experimento/0003).</li>
 *   <li>{@code signatureMode = INDIVIDUAL}: coerente com {@code esquema_assinatura: individual} das baterias.</li>
 *   <li>{@code messageOverheadBytes = 10}: mesmo overhead de cabecalho do voto PBFT ({@code PBFT_VOTE_SIZE_OVERHEAD}).</li>
 * </ul>
 */
public final class HotStuffConsensusConfig implements ConsensusAlgorithmConfig {

    /** Numero minimo de nos: n = 3f+1 com f &gt;= 1. */
    public static final int MIN_NODES = 4;

    public enum LeaderPolicy { ROTATING, STABLE }

    public enum SignatureMode { INDIVIDUAL, AGGREGATED }

    /**
     * Codificacao dos identificadores dos votantes dentro de um QC. A definir com o humano (codificacao dos
     * votantes): a escolha e suposicao, nao deriva de norma nem do spec.
     */
    public enum VoterEncoding {
        /** Um bit por no da rede: {@code ceil(n/8)} bytes, independente de quantos votaram. */
        BITMAP
    }

    public static final LeaderPolicy DEFAULT_LEADER_POLICY = LeaderPolicy.ROTATING;
    public static final SignatureMode DEFAULT_SIGNATURE_MODE = SignatureMode.INDIVIDUAL;
    public static final int DEFAULT_MESSAGE_OVERHEAD_BYTES = 10;

    private final LeaderPolicy leaderPolicy;
    private final SignatureMode signatureMode;
    private final VoterEncoding voterEncoding;
    private final double viewTimeoutSeconds;
    private final int batchSize;
    private final int individualSignatureSizeBytes;
    private final int aggregatedSignatureSizeBytes;
    private final int aggregatedShareSizeBytes;
    private final int messageOverheadBytes;

    private HotStuffConsensusConfig(Builder b) {
        if (b.leaderPolicy == null || b.signatureMode == null) {
            throw new IllegalArgumentException("leaderPolicy e signatureMode sao obrigatorios");
        }
        requireSet(b.voterEncoding, "voterEncoding (a definir com o humano: codificacao dos votantes)");
        requireSet(b.viewTimeoutSeconds, "viewTimeoutSeconds");
        requireSet(b.batchSize, "batchSize (use batchSizeFor(blockSizeKb, txSizeBytes))");
        requireSet(b.individualSignatureSizeBytes, "individualSignatureSizeBytes");
        requireSet(b.aggregatedSignatureSizeBytes, "aggregatedSignatureSizeBytes");
        requireSet(b.aggregatedShareSizeBytes, "aggregatedShareSizeBytes");
        if (!(b.viewTimeoutSeconds > 0.0) || Double.isInfinite(b.viewTimeoutSeconds)) {
            throw new IllegalArgumentException("viewTimeoutSeconds deve ser finito e > 0: " + b.viewTimeoutSeconds);
        }
        if (b.batchSize <= 0) {
            throw new IllegalArgumentException("batchSize deve ser > 0: " + b.batchSize);
        }
        if (b.individualSignatureSizeBytes <= 0 || b.aggregatedSignatureSizeBytes <= 0
                || b.aggregatedShareSizeBytes <= 0) {
            throw new IllegalArgumentException("tamanhos de assinatura devem ser > 0");
        }
        if (b.messageOverheadBytes < 0) {
            throw new IllegalArgumentException("messageOverheadBytes deve ser >= 0: " + b.messageOverheadBytes);
        }
        this.leaderPolicy = b.leaderPolicy;
        this.signatureMode = b.signatureMode;
        this.voterEncoding = b.voterEncoding;
        this.viewTimeoutSeconds = b.viewTimeoutSeconds;
        this.batchSize = b.batchSize;
        this.individualSignatureSizeBytes = b.individualSignatureSizeBytes;
        this.aggregatedSignatureSizeBytes = b.aggregatedSignatureSizeBytes;
        this.aggregatedShareSizeBytes = b.aggregatedShareSizeBytes;
        this.messageOverheadBytes = b.messageOverheadBytes;
    }

    public static Builder builder() {
        return new Builder();
    }

    private static void requireSet(Object value, String name) {
        if (value == null) {
            throw new IllegalStateException(name + " e obrigatorio e nao tem padrao (a definir com o humano)");
        }
    }

    /**
     * Transacoes que cabem em um bloco: {@code floor(blockSizeKb * 1024 / txSizeBytes)}.
     * Com o spec oficial (.experimento/bateria1.yaml: 1024 KB, 512 B) resulta em 2048.
     *
     * @param blockSizeKb tamanho do bloco em KB (&gt; 0)
     * @param txSizeBytes tamanho da transacao em bytes (&gt; 0)
     * @return lote em transacoes (&gt;= 1)
     * @throws IllegalArgumentException se algum argumento for &lt;= 0 ou a tx nao couber no bloco
     */
    public static int batchSizeFor(int blockSizeKb, int txSizeBytes) {
        if (blockSizeKb <= 0 || txSizeBytes <= 0) {
            throw new IllegalArgumentException("blockSizeKb e txSizeBytes devem ser > 0: "
                    + blockSizeKb + ", " + txSizeBytes);
        }
        long batch = (long) blockSizeKb * 1024L / txSizeBytes;
        if (batch < 1) {
            throw new IllegalArgumentException("transacao maior que o bloco: " + txSizeBytes + " B > " + blockSizeKb + " KB");
        }
        return (int) batch;
    }

    // ---------- funcoes puras (testaveis sem simulador) ----------

    /** f = floor((n-1)/3). Exige n &gt;= 4. */
    public static int maxFaultyNodes(int n) {
        requireValidN(n);
        return (n - 1) / 3;
    }

    /** Quorum teorico 2f+1, f = floor((n-1)/3). Exige n &gt;= 4. Comparar sempre com {@code >=}. */
    public static int quorumSize(int n) {
        return 2 * maxFaultyNodes(n) + 1;
    }

    /**
     * Lider da visao, deterministico. ROTATING: {@code view mod n}; STABLE: sempre o no 0.
     *
     * @param view visao (&gt;= 0)
     * @param n numero de nos (&gt;= 4)
     * @param policy politica de lideranca
     * @return indice do lider em [0, n)
     */
    public static int leaderFor(long view, int n, LeaderPolicy policy) {
        requireValidN(n);
        if (view < 0) {
            throw new IllegalArgumentException("view deve ser >= 0: " + view);
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy obrigatoria");
        }
        return policy == LeaderPolicy.ROTATING ? (int) (view % n) : 0;
    }

    private static void requireValidN(int n) {
        if (n < MIN_NODES) {
            throw new IllegalArgumentException("HotStuff exige n >= " + MIN_NODES + ", recebido n=" + n);
        }
    }

    /** Valida a configuracao contra o tamanho da rede (n &gt;= 4). */
    public void validateFor(int n) {
        requireValidN(n);
    }

    // ---------- tamanhos em bytes ----------

    /** Bytes de assinatura carregados por UM voto (replica para lider) no modo dado. */
    public int voteSignatureSize(SignatureMode mode) {
        return mode == SignatureMode.INDIVIDUAL ? individualSignatureSizeBytes : aggregatedShareSizeBytes;
    }

    /**
     * Bytes de assinatura de um QC com {@code signerCount} signatarios.
     * INDIVIDUAL: soma das assinaturas ({@code signerCount * individual}); AGGREGATED: constante.
     */
    public long qcSignatureSize(SignatureMode mode, int signerCount) {
        if (signerCount <= 0) {
            throw new IllegalArgumentException("signerCount deve ser > 0: " + signerCount);
        }
        return mode == SignatureMode.INDIVIDUAL
                ? (long) signerCount * individualSignatureSizeBytes
                : aggregatedSignatureSizeBytes;
    }

    /**
     * Bytes dos identificadores dos votantes de um QC numa rede de {@code n} nos, na codificacao configurada.
     * BITMAP: {@code ceil(n/8)}, igual em INDIVIDUAL e AGGREGATED (a codificacao e suposicao: a definir com o
     * humano, codificacao dos votantes).
     *
     * @param n numero de nos da rede (&gt;= 4)
     * @return bytes dos identificadores
     */
    public int voterIdentifiersSize(int n) {
        requireValidN(n);
        switch (voterEncoding) {
            case BITMAP:
                return (int) ((n + 7L) / 8L);
            default:
                throw new IllegalStateException("VoterEncoding nao implementado: " + voterEncoding);
        }
    }

    // ---------- acessores ----------

    public LeaderPolicy getLeaderPolicy() { return leaderPolicy; }
    public SignatureMode getSignatureMode() { return signatureMode; }
    public VoterEncoding getVoterEncoding() { return voterEncoding; }
    public double getViewTimeoutSeconds() { return viewTimeoutSeconds; }
    public int getBatchSize() { return batchSize; }
    public int getIndividualSignatureSizeBytes() { return individualSignatureSizeBytes; }
    public int getAggregatedSignatureSizeBytes() { return aggregatedSignatureSizeBytes; }
    public int getAggregatedShareSizeBytes() { return aggregatedShareSizeBytes; }
    public int getMessageOverheadBytes() { return messageOverheadBytes; }

    public static final class Builder {
        private LeaderPolicy leaderPolicy = DEFAULT_LEADER_POLICY;
        private SignatureMode signatureMode = DEFAULT_SIGNATURE_MODE;
        private VoterEncoding voterEncoding; // sem padrao: a definir com o humano
        private Double viewTimeoutSeconds;
        private Integer batchSize;
        private Integer individualSignatureSizeBytes;
        private Integer aggregatedSignatureSizeBytes;
        private Integer aggregatedShareSizeBytes;
        private int messageOverheadBytes = DEFAULT_MESSAGE_OVERHEAD_BYTES;

        private Builder() { }

        public Builder leaderPolicy(LeaderPolicy v) { this.leaderPolicy = v; return this; }
        public Builder signatureMode(SignatureMode v) { this.signatureMode = v; return this; }
        public Builder voterEncoding(VoterEncoding v) { this.voterEncoding = v; return this; }
        public Builder viewTimeoutSeconds(double v) { this.viewTimeoutSeconds = v; return this; }
        public Builder batchSize(int v) { this.batchSize = v; return this; }
        public Builder individualSignatureSizeBytes(int v) { this.individualSignatureSizeBytes = v; return this; }
        public Builder aggregatedSignatureSizeBytes(int v) { this.aggregatedSignatureSizeBytes = v; return this; }
        public Builder aggregatedShareSizeBytes(int v) { this.aggregatedShareSizeBytes = v; return this; }
        public Builder messageOverheadBytes(int v) { this.messageOverheadBytes = v; return this; }

        /**
         * Constroi a configuracao.
         *
         * @return configuracao imutavel
         * @throws IllegalStateException se timeout de visao, lote, tamanhos de assinatura ou codificacao dos votantes nao foram informados
         * @throws IllegalArgumentException se algum valor for invalido
         */
        public HotStuffConsensusConfig build() {
            return new HotStuffConsensusConfig(this);
        }
    }
}
