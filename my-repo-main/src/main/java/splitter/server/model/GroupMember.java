package splitter.server.model;

import java.time.Instant;
import java.util.Objects;

/** One membership in a group. memberOrder is stable, even if the user leaves and rejoins. */
public final class GroupMember {
    private final int memberOrder;
    private final String username;
    private final String name;
    private final Instant joinedAt;
    private boolean hasLeft;

    /** Compatibility constructor for tests and older local data. */
    public GroupMember(String username, String name, Instant joinedAt, boolean hasLeft) {
        this(0, username, name, joinedAt, hasLeft);
    }

    public GroupMember(int memberOrder, String username, String name, Instant joinedAt, boolean hasLeft) {
        if (memberOrder < 0) throw new IllegalArgumentException("memberOrder cannot be negative");
        this.memberOrder = memberOrder;
        this.username = Objects.requireNonNull(username, "username cannot be null");
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.joinedAt = Objects.requireNonNull(joinedAt, "joinedAt cannot be null");
        this.hasLeft = hasLeft;
    }

    public int getMemberOrder() { return memberOrder; }
    public String getUsername() { return username; }
    public String getName() { return name; }
    public Instant getJoinedAt() { return joinedAt; }
    public boolean hasLeft() { return hasLeft; }

    public void markLeft() { this.hasLeft = true; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GroupMember that)) return false;
        return memberOrder == that.memberOrder && hasLeft == that.hasLeft
                && username.equals(that.username) && name.equals(that.name)
                && joinedAt.equals(that.joinedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(memberOrder, username, name, joinedAt, hasLeft);
    }
}
