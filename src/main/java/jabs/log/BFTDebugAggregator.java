package jabs.log;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * Simple aggregator to collect BFT debug fragments per block and emit a single
 * consolidated line (with flush) when requested.
 */
public class BFTDebugAggregator {
    /**
     * When true the aggregator will actually print debug output. This can be
     * toggled at runtime by test code or a simple System property if desired.
     */
    public static boolean ENABLED = false;

    private static final ConcurrentHashMap<Integer, CopyOnWriteArrayList<String>> parts = new ConcurrentHashMap<>();

    public static void addPart(int blockHeight, String part) {
        if (!ENABLED) return;
        parts.computeIfAbsent(blockHeight, k -> new CopyOnWriteArrayList<>()).add(part);
    }

    /**
     * Consume and print the aggregated parts for a block in one line, then flush.
     */
    public static void emitAndClear(int blockHeight) {
        if (!ENABLED) return;
        List<String> list = parts.remove(blockHeight);
        if (list == null || list.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("[BFT-debug-AGG] block=").append(blockHeight).append(" : ");
        boolean first = true;
        for (String p : list) {
            if (!first) sb.append(" | ");
            sb.append(p);
            first = false;
        }
        System.out.println(sb.toString());
        System.out.flush();
    }
}
