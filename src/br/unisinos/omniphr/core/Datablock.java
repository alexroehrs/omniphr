package br.unisinos.omniphr.core;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A chained health datablock, the basic unit of the OmniPHR model.
 *
 * The PHR is divided into datablocks chained as in blockchain technology:
 * each block carries the time it was created and the hash pointer of the
 * previous datablock. Every datablock is encrypted and carries the digital
 * signature of the single responsible informant (patient, health
 * professional, authorized third-party or sensor).
 *
 * The clinical content itself is an openEHR archetype instance, stored here
 * only in encrypted form ({@link #getEncryptedContent()}).
 */
public final class Datablock {

    /** Previous-hash marker of the first block of a patient's chain. */
    public static final String GENESIS_PREVIOUS_HASH = "0000000000000000";

    private final String blockId;             // globally unique block identifier
    private final String patientId;           // unique patient hash id
    private final long sequence;              // position in the patient's chain
    private final Instant createdAt;          // time the block was created
    private final String previousHash;        // hash pointer to the previous datablock
    private final String archetypeId;         // openEHR archetype identifier
    private final HealthCategory category;    // logical/hierarchical division
    private final StandardFormat sourceStandard; // standard used by the source provider
    private final String authorId;            // the one responsible for the information
    private final AuthorRole authorRole;
    private final byte[] encryptedContent;    // archetype payload, encrypted
    private final Map<String, byte[]> encryptedContentKeys; // content key wrapped per authorized reader
    private final String contentHash;         // SHA-256 of the canonical plaintext content
    private final byte[] signature;           // author's digital signature
    private final String hash;                // this block's hash, referenced by the next block

    public Datablock(String blockId, String patientId, long sequence, Instant createdAt,
                     String previousHash, String archetypeId, HealthCategory category,
                     StandardFormat sourceStandard, String authorId, AuthorRole authorRole,
                     byte[] encryptedContent, Map<String, byte[]> encryptedContentKeys,
                     String contentHash, byte[] signature) {
        this.blockId = blockId;
        this.patientId = patientId;
        this.sequence = sequence;
        this.createdAt = createdAt;
        this.previousHash = previousHash;
        this.archetypeId = archetypeId;
        this.category = category;
        this.sourceStandard = sourceStandard;
        this.authorId = authorId;
        this.authorRole = authorRole;
        this.encryptedContent = encryptedContent.clone();
        this.encryptedContentKeys = new ConcurrentHashMap<>(encryptedContentKeys);
        this.contentHash = contentHash;
        this.signature = signature.clone();
        this.hash = computeHash();
    }

    /**
     * Header bytes covered by both the block hash and the digital signature.
     * Includes the hash pointer to the previous block, so a signature also
     * seals the block position in the chain.
     */
    public String headerString() {
        return buildHeaderString(blockId, patientId, sequence, createdAt, previousHash,
                archetypeId, category, authorId, authorRole, contentHash);
    }

    /**
     * Static form of the header, so the author can digitally sign the block
     * header before the block object is materialized.
     */
    public static String buildHeaderString(String blockId, String patientId, long sequence,
                                           Instant createdAt, String previousHash, String archetypeId,
                                           HealthCategory category, String authorId, AuthorRole authorRole,
                                           String contentHash) {
        return blockId + '|' + patientId + '|' + sequence + '|' + createdAt.toEpochMilli()
                + '|' + previousHash + '|' + archetypeId + '|' + category + '|' + authorId
                + '|' + authorRole + '|' + contentHash;
    }

    private String computeHash() {
        return HashUtil.sha256Hex(headerString());
    }

    /** Recomputes the hash from the current fields (integrity check). */
    public boolean hashIsConsistent() {
        return hash.equals(computeHash());
    }

    public String getBlockId() {
        return blockId;
    }

    public String getPatientId() {
        return patientId;
    }

    public long getSequence() {
        return sequence;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getPreviousHash() {
        return previousHash;
    }

    public String getArchetypeId() {
        return archetypeId;
    }

    public HealthCategory getCategory() {
        return category;
    }

    public StandardFormat getSourceStandard() {
        return sourceStandard;
    }

    public String getAuthorId() {
        return authorId;
    }

    public AuthorRole getAuthorRole() {
        return authorRole;
    }

    public byte[] getEncryptedContent() {
        return encryptedContent.clone();
    }

    /** Content key wrapped with the public key of each authorized reader. */
    public Map<String, byte[]> getEncryptedContentKeys() {
        return encryptedContentKeys;
    }

    public void addEncryptedContentKey(String readerId, byte[] wrappedKey) {
        encryptedContentKeys.put(readerId, wrappedKey);
    }

    public void removeEncryptedContentKey(String readerId) {
        encryptedContentKeys.remove(readerId);
    }

    public String getContentHash() {
        return contentHash;
    }

    public byte[] getSignature() {
        return signature.clone();
    }

    public String getHash() {
        return hash;
    }

    @Override
    public String toString() {
        return "Datablock{seq=" + sequence + ", category=" + category + ", archetype=" + archetypeId
                + ", author=" + authorId + " (" + authorRole + "), hash=" + hash.substring(0, 12) + "...}";
    }
}
