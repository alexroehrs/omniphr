package br.unisinos.omniphr.p2p.chord;

/**
 * Accumulator for the one-way hop count and one-way latency of a single
 * routed message, matching the metrics collected by the evaluation harness.
 */
public final class Hops {

    private int count;
    private double latencyMs;

    public void addHop(double hopLatencyMs) {
        count++;
        latencyMs += hopLatencyMs;
    }

    public int getCount() {
        return count;
    }

    public double getLatencyMs() {
        return latencyMs;
    }
}
