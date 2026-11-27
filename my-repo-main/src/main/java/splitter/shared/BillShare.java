package splitter.shared;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** A share is attached to a specific membership, not merely a display name. */
public final class BillShare {
    private final int memberOrder;
    private final String memberName;
    private final int weightHundredths;
    private final long shareCents;
    private final boolean isConfirmed;
    private final Instant confirmedAt;

    /** Compatibility constructor for existing fixtures and older local files. */
    public BillShare(String memberName, int weightHundredths, long shareCents,
                     boolean isConfirmed, Instant confirmedAt) {
        this(-1, memberName, weightHundredths, shareCents, isConfirmed, confirmedAt);
    }

    public BillShare(int memberOrder, String memberName, int weightHundredths, long shareCents,
                     boolean isConfirmed, Instant confirmedAt) {
        this.memberOrder = memberOrder;
        this.memberName = Objects.requireNonNull(memberName, "memberName cannot be null");
        this.weightHundredths = weightHundredths;
        this.shareCents = shareCents;
        this.isConfirmed = isConfirmed;
        this.confirmedAt = confirmedAt;
    }

    public int getMemberOrder() { return memberOrder; }
    public String getMemberName() { return memberName; }
    public int getWeightHundredths() { return weightHundredths; }
    public long getShareCents() { return shareCents; }
    public boolean isConfirmed() { return isConfirmed; }
    public Optional<Instant> getConfirmedAt() { return Optional.ofNullable(confirmedAt); }
    public Instant getConfirmedAtInstant() { return confirmedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BillShare that)) return false;
        return memberOrder == that.memberOrder && weightHundredths == that.weightHundredths
                && shareCents == that.shareCents && isConfirmed == that.isConfirmed
                && memberName.equals(that.memberName) && Objects.equals(confirmedAt, that.confirmedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(memberOrder, memberName, weightHundredths, shareCents, isConfirmed, confirmedAt);
    }
}
