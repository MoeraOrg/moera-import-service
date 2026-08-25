package org.moera.importer.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class Sha256 {

    private Sha256() {
    }

    public static byte[] digest(byte[] data) {
        Objects.requireNonNull(data, "data");
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public static String hex(byte[] data) {
        return HexFormat.of().formatHex(digest(data));
    }

}
