package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.termux.R;

/**
 * qos: opens Android apps for qos-open, and refreshes qos Desktop's Android menu for qos-desktop.
 *
 * Not exported: only qos's own user, which the terminal and qos Desktop run as, can reach it. A
 * broadcast rather than "am start" because Termux's am refuses activity starts without the
 * Display over other apps permission.
 */
public final class QosAppReceiver extends BroadcastReceiver {

    public static final String ACTION_OPEN = "com.termux.qos.OPEN_APP";
    public static final String ACTION_SYNC = "com.termux.qos.SYNC_APPS";
    public static final String EXTRA_PACKAGE = "package";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_OPEN.equals(intent.getAction())) {
            String pkg = intent.getStringExtra(EXTRA_PACKAGE);
            if (pkg == null || !QosApps.open(context, pkg))
                Toast.makeText(context, context.getString(R.string.apps_not_found, String.valueOf(pkg)), Toast.LENGTH_LONG).show();
        } else if (ACTION_SYNC.equals(intent.getAction())) {
            PendingResult result = goAsync();
            new Thread(() -> {
                try { QosApps.syncDesktopMenu(context.getApplicationContext()); }
                finally { result.finish(); }
            }).start();
        }
    }
}
