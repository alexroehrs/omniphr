package br.unisinos.omniphr.middleware.datablock;

import br.unisinos.omniphr.core.Datablock;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Message Router component of the Datablock and Service Module. Provides
 * communication services such as the packaging and routing of messages,
 * receiving and forwarding requests to the other modules and components. It
 * also keeps an in-memory cache that holds newly requested datablocks for a
 * limited time, improving read performance.
 */
public class MessageRouter {

    /** Request types accepted by the routing overlay. */
    public enum RequestType {
        SUBMIT_DATABLOCK,
        FETCH_DATABLOCK,
        QUERY_PHR,
        GRANT_ACCESS,
        REVOKE_ACCESS,
        REGISTER_USER
    }

    /** Packaged request forwarded to the destination component. */
    public static final class Request {
        public final RequestType type;
        public final Object payload;

        public Request(RequestType type, Object payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    private static final class CacheEntry {
        final Datablock block;
        final long expiresAtNanos;

        CacheEntry(Datablock block, long expiresAtNanos) {
            this.block = block;
            this.expiresAtNanos = expiresAtNanos;
        }
    }

    public static final long DEFAULT_TTL_MILLIS = 30_000;

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<RequestType, Function<Object, Object>> handlers = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private long cacheHits, cacheMisses;

    public MessageRouter() {
        this(DEFAULT_TTL_MILLIS);
    }

    public MessageRouter(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    /** Components register themselves as request handlers. */
    public void registerHandler(RequestType type, Function<Object, Object> handler) {
        handlers.put(type, handler);
    }

    /** Receives and forwards a request to the responsible component. */
    public Object route(Request request) {
        Function<Object, Object> handler = handlers.get(request.type);
        if (handler == null) {
            throw new IllegalStateException("no component registered for " + request.type);
        }
        return handler.apply(request.payload);
    }

    // ------------------ time-limited datablock cache ------------------

    public synchronized Datablock cachedBlock(String blockId) {
        CacheEntry entry = cache.get(blockId);
        if (entry == null) {
            cacheMisses++;
            return null;
        }
        if (System.nanoTime() > entry.expiresAtNanos) {
            cache.remove(blockId);
            cacheMisses++;
            return null;
        }
        cacheHits++;
        return entry.block;
    }

    /** Keeps a newly requested datablock in memory for a limited time. */
    public void cacheBlock(Datablock block) {
        cache.put(block.getBlockId(),
                new CacheEntry(block, System.nanoTime() + ttlMillis * 1_000_000L));
    }

    public synchronized String cacheStats() {
        return "cache hits=" + cacheHits + " misses=" + cacheMisses + " size=" + cache.size();
    }

    /** Administrative cache flush (entries also expire by TTL). */
    public void clearCache() {
        cache.clear();
    }
}
