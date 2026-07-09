package br.unisinos.omniphr.middleware.datablock;

import br.unisinos.omniphr.core.Datablock;
import br.unisinos.omniphr.middleware.security.DigitalSigner;

import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Validator component of the Datablock and Service Module. Responsible for
 * validating the health datablock chaining: it checks the integrity of the
 * datablocks, ensures their consistency and their correct sequencing, and
 * authenticates each new datablock before it can form the next datablock in
 * the chain.
 */
public class Validator {

    /** Outcome of a chain or block validation. */
    public static final class ValidationResult {
        private final boolean valid;
        private final List<String> issues;

        ValidationResult(boolean valid, List<String> issues) {
            this.valid = valid;
            this.issues = issues;
        }

        public boolean isValid() {
            return valid;
        }

        public List<String> getIssues() {
            return issues;
        }

        @Override
        public String toString() {
            return valid ? "VALID" : "INVALID " + issues;
        }
    }

    private final DigitalSigner signer;

    public Validator(DigitalSigner signer) {
        this.signer = signer;
    }

    /**
     * Authenticates a new datablock before it is appended to the chain: the
     * author signature must verify, the block must point to the current
     * chain head, and the sequencing/time must be correct.
     */
    public ValidationResult authenticateNewBlock(Datablock block, Datablock currentHead,
                                                 PublicKey authorPublicKey) {
        List<String> issues = new ArrayList<>();
        if (!block.hashIsConsistent()) {
            issues.add("block hash does not match its content");
        }
        if (authorPublicKey == null) {
            issues.add("unknown author " + block.getAuthorId());
        } else if (!signer.verify(block.headerString(), block.getSignature(), authorPublicKey)) {
            issues.add("invalid digital signature of author " + block.getAuthorId());
        }
        if (currentHead == null) {
            if (block.getSequence() != 0) {
                issues.add("first block of the chain must have sequence 0");
            }
            if (!Datablock.GENESIS_PREVIOUS_HASH.equals(block.getPreviousHash())) {
                issues.add("first block must point to the genesis marker");
            }
        } else {
            if (!block.getPreviousHash().equals(currentHead.getHash())) {
                issues.add("previous-hash pointer does not match the chain head");
            }
            if (block.getSequence() != currentHead.getSequence() + 1) {
                issues.add("wrong sequence: expected " + (currentHead.getSequence() + 1)
                        + ", got " + block.getSequence());
            }
            if (block.getCreatedAt().isBefore(currentHead.getCreatedAt())) {
                issues.add("block is older than the current chain head");
            }
        }
        return new ValidationResult(issues.isEmpty(), issues);
    }

    /**
     * Validates a whole retrieved chain: hash pointers, per-block integrity,
     * sequencing and the digital signature of every responsible informant,
     * checking whether the chaining is intact or had some manipulation.
     */
    public ValidationResult validateChain(List<Datablock> chain,
                                          Function<String, PublicKey> authorKeyLookup) {
        List<String> issues = new ArrayList<>();
        List<Datablock> ordered = new ArrayList<>(chain);
        ordered.sort(Comparator.comparingLong(Datablock::getSequence));
        Datablock previous = null;
        for (Datablock block : ordered) {
            String at = "block seq " + block.getSequence();
            if (!block.hashIsConsistent()) {
                issues.add(at + ": hash inconsistent with content (possible manipulation)");
            }
            PublicKey key = authorKeyLookup.apply(block.getAuthorId());
            if (key == null) {
                issues.add(at + ": unknown author " + block.getAuthorId());
            } else if (!signer.verify(block.headerString(), block.getSignature(), key)) {
                issues.add(at + ": invalid signature of " + block.getAuthorId());
            }
            if (previous == null) {
                if (block.getSequence() != 0) {
                    issues.add(at + ": chain does not start at sequence 0");
                }
            } else {
                if (block.getSequence() != previous.getSequence() + 1) {
                    issues.add(at + ": sequence gap after " + previous.getSequence());
                }
                if (!block.getPreviousHash().equals(previous.getHash())) {
                    issues.add(at + ": broken hash pointer to previous block");
                }
            }
            previous = block;
        }
        return new ValidationResult(issues.isEmpty(), issues);
    }
}
