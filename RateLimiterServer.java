import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;

public class RateLimiterServer {

    static final int CAPACITY = 5;
    static final long REFILL_MS = 30000;

    // Server keeps the details of every client here
    static class Client {
        int allowed = 0;
        int blocked = 0;
        long firstSeen = System.currentTimeMillis();
        long lastRequest = 0;
        String ip = "-";
        String browser = "-";
        String remaining = String.valueOf(CAPACITY);
        LinkedList<String> history = new LinkedList<>();   // last 10 requests, newest first
    }

    static final ConcurrentHashMap<String, Client> clients = new ConcurrentHashMap<>();

    static final String PAGE = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Rate Limiter</title>
<style>
body { font-family: Arial; max-width: 650px; margin: 30px auto; padding: 0 16px; }
.group { border: 1px solid #ddd; border-radius: 12px; padding: 12px; margin-bottom: 20px; }
.group h3 { margin: 0 0 10px; }
.row { display: flex; gap: 10px; margin-bottom: 12px; }
.card { flex: 1; border: 2px solid #ccc; border-radius: 10px; padding: 10px; cursor: pointer; }
.on { border-color: #2b6cb0; background: #f0f6ff; }
.ok { color: #11743a; }
.no { color: #b42318; }
button { padding: 10px 14px; margin: 2px 2px 2px 0; }
button:disabled { opacity: .5; cursor: not-allowed; }
pre { background: #f5f5f5; padding: 10px; border-radius: 8px; white-space: pre-wrap; word-break: break-all; }
</style>
</head>
<body>

<h2>Token Bucket Rate Limiter</h2>
<p>Each user and each client has __CAP__ tokens. One token comes back every __SEC__ seconds. Select one and click a button. Nothing runs by itself.</p>

<div id="app"></div>

<script>
var CAP = __CAP__;
var REFILL = __REFILL__;
var sid = Date.now();
var groups = [];

function clock(t) {
    return t ? new Date(t).toLocaleTimeString() : "-";
}

function sleep(ms) {
    return new Promise(function (r) { setTimeout(r, ms); });
}

// Current tokens = last known tokens + refill since last request
function tokens(u) {
    var refilled = (Date.now() - u.time) / REFILL;
    return Math.min(CAP, u.tokens + refilled);
}

// 1. Create one group (3 cards + buttons + details). Used for Users and for Clients.
function createGroup(title, label, prefix) {
    var g = { label: label, active: 0, items: [], cards: [] };

    var box = document.createElement("div");
    box.className = "group";
    box.innerHTML =
        "<h3>" + title + "</h3>" +
        "<div class='row'></div>" +
        "<button class='send'>Send Request</button>" +
        "<button class='spamAllow'>Spam Allow 10</button>" +
        "<button class='getDet'>Get Details</button>" +
        "<button class='spamDet'>Spam Details 10</button>" +
        "<h4 class='msg'></h4>" +
        "<pre class='details'></pre>";
    document.getElementById("app").appendChild(box);

    g.row = box.querySelector(".row");
    g.msg = box.querySelector(".msg");
    g.sendBtn = box.querySelector(".send");
    g.spamAllowBtn = box.querySelector(".spamAllow");
    g.getDetBtn = box.querySelector(".getDet");
    g.spamDetBtn = box.querySelector(".spamDet");
    g.details = box.querySelector(".details");

    // 3 items, each with its own state and its own client id
    for (let i = 0; i < 3; i++) {
        g.items.push({
            id: prefix + (i + 1) + "-" + sid,
            tokens: CAP,
            time: Date.now(),
            until: 0,
            status: "-",
            msg: "Ready",
            ok: 0,
            no: 0,
            run: 0,          // changes when a new spam starts, so the old spam stops
            mode: "",        // "allow" or "details" while a spam is running
            server: null     // data from the /details API
        });

        var card = document.createElement("div");
        card.onclick = function () {
            g.active = i;
            render();
        };
        g.row.appendChild(card);
        g.cards.push(card);
    }

    // Buttons work only for the selected item of this group
    g.sendBtn.onclick = function () {
        var u = g.items[g.active];
        u.run++;                  // stop any running spam
        u.mode = "";
        callAllow(u);
    };
    g.getDetBtn.onclick = function () {
        var u = g.items[g.active];
        u.run++;
        u.mode = "";
        callDetails(u);
    };
    g.spamAllowBtn.onclick = function () {
        spam(g.items[g.active], "allow");
    };
    g.spamDetBtn.onclick = function () {
        spam(g.items[g.active], "details");
    };

    groups.push(g);
}

// 2. Spam: a new spam stops the old one (because u.run changes)
async function spam(u, kind) {
    u.run++;
    var mine = u.run;
    u.mode = kind;

    for (var n = 0; n < 10; n++) {
        if (mine !== u.run) return;           // another button was pressed, stop here

        if (kind == "allow") {
            var allowed = await callAllow(u);
            if (!allowed) break;              // blocked, stop
        } else {
            await callDetails(u);
        }

        await sleep(120);
    }

    if (mine === u.run) u.mode = "";
    render();
}

// 3. Draw one group from its data
function renderGroup(g) {
    var now = Date.now();

    // cards
    g.items.forEach(function (u, i) {
        var wait = u.until - now;
        var state = wait > 0
            ? "<span class='no'>Blocked " + (wait / 1000).toFixed(1) + " s</span>"
            : "<span class='ok'>Ready</span>";

        g.cards[i].className = (i == g.active) ? "card on" : "card";
        g.cards[i].innerHTML =
            "<b>" + g.label + " " + (i + 1) + "</b><br>" +
            "Tokens: " + Math.floor(tokens(u) + 0.001) + "/" + CAP + "<br>" +
            state + "<br>" +
            "<small>Allowed " + u.ok + " | Blocked " + u.no + "</small>";
    });

    // message and buttons for the selected item
    var u = g.items[g.active];
    var wait = u.until - now;

    if (u.mode == "allow") g.msg.textContent = "Running: Spam Allow...";
    else if (u.mode == "details") g.msg.textContent = "Running: Spam Details...";
    else g.msg.textContent = wait > 0 ? "Please wait " + (wait / 1000).toFixed(1) + " s" : u.msg;

    // only the allow buttons stop when blocked, details buttons always work
    g.sendBtn.disabled = wait > 0;
    g.spamAllowBtn.disabled = wait > 0;

    // details panel
    var lines = [
        "Client ID: " + u.id,
        "Limit: " + CAP + " requests, 1 token comes back every " + (REFILL / 1000) + " s",
        "Tokens now: " + tokens(u).toFixed(2) + " / " + CAP,
        "Last allow HTTP status: " + u.status
    ];

    var s = u.server;
    if (s) {
        lines.push("");
        lines.push("From /details API:");
        lines.push("Total requests: " + s.total);
        lines.push("Allowed: " + s.allowed + "   Blocked: " + s.blocked);
        lines.push("IP (seen by server): " + s.ip);
        lines.push("Browser (seen by server): " + s.userAgent);
        lines.push("First seen: " + clock(s.firstSeen));
        lines.push("Last request: " + clock(s.lastRequest));
        lines.push("");
        lines.push("Last 10 requests (newest first):");
        s.history.forEach(function (h) {
            lines.push(clock(h.time) + "  |  HTTP " + h.status + "  |  tokens left " + h.remaining);
        });
    } else {
        lines.push("");
        lines.push("Press Get Details or Spam Details to load server data.");
    }

    g.details.textContent = lines.join("\\n");
}

function render() {
    groups.forEach(renderGroup);
}

// 4. API 1: one /allow call. Does NOT call /details.
async function callAllow(u) {
    var res = await fetch("/allow?clientId=" + u.id);
    var d = await res.json();

    u.time = Date.now();
    u.tokens = d.remaining;
    u.status = res.status;
    u.msg = d.message;

    if (d.allowed) {
        u.ok++;
        u.until = 0;
    } else {
        u.no++;
        u.until = u.time + d.retryAfterMs;
    }

    render();
    return d.allowed;
}

// 5. API 2: one /details call. Does NOT call /allow.
async function callDetails(u) {
    var res = await fetch("/details?clientId=" + u.id);

    if (res.ok) {
        u.server = await res.json();
    } else {
        u.msg = "No details yet. Send a request first.";
    }

    render();
    return res.ok;
}

// 6. Build both groups
createGroup("Users", "User", "user");
createGroup("Clients", "Client", "client");

// refresh the screen every 200 ms so countdowns stay live
setInterval(render, 200);
render();
</script>
</body>
</html>
""";

    // Send a response to the browser (handles its own exceptions)
    static void send(HttpExchange ex, int code, String type, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", type);
            ex.sendResponseHeaders(code, bytes.length);

            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
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
        String query = ex.getRequestURI().getQuery();
        String id = "anonymous";
        if (query != null && query.startsWith("clientId=")) {
            id = query.substring(9).replaceAll("[^a-zA-Z0-9_-]", "");
            if (id.isEmpty()) id = "anonymous";
        }
        return id;
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(CAPACITY, REFILL_MS);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        String page = PAGE
                .replace("__CAP__", String.valueOf(CAPACITY))
                .replace("__REFILL__", String.valueOf(REFILL_MS))
                .replace("__SEC__", String.valueOf(REFILL_MS / 1000));

        // Home page
        server.createContext("/", ex -> send(ex, 200, "text/html; charset=UTF-8", page));

        // API 1: rate limit check (uses a token)
        server.createContext("/allow", ex -> {
            String id = getId(ex);

            // Client details (Render is behind a proxy, so check X-Forwarded-For first)
            String ip = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (ip != null && !ip.isEmpty()) {
                ip = ip.split(",")[0].trim();
            } else {
                ip = ex.getRemoteAddress().getAddress().getHostAddress();
            }
            String browser = ex.getRequestHeaders().getFirst("User-Agent");

            // Ask the token bucket
            TokenBucketRateLimiter.Result r = limiter.tryAcquire(id);
            int code = r.allowed() ? 200 : 429;

            // Save this request in the client's record
            Client c = clients.computeIfAbsent(id, k -> new Client());
            synchronized (c) {
                if (r.allowed()) c.allowed++; else c.blocked++;
                c.lastRequest = System.currentTimeMillis();
                c.ip = ip;
                c.browser = browser == null ? "-" : browser;
                c.remaining = String.valueOf(r.remaining());

                c.history.addFirst("{\"time\":" + c.lastRequest
                        + ",\"status\":" + code
                        + ",\"remaining\":" + c.remaining + "}");
                if (c.history.size() > 10) c.history.removeLast();
            }

            // Build the JSON response
            String message = r.allowed()
                    ? "Request allowed"
                    : "Too many requests. Wait " + r.waitMs() / 1000.0 + " seconds";

            String body = "{"
                    + "\"allowed\":" + r.allowed() + ","
                    + "\"remaining\":" + r.remaining() + ","
                    + "\"retryAfterMs\":" + r.waitMs() + ","
                    + "\"message\":\"" + message + "\""
                    + "}";

            send(ex, code, "application/json; charset=UTF-8", body);
        });

        // API 2: client details (does NOT use a token)
        server.createContext("/details", ex -> {
            String id = getId(ex);
            Client c = clients.get(id);

            if (c == null) {
                send(ex, 404, "application/json; charset=UTF-8", "{\"message\":\"Client not found\"}");
                return;
            }

            String body;
            synchronized (c) {
                body = "{"
                        + "\"clientId\":\"" + id + "\","
                        + "\"limit\":" + CAPACITY + ","
                        + "\"refillSeconds\":"
