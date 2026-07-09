package br.unisinos.omniphr.middleware.security;

import br.unisinos.omniphr.core.AuthorRole;
import br.unisinos.omniphr.core.HashUtil;
import br.unisinos.omniphr.repository.RelationalRepository;
import br.unisinos.omniphr.repository.UserAccount;

import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authenticator component of the Security and Privacy Module. Ensures
 * authorized access and proper profile attribution, prevents unauthorized
 * access, blocks accounts after repeated failures and provides a lost-access
 * recovery mechanism.
 *
 * When entering the network, each user receives an OpenID-style identifier
 * generated for health record identification. The identifier is derived as a
 * single hash code from the user's natural demographic keys, making the
 * identification unequivocal and avoiding duplicated users. This identifier
 * is the main health record identifier.
 *
 * Authentication is challenge-response over the user's key pair: the user
 * proves possession of the private key by signing a nonce, verified against
 * the public key registered with the identifier.
 */
public class Authenticator {

    public static final int MAX_FAILED_ATTEMPTS = 3;

    private final RelationalRepository repository;
    private final DigitalSigner signer;
    private final SecureRandom random = new SecureRandom();

    /** Open sessions: token -> openId. */
    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    /** Pending challenges: openId -> nonce. */
    private final Map<String, String> challenges = new ConcurrentHashMap<>();

    public Authenticator(RelationalRepository repository, DigitalSigner signer) {
        this.repository = repository;
        this.signer = signer;
    }

    /**
     * Registers a user, generating the unique OpenID-style identifier from
     * the natural keys and refusing duplicated registrations. Returns the
     * created (or pre-existing) account.
     */
    public UserAccount register(String fullName, String birthDate, String documentNumber,
                                AuthorRole role, PublicKey publicKey, String recoveryCode) {
        String openId = generateOpenId(fullName, birthDate, documentNumber);
        UserAccount existing = repository.findUser(openId);
        if (existing != null) {
            // duplication avoided: the same natural keys map to the same id
            return existing;
        }
        UserAccount account = new UserAccount(openId, fullName, role, publicKey,
                HashUtil.sha256Hex(recoveryCode));
        repository.saveUser(account);
        return account;
    }

    /** OpenID-style URI identifier: single hash code of the natural keys. */
    public static String generateOpenId(String fullName, String birthDate, String documentNumber) {
        String naturalKeys = SemanticNormalizer.normalize(fullName) + '|' + birthDate + '|' + documentNumber;
        return "omniphr://id/" + HashUtil.sha256Hex(naturalKeys);
    }

    /** Step 1 of the challenge-response authentication. */
    public String requestChallenge(String openId) {
        UserAccount account = repository.findUser(openId);
        if (account == null) {
            throw new SecurityException("unknown user: " + openId);
        }
        if (account.isBlocked()) {
            throw new SecurityException("account blocked: " + openId);
        }
        String nonce = Long.toHexString(random.nextLong()) + Long.toHexString(random.nextLong());
        challenges.put(openId, nonce);
        return nonce;
    }

    /**
     * Step 2: verifies the signed nonce. On success opens a session; after
     * {@link #MAX_FAILED_ATTEMPTS} failures the account is blocked.
     */
    public String authenticate(String openId, byte[] signedNonce) {
        UserAccount account = repository.findUser(openId);
        if (account == null) {
            throw new SecurityException("unknown user: " + openId);
        }
        if (account.isBlocked()) {
            throw new SecurityException("account blocked: " + openId);
        }
        String nonce = challenges.remove(openId);
        if (nonce != null && signer.verify(nonce, signedNonce, account.getPublicKey())) {
            account.setFailedAttempts(0);
            String token = UUID.randomUUID().toString();
            sessions.put(token, openId);
            return token;
        }
        account.setFailedAttempts(account.getFailedAttempts() + 1);
        if (account.getFailedAttempts() >= MAX_FAILED_ATTEMPTS) {
            account.setBlocked(true);
        }
        throw new SecurityException("authentication failed for " + openId
                + (account.isBlocked() ? " (account is now blocked)" : ""));
    }

    /** Lost-access recovery mechanism. */
    public boolean recoverAccess(String openId, String recoveryCode) {
        UserAccount account = repository.findUser(openId);
        if (account != null && account.getRecoveryCodeHash().equals(HashUtil.sha256Hex(recoveryCode))) {
            account.setBlocked(false);
            account.setFailedAttempts(0);
            return true;
        }
        return false;
    }

    public String sessionOwner(String token) {
        return sessions.get(token);
    }

    /** Small local helper to avoid a dependency cycle with the repository package. */
    static final class SemanticNormalizer {
        static String normalize(String s) {
            return s.toLowerCase().trim().replaceAll("\\s+", " ");
        }
    }
}
