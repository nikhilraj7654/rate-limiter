import java.util.concurrent.ConcurrentHashMap;

public class TokenBucketRateLimiter {

    public record Result(boolean allowed, int remaining, long waitMs) {}

    private static class Bucket {
        double tokens;
        long last;
        Bucket(double tokens, long last) { this.tokens = tokens; this.last = last; }
    }

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int capacity;
    private final long refillMs; // time to get 1 token back

    public TokenBucketRateLimiter(int capacity, long refillMs) {
        this.capacity = capacity;
        this.refillMs = refillMs;
    }

    public Result tryAcquire(String clientId) {
        long now = System.currentTimeMillis();
        // a new client always starts with a full bucket
        Bucket b = buckets.computeIfAbsent(clientId, id -> new Bucket(capacity, now));
        synchronized (b) {
            b.tokens = Math.min(capacity, b.tokens + (double) (now - b.last) / refillMs);
            b.last = now;
            if (b.tokens >= 1) {
                b.tokens -= 1;
                return new Result(true, (int) b.tokens, 0);
            }
            return new Result(false, 0, (long) Math.ceil((1 - b.tokens) * refillMs));
        }
    }
}
