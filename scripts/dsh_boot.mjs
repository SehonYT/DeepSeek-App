#!/usr/bin/env node
// dsh_boot.mjs - Lightweight startup wrapper for timing instrumentation,
// instant HTTP-bind wakeup, and deferred V8 compile-cache flushing.
// Capture line-1 timestamp before importing built-in modules so node_line1_ms
// measures bare VM startup cleanly.
const tLine1 = process.hrtime.bigint();
const forkWallMs = Number(process.env.DSH_FORK_WALL_MS || 0);
const forkToLine1Ms = forkWallMs > 0 ? Math.max(0, Date.now() - forkWallMs) : -1;

// Use CJS require() for node:http / node:module so ESM syncExports() does not
// eagerly evaluate node:http's WebSocket/fetch getters (which load internal undici).
const { createRequire } = await import("node:module");
const _bootRequire = createRequire(import.meta.url);
const http = _bootRequire("node:http");
const moduleApi = _bootRequire("node:module");

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
            // Delay compile-cache flush past WebView first paint to avoid flash I/O contention.
            const timer = setTimeout(flushCompileCacheOnce, 2000);
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
