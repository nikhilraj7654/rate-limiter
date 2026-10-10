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
button { padding: 10px 16px; }
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
        "<button class='send'>Send Request</button> " +
        "<button class='spam'>Spam 10</button>" +
        "<h4 class='msg'></h4>" +
        "<pre class='details'></pre>";
    document.getElementById("app").appendChild(box);

    g.row = box.querySelector(".row");
    g.msg = box.querySelector(".msg");
    g.sendBtn = box.querySelector(".send");
    g.spamBtn = box.querySelector(".spam");
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
            busy: false,
            server: null      // data from the /details API
        });

        var card = document.createElement("div");
        card.onclick = function () {
            g.active = i;
            loadDetails(g.items[i]);
            render();
        };
        g.row.appendChild(card);
        g.cards.push(card);
    }

    // Buttons work only for the selected item of this group
    g.sendBtn.onclick = function () {
        sendRequest(g.items[g.active]);
    };

    g.spamBtn.onclick = async function () {
        var u = g.items[g.active];
        u.busy = true;

        for (var n = 0; n < 10; n++) {
            var allowed = await sendRequest(u);
            if (!allowed) break;
            await new Promise(function (r) { setTimeout(r, 120); });
        }

        u.busy = false;
        render();
    };

    groups.push(g);
}

// 2. Draw one group from its data
function renderGroup(g) {
    var now = Date.now();

    // cards
    g.items.forEach(function (u, i) {
        var wait = u.until - now;
        var state = wait > 0
            ? "<span class='no'>Blocked " + (wait / 1000).toFixed(1) + " s</span>"
            : "<span class='ok'>Ready</span>";
        var s = u.server;

        g.cards[i].className = (i == g.active) ? "card on" : "card";
        g.cards[i].innerHTML =
            "<b>" + g.label + " " + (i + 1) + "</b><br>" +
            "Tokens: " + Math.floor(tokens(u) + 0.001) + "/" + CAP + "<br>" +
            state + "<br>" +
            "<small>Allowed " + (s ? s.allowed : 0) + " | Blocked " + (s ? s.blocked : 0) + "</small>";
    });

    // message and buttons for the selected item
    var u = g.items[g.active];
    var wait = u.until - now;
    var disabled = u.busy || wait > 0;

    g.msg.textContent = wait > 0 ? "Please wait " + (wait / 1000).toFixed(1) + " s" : u.msg;
    g.sendBtn.disabled = disabled;
    g.spamBtn.disabled = disabled;

    // details panel (data comes from the /details API)
    var s = u.server;
    var lines = [
        "Client ID: " + u.id,
        "Limit: " + CAP + " requests, 1 token comes back every " + (REFILL / 1000) + " s",
        "Tokens now: " + tokens(u).toFixed(2) + " / " + CAP
    ];

    if (s) {
        lines.push("Total requests: " + s.total);
        lines.push("Allowed: " + s.allowed + "   Blocked: " + s.blocked);
        lines.push("Last HTTP status: " + u.status);
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
        lines.push("No requests sent yet.");
    }

    g.details.textContent = lines.join("\\n");
}

function render() {
    groups.forEach(renderGroup);
}

// 3. API 2: get the saved details of one client from the server
async function loadDetails(u) {
    var res = await fetch("/details?clientId=" + u.id);
    if (res.ok) {
        u.server = await res.json();
    }
    render();
}

// 4. API 1: send one request for one client
async function sendRequest(u) {
    var res = await fetch("/allow?clientId=" + u.id);
    var d = await res.json();

    u.time = Date.now();
    u.tokens = d.remaining;
    u.status = res.status;
    u.msg = d.message;

    if (d.allowed) {
        u.until = 0;
    } else {
        u.until = u.time + d.retryAfterMs;
    }

    await loadDetails(u);
    return d.allowed;
}

// 5. Build both groups
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
