```javascript
// Switching users does not stop background spam
function pick(i) {
    active = i;
    tick();
}

// Send requ```javascript
// Switching users does not stop background spam
function pick(i) {
    active = i;
    tick();
}

// Send request for a specific user
function hitUser(s) {
    return fetch("/allow?clientId=" + s.id)
        .then(x => x.json())
        .then(d => {
            var now = Date.now();
            s.stamp = now;

            if (d.allowed) {
                s.ok++;
                s.tokens = d.remaining;
                s.until = 0;
                s.msg = "Allowed. Remaining: " + d.remaining;
                s.cls = "ok";
            } else {
                s.no++;
                s.tokens = d.remaining;
                s.until = now + d.retryAfterMs;
                s.msg = d.message;
                s.cls = "no";
            }

            tick();
            return d.allowed;
        })
        .catch(() => false);
}

function hit() {
    return hitUser(users[active]);
}

$("send").onclick = hit;

// Each user's spam runs independently
$("spam").onclick = function () {
    var s = users[active];

    if (s.busy || s.until > Date.now()) return;

    s.busy = true;
    var n = 0;

    function next() {
        if (n >= 10 || s.until > Date.now()) {
            s.busy = false;
            tick();
            return;
        }

        n++;

        hitUser(s).then(ok => {
            if (ok) {
                setTimeout(next, 120);
            } else {
                s.busy = false;
                tick();
            }
        });
    }

    next();
};
``````javascript
// Switching users does not stop background spam
function pick(i) {
    active = i;
    tick();
}

// Send request for a specific user
function hitUser(s) {
    return fetch("/allow?clientId=" + s.id)
        .then(x => x.json())
        .then(d => {
            var now = Date.now();
            s.stamp = now;

            if (d.allowed) {
                s.ok++;
                s.tokens = d.remaining;
                s.until = 0;
                s.msg = "Allowed. Remaining: " + d.remaining;
                s.cls = "ok";
            } else {
                s.no++;
                s.tokens = d.remaining;
                s.until = now + d.retryAfterMs;
                s.msg = d.message;
                s.cls = "no";
            }

            tick();
            return d.allowed;
        })
        .catch(() => false);
}

function hit() {
    return hitUser(users[active]);
}

$("send").onclick = hit;

// Each user's spam runs independently
$("spam").onclick = function () {
    var s = users[active];

    if (s.busy || s.until > Date.now()) return;

    s.busy = true;
    var n = 0;

    function next() {
        if (n >= 10 || s.until > Date.now()) {
            s.busy = false;
            tick();
            return;
        }

        n++;

        hitUser(s).then(ok => {
            if (ok) {
                setTimeout(next, 120);
            } else {
                s.busy = false;
                tick();
            }
        });
    }

    next();
};
``````javascript
// Switching users does not stop background spam
function pick(i) {
    active = i;
    tick();
}

// Send request for a specific user
function hitUser(s) {
    return fetch("/allow?clientId=" + s.id)
        .then(x => x.json())
        .then(d => {
            var now = Date.now();
            s.stamp = now;

            if (d.allowed) {
                s.ok++;
                s.tokens = d.remaining;
                s.until = 0;
                s.msg = "Allowed. Remaining: " + d.remaining;
                s.cls = "ok";
            } else {
                s.no++;
                s.tokens = d.remaining;
                s.until = now + d.retryAfterMs;
                s.msg = d.message;
                s.cls = "no";
            }

            tick();
            return d.allowed;
        })
        .catch(() => false);
}

function hit() {
    return hitUser(users[active]);
}

$("send").onclick = hit;

// Each user's spam runs independently
$("spam").onclick = function () {
    var s = users[active];

    if (s.busy || s.until > Date.now()) return;

    s.busy = true;
    var n = 0;

    function next() {
        if (n >= 10 || s.until > Date.now()) {
            s.busy = false;
            tick();
            return;
        }

        n++;

        hitUser(s).then(ok => {
            if (ok) {
                setTimeout(next, 120);
            } else {
                s.busy = false;
                tick();
            }
        });
    }

    next();
};
``````javascript
// Switching users does not stop background spam
function pick(i) {
    active = i;
    tick();
}

