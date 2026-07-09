package br.unisinos.omniphr.node;

import br.unisinos.omniphr.core.AuthorRole;
import br.unisinos.omniphr.middleware.security.DigitalSigner;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * A user of the system (patient, health professional, authorized
 * third-party) or a sensor device acting as informant.
 *
 * The actor holds the own key pair: the private key is secret and never
 * leaves the actor, while the public key is distributed with the user
 * identifier. Every datablock the actor informs is digitally signed with
 * the private key.
 */
public class Actor {

    private final String fullName;
    private final AuthorRole role;
    private final KeyPair keyPair;
    private final DigitalSigner signer = new DigitalSigner();

    private String openId;  // assigned on registration

    public Actor(String fullName, AuthorRole role, KeyPair keyPair) {
        this.fullName = fullName;
        this.role = role;
        this.keyPair = keyPair;
    }

    /** Signs data with the actor's private key (executed at the source). */
    public byte[] sign(String data) {
        return signer.sign(data, keyPair.getPrivate());
    }

    public PublicKey getPublicKey() {
        return keyPair.getPublic();
    }

    /**
     * Exposed only to the actor itself (in-process demo); used by the
     * patient to decrypt own blocks and to re-wrap content keys on grants.
     */
    public PrivateKey getPrivateKey() {
        return keyPair.getPrivate();
    }

    public String getFullName() {
        return fullName;
    }

    public AuthorRole getRole() {
        return role;
    }

    public String getOpenId() {
        return openId;
    }

    public void setOpenId(String openId) {
        this.openId = openId;
    }

    @Override
    public String toString() {
        return fullName + " (" + role + ")";
    }
}
