package splitter.server.persistence;

import splitter.server.model.StorageException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Small JDBC connection helper for the hosted PostgreSQL database. */
public final class PostgresDatabase {
    private final DatabaseConfig config;

    public PostgresDatabase(DatabaseConfig config) {
        this.config = config;
    }

    public Connection getConnection() throws SQLException {
        if (config.username() == null) {
            return DriverManager.getConnection(config.jdbcUrl());
        }
        return DriverManager.getConnection(config.jdbcUrl(), config.username(),
                config.password() == null ? "" : config.password());
    }

    /** Checks that the configured database can accept a query. */
    public void healthCheck() {
        try (Connection connection = getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1")) {
            if (!result.next() || result.getInt(1) != 1) {
                throw new StorageException("PostgreSQL health check returned an unexpected result");
            }
        } catch (SQLException e) {
            throw new StorageException("PostgreSQL is unavailable", e);
        }
    }

    /** Applies idempotent schema creation before the server accepts requests. */
    public void initializeSchema() {
        try (Connection connection = getConnection();
             InputStream input = PostgresDatabase.class.getResourceAsStream("/db/schema.sql")) {
            if (input == null) {
                throw new StorageException("Database schema resource /db/schema.sql was not packaged");
            }
            String schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement statement = connection.createStatement()) {
                for (String command : schema.split(";")) {
                    if (!command.isBlank()) {
                        statement.execute(command.trim());
                    }
                }
            }
        } catch (SQLException | IOException e) {
            throw new StorageException("Could not initialize PostgreSQL schema", e);
        }
    }
}
