package splitter.server.persistence;

import splitter.server.model.Group;
import splitter.server.model.GroupMember;
import splitter.server.model.GroupRepository;
import splitter.server.model.StorageException;
import splitter.shared.Bill;
import splitter.shared.BillShare;
import splitter.shared.GroupSummary;
import splitter.shared.InputValidator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** PostgreSQL-backed group repository. A group row lock serializes each group's writes. */
public final class PostgresGroupRepository extends GroupRepository {
    private final PostgresDatabase database;

    public PostgresGroupRepository(PostgresDatabase database) {
        super();
        this.database = database;
    }

    @Override
    public boolean codeExists(String code) {
        if (code == null) return false;
        String sql = "SELECT 1 FROM iou_groups WHERE group_code = ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, InputValidator.normalizeGroupCode(code));
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException e) {
            throw new StorageException("Could not check group code", e);
        }
    }

    @Override
    public long createGroup(String name, String code, String ownerUsername, String ownerMemberName) {
        return inTransaction(connection -> {
            long groupId;
            String insertGroup = "INSERT INTO iou_groups(name, group_code, owner_username_key) "
                    + "VALUES (?, ?, ?) RETURNING id";
            try (PreparedStatement statement = connection.prepareStatement(insertGroup)) {
                statement.setString(1, name);
                statement.setString(2, InputValidator.normalizeGroupCode(code));
                statement.setString(3, usernameKey(ownerUsername));
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw new SQLException("The database did not return a group id");
                    groupId = result.getLong(1);
                }
            }

            String insertMember = "INSERT INTO iou_group_members "
                    + "(group_id, member_order, username_key, display_name, joined_at, has_left) "
                    + "VALUES (?, 0, ?, ?, ?, FALSE)";
            try (PreparedStatement statement = connection.prepareStatement(insertMember)) {
                statement.setLong(1, groupId);
                statement.setString(2, usernameKey(ownerUsername));
                statement.setString(3, ownerMemberName);
                statement.setTimestamp(4, Timestamp.from(Instant.now()));
                statement.executeUpdate();
            }
            return groupId;
        });
    }

    @Override
    public Optional<Group> loadGroup(long groupId) {
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            Optional<Group> group = loadGroup(connection, groupId);
            connection.commit();
            return group;
        } catch (SQLException e) {
            throw new StorageException("Could not load group", e);
        }
    }

    @Override
    public Optional<Group> loadGroupByCode(String code) {
        if (code == null) return Optional.empty();
        String sql = "SELECT id FROM iou_groups WHERE group_code = ?";
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            long groupId;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, InputValidator.normalizeGroupCode(code));
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        connection.commit();
                        return Optional.empty();
                    }
                    groupId = result.getLong(1);
                }
            }
            Optional<Group> group = loadGroup(connection, groupId);
            connection.commit();
            return group;
        } catch (SQLException e) {
            throw new StorageException("Could not find group by code", e);
        }
    }

    @Override
    public List<GroupSummary> listGroupsOfUser(String username) {
        if (username == null) return List.of();
        String sql = "SELECT g.id, g.name FROM iou_groups g "
                + "JOIN iou_group_members m ON m.group_id = g.id "
                + "WHERE m.username_key = ? AND m.has_left = FALSE ORDER BY g.id";
        List<GroupSummary> groups = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, usernameKey(username));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    groups.add(new GroupSummary(result.getLong("id"), result.getString("name")));
                }
            }
            return groups;
        } catch (SQLException e) {
            throw new StorageException("Could not list user's groups", e);
        }
    }

    @Override
    public void renameGroup(long groupId, String newName) {
        inTransaction(connection -> {
            lockGroup(connection, groupId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE iou_groups SET name = ? WHERE id = ?")) {
                statement.setString(1, newName);
                statement.setLong(2, groupId);
                requireUpdated(statement.executeUpdate(), "Group not found: " + groupId);
            }
            return null;
        });
    }

    @Override
    public void replaceCode(long groupId, String newCode) {
        inTransaction(connection -> {
            lockGroup(connection, groupId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE iou_groups SET group_code = ? WHERE id = ?")) {
                statement.setString(1, InputValidator.normalizeGroupCode(newCode));
                statement.setLong(2, groupId);
                requireUpdated(statement.executeUpdate(), "Group not found: " + groupId);
            }
            return null;
        });
    }

    @Override
    public void addMember(long groupId, String username, String memberName) {
        inTransaction(connection -> {
            lockGroup(connection, groupId);
            int nextOrder;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COALESCE(MAX(member_order), -1) + 1 FROM iou_group_members WHERE group_id = ?")) {
                statement.setLong(1, groupId);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    nextOrder = result.getInt(1);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO iou_group_members "
                            + "(group_id, member_order, username_key, display_name, joined_at, has_left) "
                            + "VALUES (?, ?, ?, ?, ?, FALSE)")) {
                statement.setLong(1, groupId);
                statement.setInt(2, nextOrder);
                statement.setString(3, usernameKey(username));
                statement.setString(4, memberName);
                statement.setTimestamp(5, Timestamp.from(Instant.now()));
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void leaveGroup(long groupId, String username, String newOwnerUsername) {
        inTransaction(connection -> {
            lockGroup(connection, groupId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE iou_group_members SET has_left = TRUE "
                            + "WHERE group_id = ? AND username_key = ? AND has_left = FALSE")) {
                statement.setLong(1, groupId);
                statement.setString(2, usernameKey(username));
                requireUpdated(statement.executeUpdate(), "Current membership not found");
            }
            if (newOwnerUsername != null) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE iou_groups SET owner_username_key = ? WHERE id = ?")) {
                    statement.setString(1, usernameKey(newOwnerUsername));
                    statement.setLong(2, groupId);
                    requireUpdated(statement.executeUpdate(), "Group not found: " + groupId);
                }
            }
            return null;
        });
    }

    @Override
    public void deleteGroup(long groupId) {
        inTransaction(connection -> {
            lockGroup(connection, groupId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM iou_groups WHERE id = ?")) {
                statement.setLong(1, groupId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public long addBill(long groupId, Bill bill) {
        return inTransaction(connection -> {
            lockGroup(connection, groupId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE iou_bills SET display_order = display_order + 1 WHERE group_id = ?")) {
                statement.setLong(1, groupId);
                statement.executeUpdate();
            }

            long billId;
            String insertBill = "INSERT INTO iou_bills "
                    + "(group_id, description, total_cents, bill_owner_member_name, bill_owner_username_key, "
                    + "bill_owner_member_order, created_at, suggested_date, display_order) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0) RETURNING id";
            try (PreparedStatement statement = connection.prepareStatement(insertBill)) {
                statement.setLong(1, groupId);
                statement.setString(2, bill.getDescription());
                statement.setLong(3, bill.getTotalCents());
                statement.setString(4, bill.getBillOwnerMemberName());
                if (bill.getBillOwnerUsername() == null) {
                    statement.setObject(5, null);
                } else {
                    statement.setString(5, usernameKey(bill.getBillOwnerUsername()));
                }
                statement.setInt(6, bill.getBillOwnerMemberOrder());
                statement.setTimestamp(7, Timestamp.from(bill.getCreatedAt()));
                statement.setObject(8, bill.getSuggestedDateValue());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw new SQLException("The database did not return a bill id");
                    billId = result.getLong(1);
                }
            }

            String insertShare = "INSERT INTO iou_bill_shares "
                    + "(bill_id, group_id, member_order, weight_hundredths, share_cents, confirmed, confirmed_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(insertShare)) {
                for (BillShare share : bill.getShares()) {
                    statement.setLong(1, billId);
                    statement.setLong(2, groupId);
                    statement.setInt(3, share.getMemberOrder());
                    statement.setInt(4, share.getWeightHundredths());
                    statement.setLong(5, share.getShareCents());
                    statement.setBoolean(6, share.isConfirmed());
                    if (share.getConfirmedAtInstant() == null) {
                        statement.setObject(7, null);
                    } else {
                        statement.setTimestamp(7, Timestamp.from(share.getConfirmedAtInstant()));
                    }
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            return billId;
        });
    }

    @Override
    public boolean setBillDate(long groupId, long billId, LocalDate date) {
        return inTransaction(connection -> {
            lockGroup(connection, groupId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE iou_bills SET suggested_date = ? WHERE group_id = ? AND id = ?")) {
                statement.setObject(1, date);
                statement.setLong(2, groupId);
                statement.setLong(3, billId);
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public boolean confirmShare(long groupId, long billId, String memberName, Instant when) {
        return inTransaction(connection -> {
            lockGroup(connection, groupId);
            int memberOrder = findMemberOrderByName(connection, groupId, memberName);
            if (memberOrder < 0) return false;
            return confirmShare(connection, groupId, billId, memberOrder, when);
        });
    }

    @Override
    public boolean confirmShare(long groupId, long billId, int memberOrder, Instant when) {
        return inTransaction(connection -> {
            lockGroup(connection, groupId);
            return confirmShare(connection, groupId, billId, memberOrder, when);
        });
    }

    private boolean confirmShare(Connection connection, long groupId, long billId,
                                  int memberOrder, Instant when) throws SQLException {
        String sql = "UPDATE iou_bill_shares SET confirmed = TRUE, confirmed_at = ? "
                + "WHERE bill_id = ? AND group_id = ? AND member_order = ? "
                + "AND share_cents > 0 AND confirmed = FALSE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(when));
            statement.setLong(2, billId);
            statement.setLong(3, groupId);
            statement.setInt(4, memberOrder);
            return statement.executeUpdate() == 1;
        }
    }

    private int findMemberOrderByName(Connection connection, long groupId, String memberName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT member_order FROM iou_group_members "
                        + "WHERE group_id = ? AND LOWER(BTRIM(display_name)) = LOWER(BTRIM(?)) "
                        + "ORDER BY has_left ASC, member_order DESC LIMIT 1")) {
            statement.setLong(1, groupId);
            statement.setString(2, memberName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : -1;
            }
        }
    }

    @Override
    public boolean deleteBill(long groupId, long billId) {
        return inTransaction(connection -> {
            lockGroup(connection, groupId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM iou_bills WHERE group_id = ? AND id = ?")) {
                statement.setLong(1, groupId);
                statement.setLong(2, billId);
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public void deleteAllBills(long groupId) {
        inTransaction(connection -> {
            lockGroup(connection, groupId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM iou_bills WHERE group_id = ?")) {
                statement.setLong(1, groupId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private Optional<Group> loadGroup(Connection connection, long groupId) throws SQLException {
        long id;
        String name;
        String code;
        String ownerUsername;
        String groupSql = "SELECT g.id, g.name, g.group_code, u.username AS owner_username "
                + "FROM iou_groups g JOIN iou_users u ON u.username_key = g.owner_username_key WHERE g.id = ?";
        try (PreparedStatement statement = connection.prepareStatement(groupSql)) {
            statement.setLong(1, groupId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                id = result.getLong("id");
                name = result.getString("name");
                code = result.getString("group_code");
                ownerUsername = result.getString("owner_username");
            }
        }

        List<GroupMember> members = new ArrayList<>();
        String memberSql = "SELECT m.member_order, u.username, m.display_name, m.joined_at, m.has_left "
                + "FROM iou_group_members m JOIN iou_users u ON u.username_key = m.username_key "
                + "WHERE m.group_id = ? ORDER BY m.member_order";
        try (PreparedStatement statement = connection.prepareStatement(memberSql)) {
            statement.setLong(1, groupId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    members.add(new GroupMember(result.getInt("member_order"), result.getString("username"),
                            result.getString("display_name"), result.getTimestamp("joined_at").toInstant(),
                            result.getBoolean("has_left")));
                }
            }
        }

        Map<Long, BillBuilder> billBuilders = new LinkedHashMap<>();
        String billSql = "SELECT b.id, b.description, b.total_cents, b.bill_owner_member_name, "
                + "b.bill_owner_member_order, owner.username AS bill_owner_username, "
                + "b.created_at, b.suggested_date, s.member_order AS share_member_order, "
                + "member.display_name AS share_member_name, s.weight_hundredths, s.share_cents, "
                + "s.confirmed, s.confirmed_at FROM iou_bills b "
                + "LEFT JOIN iou_users owner ON owner.username_key = b.bill_owner_username_key "
                + "LEFT JOIN iou_bill_shares s ON s.bill_id = b.id "
                + "LEFT JOIN iou_group_members member ON member.group_id = s.group_id "
                + "AND member.member_order = s.member_order "
                + "WHERE b.group_id = ? ORDER BY b.display_order, s.member_order";
        try (PreparedStatement statement = connection.prepareStatement(billSql)) {
            statement.setLong(1, groupId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long billId = result.getLong("id");
                    BillBuilder builder = billBuilders.get(billId);
                    if (builder == null) {
                        LocalDate suggestedDate = result.getObject("suggested_date", LocalDate.class);
                        builder = new BillBuilder(billId, result.getString("description"),
                                result.getLong("total_cents"), result.getString("bill_owner_member_name"),
                                result.getString("bill_owner_username"), result.getInt("bill_owner_member_order"),
                                result.getTimestamp("created_at").toInstant(), suggestedDate);
                        billBuilders.put(billId, builder);
                    }
                    String memberName = result.getString("share_member_name");
                    if (memberName != null) {
                        Timestamp confirmedAt = result.getTimestamp("confirmed_at");
                        builder.shares.add(new BillShare(result.getInt("share_member_order"), memberName,
                                result.getInt("weight_hundredths"), result.getLong("share_cents"),
                                result.getBoolean("confirmed"), confirmedAt == null ? null : confirmedAt.toInstant()));
                    }
                }
            }
        }

        List<Bill> bills = new ArrayList<>();
        for (BillBuilder builder : billBuilders.values()) {
            bills.add(new Bill(builder.id, builder.description, builder.totalCents, builder.ownerName,
                    builder.ownerUsername, builder.ownerMemberOrder, builder.createdAt, builder.suggestedDate, builder.shares));
        }
        return Optional.of(new Group(id, name, code, ownerUsername, members, bills));
    }

    private void lockGroup(Connection connection, long groupId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM iou_groups WHERE id = ? FOR UPDATE")) {
            statement.setLong(1, groupId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new StorageException("Group not found: " + groupId);
            }
        }
    }

    private <T> T inTransaction(SqlWork<T> work) {
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                if (e instanceof StorageException storageException) throw storageException;
                if (e instanceof SQLException sqlException && "23505".equals(sqlException.getSQLState())) {
                    throw new StorageException("A group code or member name is already in use", sqlException);
                }
                throw new StorageException("Database transaction failed", e);
            }
        } catch (SQLException e) {
            throw new StorageException("Could not connect to PostgreSQL", e);
        }
    }

    private static String usernameKey(String username) {
        return username == null ? "" : username.toLowerCase(java.util.Locale.ROOT);
    }

    private static void requireUpdated(int rows, String message) {
        if (rows == 0) throw new StorageException(message);
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws Exception;
    }

    private static final class BillBuilder {
        private final long id;
        private final String description;
        private final long totalCents;
        private final String ownerName;
        private final String ownerUsername;
        private final int ownerMemberOrder;
        private final Instant createdAt;
        private final LocalDate suggestedDate;
        private final List<BillShare> shares = new ArrayList<>();

        private BillBuilder(long id, String description, long totalCents, String ownerName,
                            String ownerUsername, int ownerMemberOrder, Instant createdAt, LocalDate suggestedDate) {
            this.id = id;
            this.description = description;
            this.totalCents = totalCents;
            this.ownerName = ownerName;
            this.ownerUsername = ownerUsername;
            this.ownerMemberOrder = ownerMemberOrder;
            this.createdAt = createdAt;
            this.suggestedDate = suggestedDate;
        }
    }
}
