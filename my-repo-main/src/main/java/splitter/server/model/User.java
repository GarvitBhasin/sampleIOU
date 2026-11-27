package splitter.server.model;

import java.util.Objects;

/**
 * Server-side representation of a registered user.
 */
public final class User {
    private final String username;
    private final String loginTokenHash;

    public User(String username, String loginTokenHash) {
        this.username = Objects.requireNonNull(username, "username cannot be null");
        this.loginTokenHash = Objects.requireNonNull(loginTokenHash, "loginTokenHash cannot be null");
    }

    public String getUsername() {
        return username;
    }

    public String getLoginTokenHash() {
        return loginTokenHash;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User)) return false;
        User user = (User) o;
        return Objects.equals(username, user.username) && Objects.equals(loginTokenHash, user.loginTokenHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(username, loginTokenHash);
    }
}
