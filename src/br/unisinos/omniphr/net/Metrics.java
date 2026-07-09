package br.unisinos.omniphr.net;

/**
 * Collector of the evaluation metrics:
 *
 * - Messages Present (MP): average number of messages present on the
 *   network at every instant, i.e. concurrently in transmission;
 * - One-way Hop Count (OHC): average number of hops each message jumps
 *   between nodes in one way (source to target);
 * - One-way Latency (OL): average delay, in seconds, for a message to
 *   traverse the network from the source node to the target node.
 *
 * MP is the time-average of the number of in-flight messages, computed as
 * (sum of message latencies) / (observation period).
 */
public class Metrics {

    private long messageCount;
    private long totalHops;
    private double totalLatencyMs;

    public synchronized void recordMessage(int hops, double latencyMs) {
        messageCount++;
        totalHops += hops;
        totalLatencyMs += latencyMs;
    }

    public synchronized void reset() {
        messageCount = 0;
        totalHops = 0;
        totalLatencyMs = 0;
    }

    public synchronized long getMessageCount() {
        return messageCount;
    }

    public synchronized double averageHopCount() {
        return messageCount == 0 ? 0 : (double) totalHops / messageCount;
    }

    public synchronized double averageLatencySeconds() {
        return messageCount == 0 ? 0 : (totalLatencyMs / messageCount) / 1000.0;
    }

    /** Time-average of concurrently in-flight messages over the given period. */
    public synchronized double messagesPresent(double periodSeconds) {
        return periodSeconds <= 0 ? 0 : (totalLatencyMs / 1000.0) / periodSeconds;
    }
}
