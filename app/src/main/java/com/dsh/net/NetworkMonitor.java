package com.dsh.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.util.Log;

/**
 * Watches the system default network and notifies its listener on every
 * connectivity change. Degrades to a no-op (older device, missing permission,
 * SecurityException) instead of breaking core startup.
 */
public final class NetworkMonitor {

    private static final String TAG = "NetworkMonitor";

    public interface Listener {
        void onNetworkChanged();
    }

    public interface RecoveryListener {
        void onNetworkRecovered();
    }

    private final Context appContext;
    private final Listener listener;
    private final RecoveryListener recoveryListener;
    private final Object stateLock = new Object();

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback callback;
    private boolean registered;
    private boolean offline;
    private boolean ignoreInitialAvailable;
    private Network currentNetwork;
    /** Link-properties fingerprint; suppresses duplicate DNS rewrites. */
    private String lastDnsSignature = "";

    public NetworkMonitor(Context context, Listener listener) {
        this(context, listener, null);
    }

    public NetworkMonitor(Context context, Listener listener, RecoveryListener recoveryListener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        this.recoveryListener = recoveryListener;
    }

    /** Idempotent. Safe to call from any thread; never throws. */
    public synchronized void register() {
        if (registered) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.i(TAG, "default network callback needs API 24; DNS refresh disabled");
            return;
        }
        try {
            final ConnectivityManager cm =
                    (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                Log.w(TAG, "ConnectivityManager unavailable; DNS refresh disabled");
                return;
            }
            final Network initialNet = cm.getActiveNetwork();
            synchronized (stateLock) {
                this.currentNetwork = initialNet;
                this.offline = (initialNet == null);
                this.ignoreInitialAvailable = (initialNet != null);
            }
            ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    notifyChanged();
                    boolean recovered = false;
                    synchronized (stateLock) {
                        if (ignoreInitialAvailable && !offline
                                && (currentNetwork == null || currentNetwork.equals(network))) {
                            ignoreInitialAvailable = false;
                        } else if (offline) {
                            offline = false;
                            ignoreInitialAvailable = false;
                            currentNetwork = network;
                            recovered = true;
                        } else {
                            ignoreInitialAvailable = false;
                            currentNetwork = network;
                        }
                    }
                    if (recovered) {
                        notifyRecovered();
                    }
                }

                @Override
                public void onLost(Network network) {
                    // Only report offline when no usable default network is left.
                    // The old test also fired when currentNetwork was still null
                    // (the callback can beat the initial getActiveNetwork()
                    // snapshot) and it marked the device offline even if the OS
                    // had another default network ready. A spurious offline is
                    // not cosmetic: recovery is the only thing that dispatches
                    // window 'online', so the WebView stayed on "Reconnecting".
                    synchronized (stateLock) {
                        Network active = null;
                        try {
                            active = cm.getActiveNetwork();
                        } catch (Exception ignored) {
                        }
                        if (currentNetwork == null || currentNetwork.equals(network)) {
                            currentNetwork = active;
                        }
                        offline = (active == null);
                        ignoreInitialAvailable = false;
                    }
                    // A loss that still leaves a default network only changes the
                    // DNS set; refresh it. Never publish a bare "offline" event:
                    // MainActivity only reacts to recovery, and the old code
                    // marked the device offline even when the OS had already
                    // handed over to another default network (Wi-Fi -> mobile),
                    // which left the WebView stuck on "Reconnecting".
                    notifyChanged();
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                    // Fires often (DHCP renew, IPv6 RA, MTU change). Only rewrite
                    // the DNS config when the server list actually changed, so a
                    // chatty callback cannot turn into repeated fsyncs.
                    String signature = dnsSignature(linkProperties);
                    synchronized (stateLock) {
                        if (signature.equals(lastDnsSignature)) {
                            return;
                        }
                        lastDnsSignature = signature;
                    }
                    notifyChanged();
                }

                @Override
                public void onCapabilitiesChanged(Network network,
                                                  android.net.NetworkCapabilities capabilities) {
                    // Association/handover shows up here first; a network that is
                    // validated is by definition not offline. Clearing the flag
                    // here prevents a lost-then-replaced callback pair from
                    // leaving the UI stuck in the offline state.
                    synchronized (stateLock) {
                        if (capabilities != null
                                && capabilities.hasCapability(
                                        android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                            ignoreInitialAvailable = false;
                            currentNetwork = network;
                            offline = false;
                        }
                    }
                }
            };
            cm.registerDefaultNetworkCallback(cb);
            this.connectivityManager = cm;
            this.callback = cb;
            this.registered = true;
            Log.i(TAG, "registered default network callback");
        } catch (SecurityException e) {
            Log.w(TAG, "ACCESS_NETWORK_STATE missing; DNS refresh disabled", e);
        } catch (Exception e) {
            Log.w(TAG, "cannot register network callback; DNS refresh disabled", e);
        }
    }

    /** Idempotent teardown. Safe to call from any thread; never throws. */
    public synchronized void unregister() {
        if (!registered || connectivityManager == null || callback == null) {
            registered = false;
            callback = null;
            synchronized (stateLock) {
                currentNetwork = null;
                offline = false;
                ignoreInitialAvailable = false;
                lastDnsSignature = "";
            }
            return;
        }
        try {
            connectivityManager.unregisterNetworkCallback(callback);
        } catch (Exception e) {
            Log.w(TAG, "cannot unregister network callback", e);
        } finally {
            registered = false;
            connectivityManager = null;
            callback = null;
            synchronized (stateLock) {
                currentNetwork = null;
                offline = false;
                ignoreInitialAvailable = false;
            }
        }
    }

    private void notifyChanged() {
        if (listener == null) {
            return;
        }
        try {
            listener.onNetworkChanged();
        } catch (Exception e) {
            Log.w(TAG, "network change listener failed", e);
        }
    }

    private static String dnsSignature(LinkProperties link) {
        if (link == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (java.net.InetAddress addr : link.getDnsServers()) {
            if (addr != null) {
                sb.append(addr.getHostAddress()).append(',');
            }
        }
        return sb.toString();
    }

    private void notifyRecovered() {
        if (recoveryListener == null) {
            return;
        }
        try {
            recoveryListener.onNetworkRecovered();
        } catch (Exception e) {
            Log.w(TAG, "network recovery listener failed", e);
        }
    }
}
