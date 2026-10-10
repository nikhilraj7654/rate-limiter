import java.util.concurrent.ConcurrentHashMap;

public class TokenBucketRateLimiter {

    public static class Result {
        public final boolean allowed;
        public final int remaining;
        public final double tokens;
        public final long retryAfterMs;
        public final long fullInMs;

        Result(boolean allowed, double tokens, long retryAfterMs, long fullInMs) {
            this.allowed = allowed;
            this.tokens = tokens;
            this.remaining = (int) Math.floor(tokens);
            this.retryAfterMs = retryAfterMs;
            this.fullInMs = fullInMs;
        }
    }

    private static class Bucket {
        double tokens;
        long lastRefillMs;
        volatile long lastAccessMs;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastRefillMs = now;
            this.lastAccessMs = now;
        }
    }

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int capacity;
    private final long refillMsPerToken;

    public TokenBucketRateLimiter(int capacity, long refillMsPerToken) {
        if (capacity < 1 || refillMsPerToken < 1) {
            throw new IllegalArgumentException("capacity and refill time must be >= 1");
        }
        this.capacity = capacity;
        this.refillMsPerToken = refillMsPerToken;
    }

    private void refill(Bucket b, long now) {
        long elapsed = now - b.lastRefillMs;
        if (elapsed > 0) {
            b.tokens = Math.min(capacity, b.tokens + (double) elapsed / refillMsPerToken);
            b.lastRefillMs = now;
        }
    }

    private Result result(boolean allowed, double tokens, long retryAfterMs) {
        long fullIn = (long) Math.ceil((capacity - tokens) * refillMsPerToken);
        return new Result(allowed, tokens, retryAfterMs, Math.max(0, fullIn));
    }

    public Result tryAcquire(String clientId) {
        return tryAcquire(clientId, 1);
    }

    // A new client always starts with a full bucket
    public Result tryAcquire(String clientId, int cost) {
        long now = System.currentTimeMillis();
        Bucket b = buckets.computeIfAbsent(clientId, id -> new Bucket(capacity, now));
        synchronized (b) {
            refill(b, now);
            b.lastAccessMs = now;
            if (b.tokens >= cost) {
                b.tokens -= cost;
                return result(true, b.tokens, 0);
            }
            long wait = (long) Math.ceil((cost - b.tokens) * refillMsPerToken);
            return result(false, b.tokens, wait);
        }
    }

    // Check the bucket without using a token
    public Result peek(String clientId) {
        Bucket b = buckets.get(clientId);
        if (b == null) {
            return result(true, capacity, 0);
        }
        long now = System.currentTimeMillis();
        synchronized (b) {
            refill(b, now);
            boolean ok = b.tokens >= 1;
            long wait = ok ? 0 : (long) Math.ceil((1 - b.tokens) * refillMsPerToken);
            return result(ok, b.tokens, wait);
        }
    }

    public boolean allowRequest(String clientId) {
        return tryAcquire(clientId, 1).allowed;
    }

    public boolean reset(String clientId) {
        return buckets.remove(clientId) != null;
    }

    public void removeIdle(long idleMs) {
        long now = System.currentTimeMillis();
        buckets.entrySet().removeIf(e -> now - e.getValue().lastAccessMs > idleMs);
    }

    public int activeClients() {
        return buckets.size();
    }
}
