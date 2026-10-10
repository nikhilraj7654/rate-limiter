import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class RateLimiterServer {

    static final int CAPACITY = envInt("CAPACITY", 5);
    static final long REFILL_MS = envLong("REFILL_MS", 2000);
    static final String ADMIN_KEY = System.getenv("ADMIN_KEY");
    static final long STARTED_AT = System.currentTimeMillis();
    static final AtomicLong totalAllowed = new AtomicLong();
    static final AtomicLong totalBlocked = new AtomicLong();

    static final String PAGE = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Rate Limiter Demo</title>
<style>
  body { font-family: Arial, sans-serif; max-width: 640px; margin: 30px auto; padding: 0 16px; color: #222; }
  h2 { margin-bottom: 4px; }
  .sub { color: #666; margin-top: 0; }
  .card { border: 1px solid #ddd; border-radius: 10px; padding: 16px; margin-top: 16px; }
  .tokens { display: flex; gap: 8px; margin: 10px 0; }
  .slot { flex: 1; height: 26px; border: 2px solid #2b6cb0; border-radius: 6px; overflow: hidden; background: #f0f6ff; }
  .fill { height: 100%; width: 0%; background: #2b6cb0; }
  button { padding: 10px 18px; font-size: 15px; cursor: pointer; margin-right: 8px; border-radius: 6px; border: 1px solid #888; background: #f7f7f7; }
  button:disabled { cursor: not-allowed; opacity: 0.5; }
  #status { margin-top: 14px; padding: 12px; border-radius: 8px; font-size: 16px; background: #f2f2f2; }
  .ok { background: #e6f9ec !important; color: #11743a; }
  .blocked { background: #fdeaea !important; color: #b42318; }
  .counts span { margin-right: 18px; font-weight: bold; }
  #log { font-family: monospace; font-size: 13px; margin-top: 8px; }
  #log div { padding: 2px 0; }
  .small { font-size: 13px; color: #555; }
</style>
</head>
<body>
<h2>Token Bucket Rate Limiter</h2>
<p class="sub">Each user has a bucket of tokens. A request uses 1 token. Tokens refill slowly over time. A new user always starts with a full bucket.</p>

<div class="card">
  <div>User: <b id="uid"></b> <button id="switchBtn">Switch user</button></div>
  <div class="tokens" id="slots"></div>
  <div class="small">Tokens: <span id="tokenText">5.0</span> / <span id="capText">5</span></div>
  <div style="margin-top:12px;">
    <button id="sendBtn">Send Request</button>
    <button id="spamBtn">Spam 10 requests</button>
  </div>
  <div id="status">Ready</div>
  <div class="counts" style="margin-top:12px;">
    <span id="allowedCount">Allowed: 0</span>
    <span id="blockedCount">Blocked: 0</span>
  </div>
  <div id="log"></div>
</div>

<div class="card small" id="serverStats">Server stats loading...</div>

<script>
  var CAP = 5, REFILL = 2000;
  var tokens = CAP, stamp = Date.now();
  var allowedCount = 0, blockedCount = 0, blockedUntil = 0, busy = false;
  var runId = 0;
  var clientId = newId();

  var slotsBox = document.getElementById("slots");
  var fills = [];
  for (var i = 0; i < CAP; i++) {
    var slot = document.createElement("div");
    slot.className = "slot";
    var fill = document.createElement("div");
    fill.className = "fill";
    slot.appendChild(fill);
    slotsBox.appendChild(slot);
    fills.push(fill);
  }
  document.getElementById("uid").innerText = clientId;

  function newId() {
    return "user-" + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  }

  function current() { return Math.min(CAP, tokens + (Date.now() - stamp) / REFILL); }

  function setButtons() {
    var waiting = blockedUntil > Date.now();
    document.getElementById("sendBtn").disabled = busy || waiting;
    document.getElementById("spamBtn").disabled = busy || waiting;
  }

  function tick() {
    var t = current();
    for (var i = 0; i < fills.length; i++) {
      var f = Math.max(0, Math.min(1, t - i));
      fills[i].style.width = (f * 100) + "%";
    }
    document.getElementById("tokenText").innerText = t.toFixed(1);
    document.getElementById("capText").innerText = CAP;

    var statusBox = document.getElementById("status");
    var left = blockedUntil - Date.now();
    if (left > 0) {
      statusBox.className = "blocked";
      statusBox.innerText = "Too many requests. Please wait " + (left / 1000).toFixed(1) + " s...";
    } else if (blockedUntil !== 0) {
      blockedUntil = 0;
      statusBox.className = "ok";
      statusBox.innerText = "You can send requests again!";
    }
    setButtons();
  }
  setInterval(tick, 100);

  function addLog(text) {
    var box = document.getElementById("log");
    var row = document.createElement("div");
    row.textContent = new Date().toLocaleTimeString() + "  " + text;
    box.insertBefore(row, box.firstChild);
    while (box.children.length > 8) { box.removeChild(box.lastChild); }
  }

  function showCounts() {
    document.getElementById("allowedCount").innerText = "Allowed: " + allowedCount;
    document.getElementById("blockedCount").innerText = "Blocked: " + blockedCount;
  }

  // Resolves true if the response was used, false if the user switched meanwhile
  function sendOne() {
    var myRun = runId;
    var myId = clientId;
    return fetch("/allow?clientId=" + myId)
      .then(function (r) { return r.json(); })
      .then(function (d) {
        if (myRun !== runId) { return false; }
        CAP = d.limit; REFILL = d.refillMs;
        tokens = d.tokens; stamp = Date.now();
        var statusBox = document.getElementById("status");
        if (d.allowed) {
          allowedCount++;
          statusBox.className = "ok";
          statusBox.innerText = "Request allowed. Remaining: " + d.remaining;
          addLog("200 allowed (remaining " + d.remaining + ")");
        } else {
          blockedCount++;
          blockedUntil = Date.now() + d.retryAfterMs;
          addLog("429 blocked (retry in " + d.retryAfterMs + " ms)");
        }
        showCounts();
        return true;
      })
      .catch(function () {
        if (myRun === runId) {
          busy = false;
          var statusBox = document.getElementById("status");
          statusBox.className = "blocked";
          statusBox.innerText = "Could not reach the server. Try again.";
        }
        return false;
      });
  }

  document.getElementById("sendBtn").onclick = function () { sendOne(); };

  document.getElementById("spamBtn").onclick = function () {
    var myRun = runId;
    busy = true;
    var n = 0;
    function next() {
      if (myRun !== runId) { return; }
      if (n >= 10) { busy = false; return; }
      n++;
      sendOne().then(function (used) {
        if (used) { setTimeout(next, 120); }
      });
    }
    next();
  };

  // Switch user: stop the old process and start a fresh one with a full bucket
  document.getElementById("switchBtn").onclick = function () {
    runId++;
    busy = false;
    clientId = newId();
    document.getElementById("uid").innerText = clientId;

    tokens = CAP; stamp = Date.now(); blockedUntil = 0;
    allowedCount = 0; blockedCount = 0;
    showCounts();

    document.getElementById("log").innerHTML = "";
    var statusBox = document.getElementById("status");
    statusBox.className = "ok";
    statusBox.innerText = "New user started with a fresh bucket";
    addLog("started fresh as " + clientId);
  };

  function loadStats() {
    fetch("/stats").then(function (r) { return r.json(); }).then(function (s) {
      document.getElementById("serverStats").innerText =
        "Server: " + s.totalAllowed + " allowed, " + s.totalBlocked + " blocked, " + s.activeClients + " active clients";
    }).catch(function () {});
  }
  loadStats();
  setInterval(loadStats, 3000);
</script>
</body>
</html>
""";

    interface Route {
        void handle(HttpExchange ex, Map<String, String> q) throws Exception;
    }

    static int envInt(String name, int def) {
        try { return Integer.parseInt(System.getenv(name)); } catch (Exception e) { return def; }
    }

    static long envLong(String name, long def) {
        try { return Long.parseLong(System.getenv(name)); } catch (Exception e) { return def; }
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 32) sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }

    static String json(Object... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append('"').append(kv[i]).append("\":");
            Object v = kv[i + 1];
            if (v instanceof String) sb.append('"').append(esc((String) v)).append('"');
            else if (v instanceof Double) sb.append(String.format(Locale.US, "%.2f", v));
            else sb.append(v);
        }
        return sb.append('}').toString();
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String p : raw.split("&")) {
            if (p.isEmpty()) continue;
            int i = p.indexOf('=');
            String k = i < 0 ? p : p.substring(0, i);
            String v = i < 0 ? "" : p.substring(i + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.setAttribute("status", status);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static void sendJson(HttpExchange ex, int status, String body) throws IOException {
        send(ex, status, "application/json", body);
    }

    static String clientIdOf(Map<String, String> q, HttpExchange ex) {
        String id = q.get("clientId");
        if (id == null || id.isBlank()) {
            String fwd = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            id = (fwd != null) ? fwd.split(",")[0].trim()
                               : ex.getRemoteAddress().getAddress().getHostAddress();
        }
        id = id.replaceAll("[^a-zA-Z0-9_.:-]", "");
        if (id.length() > 40) id = id.substring(0, 40);
        return id.isEmpty() ? "anonymous" : id;
    }

    static void register(HttpServer server, String path, Route route, String... methods) {
        server.createContext(path, ex -> {
            long start = System.nanoTime();
            try {
                boolean ok = false;
                for (String m : methods) {
                    if (m.equals(ex.getRequestMethod())) ok = true;
                }
                if (!ok) {
                    ex.getResponseHeaders().add("Allow", String.join(", ", methods));
                    sendJson(ex, 405, json("error", "Method not allowed"));
                    return;
                }
                route.handle(ex, parseQuery(ex.getRequestURI().getRawQuery()));
            } catch (IllegalArgumentException e) {
                sendJson(ex, 400, json("error", "Bad request"));
            } catch (Exception e) {
                e.printStackTrace();
                sendJson(ex, 500, json("error", "Internal server error"));
            } finally {
                Object st = ex.getAttribute("status");
                long ms = (System.nanoTime() - start) / 1_000_000;
                System.out.println(ex.getRequestMethod() + " " + ex.getRequestURI().getPath()
                        + " -> " + st + " (" + ms + " ms)");
                ex.close();
            }
        });
    }

    public static void main(String[] args) throws Exception {
        int port = envInt("PORT", 8080);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(CAPACITY, REFILL_MS);

        ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleAtFixedRate(() -> limiter.removeIdle(10 * 60 * 1000), 1, 1, TimeUnit.MINUTES);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        server.setExecutor(pool);

        // Demo page (unknown paths get a 404)
        register(server, "/", (ex, q) -> {
            if (!ex.getRequestURI().getPath().equals("/")) {
                sendJson(ex, 404, json("error", "Not found"));
                return;
            }
            send(ex, 200, "text/html; charset=UTF-8", PAGE);
        }, "GET");

        // API info
        register(server, "/api", (ex, q) -> sendJson(ex, 200, json(
                "name", "Token Bucket Rate Limiter",
                "capacity", CAPACITY,
                "refillMsPerToken", REFILL_MS,
                "endpoints", "GET /allow?clientId=&cost= | GET /status?clientId= | POST /reset?clientId= | GET /stats | GET /health")),
                "GET");

        // Consume tokens
        register(server, "/allow", (ex, q) -> {
            String id = clientIdOf(q, ex);
            int cost = q.containsKey("cost") ? Integer.parseInt(q.get("cost")) : 1;
            if (cost < 1 || cost > CAPACITY) {
                sendJson(ex, 400, json("error", "cost must be between 1 and " + CAPACITY));
                return;
            }

            TokenBucketRateLimiter.Result r = limiter.tryAcquire(id, cost);
            ex.getResponseHeaders().add("X-RateLimit-Limit", String.valueOf(CAPACITY));
            ex.getResponseHeaders().add("X-RateLimit-Remaining", String.valueOf(r.remaining));
            ex.getResponseHeaders().add("X-RateLimit-Reset", String.valueOf((r.fullInMs + 999) / 1000));

            if (r.allowed) {
                totalAllowed.incrementAndGet();
                sendJson(ex, 200, json("clientId", id, "allowed", true, "cost", cost,
                        "limit", CAPACITY, "remaining", r.remaining, "tokens", r.tokens,
                        "refillMs", REFILL_MS, "retryAfterMs", 0L,
                        "message", "Request allowed"));
            } else {
                totalBlocked.incrementAndGet();
                ex.getResponseHeaders().add("Retry-After", String.valueOf((r.retryAfterMs + 999) / 1000));
                sendJson(ex, 429, json("clientId", id, "allowed", false, "cost", cost,
                        "limit", CAPACITY, "remaining", r.remaining, "tokens", r.tokens,
                        "refillMs", REFILL_MS, "retryAfterMs", r.retryAfterMs,
                        "message", "Too many requests. Please wait "
                                + String.format(Locale.US, "%.1f", r.retryAfterMs / 1000.0)
                                + " seconds and try again."));
            }
        }, "GET", "POST");

        // Check a client's bucket without using a token
        register(server, "/status", (ex, q) -> {
            String id = clientIdOf(q, ex);
            TokenBucketRateLimiter.Result r = limiter.peek(id);
            sendJson(ex, 200, json("clientId", id, "limit", CAPACITY, "tokens", r.tokens,
                    "remaining", r.remaining, "wouldAllow", r.allowed,
                    "retryAfterMs", r.retryAfterMs, "fullInMs", r.fullInMs));
        }, "GET");

        // Reset a client's bucket (needs X-Admin-Key header if ADMIN_KEY is set)
        register(server, "/reset", (ex, q) -> {
            if (ADMIN_KEY != null && !ADMIN_KEY.equals(ex.getRequestHeaders().getFirst("X-Admin-Key"))) {
                sendJson(ex, 401, json("error", "Unauthorized"));
                return;
            }
            String id = clientIdOf(q, ex);
            sendJson(ex, 200, json("clientId", id, "reset", limiter.reset(id)));
        }, "POST");

        register(server, "/stats", (ex, q) -> sendJson(ex, 200, json(
                "totalAllowed", totalAllowed.get(),
                "totalBlocked", totalBlocked.get(),
                "activeClients", limiter.activeClients(),
                "uptimeSeconds", (System.currentTimeMillis() - STARTED_AT) / 1000,
                "capacity", CAPACITY,
                "refillMsPerToken", REFILL_MS)), "GET");

        register(server, "/health", (ex, q) -> sendJson(ex, 200, json("status", "ok")), "GET");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            server.stop(1);
            pool.shutdown();
        }));

        server.start();
        System.out.println("Running on port " + port + " (capacity=" + CAPACITY + ", refillMs=" + REFILL_MS + ")");
    }
}
