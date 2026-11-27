package splitter.shared;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A bill and the shares fixed when it was created. */
public final class Bill {
    private final long id;
    private final String description;
    private final long totalCents;
    private final String billOwnerMemberName;
    private final String billOwnerUsername;
    private final int billOwnerMemberOrder;
    private final Instant createdAt;
    private final LocalDate suggestedDate;
    private final List<BillShare> shares;

    /** Compatibility constructor for older fixtures and local records. */
    public Bill(long id, String description, long totalCents, String billOwnerMemberName,
                Instant createdAt, LocalDate suggestedDate, List<BillShare> shares) {
        this(id, description, totalCents, billOwnerMemberName, null, -1,
                createdAt, suggestedDate, shares);
    }

    public Bill(long id, String description, long totalCents, String billOwnerMemberName,
                String billOwnerUsername, Instant createdAt, LocalDate suggestedDate, List<BillShare> shares) {
        this(id, description, totalCents, billOwnerMemberName, billOwnerUsername, -1,
                createdAt, suggestedDate, shares);
    }

    public Bill(long id, String description, long totalCents, String billOwnerMemberName,
                String billOwnerUsername, int billOwnerMemberOrder, Instant createdAt,
                LocalDate suggestedDate, List<BillShare> shares) {
        this.id = id;
        this.description = Objects.requireNonNull(description, "description cannot be null");
        this.totalCents = totalCents;
        this.billOwnerMemberName = Objects.requireNonNull(billOwnerMemberName, "billOwnerMemberName cannot be null");
        this.billOwnerUsername = billOwnerUsername;
        this.billOwnerMemberOrder = billOwnerMemberOrder;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null");
        this.suggestedDate = suggestedDate;
        this.shares = Collections.unmodifiableList(new ArrayList<>(shares));
    }

    public long getId() { return id; }
    public String getDescription() { return description; }
    public long getTotalCents() { return totalCents; }
    public String getBillOwnerMemberName() { return billOwnerMemberName; }
    public String getBillOwnerUsername() { return billOwnerUsername; }
    public int getBillOwnerMemberOrder() { return billOwnerMemberOrder; }
    public Instant getCreatedAt() { return createdAt; }
    public Optional<LocalDate> getSuggestedDate() { return Optional.ofNullable(suggestedDate); }
    public LocalDate getSuggestedDateValue() { return suggestedDate; }
    public List<BillShare> getShares() { return shares; }

    public Optional<BillShare> findShare(String memberName) {
        return shares.stream().filter(s -> InputValidator.namesEqual(s.getMemberName(), memberName)).findFirst();
    }

    public Optional<BillShare> findShare(int memberOrder) {
        return shares.stream().filter(s -> s.getMemberOrder() == memberOrder).findFirst();
    }

    public boolean isFullyPaid() {
        return shares.stream().noneMatch(s -> s.getShareCents() > 0 && !s.isConfirmed());
    }

    public int getPaidSharesCount() {
        return (int) shares.stream().filter(this::isOwedShare)
                .filter(BillShare::isConfirmed).count();
    }

    public int getPayableSharesCount() {
        return (int) shares.stream().filter(this::isOwedShare).count();
    }

    private boolean isOwedShare(BillShare share) {
        if (share.getShareCents() <= 0) return false;
        if (billOwnerMemberOrder >= 0 && share.getMemberOrder() >= 0) {
            return share.getMemberOrder() != billOwnerMemberOrder;
        }
        return !InputValidator.namesEqual(share.getMemberName(), billOwnerMemberName);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Bill that)) return false;
        return id == that.id && totalCents == that.totalCents
                && billOwnerMemberOrder == that.billOwnerMemberOrder
                && Objects.equals(description, that.description)
                && Objects.equals(billOwnerMemberName, that.billOwnerMemberName)
                && Objects.equals(billOwnerUsername, that.billOwnerUsername)
                && Objects.equals(createdAt, that.createdAt)
                && Objects.equals(suggestedDate, that.suggestedDate)
                && Objects.equals(shares, that.shares);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, description, totalCents, billOwnerMemberName, billOwnerUsername,
                billOwnerMemberOrder, createdAt, suggestedDate, shares);
    }
}
