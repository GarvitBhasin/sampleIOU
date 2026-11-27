package splitter.server.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Calculates exact shares in cents using integer arithmetic only.
 */
public final class SplitCalculator {
    public SplitCalculator() {}

    /**
     * Computes the shares in cents for each member based on total cents and weights in hundredths.
     * Weights must be provided in member list order and sum to 100.
     *
     * @param totalCents total amount in cents
     * @param weightsHundredths map of member names to weights in hundredths (0 to 100)
     * @return map of member names to share in cents in the same order
     */
    public Map<String, Long> computeShares(long totalCents, Map<String, Integer> weightsHundredths) {
        Objects.requireNonNull(weightsHundredths, "weights cannot be null");
        Map<String, Long> shares = new LinkedHashMap<>();

        long allocated = 0;
        for (Map.Entry<String, Integer> entry : weightsHundredths.entrySet()) {
            Integer suppliedWeight = entry.getValue();
            if (suppliedWeight == null || suppliedWeight < 0 || suppliedWeight > 100) {
                throw new IllegalArgumentException("Every weight must be between 0 and 100 hundredths");
            }
            // Split before multiplying so large valid totals do not overflow in the
            // intermediate totalCents * weight calculation.
            long wholePart = Math.multiplyExact(totalCents / 100L, suppliedWeight.longValue());
            long fractionalPart = (totalCents % 100L) * suppliedWeight.longValue() / 100L;
            long share = Math.addExact(wholePart, fractionalPart);
            shares.put(entry.getKey(), share);
            allocated = Math.addExact(allocated, share);
        }

        long remainder = Math.subtractExact(totalCents, allocated);
        if (remainder > 0) {
            for (Map.Entry<String, Integer> entry : weightsHundredths.entrySet()) {
                if (remainder <= 0) {
                    break;
                }
                int weight = entry.getValue() != null ? entry.getValue() : 0;
                if (weight > 0) {
                    shares.put(entry.getKey(), shares.get(entry.getKey()) + 1L);
                    remainder--;
                }
            }
        }

        return shares;
    }
}
