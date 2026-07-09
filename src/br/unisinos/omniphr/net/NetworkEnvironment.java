package br.unisinos.omniphr.net;

import br.unisinos.omniphr.p2p.chord.ChordId;
import br.unisinos.omniphr.p2p.chord.ChordNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Self-contained in-process P2P network environment: nodes grouped into subnetworks, each
 * subnetwork served by routing overlays, and subnetworks interconnected by
 * backbone routers. Link latencies are configurable: a hop inside a subnetwork
 * is cheaper than a hop crossing a backbone router.
 */
public class NetworkEnvironment {

    private final Map<String, ChordNode> nodesByName = new ConcurrentHashMap<>();
    private final List<ChordNode> nodes = new ArrayList<>();
    private final Random random;
    private final Metrics metrics = new Metrics();

    /** Default one-hop latency bounds, in milliseconds. */
    private static final double INTRA_SUBNET_MIN_MS = 10, INTRA_SUBNET_MAX_MS = 40;
    private static final double INTER_SUBNET_MIN_MS = 20, INTER_SUBNET_MAX_MS = 60;
    private static final double BACKBONE_MIN_MS = 10, BACKBONE_MAX_MS = 30;

    /**
     * Subnetworks per backbone router domain: crossing backbone domains
     * costs one extra backbone traversal.
     */
    private int subnetsPerBackboneDomain = Integer.MAX_VALUE;

    public NetworkEnvironment(long seed) {
        this.random = new Random(seed);
    }

    public void setSubnetsPerBackboneDomain(int subnetsPerBackboneDomain) {
        this.subnetsPerBackboneDomain = Math.max(1, subnetsPerBackboneDomain);
    }

    public synchronized void register(ChordNode node) {
        nodesByName.put(node.getName(), node);
        nodes.add(node);
    }

    public synchronized void unregister(ChordNode node) {
        nodesByName.remove(node.getName());
        nodes.remove(node);
    }

    public ChordNode byName(String name) {
        return nodesByName.get(name);
    }

    public synchronized List<ChordNode> allNodes() {
        return new ArrayList<>(nodes);
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public Random getRandom() {
        return random;
    }

    /**
     * Latency of a single hop between two nodes. Crossing
     * subnetworks adds the backbone router cost.
     */
    public double hopLatencyMs(ChordNode from, ChordNode to) {
        if (from == to) {
            return 0;
        }
        synchronized (random) {
            if (from.getSubnetId() == to.getSubnetId()) {
                return uniform(INTRA_SUBNET_MIN_MS, INTRA_SUBNET_MAX_MS);
            }
            double latency = uniform(INTER_SUBNET_MIN_MS, INTER_SUBNET_MAX_MS)
                    + uniform(BACKBONE_MIN_MS, BACKBONE_MAX_MS);
            if (from.getSubnetId() / subnetsPerBackboneDomain
                    != to.getSubnetId() / subnetsPerBackboneDomain) {
                latency += uniform(BACKBONE_MIN_MS, BACKBONE_MAX_MS); // extra backbone traversal
            }
            return latency;
        }
    }

    private double uniform(double min, double max) {
        return min + random.nextDouble() * (max - min);
    }

    /**
     * Deterministically converges the ring (successors, predecessors,
     * successor lists and finger tables) from the current set of alive
     * nodes. Used to bootstrap large evaluation setups quickly; incremental
     * joins/leaves then run the regular Chord maintenance.
     */
    public synchronized void convergeRing() {
        List<ChordNode> ring = new ArrayList<>();
        for (ChordNode n : nodes) {
            if (n.isAlive()) {
                ring.add(n);
            }
        }
        if (ring.isEmpty()) {
            return;
        }
        ring.sort(Comparator.comparing(ChordNode::getId));
        int n = ring.size();
        for (int i = 0; i < n; i++) {
            ChordNode node = ring.get(i);
            node.setSuccessor(ring.get((i + 1) % n));
            node.setPredecessor(ring.get((i - 1 + n) % n));
            List<ChordNode> succList = new ArrayList<>();
            for (int k = 1; k <= ChordNode.SUCCESSOR_LIST_SIZE; k++) {
                succList.add(ring.get((i + k) % n));
            }
            node.setSuccessorList(succList);
            for (int f = 0; f < ChordId.M; f++) {
                node.setFinger(f, successorOf(ring, node.getId().addPowerOfTwo(f)));
            }
        }
    }

    /** Binary search for the first node whose id >= key (circular). */
    private ChordNode successorOf(List<ChordNode> sortedRing, ChordId key) {
        int lo = 0, hi = sortedRing.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (sortedRing.get(mid).getId().value().compareTo(key.value()) >= 0) {
                ans = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return ans == -1 ? sortedRing.get(0) : sortedRing.get(ans);
    }
}
