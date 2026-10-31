package jabs.consensus.algorithm.hotstuff;

import jabs.ledgerdata.hotstuff.HotStuffBlock;
import jabs.ledgerdata.hotstuff.HotStuffQuorumCertificate;

import java.util.Optional;

/**
 * Regra de seguranca {@code safeNode} do HotStuff basico (Yin et al., PODC 2019, Algoritmo 1/2): a replica so vota
 * numa proposta se
 * <pre>
 *   proposta estende lockedQC.node   OU   proposalQc.view &gt; lockedQC.view
 * </pre>
 * O primeiro termo e a regra de SEGURANCA (nao trair o bloco travado); o segundo, a de VIVACIDADE (um QC mais novo
 * que o travamento prova que um quorum ja largou o bloco travado). Sem {@code lockedQC} (replica sem travamento) a
 * proposta e sempre segura.
 *
 * <p><b>Como "estende" e calculado, sem {@code getParents} mutavel.</b> Sobe-se da proposta pela
 * {@link ParentLookup} (arvore local fornecida por quem chama) enquanto a altura do bloco corrente for maior que a
 * do bloco travado ({@code getHeight()}, campo {@code final}); estende se e somente se a subida parar exatamente na
 * altura do bloco travado EM CIMA DA MESMA REFERENCIA (identidade: {@code HotStuffBlock} nao define
 * {@code equals/hashCode}). Consequencias: (a) um bloco estende a si mesmo (relacao reflexiva, como no artigo,
 * "extends" inclui o proprio no); (b) proposta de altura menor que a do travado nao estende; (c) pai desconhecido
 * na arvore local entao nao estende (conservador: nunca vota por falta de informacao, so perde vivacidade).
 *
 * <p><b>A conferir com o artigo:</b>
 * <ul>
 *   <li>que a visao do QC da proposta e comparada com {@code lockedQC.view} com {@code >} estrito (artigo,
 *       alg. 1, {@code safeNode});</li>
 *   <li>que, na primeira visao (proposta sem QC), so vale o termo de extensao: aqui {@code proposalQc} vazio =&gt;
 *       a regra de vivacidade nao se aplica;</li>
 *   <li>que o artigo compara o QC da proposta, e nao o QC de fase especifica; esta classe nao confere a fase de
 *       {@code proposalQc} (isso e validacao de mensagem, da camada de rede/2c-2).</li>
 * </ul>
 *
 * <p>Classe utilitaria pura, sem estado.
 */
public final class SafetyRule {

    private SafetyRule() { }

    /**
     * {@code safeNode(proposedBlock, proposalQc, lockedQc)}.
     *
     * @param proposedBlock bloco da proposta (nao nulo)
     * @param proposalQc QC que a proposta carrega (vazio = nenhum, p.ex. primeira visao)
     * @param lockedQc QC de travamento da replica (vazio = sem travamento)
     * @param tree arvore local, para calcular a ascendencia sem {@code getParents}
     * @return {@code true} se a replica pode votar na proposta
     * @throws IllegalArgumentException se algum argumento for nulo, ou se {@code tree} devolver um pai de altura
     *                                  nao menor que a do filho
     */
    public static boolean safeNode(HotStuffBlock proposedBlock, Optional<HotStuffQuorumCertificate> proposalQc,
                                   Optional<HotStuffQuorumCertificate> lockedQc, ParentLookup tree) {
        if (proposedBlock == null || proposalQc == null || lockedQc == null || tree == null) {
            throw new IllegalArgumentException("proposedBlock, proposalQc, lockedQc e tree sao obrigatorios");
        }
        if (lockedQc.isEmpty()) {
            return true;
        }
        HotStuffQuorumCertificate locked = lockedQc.get();
        if (extendsBlock(proposedBlock, locked.getBlock(), tree)) {
            return true;
        }
        return proposalQc.isPresent() && proposalQc.get().getView() > locked.getView();
    }

    /**
     * {@code candidate} estende {@code ancestor} (inclusive: um bloco estende a si mesmo)?
     *
     * @param candidate bloco de cima (nao nulo)
     * @param ancestor bloco de baixo (nao nulo)
     * @param tree arvore local (nao nula)
     * @return {@code true} se {@code ancestor} esta na cadeia de ascendentes de {@code candidate} (ou e ele)
     * @throws IllegalArgumentException se algum argumento for nulo ou a arvore devolver pai de altura nao menor
     */
    public static boolean extendsBlock(HotStuffBlock candidate, HotStuffBlock ancestor, ParentLookup tree) {
        if (candidate == null || ancestor == null || tree == null) {
            throw new IllegalArgumentException("candidate, ancestor e tree sao obrigatorios");
        }
        HotStuffBlock current = candidate;
        while (current.getHeight() > ancestor.getHeight()) {
            Optional<HotStuffBlock> parent = tree.parentOf(current);
            if (parent == null) {
                throw new IllegalArgumentException("ParentLookup devolveu null (use Optional.empty)");
            }
            if (parent.isEmpty()) {
                return false;
            }
            if (parent.get().getHeight() >= current.getHeight()) {
                throw new IllegalArgumentException("pai com altura " + parent.get().getHeight()
                        + " nao menor que a do filho " + current.getHeight());
            }
            current = parent.get();
        }
        return current == ancestor;
    }
}
