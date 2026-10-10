import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class RateLimiterServer {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 10000); // adjust values

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/allow", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String clientId = "anonymous";
            if (query != null && query.startsWith("clientId=")) {
                clientId = query.substring("clientId=".length());
            }

            boolean allowed = limiter.allowRequest(clientId);
            String body = "{\"clientId\":\"" + clientId + "\",\"allowed\":" + allowed + "}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(allowed ? 200 : 429, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.start();
        System.out.println("Running on port " + port);
    }
}
