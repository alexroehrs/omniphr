package br.unisinos.omniphr.p2p.pubsub;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Publish-subscribe system supporting the communication among nodes.
 * Publisher nodes publish messages to a service hosted on the routing
 * overlay, and subscribers get these messages, in an indirect communication
 * between nodes.
 *
 * One instance of this service runs on each routing overlay. Topics are
 * used, for example, to notify interested parties (patient devices, treating
 * organizations) that a new or updated datablock is available.
 */
public class PubSubService {

    /** A subscriber registered on a topic. */
    public interface Subscriber {
        String subscriberId();

        void onMessage(String topic, PubSubMessage message);
    }

    private final String overlayName;
    private final Map<String, List<Subscriber>> topics = new ConcurrentHashMap<>();

    public PubSubService(String overlayName) {
        this.overlayName = overlayName;
    }

    public void subscribe(String topic, Subscriber subscriber) {
        topics.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(subscriber);
    }

    public void unsubscribe(String topic, Subscriber subscriber) {
        List<Subscriber> list = topics.get(topic);
        if (list != null) {
            list.removeIf(s -> s.subscriberId().equals(subscriber.subscriberId()));
        }
    }

    /** Publishes a message; delivery is indirect, through this service. */
    public int publish(String topic, PubSubMessage message) {
        List<Subscriber> list = topics.get(topic);
        if (list == null || list.isEmpty()) {
            return 0;
        }
        for (Subscriber s : list) {
            s.onMessage(topic, message);
        }
        return list.size();
    }

    public String getOverlayName() {
        return overlayName;
    }
}
