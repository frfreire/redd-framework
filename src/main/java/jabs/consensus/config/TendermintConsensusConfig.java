package jabs.consensus.config;

import jabs.consensus.algorithm.tendermint.TendermintTimeouts;

/**
 * Configuracao do adaptador JABS do Tendermint. Somente dados e funcoes puras: o nucleo do protocolo
 * ({@link jabs.consensus.algorithm.tendermint.TendermintCore}) nao conhece simulador, rede nem config.
 *
 * <p><b>Sem padroes para parametros de pesquisa.</b> {@code timeouts}, {@code batchSize}, {@code txSizeBytes},
 * {@code messageOverheadBytes}, {@code signatureSizeBytes} e {@code proposerSignatureSizeBytes} precisam ser escritos por quem constroi (ADR-0002: valores
 * de latencia vem da pre-registracao). Nenhum valor numerico de pesquisa e embutido nesta classe.
 *
 * <p><b>Overhead de mensagem.</b> {@code messageOverheadBytes} compoe o tamanho das mensagens de consenso (s_v) e e
 * parametro de pesquisa OBRIGATORIO, sem valor padrao: {@code build()} falha se faltar e rejeita negativo. O valor
 * e <b>a definir com o humano</b> (ADR-0010 PI-08); o 10 B usado em PBFT/HotStuff nao e decisao normativa para o
 * Tendermint. Zero e ADMITIDO, mas somente se informado explicitamente (significa "sem cabecalho", escolha
 * declarada de quem constroi, nunca um padrao silencioso).
 *
 * <p><b>Assinatura do voto.</b> {@code signatureSizeBytes} e parametro de pesquisa OBRIGATORIO, sem valor padrao
 * (mesmo padrao do {@link HotStuffConsensusConfig}): {@code build()} falha se faltar e rejeita {@code <= 0}. Zero
 * ("voto sem assinatura") NAO e admitido: um voto Tendermint real e assinado e aceitar 0 reintroduziria um padrao
 * silencioso. O tamanho da assinatura e o esquema que o define sao <b>a definir com o humano</b> (ADR-0010 PI-08 e
 * PI-16); nenhum valor numerico e embutido aqui. O tamanho entra no voto (ver
 * {@link jabs.network.message.TendermintVoteMessage}).
 *
 * <p><b>Assinatura do proponente.</b> No Tendermint a proposta tambem e assinada (pelo proponente da rodada).
 * {@code proposerSignatureSizeBytes} e parametro de pesquisa OBRIGATORIO, sem valor padrao, <b>SEPARADO</b> de
 * {@code signatureSizeBytes}: um esquema de assinatura pode diferir entre proposta e voto, e reutilizar o valor do
 * voto na proposta seria um padrao implicito. {@code build()} falha com {@link IllegalStateException} se faltar e com
 * {@link IllegalArgumentException} se {@code <= 0}. O valor e <b>a definir com o humano</b> (ADR-0010 PI-08/PI-16).
 * O tamanho entra na proposta (ver {@link jabs.network.message.TendermintProposalMessage}) e no contador de
 * consenso (nao na carga do bloco).
 *
 * <p><b>Tamanho do bloco.</b> O adaptador amostra um lote cheio: {@code batchSize} transacoes de
 * {@code txSizeBytes}. {@link #batchSizeFor(int, int)} deriva o lote de um tamanho de bloco em KB, como no
 * {@link HotStuffConsensusConfig}.
 */
public final class TendermintConsensusConfig implements ConsensusAlgorithmConfig {

    /** Numero minimo de validadores: n = 3f+1 com f &gt;= 1. */
    public static final int MIN_NODES = 4;

    private final TendermintTimeouts timeouts;
    private final int batchSize;
    private final int txSizeBytes;
    private final int messageOverheadBytes;
    private final int signatureSizeBytes;
    private final int proposerSignatureSizeBytes;

