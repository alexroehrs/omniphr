package br.unisinos.omniphr.p2p.chord;

import br.unisinos.omniphr.core.HashUtil;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * Chord identifier in the circular space of 2^m, with m = 160 bits, produced
 * by consistent hashing (SHA-1) as in the original Chord algorithm
 * (Stoica et al.). Consistent hashing provides load balancing through a
 * uniform distribution of identifiers.
 */
public final class ChordId implements Comparable<ChordId> {

    public static final int M = 160;
    public static final BigInteger RING_SIZE = BigInteger.ONE.shiftLeft(M);

    private final BigInteger value;

    private ChordId(BigInteger value) {
        this.value = value.mod(RING_SIZE);
    }

    public static ChordId of(String key) {
        return new ChordId(new BigInteger(1, HashUtil.sha1(key.getBytes(StandardCharsets.UTF_8))));
    }

    public static ChordId ofRaw(BigInteger raw) {
        return new ChordId(raw);
    }

    public BigInteger value() {
        return value;
    }

    /** id + 2^i (mod 2^m), used to compute finger table entries. */
    public ChordId addPowerOfTwo(int i) {
        return new ChordId(value.add(BigInteger.ONE.shiftLeft(i)));
    }

    /** True if this id lies in the open circular interval (a, b). */
    public boolean inOpenInterval(ChordId a, ChordId b) {
        if (a.value.compareTo(b.value) < 0) {
            return value.compareTo(a.value) > 0 && value.compareTo(b.value) < 0;
        }
        // interval wraps around the ring
        return value.compareTo(a.value) > 0 || value.compareTo(b.value) < 0;
    }

    /** True if this id lies in the circular interval (a, b]. */
    public boolean inRightClosedInterval(ChordId a, ChordId b) {
        return b.value.equals(value) || inOpenInterval(a, b);
    }

    @Override
    public int compareTo(ChordId o) {
        return value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ChordId && value.equals(((ChordId) o).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        String s = value.toString(16);
        return s.length() > 10 ? s.substring(0, 10) : s;
    }
}
