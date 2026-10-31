package jabs.consensus.algorithm.hotstuff;

import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffQuorumCertificate;
import jabs.ledgerdata.hotstuff.HotStuffVote;

import java.util.Optional;

/**
 * Estado de decisao de UMA replica no HotStuff basico (Yin et al., PODC 2019, Algoritmo 2): visao atual,
 * {@code prepareQC} (o "highQC" que a replica conhece), {@code lockedQC} e o bloco finalizado mais alto. Classe
 * pura: sem rede, sem simulador, sem estado global. NAO e thread-safe (o simulador e de thread unica; cada
 * replica tem o seu estado).
 *
 * <p>QC ausente e {@link Optional#empty()} (o simbolo "bottom" do artigo), nunca {@code null}. O parametro de tipo
 * {@code B} e o tipo do bloco finalizado (hoje {@link HotStuffBlock} ou {@code HotStuffBlockWithTx}); os QCs usam
 * {@link HotStuffBlock} porque {@link HotStuffQuorumCertificate} nao e generico.
 *
 * <h3>Invariantes impostas pelos mutadores</h3>
 * <ul>
 *   <li><b>visao</b> nunca regride: {@link #advanceView} com visao menor lanca {@link IllegalStateException};
 *       visao igual e no-op permitido.</li>
 *   <li><b>prepareQC</b> so avanca em visao: o novo QC deve ser de fase PREPARE e ter visao ESTRITAMENTE maior que
 *       a do atual (se houver); visao igual ou menor lanca {@link IllegalStateException}. Quem recebe QC repetido
 *       deve consultar {@link #wouldAdvancePrepareQc} antes (duplicata nao e erro de protocolo, so nao e
 *       atualizacao).</li>
 *   <li><b>lockedQC</b> nunca regride em visao: o novo QC deve ser de fase PRE_COMMIT e ter visao maior ou igual
 *       a do atual; se a visao for igual, o bloco deve ser a MESMA referencia (dois travamentos distintos na mesma
 *       visao seriam violacao de seguranca) e a chamada e no-op. Menor lanca {@link IllegalStateException}.</li>
 *   <li><b>bloco finalizado</b> nunca regride em altura: altura menor lanca {@link IllegalStateException}; a mesma
 *       altura exige a mesma referencia (dois blocos finalizados na mesma altura seriam violacao de seguranca).</li>
 *   <li>Argumento nulo, visao negativa ou fase errada lancam {@link IllegalArgumentException}.</li>
 * </ul>
 * Esta classe NAO confere que o QC pertence a visao atual nem que a replica esteja na fase certa: isso e do
 * algoritmo (2c-2). Um QC de visao maior que {@code viewAtual} e aceito (a replica pode estar atrasada).
 *
 * <p><b>A conferir com o artigo</b> (a regra de QUANDO chamar cada mutador e do algoritmo; aqui so se garante a
 * monotonicidade):
 * <ul>
 *   <li>{@code prepareQC} e atualizado ao receber a proposta PRE_COMMIT (carrega o prepareQC formado);</li>
 *   <li>{@code lockedQC} e atualizado ao receber a proposta COMMIT (carrega o precommitQC formado);</li>
 *   <li>o bloco e finalizado ao receber DECIDE (carrega o commitQC);</li>
 *   <li>que a visao avanca por timeout/NEW_VIEW e que o lider da nova visao usa o maior prepareQC dos NEW_VIEW
 *       (highQC) na proposta PREPARE;</li>
 *   <li>que "estritamente maior" para o prepareQC e "maior ou igual" para o lockedQC e a leitura certa de
 *       "so avanca" / "nao regride" (o artigo atualiza para o QC recebido sem exigir comparacao; as checagens
 *       aqui sao defesa adicional).</li>
 * </ul>
 *
 * @param <B> tipo do bloco finalizado
 */
public final class ReplicaState<B extends HotStuffBlock> {

    private long currentView;
    private Optional<HotStuffQuorumCertificate> prepareQc = Optional.empty();
    private Optional<HotStuffQuorumCertificate> lockedQc = Optional.empty();
    private Optional<B> highestFinalized = Optional.empty();

    /**
     * Cria o estado inicial: sem prepareQC, sem lockedQC, nada finalizado.
     *
     * @param initialView visao inicial (&gt;= 0)
     * @throws IllegalArgumentException se {@code initialView} &lt; 0
     */
    public ReplicaState(long initialView) {
        requireViewNonNegative(initialView);
        this.currentView = initialView;
    }

    /** Visao em que a replica esta. */
    public long getCurrentView() {
        return currentView;
    }

    /** {@code prepareQC} (highQC) conhecido; vazio = bottom. */
    public Optional<HotStuffQuorumCertificate> getPrepareQc() {
        return prepareQc;
    }

    /** {@code lockedQC}; vazio = bottom (sem travamento). */
    public Optional<HotStuffQuorumCertificate> getLockedQc() {
        return lockedQc;
    }

