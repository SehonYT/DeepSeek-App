#!/usr/bin/env node
// dsh_boot.mjs - Lightweight startup wrapper for timing instrumentation,
// instant HTTP-bind wakeup, and deferred V8 compile-cache flushing.
// Capture line-1 timestamp before importing built-in modules so node_line1_ms
// measures bare VM startup cleanly.
const tLine1 = process.hrtime.bigint();
const forkWallMs = Number(process.env.DSH_FORK_WALL_MS || 0);
const forkToLine1Ms = forkWallMs > 0 ? Math.max(0, Date.now() - forkWallMs) : -1;

// Defer V8 stack trace capture during startup initialization (restored on HTTP listen)
// so dense plugin graph wiring does not walk 10 frames per transient Error allocation.
const origStackLimit = Error.stackTraceLimit;
Error.stackTraceLimit = 0;

// Use CJS require() for node:http / node:module so ESM syncExports() does not
// eagerly evaluate node:http's WebSocket/fetch getters (which load internal undici).
const { createRequire } = await import("node:module");
const _bootRequire = createRequire(import.meta.url);
const http = _bootRequire("node:http");
const moduleApi = _bootRequire("node:module");
const fs = _bootRequire("node:fs");
const path = _bootRequire("node:path");
const { fileURLToPath } = _bootRequire("node:url");

const dshRoot = path.resolve(fileURLToPath(import.meta.url), "../../../..");
let rpCache = new Map();
let readCache = new Map();
const origRealpathSync = fs.realpathSync;
const origRealpathNative = origRealpathSync.native;
const origReadFileSync = fs.readFileSync;

// Short-lived startup caches eliminate thousands of redundant lstat/realpath and
// package.json readFile syscalls during ESM resolution and preflight checks; both
// hooks are torn down cleanly as soon as the core binds its HTTP port.
const cachedRealpathSync = function (p, opts) {
    if (rpCache && opts === undefined && typeof p === "string") {
        let hit = rpCache.get(p);
        if (hit !== undefined) return hit;
        hit = origRealpathSync.call(this, p);
        rpCache.set(p, hit);
        return hit;
    }
    return origRealpathSync.call(this, p, opts);
};
Object.assign(cachedRealpathSync, origRealpathSync);
if (typeof origRealpathNative === "function") {
    cachedRealpathSync.native = function (p, opts) {
        if (rpCache && opts === undefined && typeof p === "string") {
            let hit = rpCache.get(p);
            if (hit !== undefined) return hit;
            hit = origRealpathNative.call(this, p);
            rpCache.set(p, hit);
            return hit;
        }
        return origRealpathNative.call(this, p, opts);
    };
}
fs.realpathSync = cachedRealpathSync;

fs.readFileSync = function (p, opts) {
    if (readCache && (opts === "utf8" || opts === "utf-8") && typeof p === "string"
            && p.startsWith(dshRoot) && p.endsWith("package.json")) {
        let hit = readCache.get(p);
        if (hit !== undefined) return hit;
        hit = origReadFileSync.call(this, p, opts);
        readCache.set(p, hit);
        return hit;
    }
    return origReadFileSync.call(this, p, opts);
};

function restoreBootHooks() {
    Error.stackTraceLimit = origStackLimit;
    rpCache = null;
    readCache = null;
    fs.realpathSync = origRealpathSync;
    fs.readFileSync = origReadFileSync;
}

let tModuleLoaded = tLine1;
let moduleLoadMs = -1;
let readyEmitted = false;
let cacheFlushed = false;

function flushCompileCacheOnce() {
    if (cacheFlushed) return;
    cacheFlushed = true;
    try {
        if (typeof moduleApi.flushCompileCache === "function") {
            moduleApi.flushCompileCache();
        }
    } catch {
        // Best-effort flush; ignore unsupported runtimes.
    }
}

process.on("SIGTERM", () => {
    flushCompileCacheOnce();
});

// Persist the compile cache on every path the JVM-side supervisor uses to stop
// us. Only SIGTERM was handled before, and the single delayed flush could lose
// everything when Android kills the process. Node writes the cache directory on
// flush, so a second boot that starts from a complete cache skips recompiling
// the ~700 modules it would otherwise parse again.
for (const signal of ["SIGINT", "SIGHUP"]) {
    process.on(signal, () => {
        flushCompileCacheOnce();
        process.exit(0);
    });
}
process.on("beforeExit", () => {
    flushCompileCacheOnce();
});

const targetPort = Number(process.env.DEEPSEEK_PORT || 0);
const origListen = http.Server.prototype.listen;
http.Server.prototype.listen = function wrappedListen(...args) {
    if (!readyEmitted) {
        this.once("listening", () => {
            if (readyEmitted) return;
            let boundPort = targetPort;
            try {
                const addr = this.address();
                if (addr && typeof addr === "object" && typeof addr.port === "number") {
                    boundPort = addr.port;
                }
            } catch {
            }
            if (targetPort > 0 && boundPort > 0 && boundPort !== targetPort) {
                return;
            }
            readyEmitted = true;
            restoreBootHooks();
            const tListen = process.hrtime.bigint();
            const appInitMs = Number((tListen - tModuleLoaded) / 1_000_000n);
            const httpListenMs = Number((tListen - tLine1) / 1_000_000n);
            process.stdout.write(`\nDSH_HTTP_BOUND ${boundPort}\n`);
            process.stdout.write(
                `[dsh_boot] ${JSON.stringify({
                    fork_to_line1_ms: forkToLine1Ms,
                    module_load_ms: moduleLoadMs,
                    app_init_ms: appInitMs,
                    http_listen_ms: httpListenMs,
                })}\n`
            );
            // Flush twice. The first flush lands as soon as the port is bound, so a
            // process killed shortly after start still leaves a usable cache behind
            // (Android kills background processes aggressively). The second, delayed
            // one runs after WebView first paint to capture anything compiled since,
            // without competing with the first frame for flash I/O.
            // Defer to a macrotask so the flush never sits on the listen critical
            // path: the WebSocket handshake and first paint must not wait on flash I/O.
            setTimeout(() => {
                try {
                    flushCompileCacheOnce();
                } catch {
                    // Best-effort; never break boot.
                }
                cacheFlushed = false;
            }, 0);
            const timer = setTimeout(flushCompileCacheOnce, 2500);
            if (typeof timer.unref === "function") {
                timer.unref();
            }
        });
    }
    return origListen.apply(this, args);
};

const { runCli } = await import("./bin.js");
tModuleLoaded = process.hrtime.bigint();
moduleLoadMs = Number((tModuleLoaded - tLine1) / 1_000_000n);
await runCli();
