package com.dsh.core;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Starts/stops/restarts the foreground core host service. */
public final class CoreServiceLauncher {

    public static final String ACTION_RESTART_CORE = "com.dsh.core.ACTION_RESTART_CORE";
    public static final String ACTION_HEALTH_CHECK = "com.dsh.core.ACTION_HEALTH_CHECK";
    public static final String EXTRA_REASON = "reason";

    private CoreServiceLauncher() {
    }

    /** May throw if the platform refuses the service start; callers surface it. */
    public static void start(Context context) {
        Intent intent = new Intent(context, CoreServerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void restart(Context context, String reason) {
        try {
            Intent intent = new Intent(context, CoreServerService.class);
            intent.setAction(ACTION_RESTART_CORE);
            intent.putExtra(EXTRA_REASON, reason);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception ignored) {
        }
    }

    public static void checkHealth(Context context) {
        try {
            Intent intent = new Intent(context, CoreServerService.class);
            intent.setAction(ACTION_HEALTH_CHECK);
            context.startService(intent);
        } catch (Exception ignored) {
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, CoreServerService.class));
        } catch (Exception ignored) {
        }
    }
}
