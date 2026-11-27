package splitter.server.persistence;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Reads PostgreSQL connection settings from environment variables. */
public final class DatabaseConfig {
    private final String jdbcUrl;
    private final String username;
    private final String password;

    private DatabaseConfig(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    public static DatabaseConfig fromEnvironment() {
        String databaseUrl = environment("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank()) {
            return null;
        }

        String dbUser = environment("DB_USERNAME");
        String dbPassword = environment("DB_PASSWORD");

        try {
            if (databaseUrl.startsWith("jdbc:postgresql://")) {
                return new DatabaseConfig(databaseUrl, dbUser, dbPassword);
            }

            URI uri = URI.create(databaseUrl);
            if (!"postgresql".equalsIgnoreCase(uri.getScheme())
                    && !"postgres".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("DATABASE_URL must use PostgreSQL");
            }
            if (uri.getHost() == null || uri.getPath() == null || uri.getPath().length() < 2) {
                throw new IllegalArgumentException("DATABASE_URL is missing its host or database name");
            }

            String user = dbUser;
            String password = dbPassword;
            if (uri.getRawUserInfo() != null) {
                String[] credentials = uri.getRawUserInfo().split(":", 2);
                user = decode(credentials[0]);
                password = credentials.length > 1 ? decode(credentials[1]) : password;
            }

            StringBuilder jdbcUrl = new StringBuilder("jdbc:postgresql://")
                    .append(uri.getHost());
            if (uri.getPort() >= 0) {
                jdbcUrl.append(':').append(uri.getPort());
            }
            jdbcUrl.append(uri.getRawPath());
            String query = uri.getRawQuery();
            if (query == null || query.isBlank()) {
                query = "sslmode=require";
            } else {
                // Neon displays the libpq spelling; pgJDBC uses the camel-case property name.
                query = query.replaceAll("(?i)(^|&)channel_binding=", "$1channelBinding=");
                if (!query.matches("(?is).*(^|&)sslmode=.*")) {
                    query += "&sslmode=require";
                }
            }
            jdbcUrl.append('?').append(query);
            return new DatabaseConfig(jdbcUrl.toString(), user, password);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid DATABASE_URL: " + e.getMessage(), e);
        }
    }

    private static String environment(String key) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String decode(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }
}
