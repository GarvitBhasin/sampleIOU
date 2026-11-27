package splitter.server.controller;

import splitter.server.model.Group;
import splitter.server.model.GroupMember;
import splitter.server.model.GroupRepository;
import splitter.server.model.SplitCalculator;
import splitter.server.model.StorageException;
import splitter.shared.Bill;
import splitter.shared.BillShare;
import splitter.shared.FailureReasons;
import splitter.shared.GroupAccess;
import splitter.shared.InputValidator;
import splitter.shared.NewBill;
import splitter.shared.Result;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Handles bill creation, payment confirmations, dates, and cancellations. */
public class BillApiController {
    private final AuthGuard guard;
    private final GroupRepository groups;
    private final SplitCalculator splitter;
    private final GroupLockManager locks;
    private final Clock clock;

    public BillApiController(AuthGuard guard, GroupRepository groups, SplitCalculator splitter,
                             GroupLockManager locks) {
        this(guard, groups, splitter, locks, Clock.systemDefaultZone());
    }

    public BillApiController(AuthGuard guard, GroupRepository groups, SplitCalculator splitter,
                             GroupLockManager locks, Clock clock) {
        this.guard = Objects.requireNonNull(guard);
        this.groups = Objects.requireNonNull(groups);
        this.splitter = Objects.requireNonNull(splitter);
        this.locks = Objects.requireNonNull(locks);
        this.clock = Objects.requireNonNull(clock);
    }

    public Result<Long> createBill(GroupAccess access, NewBill bill) {
        if (bill == null) return Result.fail("Bill cannot be null");
        Result<Group> initialAuthorization = guard.authorizeGroupAccess(access);
        if (!initialAuthorization.isSuccess()) return Result.fail(initialAuthorization.getFailureReason());

        Result<String> description = InputValidator.validateName(bill.getDescription(), "Description");
        if (!description.isSuccess()) return Result.fail(description.getFailureReason());
        if (bill.getTotalCents() <= 0) return Result.fail("Amount must be greater than zero");
        if (bill.getSuggestedDateValue() != null
                && !InputValidator.isNotInPast(bill.getSuggestedDateValue(), LocalDate.now(clock))) {
            return Result.fail("Suggested date cannot be in the past");
        }
        Result<Void> validWeights = InputValidator.validateWeightsSum(
                bill.getWeightsHundredths() == null ? null : bill.getWeightsHundredths().values());
        if (!validWeights.isSuccess()) return Result.fail(validWeights.getFailureReason());

        long groupId = initialAuthorization.getData().getId();
        locks.lock(groupId);
        try {
            // Re-check the user and group code after locking, against the latest group state.
            Result<Group> authorization = guard.authorizeGroupAccess(access);
            if (!authorization.isSuccess()) return Result.fail(authorization.getFailureReason());
            Group group = authorization.getData();
            String username = access.getCredentials().getUsername();
            GroupMember caller = group.findCurrentMemberByUsername(username).orElse(null);
            if (caller == null) return Result.fail(FailureReasons.NOT_A_MEMBER);

            List<GroupMember> currentMembers = group.currentMembersInOrder();
            if (bill.getWeightsHundredths().size() != currentMembers.size()) {
                return Result.fail("Weights must match current members");
            }

            // Rebuild the map in member-list order; client JSON/map ordering is not trusted.
            Map<String, Integer> orderedWeights = new LinkedHashMap<>();
            for (GroupMember member : currentMembers) {
                Integer weight = findWeight(bill.getWeightsHundredths(), member.getName());
                if (weight == null) return Result.fail("Missing or invalid weight for member: " + member.getName());
                if (weight < 0 || weight > 100) {
                    return Result.fail("Weight for " + member.getName() + " must be between 0.00 and 1.00");
                }
                orderedWeights.put(member.getName(), weight);
            }
            for (String submittedName : bill.getWeightsHundredths().keySet()) {
                if (currentMembers.stream().noneMatch(member -> InputValidator.namesEqual(member.getName(), submittedName))) {
                    return Result.fail("Weight specified for non-current member: " + submittedName);
                }
            }
            Result<Void> orderedSum = InputValidator.validateWeightsSum(orderedWeights.values());
            if (!orderedSum.isSuccess()) return Result.fail(orderedSum.getFailureReason());

            Map<String, Long> shares = splitter.computeShares(bill.getTotalCents(), orderedWeights);
            List<BillShare> billShares = new ArrayList<>();
            Instant now = Instant.now(clock);
            for (GroupMember member : currentMembers) {
                long shareCents = shares.getOrDefault(member.getName(), 0L);
                boolean ownerShare = member.getUsername().equalsIgnoreCase(username) && shareCents > 0;
                billShares.add(new BillShare(member.getMemberOrder(), member.getName(),
                        orderedWeights.get(member.getName()), shareCents, ownerShare, ownerShare ? now : null));
            }

            Bill createdBill = new Bill(0, description.getData(), bill.getTotalCents(), caller.getName(),
                    caller.getUsername(), caller.getMemberOrder(), now, bill.getSuggestedDateValue(), billShares);
            return Result.ok(groups.addBill(groupId, createdBill));
        } catch (StorageException e) {
            return Result.fail("Storage error: " + e.getMessage());
        } catch (Exception e) {
            return Result.fail("Unexpected error: " + e.getMessage());
        } finally {
            locks.unlock(groupId);
        }
    }

