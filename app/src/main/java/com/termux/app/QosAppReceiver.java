package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.termux.R;

/**
 * qos: opens Android apps and Android's home screen for qos-open, and, for qos-desktop, shows the
 * desktop and refreshes its Android menu.
 *
 * Not exported: only qos's own user, which the terminal and qos Desktop run as, can reach it. A
 * broadcast rather than "am start" because Termux's am refuses activity starts without the
 * Display over other apps permission.
 */
public final class QosAppReceiver extends BroadcastReceiver {

    public static final String ACTION_OPEN = "com.termux.qos.OPEN_APP";
    public static final String ACTION_SYNC = "com.termux.qos.SYNC_APPS";
    public static final String ACTION_HOME = "com.termux.qos.OPEN_ANDROID_HOME";
    public static final String ACTION_DESKTOP = "com.termux.qos.OPEN_DESKTOP";
    public static final String EXTRA_PACKAGE = "package";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_OPEN.equals(intent.getAction())) {
            String pkg = intent.getStringExtra(EXTRA_PACKAGE);
            if (pkg == null || !QosApps.open(context, pkg))
                Toast.makeText(context, context.getString(R.string.apps_not_found, String.valueOf(pkg)), Toast.LENGTH_LONG).show();
        } else if (ACTION_HOME.equals(intent.getAction())) {
            // With several home screens and none chosen yet, qos asks which.
            if (!QosHome.openAndroidHome(context))
                context.startActivity(new Intent(context, TermuxActivity.class)
                    .putExtra("com.termux.qos.open", "android-home").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (ACTION_DESKTOP.equals(intent.getAction())) {
            // qos-desktop asks once its X server is starting: show the desktop.
            context.startActivity(new Intent(context, com.termux.x11.MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (ACTION_SYNC.equals(intent.getAction())) {
            PendingResult result = goAsync();
            new Thread(() -> {
                try { QosApps.syncDesktopMenu(context.getApplicationContext()); }
                finally { result.finish(); }
            }).start();
        }
    }
}
