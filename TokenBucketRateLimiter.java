import java.util.concurrent.ConcurrentHashMap;

public class TokenBucketRateLimiter {

    public static class Result {
        public final boolean allowed;
        public final int remaining;
        public final double tokens;
        public final long retryAfterMs;

        Result(boolean allowed, double tokens, long retryAfterMs) {
            this.allowed = allowed;
            this.tokens = tokens;
            this.remaining = (int) Math.floor(tokens);
            this.retryAfterMs = retryAfterMs;
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

    // capacity = max tokens, refillMsPerToken = time to add 1 token
    public TokenBucketRateLimiter(int capacity, long refillMsPerToken) {
        this.capacity = capacity;
        this.refillMsPerToken = refillMsPerToken;
    }

    public Result tryAcquire(String clientId) {
        long now = System.currentTimeMillis();
        Bucket bucket = buckets.computeIfAbsent(clientId, id -> new Bucket(capacity, now));

        synchronized (bucket) {
            long elapsed = now - bucket.lastRefillMs;
            if (elapsed > 0) {
                bucket.tokens = Math.min(capacity, bucket.tokens + (double) elapsed / refillMsPerToken);
                bucket.lastRefillMs = now;
            }
            bucket.lastAccessMs = now;

            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return new Result(true, bucket.tokens, 0);
            }
            long waitMs = (long) Math.ceil((1 - bucket.tokens) * refillMsPerToken);
            return new Result(false, bucket.tokens, waitMs);
        }
    }

    public boolean allowRequest(String clientId) {
        return tryAcquire(clientId).allowed;
    }

    public void removeIdle(long idleMs) {
        long now = System.currentTimeMillis();
        buckets.entrySet().removeIf(e -> now - e.getValue().lastAccessMs > idleMs);
    }

    public int activeClients() {
        return buckets.size();
    }
}
