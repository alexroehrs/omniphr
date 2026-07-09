package br.unisinos.omniphr.repository;

import br.unisinos.omniphr.core.AuthorRole;

import java.security.PublicKey;

/**
 * System user register kept by the routing overlay. The public key is
 * distributed with the user identifier.
 */
public class UserAccount {

    private final String openId;        // OpenID-style identifier
    private final String fullName;
    private final AuthorRole role;
    private final PublicKey publicKey;
    private final String recoveryCodeHash;

    private boolean blocked;
    private int failedAttempts;
    private String organizationId;      // for health professionals
    private String profileName;         // organizational profile

    public UserAccount(String openId, String fullName, AuthorRole role,
                       PublicKey publicKey, String recoveryCodeHash) {
        this.openId = openId;
        this.fullName = fullName;
        this.role = role;
        this.publicKey = publicKey;
        this.recoveryCodeHash = recoveryCodeHash;
    }

    public String getOpenId() {
        return openId;
    }

    public String getFullName() {
        return fullName;
    }

    public AuthorRole getRole() {
        return role;
    }

    public PublicKey getPublicKey() {
        return publicKey;
    }

    public String getRecoveryCodeHash() {
        return recoveryCodeHash;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public void setBlocked(boolean blocked) {
        this.blocked = blocked;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public void setFailedAttempts(int failedAttempts) {
        this.failedAttempts = failedAttempts;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }

    public String getProfileName() {
        return profileName;
    }

    public void setProfileName(String profileName) {
        this.profileName = profileName;
    }

    @Override
    public String toString() {
        return fullName + " <" + openId + "> (" + role + ")";
    }
}