    /** Bloco finalizado de maior altura; vazio = nada finalizado. */
    public Optional<B> getHighestFinalized() {
        return highestFinalized;
    }

    /**
     * Avanca a visao.
     *
     * @param newView nova visao (&gt;= visao atual)
     * @throws IllegalArgumentException se {@code newView} &lt; 0
     * @throws IllegalStateException se {@code newView} &lt; visao atual (regressao)
     */
    public void advanceView(long newView) {
        requireViewNonNegative(newView);
        if (newView < currentView) {
            throw new IllegalStateException("a visao nao regride: atual=" + currentView + ", pedida=" + newView);
        }
        currentView = newView;
    }

    /**
     * Diz se {@link #updatePrepareQc} aceitaria este QC (fase PREPARE e visao estritamente maior que a do atual).
     *
     * @param qc QC candidato (nao nulo)
     * @return {@code true} se atualizaria
     * @throws IllegalArgumentException se {@code qc} for nulo
     */
    public boolean wouldAdvancePrepareQc(HotStuffQuorumCertificate qc) {
        requireQc(qc);
        return qc.getPhase() == HotStuffVote.Phase.PREPARE
                && (prepareQc.isEmpty() || qc.getView() > prepareQc.get().getView());
    }

    /**
     * Atualiza o {@code prepareQC}.
     *
     * @param qc QC de fase PREPARE com visao maior que a do atual
     * @throws IllegalArgumentException se {@code qc} for nulo ou nao for de fase PREPARE
     * @throws IllegalStateException se a visao de {@code qc} nao for estritamente maior que a do atual
     */
    public void updatePrepareQc(HotStuffQuorumCertificate qc) {
        requireQc(qc);
        requirePhase(qc, HotStuffVote.Phase.PREPARE, "prepareQC");
        if (prepareQc.isPresent() && qc.getView() <= prepareQc.get().getView()) {
            throw new IllegalStateException("prepareQC so avanca em visao: atual=" + prepareQc.get().getView()
                    + ", novo=" + qc.getView());
        }
        prepareQc = Optional.of(qc);
    }

    /**
     * Atualiza o {@code lockedQC}.
     *
     * @param qc QC de fase PRE_COMMIT com visao &gt;= a do atual (igual: mesmo bloco, no-op)
     * @throws IllegalArgumentException se {@code qc} for nulo ou nao for de fase PRE_COMMIT
     * @throws IllegalStateException se a visao regredir, ou se for igual com bloco diferente
     */
    public void updateLockedQc(HotStuffQuorumCertificate qc) {
        requireQc(qc);
        requirePhase(qc, HotStuffVote.Phase.PRE_COMMIT, "lockedQC");
        if (lockedQc.isPresent()) {
            HotStuffQuorumCertificate atual = lockedQc.get();
            if (qc.getView() < atual.getView()) {
                throw new IllegalStateException("lockedQC nao regride em visao: atual=" + atual.getView()
                        + ", novo=" + qc.getView());
            }
            if (qc.getView() == atual.getView()) {
                if (qc.getBlock() != atual.getBlock()) {
                    throw new IllegalStateException("dois lockedQC distintos na visao " + qc.getView()
                            + " (violacao de seguranca)");
                }
                return;
            }
        }
        lockedQc = Optional.of(qc);
    }

    /**
     * Registra um bloco finalizado (DECIDE).
     *
     * @param block bloco com altura &gt;= a do finalizado atual (igual: mesma referencia, no-op)
     * @throws IllegalArgumentException se {@code block} for nulo
     * @throws IllegalStateException se a altura regredir, ou se for igual com bloco diferente
     */
    public void finalizeBlock(B block) {
        if (block == null) {
            throw new IllegalArgumentException("block obrigatorio");
        }
        if (highestFinalized.isPresent()) {
            B atual = highestFinalized.get();
            if (block.getHeight() < atual.getHeight()) {
                throw new IllegalStateException("bloco finalizado nao regride em altura: atual="
                        + atual.getHeight() + ", novo=" + block.getHeight());
            }
            if (block.getHeight() == atual.getHeight()) {
                if (block != atual) {
                    throw new IllegalStateException("dois blocos finalizados na altura " + block.getHeight()
                            + " (violacao de seguranca)");
                }
                return;
            }
        }
        highestFinalized = Optional.of(block);
    }

    private static void requireViewNonNegative(long view) {
        if (view < 0) {
            throw new IllegalArgumentException("view deve ser >= 0: " + view);
        }
    }

    private static void requireQc(HotStuffQuorumCertificate qc) {
        if (qc == null) {
            throw new IllegalArgumentException("qc obrigatorio (use Optional.empty para bottom: nao ha mutador que o escreva)");
        }
    }

    private static void requirePhase(HotStuffQuorumCertificate qc, HotStuffVote.Phase esperada, String nome) {
        if (qc.getPhase() != esperada) {
            throw new IllegalArgumentException(nome + " exige QC de fase " + esperada + ", recebido " + qc.getPhase());
        }
    }
}
