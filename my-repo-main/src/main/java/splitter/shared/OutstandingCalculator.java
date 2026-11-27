package splitter.shared;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Calculates current members' balances from saved bill shares. */
public final class OutstandingCalculator {
    private OutstandingCalculator() { }

    public static List<OutstandingAmounts> calculate(GroupSnapshot snapshot) {
        List<Member> currentMembers = snapshot.getCurrentMembers();
        Map<Integer, Long> owes = new LinkedHashMap<>();
        Map<Integer, Long> isOwed = new LinkedHashMap<>();
        for (Member member : currentMembers) {
            owes.put(member.getMemberOrder(), 0L);
            isOwed.put(member.getMemberOrder(), 0L);
        }

        for (Bill bill : snapshot.getBills()) {
            for (BillShare share : bill.getShares()) {
                if (share.isConfirmed() || share.getShareCents() <= 0) continue;
                if (share.getMemberOrder() == bill.getBillOwnerMemberOrder()) continue;

                if (owes.containsKey(share.getMemberOrder())) {
                    owes.merge(share.getMemberOrder(), share.getShareCents(), Math::addExact);
                }
                if (isOwed.containsKey(bill.getBillOwnerMemberOrder())) {
                    isOwed.merge(bill.getBillOwnerMemberOrder(), share.getShareCents(), Math::addExact);
                }
            }
        }

        List<OutstandingAmounts> result = new ArrayList<>();
        for (Member member : currentMembers) {
            int order = member.getMemberOrder();
            result.add(new OutstandingAmounts(member.getName(), owes.get(order), isOwed.get(order)));
        }
        return result;
    }
}
