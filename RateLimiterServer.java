import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;

public class RateLimiterServer {

    static final int CAPACITY = 5;
    static final long REFILL_MS = 30000;

    // Details saved for every client
    static class Client {
        int allowed, blocked;
        long first = System.currentTimeMillis(), last;
        String ip = "-", browser = "-", remaining = "" + CAPACITY;
        LinkedList<String> history = new LinkedList<>();   // last 10 requests
    }

    static final ConcurrentHashMap<String, Client> clients = new ConcurrentHashMap<>();

    static void send(HttpExchange ex, int code, String type, String body) {
        try {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", type);
            ex.sendResponseHeaders(code, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Make text safe to put inside JSON
    static String clean(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("\\p{Cntrl}", "");
    }

    // Read clientId from the URL
    static String getId(HttpExchange ex) {
        String q = ex.getRequestURI().getQuery();
        String id = (q != null && q.startsWith("clientId="))
                ? q.substring(9).replaceAll("[^a-zA-Z0-9_-]", "") : "";
        return id.isEmpty() ? "anonymous" : id;
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(CAPACITY, REFILL_MS);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // Frontend: read index.html and fill in the numbers
        String page = Files.readString(Path.of("index.html"))
                .replace("__CAP__", "" + CAPACITY)
                .replace("__REFILL__", "" + REFILL_MS)
                .replace("__SEC__", "" + (REFILL_MS / 1000));

        server.createContext("/", ex -> send(ex, 200, "text/html; charset=UTF-8", page));

        // API 1: uses a token
        server.createContext("/allow", ex -> {
            String id = getId(ex);
            String ip = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (ip != null && !ip.isEmpty()) {
                ip = ip.split(",")[0].trim();
            } else {
                ip = ex.getRemoteAddress().getAddress().getHostAddress();
            }
            String browser = ex.getRequestHeaders().getFirst("User-Agent");

            TokenBucketRateLimiter.Result r = limiter.tryAcquire(id);
            int code = r.allowed() ? 200 : 429;

            Client c = clients.computeIfAbsent(id, k -> new Client());
            synchronized (c) {
                if (r.allowed()) c.allowed++; else c.blocked++;
                c.last = System.currentTimeMillis();
                c.ip = ip;
                c.browser = browser == null ? "-" : browser;
                c.remaining = "" + r.remaining();
                c.history.addFirst("{\"time\":" + c.last + ",\"status\":" + code
                        + ",\"remaining\":" + c.remaining + "}");
                if (c.history.size() > 10) c.history.removeLast();
            }

            String msg = r.allowed() ? "Request allowed"
                    : "Too many requests. Wait " + r.waitMs() / 1000.0 + " seconds";
            send(ex, code, "application/json; charset=UTF-8",
                    "{\"allowed\":" + r.allowed() + ",\"remaining\":" + r.remaining()
                    + ",\"retryAfterMs\":" + r.waitMs() + ",\"message\":\"" + msg + "\"}");
        });

        // API 2: does NOT use a token
        server.createContext("/details", ex -> {
            Client c = clients.get(getId(ex));
            if (c == null) {
                send(ex, 404, "application/json; charset=UTF-8", "{\"message\":\"Client not found\"}");
                return;
            }
            synchronized (c) {
                send(ex, 200, "application/json; charset=UTF-8",
                        "{\"limit\":" + CAPACITY + ",\"total\":" + (c.allowed + c.blocked)
                        + ",\"allowed\":" + c.allowed + ",\"blocked\":" + c.blocked
                        + ",\"ip\":\"" + clean(c.ip) + "\",\"userAgent\":\"" + clean(c.browser)
                        + "\",\"firstSeen\":" + c.first + ",\"lastRequest\":" + c.last
                        + ",\"history\":[" + String.join(",", c.history) + "]}");
            }
        });

        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        System.out.println("Running on port " + port);
    }
}