    private TendermintConsensusConfig(Builder b) {
        if (b.timeouts == null) {
            throw new IllegalStateException("timeouts e obrigatorio e nao tem padrao (a definir com o humano)");
        }
        if (b.batchSize == null) {
            throw new IllegalStateException("batchSize e obrigatorio e nao tem padrao"
                    + " (use batchSizeFor(blockSizeKb, txSizeBytes))");
        }
        if (b.txSizeBytes == null) {
            throw new IllegalStateException("txSizeBytes e obrigatorio e nao tem padrao");
        }
        if (b.signatureSizeBytes == null) {
            throw new IllegalStateException("signatureSizeBytes e obrigatorio e nao tem padrao"
                    + " (a definir com o humano; ADR-0010 PI-08/PI-16)");
        }
        if (b.signatureSizeBytes <= 0) {
            throw new IllegalArgumentException("signatureSizeBytes deve ser > 0 (0 nao e admitido): "
                    + b.signatureSizeBytes);
        }
        if (b.proposerSignatureSizeBytes == null) {
            throw new IllegalStateException("proposerSignatureSizeBytes e obrigatorio e nao tem padrao"
                    + " (a definir com o humano; ADR-0010 PI-08/PI-16)");
        }
        if (b.proposerSignatureSizeBytes <= 0) {
            throw new IllegalArgumentException("proposerSignatureSizeBytes deve ser > 0 (0 nao e admitido): "
                    + b.proposerSignatureSizeBytes);
        }
        if (b.batchSize <= 0) {
            throw new IllegalArgumentException("batchSize deve ser > 0: " + b.batchSize);
        }
        if (b.txSizeBytes <= 0) {
            throw new IllegalArgumentException("txSizeBytes deve ser > 0: " + b.txSizeBytes);
        }
        if (b.messageOverheadBytes == null) {
            throw new IllegalStateException("messageOverheadBytes e obrigatorio e nao tem padrao"
                    + " (a definir com o humano; ADR-0010 PI-08)");
        }
        if (b.messageOverheadBytes < 0) {
            throw new IllegalArgumentException("messageOverheadBytes deve ser >= 0: " + b.messageOverheadBytes);
        }
        blockSizeBytes(b.batchSize, b.txSizeBytes);
        this.timeouts = b.timeouts;
        this.batchSize = b.batchSize;
        this.txSizeBytes = b.txSizeBytes;
        this.messageOverheadBytes = b.messageOverheadBytes;
        this.signatureSizeBytes = b.signatureSizeBytes;
        this.proposerSignatureSizeBytes = b.proposerSignatureSizeBytes;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Transacoes que cabem em um bloco: {@code floor(blockSizeKb * 1024 / txSizeBytes)}.
     *
     * @throws IllegalArgumentException se algum argumento for &lt;= 0 ou a transacao nao couber no bloco
     */
    public static int batchSizeFor(int blockSizeKb, int txSizeBytes) {
        if (blockSizeKb <= 0 || txSizeBytes <= 0) {
            throw new IllegalArgumentException("blockSizeKb e txSizeBytes devem ser > 0: "
                    + blockSizeKb + ", " + txSizeBytes);
        }
        long batch = (long) blockSizeKb * 1024L / txSizeBytes;
        if (batch < 1) {
            throw new IllegalArgumentException("transacao maior que o bloco: " + txSizeBytes + " B > "
                    + blockSizeKb + " KB");
        }
        return (int) batch;
    }

    /** Tamanho do lote cheio em bytes, {@code batchSize * txSizeBytes}. */
    public long blockSizeBytes() {
        return blockSizeBytes(batchSize, txSizeBytes);
    }

    private static long blockSizeBytes(int batchSize, int txSizeBytes) {
        long size = (long) batchSize * txSizeBytes;
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("tamanho do bloco excede int: " + size);
        }
        return size;
    }

    /** Valida a configuracao contra o tamanho da rede (n &gt;= 4). */
    public void validateFor(int n) {
        if (n < MIN_NODES) {
            throw new IllegalArgumentException("Tendermint exige n >= " + MIN_NODES + ", recebido n=" + n);
        }
    }

    public TendermintTimeouts getTimeouts() { return timeouts; }
    public int getBatchSize() { return batchSize; }
    public int getTxSizeBytes() { return txSizeBytes; }
    public int getMessageOverheadBytes() { return messageOverheadBytes; }
    /** Bytes de assinatura por voto; parametro de pesquisa sem padrao (ADR-0010 PI-08/PI-16). */
    public int getSignatureSizeBytes() { return signatureSizeBytes; }
    /** Bytes de assinatura do proponente por proposta; separado do voto; sem padrao (ADR-0010 PI-08/PI-16). */
    public int getProposerSignatureSizeBytes() { return proposerSignatureSizeBytes; }

    public static final class Builder {
        private TendermintTimeouts timeouts;
        private Integer batchSize;
        private Integer txSizeBytes;
        private Integer messageOverheadBytes;
        private Integer signatureSizeBytes;
        private Integer proposerSignatureSizeBytes;

        private Builder() { }

        public Builder timeouts(TendermintTimeouts v) { this.timeouts = v; return this; }
        public Builder batchSize(int v) { this.batchSize = v; return this; }
        public Builder txSizeBytes(int v) { this.txSizeBytes = v; return this; }
        /** Overhead de cabecalho por mensagem de consenso em bytes (&gt;= 0; 0 so se informado). Obrigatorio, sem padrao. */
        public Builder messageOverheadBytes(int v) { this.messageOverheadBytes = v; return this; }
        /** Tamanho da assinatura do voto em bytes (&gt; 0). Obrigatorio, sem padrao. */
        public Builder signatureSizeBytes(int v) { this.signatureSizeBytes = v; return this; }
        /** Tamanho da assinatura do proponente na proposta em bytes (&gt; 0), separado do voto. Obrigatorio, sem padrao. */
        public Builder proposerSignatureSizeBytes(int v) { this.proposerSignatureSizeBytes = v; return this; }

        /**
         * @throws IllegalStateException se timeouts, batchSize, txSizeBytes, messageOverheadBytes, signatureSizeBytes ou proposerSignatureSizeBytes nao foram informados
         * @throws IllegalArgumentException se algum valor for invalido
         */
        public TendermintConsensusConfig build() {
            return new TendermintConsensusConfig(this);
        }
    }
}
