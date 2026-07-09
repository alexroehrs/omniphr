package br.unisinos.omniphr.p2p.chord;

import br.unisinos.omniphr.core.Datablock;
import br.unisinos.omniphr.net.SimulatedNetwork;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A node of the Chord ring.
 *
 * Chord operates two structures besides the routing itself: the finger
 * table, whose first entry always refers to the successor node, and the
 * successor list, which works as the engine that allows replicating data.
 * Lookups are resolved in O(log N) hops, where N is the number of nodes.
 *
 * Every node of the network (regular/leaf nodes and routing overlays alike)
 * participates in the ring and may hold replicated datablocks.
 */
public class ChordNode {

    /** Successor list length, which is also the replication factor. */
    public static final int SUCCESSOR_LIST_SIZE = 4;

    private final ChordId id;
    private final String name;
    private final SimulatedNetwork network;
    private final int subnetId;

    private volatile ChordNode predecessor;
    private final ChordNode[] fingers = new ChordNode[ChordId.M];
    private final List<ChordNode> successorList = new ArrayList<>();
    private volatile boolean alive = true;
    private int nextFingerToFix = 0;

    /** Local replica store of datablocks placed on this node by the DHT. */
    private final Map<String, Datablock> store = new ConcurrentHashMap<>();

    public ChordNode(String name, SimulatedNetwork network, int subnetId) {
        this.name = name;
        this.network = network;
        this.subnetId = subnetId;
        this.id = ChordId.of(name);
    }

    // ------------------------------------------------------------------
    // Ring construction and maintenance (join, stabilize, notify, fix)
    // ------------------------------------------------------------------

    /** Creates a new ring with this node alone. */
    public synchronized void create() {
        predecessor = null;
        fingers[0] = this;
    }

    /** Joins the ring known by the bootstrap node. */
    public synchronized void join(ChordNode bootstrap) {
        predecessor = null;
        fingers[0] = bootstrap.findSuccessor(id, new Hops());
    }

    /** Periodic stabilization, driven by the Nodes Manager. */
    public void stabilize() {
        if (!alive) {
            return;
        }
        ChordNode succ = successor();
        if (succ == null || !succ.alive) {
            succ = firstAliveSuccessor();
            fingers[0] = succ;
        }
        if (succ == this && predecessor != null && predecessor.alive && predecessor != this) {
            fingers[0] = predecessor;
            succ = predecessor;
        }
        ChordNode x = succ.predecessor;
        if (x != null && x.alive && x.id.inOpenInterval(this.id, succ.id)) {
            fingers[0] = x;
            succ = x;
        }
        if (succ != this) {
            succ.notifyFrom(this);
        }
        refreshSuccessorList();
    }

    /** notify() of the Chord algorithm. */
    public void notifyFrom(ChordNode n) {
        if (predecessor == null || !predecessor.alive || n.id.inOpenInterval(predecessor.id, this.id)) {
            predecessor = n;
        }
    }

    /** Refreshes one finger table entry per call, as in the original algorithm. */
    public void fixFingers() {
        if (!alive) {
            return;
        }
        nextFingerToFix = (nextFingerToFix + 1) % ChordId.M;
        fingers[nextFingerToFix] = findSuccessor(id.addPowerOfTwo(nextFingerToFix), new Hops());
    }

    /** Rebuilds the whole finger table at once (used to converge quickly). */
    public void fixAllFingers() {
        for (int i = 0; i < ChordId.M; i++) {
            fingers[i] = findSuccessor(id.addPowerOfTwo(i), new Hops());
        }
    }

    private void refreshSuccessorList() {
        synchronized (successorList) {
            successorList.clear();
            ChordNode cur = successor();
            for (int i = 0; i < SUCCESSOR_LIST_SIZE && cur != null && cur != this; i++) {
                successorList.add(cur);
                cur = cur.successor();
                if (cur != null && !cur.alive) {
                    break;
                }
            }
        }
    }

    private ChordNode firstAliveSuccessor() {
        synchronized (successorList) {
            for (ChordNode n : successorList) {
                if (n.alive) {
                    return n;
                }
            }
        }
        return this;
    }

    // ------------------------------------------------------------------
    // Lookup, O(log N)
    // ------------------------------------------------------------------

    /**
     * Finds the node responsible for the given key. Each forwarding between
     * nodes counts as one hop and accumulates the (simulated) link latency,
     * which feeds the evaluation metrics.
     */
    public ChordNode findSuccessor(ChordId key, Hops hops) {
        ChordNode current = this;
        int safety = 4 * ChordId.M;
        while (safety-- > 0) {
            ChordNode succ = current.successor();
            if (succ == null) {
                return current;
            }
            if (key.inRightClosedInterval(current.id, succ.id)) {
                if (current != this || succ != this) {
                    hops.addHop(network.hopLatencyMs(current, succ));
                }
                return succ;
            }
            ChordNode next = current.closestPrecedingNode(key);
            if (next == current) {
                hops.addHop(network.hopLatencyMs(current, succ));
                return succ;
            }
            hops.addHop(network.hopLatencyMs(current, next));
            current = next;
        }
        return current;
    }

    private ChordNode closestPrecedingNode(ChordId key) {
        for (int i = ChordId.M - 1; i >= 0; i--) {
            ChordNode f = fingers[i];
            if (f != null && f.alive && f.id.inOpenInterval(this.id, key)) {
                return f;
            }
        }
        return this;
    }

    // ------------------------------------------------------------------
    // Datablock replica storage
    // ------------------------------------------------------------------

    public void storeBlock(Datablock block) {
        store.put(block.getBlockId(), block);
    }

    public Datablock getBlock(String blockId) {
        return store.get(blockId);
    }

    public Map<String, Datablock> getStore() {
        return store;
    }

    /**
     * Graceful departure: replicas are handed to the successor and the
     * routing overlay is notified so it can disseminate copies where needed.
     */
    public synchronized List<Datablock> leave() {
        alive = false;
        List<Datablock> handedOver = new ArrayList<>(store.values());
        ChordNode succ = firstAliveSuccessor();
        if (succ != this && succ.alive) {
            for (Datablock b : handedOver) {
                succ.storeBlock(b);
            }
        }
        store.clear();
        return handedOver;
    }

    /** Abrupt failure (no hand-over), used to demonstrate replica recovery. */
    public void fail() {
        alive = false;
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public ChordNode successor() {
        return fingers[0];
    }

    public void setSuccessor(ChordNode n) {
        fingers[0] = n;
    }

    public void setFinger(int i, ChordNode n) {
        fingers[i] = n;
    }

    public ChordNode getPredecessor() {
        return predecessor;
    }

    public void setPredecessor(ChordNode n) {
        predecessor = n;
    }

    public List<ChordNode> getSuccessorList() {
        synchronized (successorList) {
            return new ArrayList<>(successorList);
        }
    }

    public void setSuccessorList(List<ChordNode> nodes) {
        synchronized (successorList) {
            successorList.clear();
            successorList.addAll(nodes);
        }
    }

    public ChordId getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getSubnetId() {
        return subnetId;
    }

    public boolean isAlive() {
        return alive;
    }

    @Override
    public String toString() {
        return name + "@" + id;
    }
}