// Send request for a specific user
function hitUser(s) {
    return fetch("/allow?clientId=" + s.id)
        .then(x => x.json())
        .then(d => {
            var now = Date.now();
            s.stamp = now;

            if (d.allowed) {
                s.ok++;
                s.tokens = d.remaining;
                s.until = 0;
                s.msg = "Allowed. Remaining: " + d.remaining;
                s.cls = "ok";
            } else {
                s.no++;
                s.tokens = d.remaining;
                s.until = now + d.retryAfterMs;
                s.msg = d.message;
                s.cls = "no";
            }

            tick();
            return d.allowed;
        })
        .catch(() => false);
}

function hit() {
    return hitUser(users[active]);
}

$("send").onclick = hit;

// Each user's spam runs independently
$("spam").onclick = function () {
    var s = users[active];

    if (s.busy || s.until > Date.now()) return;

    s.busy = true;
    var n = 0;

    function next() {
        if (n >= 10 || s.until > Date.now()) {
            s.busy = false;
            tick();
            return;
        }

        n++;

        hitUser(s).then(ok => {
            if (ok) {
                setTimeout(next, 120);
            } else {
                s.busy = false;
                tick();
            }
        });
    }

    next();
};
``````javascript
// Switching users does not stop background spam
function pick(i) {
    active = i;
    tick();
}

// Send request for a specific user
function hitUser(s) {
    return fetch("/allow?clientId=" + s.id)
        .then(x => x.json())
        .then(d => {
            var now = Date.now();
            s.stamp = now;

            if (d.allowed) {
                s.ok++;
                s.tokens = d.remaining;
                s.until = 0;
                s.msg = "Allowed. Remaining: " + d.remaining;
                s.cls = "ok";
            } else {
                s.no++;
                s.tokens = d.remaining;
                s.until = now + d.retryAfterMs;
                s.msg = d.message;
                s.cls = "no";
            }

            tick();
            return d.allowed;
        })
        .catch(() => false);
}

function hit() {
    return hitUser(users[active]);
}

$("send").onclick = hit;

// Each user's spam runs independently
$("spam").onclick = function () {
    var s = users[active];

    if (s.busy || s.until > Date.now()) return;

    s.busy = true;
    var n = 0;

    function next() {
        if (n >= 10 || s.until > Date.now()) {
            s.busy = false;
            tick();
            return;
        }

        n++;

        hitUser(s).then(ok => {
            if (ok) {
                setTimeout(next, 120);
            } else {
                s.busy = false;
                tick();
            }
        });
    }

    next();
};
``````javascript
// Switching users does not stop background spam
function pick(i) {
    active = i;
    tick();
}

// Send request for a specific user
function hitUser(s) {
    return fetch("/allow?clientId=" + s.id)
        .then(x => x.json())
        .then(d => {
            var now = Date.now();
            s.stamp = now;

            if (d.allowed) {
                s.ok++;
                s.tokens = d.remaining;
                s.until = 0;
                s.msg = "Allowed. Remaining: " + d.remaining;
                s.cls = "ok";
            } else {
                s.no++;
                s.tokens = d.remaining;
                s.until = now + d.retryAfterMs;
                s.msg = d.message;
                s.cls = "no";
            }

            tick();
            return d.allowed;
        })
        .catch(() => false);
}

function hit() {
    return hitUser(users[active]);
}

$("send").onclick = hit;

// Each user's spam runs independently
$("spam").onclick = function () {
    var s = users[active];

    if (s.busy || s.until > Date.now()) return;

    s.busy = true;
    var n = 0;

    function next() {
        if (n >= 10 || s.until > Date.now()) {
            s.busy = false;
            tick();
            return;
        }

        n++;

        hitUser(s).then(ok => {
            if (ok) {
                setTimeout(next, 120);
            } else {
                s.busy = false;
                tick();
            }
        });
    }

    next();
};
``````javascript
// Switching users does not stop background spam
function pick(i) {
    active = i;
    tick();
}

// Send request for a specific user
function hitUser(s) {
    return fetch("/allow?clientId=" + s.id)
        .then(x => x.json())
        .then(d => {
            var now = Date.now();
            s.stamp = now;

            if (d.allowed) {
                s.ok++;
                s.tokens = d.remaining;
                s.until = 0;
                s.msg = "Allowed. Remaining: " + d.remaining;
                s.cls = "ok";
            } else {
                s.no++;
                s.tokens = d.remaining;
                s.until = now + d.retryAfterMs;
                s.msg = d.message;
                s.cls = "no";
            }

            tick();
            return d.allowed;
        })
        .catch(() => false);
}

