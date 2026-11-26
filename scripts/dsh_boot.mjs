#!/usr/bin/env node
// dsh_boot.mjs - Lightweight startup wrapper for timing instrumentation,
// instant HTTP-bind wakeup, and deferred V8 compile-cache flushing.
import http from "node:http";
import moduleApi from "node:module";

const tLine1 = process.hrtime.bigint();
const forkWallMs = Number(process.env.DSH_FORK_WALL_MS || 0);
const forkToLine1Ms = forkWallMs > 0 ? Math.max(0, Date.now() - forkWallMs) : -1;

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
