package com.owo.banking_ledger.common;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.stream.Collectors;

/**
 * Fingerprints request payloads so that a retried request can be recognised as
 * identical, and a reused reference id carrying a different payload rejected.
 */
public final class RequestFingerprint {

    private static final String ALGORITHM = "SHA-256";
    private static final String SEPARATOR = "|";

    private RequestFingerprint() {
    }

    public static String of(String... parts) {
        String payload = Arrays.stream(parts)
                .map(part -> part == null ? "" : part)
                .collect(Collectors.joining(SEPARATOR));

        return hex(payload);
    }

    /**
     * Normalises scale so that {@code 100.00} and {@code 100.0000} fingerprint
     * as the same amount.
     */
    public static String normalize(BigDecimal amount) {
        return amount == null
                ? ""
                : amount.stripTrailingZeros().toPlainString();
    }

    /**
     * Derives a stable 64-bit key for advisory locking on a reference id.
     */
    public static long lockKey(String referenceId) {
        return Long.parseUnsignedLong(of(referenceId).substring(0, 16), 16);
    }

    private static String hex(String payload) {
        try {
            byte[] digest = MessageDigest.getInstance(ALGORITHM)
                    .digest(payload.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    ALGORITHM + " is not available", exception);
        }
    }
}
