package splitter.server.model;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Generates cryptographically secure login tokens of at least 32 characters.
 */
public final class TokenGenerator {
    private final SecureRandom random;

    public TokenGenerator() {
        this(new SecureRandom());
    }

    public TokenGenerator(SecureRandom random) {
        this.random = random;
    }

    public String generateLoginToken() {
        byte[] bytes = new byte[32]; // 32 bytes -> 64 hex characters >= 32 chars
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** Hash a high-entropy bearer token before storing it. */
    public static String hashLoginToken(String loginToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(loginToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }
}
