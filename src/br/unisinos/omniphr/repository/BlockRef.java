package br.unisinos.omniphr.repository;

import br.unisinos.omniphr.core.HealthCategory;

import java.time.Instant;

/**
 * Index entry pointing to a datablock and its location on the network.
 * Supports the routing overlay responsibilities of keeping PHR data and
 * querying datablocks to assemble the PHR when required, giving the
 * Distributor the knowledge of datablock locations.
 */
public class BlockRef {

    private final String blockId;
    private final String blockHash;
    private final long sequence;
    private final HealthCategory category;
    private final String archetypeId;
    private final Instant createdAt;
    private final String originNodeName;   // where the original record is kept

    public BlockRef(String blockId, String blockHash, long sequence, HealthCategory category,
                    String archetypeId, Instant createdAt, String originNodeName) {
        this.blockId = blockId;
        this.blockHash = blockHash;
        this.sequence = sequence;
        this.category = category;
        this.archetypeId = archetypeId;
        this.createdAt = createdAt;
        this.originNodeName = originNodeName;
    }

    public String getBlockId() {
        return blockId;
    }

    public String getBlockHash() {
        return blockHash;
    }

    public long getSequence() {
        return sequence;
    }

    public HealthCategory getCategory() {
        return category;
    }

    public String getArchetypeId() {
        return archetypeId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getOriginNodeName() {
        return originNodeName;
    }
}
