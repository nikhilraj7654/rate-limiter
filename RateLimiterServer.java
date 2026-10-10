import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class RateLimiterServer {

    static final int MAX_TOKENS = 5;
    static final long REFILL_MS = 10000;

    static final String PAGE = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Rate Limiter Demo</title>
<style>
  body { font-family: Arial, sans-serif; max-width: 520px; margin: 40px auto; padding: 0 16px; }
  button { padding: 12px 24px; font-size: 16px; cursor: pointer; }
  button:disabled { cursor: not-allowed; opacity: 0.6; }
  #status { margin-top: 20px; padding: 14px; border-radius: 8px; font-size: 16px; }
  .ok { background: #e6f9ec; color: #11743a; }
  .blocked { background: #fdeaea; color: #b42318; }
  #log { margin-top: 20px; font-size: 14px; color: #444; }
</style>
</head>
<body>
<h2>Token Bucket Rate Limiter Demo</h2>
<p>Click the button quickly. After 5 requests you will be blocked and asked to wait.</p>
<button id="btn">Send Request</button>
<div id="status"></div>
<div id="log"></div>
<script>
  var count = 0;
  var btn = document.getElementById("btn");
  var statusBox = document.getElementById("status");
  var log = document.getElementById("log");

  btn.onclick = function () {
    fetch("/allow?clientId=demo-user")
      .then(function (r) { return r.json(); })
      .then(function (data) {
        count++;
        if (data.allowed) {
          statusBox.className = "ok";
          statusBox.innerText = "Request " + count + " allowed";
        } else {
          statusBox.className = "blocked";
          startWait(data.retryAfterSeconds);
        }
        log.innerText = "Total requests sent: " + count;
      });
  };

  function startWait(seconds) {
    btn.disabled = true;
    var left = seconds;
    statusBox.innerText = "Too many requests. Please wait " + left + " seconds...";
    var timer = setInterval(function () {
      left--;
      if (left > 0) {
        statusBox.innerText = "Too many requests. Please wait " + left + " seconds...";
      } else {
        clearInterval(timer);
        btn.disabled = false;
        statusBox.className = "ok";
        statusBox.innerText = "You can send requests again!";
      }
    }, 1000);
  }
</script>
</body>
</html>
""";

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(MAX_TOKENS, REFILL_MS);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // Demo page
        server.createContext("/", exchange -> {
            byte[] bytes = PAGE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        // API
        server.createContext("/allow", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String clientId = "anonymous";
            if (query != null && query.startsWith("clientId=")) {
                clientId = query.substring("clientId=".length());
            }

            boolean allowed = limiter.allowRequest(clientId);
            long retrySeconds = REFILL_MS / 1000;

            String body;
            if (allowed) {
                body = "{\"clientId\":\"" + clientId + "\",\"allowed\":true,"
                     + "\"message\":\"Request allowed\"}";
            } else {
                body = "{\"clientId\":\"" + clientId + "\",\"allowed\":false,"
                     + "\"message\":\"Too many requests. Please wait " + retrySeconds + " seconds and try again.\","
                     + "\"retryAfterSeconds\":" + retrySeconds + "}";
                exchange.getResponseHeaders().add("Retry-After", String.valueOf(retrySeconds));
            }

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
