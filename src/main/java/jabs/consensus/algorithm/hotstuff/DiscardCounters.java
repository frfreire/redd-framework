package jabs.consensus.algorithm.hotstuff;

import java.util.EnumMap;
import java.util.Map;

/** Contadores de mensagens ignoradas, por {@link DiscardReason}. Zero e zero contado. Nao e thread-safe. */
public final class DiscardCounters {
    private final Map<DiscardReason, Long> counts = new EnumMap<>(DiscardReason.class);

    /** Registra um descarte do motivo dado. */
    public void count(DiscardReason reason) {
        if (reason == null) {
            throw new IllegalArgumentException("reason obrigatorio");
        }
        counts.merge(reason, 1L, Long::sum);
    }

    public long get(DiscardReason reason) {
        if (reason == null) {
            throw new IllegalArgumentException("reason obrigatorio");
        }
        return counts.getOrDefault(reason, 0L);
    }

    public long total() {
        long sum = 0L;
        for (DiscardReason r : DiscardReason.values()) {
            sum += get(r);
        }
        return sum;
    }
}