    public Result<Void> changeSuggestedDate(GroupAccess access, long billId, LocalDate newDate) {
        Result<Group> initial = guard.authorizeGroupAccess(access);
        if (!initial.isSuccess()) return Result.fail(initial.getFailureReason());
        long groupId = initial.getData().getId();

        locks.lock(groupId);
        try {
            Result<Group> authorization = guard.authorizeGroupAccess(access);
            if (!authorization.isSuccess()) return Result.fail(authorization.getFailureReason());
            Group group = authorization.getData();
            Bill bill = group.findBill(billId).orElse(null);
            if (bill == null) return Result.fail(FailureReasons.BILL_NOT_FOUND);

            Result<Void> ownerCheck = guard.requireBillOwner(group, bill, access.getCredentials().getUsername());
            if (!ownerCheck.isSuccess()) return Result.fail(ownerCheck.getFailureReason());
            if (newDate != null && !InputValidator.isNotInPast(newDate, LocalDate.now(clock))) {
                return Result.fail("Suggested date cannot be in the past");
            }
            return groups.setBillDate(groupId, billId, newDate)
                    ? Result.ok() : Result.fail(FailureReasons.BILL_NOT_FOUND);
        } catch (StorageException e) {
            return Result.fail("Storage error: " + e.getMessage());
        } catch (Exception e) {
            return Result.fail("Unexpected error: " + e.getMessage());
        } finally {
            locks.unlock(groupId);
        }
    }

    public Result<Void> confirmPayment(GroupAccess access, long billId, int memberOrder) {
        Result<Group> initial = guard.authorizeGroupAccess(access);
        if (!initial.isSuccess()) return Result.fail(initial.getFailureReason());
        long groupId = initial.getData().getId();

        locks.lock(groupId);
        try {
            Result<Group> authorization = guard.authorizeGroupAccess(access);
            if (!authorization.isSuccess()) return Result.fail(authorization.getFailureReason());
            Group group = authorization.getData();
            Bill bill = group.findBill(billId).orElse(null);
            if (bill == null) return Result.fail(FailureReasons.BILL_NOT_FOUND);

            Result<Void> permission = guard.requireMayConfirmPayment(group, bill, access.getCredentials().getUsername());
            if (!permission.isSuccess()) return Result.fail(permission.getFailureReason());

            Optional<BillShare> share = bill.findShare(memberOrder);
            if (share.isEmpty() || share.get().getShareCents() <= 0) {
                return Result.fail("Share not found for member order: " + memberOrder);
            }
            if (share.get().isConfirmed()) return Result.fail(FailureReasons.ALREADY_CONFIRMED);

            boolean confirmed = groups.confirmShare(groupId, billId, memberOrder, Instant.now(clock));
            return confirmed ? Result.ok() : Result.fail(FailureReasons.ALREADY_CONFIRMED);
        } catch (StorageException e) {
            return Result.fail("Storage error: " + e.getMessage());
        } catch (Exception e) {
            return Result.fail("Unexpected error: " + e.getMessage());
        } finally {
            locks.unlock(groupId);
        }
    }

