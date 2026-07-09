package br.unisinos.omniphr.p2p.pubsub;

import java.time.Instant;

/** Message exchanged through the publish-subscribe service. */
public final class PubSubMessage {

    public enum Type {
        DATABLOCK_PUBLISHED,
        DATABLOCK_UPDATED,
        NODE_JOINED,
        NODE_LEFT,
        ACCESS_GRANTED,
        ACCESS_REVOKED
    }

    private final Type type;
    private final String publisherId;
    private final String subject;   // e.g. blockId or nodeId
    private final String detail;
    private final Instant publishedAt;

    public PubSubMessage(Type type, String publisherId, String subject, String detail) {
        this.type = type;
        this.publisherId = publisherId;
        this.subject = subject;
        this.detail = detail;
        this.publishedAt = Instant.now();
    }

    public Type getType() {
        return type;
    }

    public String getPublisherId() {
        return publisherId;
    }

    public String getSubject() {
        return subject;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    @Override
    public String toString() {
        return "[" + type + "] from=" + publisherId + " subject=" + subject + " (" + detail + ")";
    }
}
