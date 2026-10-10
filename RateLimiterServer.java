import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class RateLimiterServer {

    static final int CAPACITY = 5;
    static final long REFILL_MS = 2000; // 1 token every 2 seconds
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
<p class="sub">Each user has a bucket of tokens. A request uses 1 token. Tokens refill slowly over time.</p>

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
  var clientId = loadId();

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

  function loadId() {
    var id = null;
    try { id = sessionStorage.getItem("cid"); } catch (e) {}
    if (!id) {
      id = newId();
      try { sessionStorage.setItem("cid", id); } catch (e) {}
    }
    return id;
  }
  function newId() { return "user-" + Math.random().toString(36).slice(2, 8); }

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

  function sendOne() {
    return fetch("/allow?clientId=" + clientId)
      .then(function (r) { return r.json(); })
      .then(function (d) {
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
        document.getElementById("allowedCount").innerText = "Allowed: " + allowedCount;
        document.getElementById("blockedCount").innerText = "Blocked: " + blockedCount;
      });
  }

  document.getElementById("sendBtn").onclick = function () { sendOne(); };

  document.getElementById("spamBtn").onclick = function () {
    busy = true;
    var n = 0;
    function next() {
      if (n >= 10) { busy = false; return; }
      n++;
      sendOne().then(function () { setTimeout(next, 120); });
    }
    next();
  };

  document.getElementById("switchBtn").onclick = function () {
    clientId = newId();
    try { sessionStorage.setItem("cid", clientId); } catch (e) {}
    document.getElementById("uid").innerText = clientId;
    tokens = CAP; stamp = Date.now(); blockedUntil = 0;
    addLog("switched to " + clientId + " (new bucket)");
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

    static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static String clientIdOf(HttpExchange ex) {
        String query = ex.getRequestURI().getQuery();
        String id = null;
        if (query != null) {
            for (String part : query.split("&")) {
                if (part.startsWith("clientId=")) {
                    id = part.substring("clientId=".length());
                }
            }
        }
        if (id == null || id.isBlank()) {
            String fwd = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            id = (fwd != null) ? fwd.split(",")[0].trim()
                               : ex.getRemoteAddress().getAddress().getHostAddress();
        }
        id = id.replaceAll("[^a-zA-Z0-9_.:-]", "");
        if (id.length() > 40) id = id.substring(0, 40);
        return id.isEmpty() ? "anonymous" : id;
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(CAPACITY, REFILL_MS);

        ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleAtFixedRate(() -> limiter.removeIdle(10 * 60 * 1000), 1, 1, TimeUnit.MINUTES);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));

        server.createContext("/", ex -> send(ex, 200, "text/html; charset=UTF-8", PAGE));

        server.createContext("/allow", ex -> {
            String clientId = clientIdOf(ex);
            TokenBucketRateLimiter.Result r = limiter.tryAcquire(clientId);

            if (r.allowed) {
                totalAllowed.incrementAndGet();
            } else {
                totalBlocked.incrementAndGet();
                ex.getResponseHeaders().add("Retry-After", String.valueOf((r.retryAfterMs + 999) / 1000));
            }
            ex.getResponseHeaders().add("X-RateLimit-Limit", String.valueOf(CAPACITY));
            ex.getResponseHeaders().add("X-RateLimit-Remaining", String.valueOf(r.remaining));

            String msg = r.allowed
                    ? "Request allowed"
                    : "Too many requests. Please wait " + String.format(Locale.US, "%.1f", r.retryAfterMs / 1000.0) + " seconds and try again.";

            String body = "{\"clientId\":\"" + clientId + "\","
                    + "\"allowed\":" + r.allowed + ","
                    + "\"limit\":" + CAPACITY + ","
                    + "\"remaining\":" + r.remaining + ","
                    + "\"tokens\":" + String.format(Locale.US, "%.2f", r.tokens) + ","
                    + "\"refillMs\":" + REFILL_MS + ","
                    + "\"retryAfterMs\":" + r.retryAfterMs + ","
                    + "\"message\":\"" + msg + "\"}";

            send(ex, r.allowed ? 200 : 429, "application/json", body);
        });

        server.createContext("/stats", ex -> {
            String body = "{\"totalAllowed\":" + totalAllowed.get()
                    + ",\"totalBlocked\":" + totalBlocked.get()
                    + ",\"activeClients\":" + limiter.activeClients() + "}";
            send(ex, 200, "application/json", body);
        });

        server.start();
        System.out.println("Running on port " + port);
    }
}
