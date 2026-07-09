package br.unisinos.omniphr.middleware.security;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * Encryptor component of the Security and Privacy Module. Establishes the
 * encryption of transmitted and stored datablocks, based on open public-key
 * cryptography: each user has a key pair, the private key is kept secret and
 * the public key is distributed with the user identifier.
 *
 * Implementation decision: as usual with public-key systems, the content is
 * encrypted with a symmetric content key (AES-256-GCM) and the content key
 * is wrapped with the RSA public key of each authorized reader (hybrid
 * encryption). This keeps the public-key base of the model while supporting
 * contents of any size and multiple authorized readers.
 */
public class Encryptor {

    private static final int AES_KEY_BITS = 256;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();

    /** Result of encrypting a datablock content. */
    public static final class EncryptedPayload {
        public final byte[] ciphertext;                 // IV || GCM ciphertext
        public final Map<String, byte[]> wrappedKeys;   // readerId -> RSA-wrapped content key

        EncryptedPayload(byte[] ciphertext, Map<String, byte[]> wrappedKeys) {
            this.ciphertext = ciphertext;
            this.wrappedKeys = wrappedKeys;
        }
    }

    /** Generates a user key pair. */
    public KeyPair generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048, secureRandom);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Encrypts the canonical content for the given readers (at least the
     * patient, whose public key is distributed with the patient identifier).
     */
    public EncryptedPayload encrypt(String plaintext, Map<String, PublicKey> readers) {
        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(AES_KEY_BITS, secureRandom);
            SecretKey contentKey = kg.generateKey();

            byte[] iv = new byte[GCM_IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, contentKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = aes.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] ciphertext = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, ciphertext, 0, iv.length);
            System.arraycopy(ct, 0, ciphertext, iv.length, ct.length);

            Map<String, byte[]> wrapped = new HashMap<>();
            for (Map.Entry<String, PublicKey> r : readers.entrySet()) {
                wrapped.put(r.getKey(), wrapKey(contentKey.getEncoded(), r.getValue()));
            }
            return new EncryptedPayload(ciphertext, wrapped);
        } catch (Exception e) {
            throw new IllegalStateException("encryption failure", e);
        }
    }

    /** Decrypts a datablock content with the reader's private key. */
    public String decrypt(byte[] ciphertext, byte[] wrappedKey, PrivateKey readerKey) {
        try {
            byte[] rawKey = unwrapKey(wrappedKey, readerKey);
            SecretKey contentKey = new SecretKeySpec(rawKey, "AES");
            byte[] iv = new byte[GCM_IV_BYTES];
            System.arraycopy(ciphertext, 0, iv, 0, GCM_IV_BYTES);
            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.DECRYPT_MODE, contentKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] pt = aes.doFinal(ciphertext, GCM_IV_BYTES, ciphertext.length - GCM_IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("decryption failure (is the reader authorized?)", e);
        }
    }

    /**
     * Re-wraps the content key of an existing datablock for a newly granted
     * reader. Executed with the patient's private key, since the master
     * controller of the PHR remains with the patient.
     */
    public byte[] rewrapForGrantee(byte[] patientWrappedKey, PrivateKey patientKey, PublicKey granteeKey) {
        try {
            byte[] rawKey = unwrapKey(patientWrappedKey, patientKey);
            return wrapKey(rawKey, granteeKey);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] wrapKey(byte[] rawKey, PublicKey readerKey) throws Exception {
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, readerKey);
        return rsa.doFinal(rawKey);
    }

    private byte[] unwrapKey(byte[] wrappedKey, PrivateKey readerKey) throws Exception {
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsa.init(Cipher.DECRYPT_MODE, readerKey);
        return rsa.doFinal(wrappedKey);
    }
}
