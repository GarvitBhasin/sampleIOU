package splitter;

import org.junit.jupiter.api.Test;
import splitter.shared.*;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OutstandingAmountsTest {
    @Test
    void balancesUseTheOriginalMembershipWhenANameIsReused() {
        Instant now = Instant.now();
        List<Member> members = List.of(
                new Member(0, "Alice", true, false, now),
                new Member(1, "Bob", false, true, now),
                new Member(2, "Bob", false, false, now)
        );

        Bill oldBill = new Bill(1, "Groceries", 2_000, "Alice", "alice", 0,
                now, null, List.of(
                new BillShare(0, "Alice", 50, 1_000, true, now),
                new BillShare(1, "Bob", 50, 1_000, false, null)
        ));

        GroupSnapshot snapshot = new GroupSnapshot(1, "House", "Alice", 0,
                true, "ABC234", members, List.of(oldBill));

        List<OutstandingAmounts> balances = OutstandingCalculator.calculate(snapshot);
        assertEquals(2, balances.size());
        assertEquals("Alice", balances.get(0).getMemberName());
        assertEquals(0, balances.get(0).getOwesCents());
        assertEquals(1_000, balances.get(0).getIsOwedCents());

        // The new Bob has the same display name but is not responsible for old Bob's share.
        assertEquals("Bob", balances.get(1).getMemberName());
        assertEquals(0, balances.get(1).getOwesCents());
        assertEquals(0, balances.get(1).getIsOwedCents());
    }

    @Test
    void billCountersExcludeTheBillOwnersOwnShare() {
        Instant now = Instant.now();
        Bill bill = new Bill(2, "Dinner", 3_000, "Alice", "alice", 4,
                now, null, List.of(
                new BillShare(4, "Alice", 34, 1_000, true, now),
                new BillShare(5, "Bob", 33, 1_000, false, null),
                new BillShare(6, "Charlie", 33, 1_000, true, now)
        ));

        assertEquals(1, bill.getPaidSharesCount());
        assertEquals(2, bill.getPayableSharesCount());
    }
}
