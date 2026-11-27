package splitter.server.persistence;

import splitter.server.model.StorageException;
import splitter.server.model.User;
import splitter.server.model.UserRepository;
import splitter.server.model.TokenGenerator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** PostgreSQL-backed user repository used by the deployed server. */
public final class PostgresUserRepository extends UserRepository {
    private final PostgresDatabase database;

    public PostgresUserRepository(PostgresDatabase database) {
        super();
        this.database = database;
    }

    @Override
    public boolean usernameExists(String username) {
        if (username == null) return false;
        String sql = "SELECT 1 FROM iou_users WHERE username_key = ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username.toLowerCase(java.util.Locale.ROOT));
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException e) {
            throw new StorageException("Could not check whether username exists", e);
        }
    }

    @Override
    public void createUser(String username, String loginToken) {
        if (username == null || loginToken == null) {
            throw new StorageException("Username and token cannot be null");
        }
        String sql = "INSERT INTO iou_users(username_key, username, login_token_hash) VALUES (?, ?, ?)";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username.toLowerCase(java.util.Locale.ROOT));
            statement.setString(2, username);
            statement.setString(3, TokenGenerator.hashLoginToken(loginToken));
            statement.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new StorageException("User already exists: " + username, e);
            }
            throw new StorageException("Could not create user", e);
        }
    }

    @Override
    public Optional<User> findByUsername(String username) {
        if (username == null) return Optional.empty();
        String sql = "SELECT username, login_token_hash FROM iou_users WHERE username_key = ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username.toLowerCase(java.util.Locale.ROOT));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new User(result.getString("username"), result.getString("login_token_hash")));
            }
        } catch (SQLException e) {
            throw new StorageException("Could not load user", e);
        }
    }
}
