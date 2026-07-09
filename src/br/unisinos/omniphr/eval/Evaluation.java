package br.unisinos.omniphr.eval;

import br.unisinos.omniphr.core.StandardFormat;
import br.unisinos.omniphr.net.Metrics;
import br.unisinos.omniphr.net.SimulatedNetwork;
import br.unisinos.omniphr.node.RegularNode;
import br.unisinos.omniphr.overlay.RoutingOverlay;
import br.unisinos.omniphr.p2p.chord.ChordId;
import br.unisinos.omniphr.p2p.chord.ChordNode;
import br.unisinos.omniphr.p2p.chord.Hops;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Evaluation harness following a modeling and profiling methodology over the
 * in-process network: setups with the number of nodes growing from 100 to
 * 3200, and for each setup two tests (A and B), where Test B quadruplicates
 * the routing overlays and backbone routers.
 *
 * For each test: (a) the target number of nodes enters the network, with
 * churn calibrated to at most 5% of entrances and outputs during the test
 * period; (b) messages are randomly triggered at ranges up to 1 s
 * concurrently between nodes, each one representing one health datablock
 * transmitted.
 *
 * Collected results: Messages Present (MP), One-way Hop Count (OHC) and
 * One-way Latency (OL).
 */
public class Evaluation {

    /** Evaluation setups: {nodes, RO-A, RO-B, BR-A, BR-B}. */
    private static final int[][] SETUPS = {
            {100, 4, 16, 1, 4},
            {200, 5, 20, 2, 8},
            {400, 6, 24, 3, 12},
            {800, 8, 32, 4, 16},
            {1200, 10, 40, 5, 20},
            {1600, 12, 48, 6, 24},
            {2000, 14, 56, 7, 28},
            {2400, 16, 64, 8, 32},
            {2800, 18, 72, 9, 36},
            {3200, 20, 80, 10, 40},
    };

    private final long seed;
    private final double durationSeconds;
    private final int maxSetups;

    public Evaluation(long seed, double durationSeconds, boolean full) {
        this.seed = seed;
        this.durationSeconds = durationSeconds;
        this.maxSetups = full ? SETUPS.length : 4;
    }

    public void run() {
        System.out.println();
        System.out.println("OmniPHR evaluation - " + maxSetups + " setups, 2 tests each,"
                + " simulated period of " + (int) durationSeconds + " s per test");
        System.out.println();
        System.out.printf("%-6s %-6s | %-9s %-9s | %-19s | %-15s | %-15s%n",
                "Setup", "N", "RO (A/B)", "BR (A/B)", "MP (A/B)", "OHC (A/B)", "OL s (A/B)");
        System.out.println(rule('-', 100));
        for (int s = 0; s < maxSetups; s++) {
            int[] cfg = SETUPS[s];
            double[] a = runTest(cfg[0], cfg[1], cfg[3], seed + s * 2L);
            double[] b = runTest(cfg[0], cfg[2], cfg[4], seed + s * 2L + 1);
            System.out.printf("%-6d %-6d | %-4d/%-4d | %-4d/%-4d | %8.0f /%8.0f | %6.2f /%6.2f | %6.3f /%6.3f%n",
                    s + 1, cfg[0], cfg[1], cfg[2], cfg[3], cfg[4],
                    a[0], b[0], a[1], b[1], a[2], b[2]);
        }
        System.out.println(rule('-', 100));
        System.out.println("MP = avg. messages present (in transmission) at every instant;");
        System.out.println("OHC = avg. one-way hop count; OL = avg. one-way latency (seconds).");
    }

    private static String rule(char c, int width) {
        StringBuilder sb = new StringBuilder(width);
        for (int i = 0; i < width; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Runs one test; returns {MP, OHC, OL(s)}.
     */
    private double[] runTest(int nodeCount, int routingOverlays, int backboneRouters, long testSeed) {
        SimulatedNetwork network = new SimulatedNetwork(testSeed);
        network.setSubnetsPerBackboneDomain(Math.max(1, routingOverlays / Math.max(1, backboneRouters)));
        Random random = new Random(testSeed);

        // --- entry of the target number of nodes ---
        List<ChordNode> nodes = new ArrayList<>();
        for (int i = 0; i < routingOverlays; i++) {
            RoutingOverlay overlay = new RoutingOverlay("overlay-" + testSeed + "-" + i, network, i);
            network.register(overlay);
            nodes.add(overlay);
        }
        for (int i = routingOverlays; i < nodeCount; i++) {
            RegularNode node = new RegularNode("node-" + testSeed + "-" + i, network,
                    i % routingOverlays, RegularNode.Kind.HOSPITAL_EHR, StandardFormat.OPENEHR);
            network.register(node);
            nodes.add(node);
        }
        network.convergeRing();

        // --- churn calibrated to at most 5% of entrances and outputs ---
        int churn = Math.max(1, (int) (nodeCount * 0.025));
        for (int i = 0; i < churn; i++) {
            ChordNode leaving = nodes.get(routingOverlays + random.nextInt(nodes.size() - routingOverlays));
            if (leaving.isAlive()) {
                leaving.leave();
            }
        }
        for (int i = 0; i < churn; i++) {
            RegularNode entering = new RegularNode("late-" + testSeed + "-" + i, network,
                    random.nextInt(routingOverlays), RegularNode.Kind.PATIENT_DEVICE, StandardFormat.OPENEHR);
            network.register(entering);
            entering.join(nodes.get(random.nextInt(routingOverlays))); // bootstrap by an overlay
            entering.fixAllFingers();
            nodes.add(entering);
        }
        // some maintenance rounds after churn, so Chord adjusts its tables
        for (int round = 0; round < 3; round++) {
            for (ChordNode n : network.allNodes()) {
                if (n.isAlive()) {
                    n.stabilize();
                    n.fixFingers();
                }
            }
        }

        // --- random messages at ranges up to 1 s between nodes ---
        Metrics metrics = network.getMetrics();
        metrics.reset();
        List<ChordNode> alive = new ArrayList<>();
        for (ChordNode n : network.allNodes()) {
            if (n.isAlive()) {
                alive.add(n);
            }
        }
        for (ChordNode source : alive) {
            double t = random.nextDouble(); // node's own start offset
            while (t < durationSeconds) {
                ChordId key = ChordId.of("datablock-" + random.nextLong());
                Hops hops = new Hops();
                source.findSuccessor(key, hops);
                metrics.recordMessage(hops.getCount(), hops.getLatencyMs());
                t += 0.05 + random.nextDouble() * 0.95; // random interval of up to 1 s
            }
        }
        return new double[]{
                metrics.messagesPresent(durationSeconds),
                metrics.averageHopCount(),
                metrics.averageLatencySeconds()};
    }
}
