package br.unisinos.omniphr.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Hashing helpers shared by the datablock chain and the DHT. */
public final class HashUtil {

    private HashUtil() {
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(String data) {
        return hex(sha256(data.getBytes(StandardCharsets.UTF_8)));
    }

    public static byte[] sha1(byte[] data) {
        try {
            // SHA-1 is used only for Chord identifiers, as in the original
            // Chord proposal (Stoica et al.).
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
