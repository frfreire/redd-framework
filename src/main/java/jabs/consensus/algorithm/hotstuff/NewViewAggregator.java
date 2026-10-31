package jabs.consensus.algorithm.hotstuff;

import jabs.ledgerdata.hotstuff.HotStuffQuorumCertificate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Armazenamento dos NEW_VIEW que o lider de uma visao recebeu (replica -> lider): por visao DE DESTINO, o prepareQC
 * (ou bottom) de cada remetente distinto e o maior deles (highQC, desempate: o primeiro recebido). SO ARMAZENA: quem
 * decide quando ha NEW_VIEW suficiente e propoe o PREPARE com o highQC e a parte 2c-3 (troca de visao).
 * Classe pura, sem rede; nao e thread-safe; {@link LinkedHashMap} em tudo que e iterado.
 */
public final class NewViewAggregator {
    private static final class Bucket {
        final Map<Integer, Optional<HotStuffQuorumCertificate>> bySender = new LinkedHashMap<>();
        Optional<HotStuffQuorumCertificate> high = Optional.empty();
    }

    private final Map<Long, Bucket> buckets = new LinkedHashMap<>();

    /**
     * @param targetView visao que o lider vai conduzir (&gt;= 0)
     * @param senderId id da replica remetente
     * @param prepareQc prepareQC da replica; vazio = bottom
     * @return {@code true} se contado; {@code false} se o remetente ja tinha enviado NEW_VIEW para esta visao
     */
    public boolean add(long targetView, int senderId, Optional<HotStuffQuorumCertificate> prepareQc) {
        if (targetView < 0 || prepareQc == null) {
            throw new IllegalArgumentException("targetView >= 0 e prepareQc (Optional) obrigatorios");
        }
        Bucket b = buckets.computeIfAbsent(targetView, k -> new Bucket());
        if (b.bySender.containsKey(senderId)) {
            return false;
        }
        b.bySender.put(senderId, prepareQc);
        if (prepareQc.isPresent() && (b.high.isEmpty() || prepareQc.get().getView() > b.high.get().getView())) {
            b.high = prepareQc;
        }
        return true;
    }

    /** Remetentes distintos de NEW_VIEW para a visao de destino. */
    public int count(long targetView) {
        Bucket b = buckets.get(targetView);
        return b == null ? 0 : b.bySender.size();
    }

    /** O maior prepareQC recebido para a visao de destino (vazio se nenhum remetente tinha QC). */
    public Optional<HotStuffQuorumCertificate> highQc(long targetView) {
        Bucket b = buckets.get(targetView);
        return b == null ? Optional.empty() : b.high;
    }
}