function hit() {
    return hitUser(users[active]);
}

$("send").onclick = hit;

// Each user's spam runs independently
$("spam").onclick = function () {
    var s = users[active];

    if (s.busy || s.until > Date.now()) return;

    s.busy = true;
    var n = 0;

    function next() {
        if (n >= 10 || s.until > Date.now()) {
            s.busy = false;
            tick();
            return;
        }

        n++;

        hitUser(s).then(ok => {
            if (ok) {
                setTimeout(next, 120);
            } else {
                s.busy = false;
                tick();
            }
        });
    }

    next();
};
``````javascript
// Switching users does not stop background spam
function pick(i) {
    active = i;
    tick();
}

// Send request for a specific user
function hitUser(s) {
    return fetch("/allow?clientId=" + s.id)
        .then(x => x.json())
        .then(d => {
            var now = Date.now();
            s.stamp = now;

            if (d.allowed) {
                s.ok++;
                s.tokens = d.remaining;
                s.until = 0;
                s.msg = "Allowed. Remaining: " + d.remaining;
                s.cls = "ok";
            } else {
                s.no++;
                s.tokens = d.remaining;
                s.until = now + d.retryAfterMs;
                s.msg = d.message;
                s.cls = "no";
            }

            tick();
            return d.allowed;
        })
        .catch(() => false);
}

function hit() {
    return hitUser(users[active]);
}

$("send").onclick = hit;

// Each user's spam runs independently
$("spam").onclick = function () {
    var s = users[active];

    if (s.busy || s.until > Date.now()) return;

    s.busy = true;
    var n = 0;

    function next() {
        if (n >= 10 || s.until > Date.now()) {
            s.busy = false;
            tick();
            return;
        }

        n++;

        hitUser(s).then(ok => {
            if (ok) {
                setTimeout(next, 120);
            } else {
                s.busy = false;
                tick();
            }
        });
    }

    next();
};
``````javascript
// Switching users does not stop background spam
function pick(i) {
    active = i;
    tick();
}

// Send request for a specific user
function hitUser(s) {
    return fetch("/allow?clientId=" + s.id)
        .then(x => x.json())
        .then(d => {
            var now = Date.now();
            s.stamp = now;

            if (d.allowed) {
                s.ok++;
                s.tokens = d.remaining;
                s.until = 0;
                s.msg = "Allowed. Remaining: " + d.remaining;
                s.cls = "ok";
            } else {
                s.no++;
                s.tokens = d.remaining;
                s.until = now + d.retryAfterMs;
                s.msg = d.message;
                s.cls = "no";
            }

            tick();
            return d.allowed;
        })
        .catch(() => false);
}

function hit() {
    return hitUser(users[active]);
}

$("send").onclick = hit;

// Each user's spam runs independently
$("spam").onclick = function () {
    var s = users[active];

    if (s.busy || s.until > Date.now()) return;

    s.busy = true;
    var n = 0;

    function next() {
        if (n >= 10 || s.until > Date.now()) {
            s.busy = false;
            tick();
            return;
        }

        n++;

        hitUser(s).then(ok => {
            if (ok) {
                setTimeout(next, 120);
            } else {
                s.busy = false;
                tick();
            }
        });
    }

    next();
};
```ng: " + d.remaining;
                s.cls = "ok";
            } else {
                s.no++;
                s.tokens = d.remaining;
                s.until = now + d.retryAfterMs;
                s.msg = d.message;
                s.cls = "no";
            }

            tick();
            return d.allowed;
        })
        .catch(() => false);
}

function hit() {
    return hitUser(users[active]);
}

$("send").onclick = hit;

// Each user's spam runs independently
$("spam").onclick = function () {
    var s = users[active];

    if (s.busy || s.until > Date.now()) return;

    s.busy = true;
    var n = 0;

    function next() {
        if (n >= 10 || s.until > Date.now()) {
            s.busy = false;
            tick();
            return;
        }

        n++;

        hitUser(s).then(ok => {
            if (ok) {
                setTimeout(next, 120);
            } else {
                s.busy = false;
                tick();
            }
        });
    }

    next();
};
```
