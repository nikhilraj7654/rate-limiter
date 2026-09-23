import java.util.concurrent.ConcurrentHashMap;

public class TokenBucketRateLimiter {

    private final ConcurrentHashMap<String, Bucket> clientBuckets = new ConcurrentHashMap<>();

    private final int maxTokens;
    private final long refillIntervalMillis;

    public TokenBucketRateLimiter(int maxTokens, long refillIntervalMillis) {
        this.maxTokens = maxTokens;
        this.refillIntervalMillis = refillIntervalMillis;
    }

    public synchronized boolean allowRequest(String clientId) {
        Bucket bucket = clientBuckets.computeIfAbsent(clientId, id -> new Bucket(maxTokens, System.currentTimeMillis()));

        refill(bucket);

        if (bucket.tokens > 0) {
            bucket.tokens--;
            return true;
        }
        return false;
    }

    private void refill(Bucket bucket) {
        long now = System.currentTimeMillis();
        long timeSinceLastRefill = now - bucket.lastRefillTimestamp;

        long tokensToAdd = timeSinceLastRefill / refillIntervalMillis;

        if (tokensToAdd > 0) {
            bucket.tokens = (int) Math.min(maxTokens, bucket.tokens + tokensToAdd);
            bucket.lastRefillTimestamp = now;
        }
    }

    private static class Bucket {
        int tokens;
        long lastRefillTimestamp;

        Bucket(int tokens, long lastRefillTimestamp) {
            this.tokens = tokens;
            this.lastRefillTimestamp = lastRefillTimestamp;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, 1000);

        String client = "user123";

        for (int i = 1; i <= 5; i++) {
            boolean allowed = limiter.allowRequest(client);
            System.out.println("Request " + i + " -> " + (allowed ? "ALLOWED" : "REJECTED (rate limit hit)"));
        }

        System.out.println("\nWaiting 2 seconds for tokens to refill...\n");
        Thread.sleep(2000);

        for (int i = 6; i <= 8; i++) {
            boolean allowed = limiter.allowRequest(client);
            System.out.println("Request " + i + " -> " + (allowed ? "ALLOWED" : "REJECTED (rate limit hit)"));
        }
    }
}