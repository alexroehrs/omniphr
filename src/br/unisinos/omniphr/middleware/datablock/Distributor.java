package br.unisinos.omniphr.middleware.datablock;

import br.unisinos.omniphr.core.AuthorRole;
import br.unisinos.omniphr.core.Datablock;
import br.unisinos.omniphr.net.NetworkEnvironment;
import br.unisinos.omniphr.p2p.chord.ChordId;
import br.unisinos.omniphr.p2p.chord.ChordNode;
import br.unisinos.omniphr.p2p.chord.Hops;

import java.util.List;

/**
 * Distributor component of the Datablock and Service Module. In charge of
 * distributing and replicating datablocks on the network, with knowledge of
 * datablock locations and the ability to fetch a datablock from the node
 * that holds the requested data and return it to the requester.
 *
 * Placement rules: datablocks are stored on the computer where they were
 * created, with copies distributed on the routing overlay and on the network
 * following the DHT algorithm; data informed by the patient is stored on the
 * routing overlay, also with copies distributed on the network.
 */
public class Distributor {

    private final NetworkEnvironment network;
    private final ChordNode overlayNode;         // the routing overlay this middleware runs on
    private final MessageRouter messageRouter;   // provides the time-limited cache

    public Distributor(NetworkEnvironment network, ChordNode overlayNode, MessageRouter messageRouter) {
        this.network = network;
        this.overlayNode = overlayNode;
        this.messageRouter = messageRouter;
    }

    /**
     * Stores the block according to the placement rules of the model and
     * replicates copies through the ring using the successor list as the
     * replication engine.
     *
     * @param originNode node of the healthcare provider that created the
     *                   data, or the patient's device node (in which case
     *                   the original is kept on the routing overlay).
     * @return number of hops used to place the DHT copies.
     */
    public Hops store(Datablock block, ChordNode originNode) {
        Hops hops = new Hops();
        if (block.getAuthorRole() == AuthorRole.PATIENT || block.getAuthorRole() == AuthorRole.SENSOR) {
            // data informed by the patient (or the patient's sensors) is
            // stored on the routing overlay
            overlayNode.storeBlock(block);
        } else if (originNode != null) {
            // original data reported by a healthcare provider remains
            // stored in the health organization
            originNode.storeBlock(block);
        }
        // copies on the routing overlay ...
        overlayNode.storeBlock(block);
        // ... and on the network following the DHT algorithm
        replicate(block, hops);
        network.getMetrics().recordMessage(hops.getCount(), hops.getLatencyMs());
        return hops;
    }

    /** Places the DHT copies of a block (owner node + successor list). */
    public void replicate(Datablock block, Hops hops) {
        ChordId key = ChordId.of(block.getBlockId());
        ChordNode owner = overlayNode.findSuccessor(key, hops);
        owner.storeBlock(block);
        List<ChordNode> successors = owner.getSuccessorList();
        int copies = 0;
        for (ChordNode replica : successors) {
            if (replica != owner && replica.isAlive()) {
                replica.storeBlock(block);
                copies++;
            }
            if (copies >= ChordNode.SUCCESSOR_LIST_SIZE - 1) {
                break;
            }
        }
    }

    /**
     * Fetches a datablock from the appropriate node that contains the
     * requested data and returns it to the requester, passing first through
     * the time-limited cache of the Message Router.
     */
    public Datablock fetch(String blockId, Hops hops) {
        Datablock cached = messageRouter.cachedBlock(blockId);
        if (cached != null) {
            return cached;
        }
        Datablock local = overlayNode.getBlock(blockId);
        if (local != null) {
            messageRouter.cacheBlock(local);
            return local;
        }
        ChordId key = ChordId.of(blockId);
        ChordNode owner = overlayNode.findSuccessor(key, hops);
        Datablock block = owner.getBlock(blockId);
        if (block == null) {
            // owner may have changed after churn: look into its successors
            for (ChordNode replica : owner.getSuccessorList()) {
                hops.addHop(network.hopLatencyMs(owner, replica));
                block = replica.getBlock(blockId);
                if (block != null) {
                    break;
                }
            }
        }
        if (block != null) {
            messageRouter.cacheBlock(block);
        }
        network.getMetrics().recordMessage(hops.getCount(), hops.getLatencyMs());
        return block;
    }
}
