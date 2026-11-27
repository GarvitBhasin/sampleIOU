package splitter.shared;

import java.time.Instant;
import java.util.Objects;

/** Public group membership data. Does not expose another member's username. */
public final class Member {
    private final int memberOrder;
    private final String name;
    private final boolean isOwner;
    private final boolean hasLeft;
    private final Instant joinedAt;

    /** Compatibility constructor for existing test fixtures. */
    public Member(String name, boolean isOwner, boolean hasLeft, Instant joinedAt) {
        this(0, name, isOwner, hasLeft, joinedAt);
    }

    public Member(int memberOrder, String name, boolean isOwner, boolean hasLeft, Instant joinedAt) {
        this.memberOrder = memberOrder;
        this.name = Objects.requireNonNull(name, "member name cannot be null");
        this.isOwner = isOwner;
        this.hasLeft = hasLeft;
        this.joinedAt = Objects.requireNonNull(joinedAt, "joinedAt cannot be null");
    }

    public int getMemberOrder() { return memberOrder; }
    public String getName() { return name; }
    public boolean isOwner() { return isOwner; }
    public boolean hasLeft() { return hasLeft; }
    public Instant getJoinedAt() { return joinedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Member that)) return false;
        return memberOrder == that.memberOrder && isOwner == that.isOwner && hasLeft == that.hasLeft
                && name.equals(that.name) && joinedAt.equals(that.joinedAt);
    }

    @Override
    public int hashCode() { return Objects.hash(memberOrder, name, isOwner, hasLeft, joinedAt); }
}