    /** Compatibility overload for tests and older clients that identify shares by name. */
    public Result<Void> confirmPayment(GroupAccess access, long billId, String memberName) {
        Result<Group> authorized = guard.authorizeGroupAccess(access);
        if (!authorized.isSuccess()) return Result.fail(authorized.getFailureReason());
        Bill bill = authorized.getData().findBill(billId).orElse(null);
        if (bill == null) return Result.fail(FailureReasons.BILL_NOT_FOUND);
        Optional<BillShare> share = bill.findShare(memberName);
        if (share.isEmpty()) return Result.fail("Share not found for member: " + memberName);
        if (share.get().getMemberOrder() >= 0) {
            return confirmPayment(access, billId, share.get().getMemberOrder());
        }
        return Result.fail("This share has no stable membership identifier");
    }

    public Result<Void> cancelBill(GroupAccess access, long billId) {
        Result<Group> initial = guard.authorizeGroupAccess(access);
        if (!initial.isSuccess()) return Result.fail(initial.getFailureReason());
        long groupId = initial.getData().getId();

        locks.lock(groupId);
        try {
            Result<Group> authorization = guard.authorizeGroupAccess(access);
            if (!authorization.isSuccess()) return Result.fail(authorization.getFailureReason());
            Group group = authorization.getData();
            Bill bill = group.findBill(billId).orElse(null);
            if (bill == null) return Result.fail(FailureReasons.BILL_NOT_FOUND);

            Result<Void> permission = guard.requireBillOwnerOrGroupOwner(
                    group, bill, access.getCredentials().getUsername());
            if (!permission.isSuccess()) return Result.fail(permission.getFailureReason());
            return groups.deleteBill(groupId, billId)
                    ? Result.ok() : Result.fail(FailureReasons.BILL_NOT_FOUND);
        } catch (StorageException e) {
            return Result.fail("Storage error: " + e.getMessage());
        } catch (Exception e) {
            return Result.fail("Unexpected error: " + e.getMessage());
        } finally {
            locks.unlock(groupId);
        }
    }

    public Result<Void> cancelAllBills(GroupAccess access) {
        Result<Group> initial = guard.authorizeGroupAccess(access);
        if (!initial.isSuccess()) return Result.fail(initial.getFailureReason());
        long groupId = initial.getData().getId();

        locks.lock(groupId);
        try {
            Result<Group> authorization = guard.authorizeGroupAccess(access);
            if (!authorization.isSuccess()) return Result.fail(authorization.getFailureReason());
            Group group = authorization.getData();
            Result<Void> ownerCheck = guard.requireGroupOwner(group, access.getCredentials().getUsername());
            if (!ownerCheck.isSuccess()) return Result.fail(ownerCheck.getFailureReason());
            groups.deleteAllBills(groupId);
            return Result.ok();
        } catch (StorageException e) {
            return Result.fail("Storage error: " + e.getMessage());
        } catch (Exception e) {
            return Result.fail("Unexpected error: " + e.getMessage());
        } finally {
            locks.unlock(groupId);
        }
    }

    private Integer findWeight(Map<String, Integer> weights, String memberName) {
        if (weights == null) return null;
        Integer found = null;
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            if (InputValidator.namesEqual(entry.getKey(), memberName)) {
                if (found != null) return null;
                found = entry.getValue();
            }
        }
        return found;
    }
}
