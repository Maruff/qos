package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import com.termux.R;

import java.util.ArrayList;
import java.util.List;

/**
 * qos as the phone's home screen, and the way back to Android's own.
 *
 * qos can be chosen as the Home app (Settings > Apps > Default apps > Home app). Android then
 * opens qos when the phone starts and whenever Home is pressed; Settings > "Home button opens"
 * says whether that is the terminal or the desktop. Android's own home screen stays a tap away:
 * the Apps screen and the desktop's Android menu open it.
 */
public final class QosHome {

    public static final String PREF_HOME_OPENS = "qos_home_opens";
    private static final String PREF_ANDROID_HOME = "qos_android_home";

    private QosHome() {}

    public static final String PREF_OPEN_AT_BOOT = "qos_open_at_boot";
    /** The extra TermuxActivity is opened with after the phone starts: it then opens what Home would. */
    public static final String OPEN_AT_BOOT = "boot";

    /**
     * When the phone has started: open qos, unless it is the home screen (Android opens it then).
     * Android lets an app open itself here only with "Display over other apps"; without it, qos
     * posts a notification to tap instead.
     */
    public static void onBoot(Context context) {
        if (!PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_OPEN_AT_BOOT, true)) return;
        ResolveInfo home = context.getPackageManager().resolveActivity(
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        if (home != null && context.getPackageName().equals(home.activityInfo.packageName)) return;

        Intent open = new Intent(context, TermuxActivity.class)
            .putExtra("com.termux.qos.open", OPEN_AT_BOOT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (android.provider.Settings.canDrawOverlays(context)) {
            context.startActivity(open);
            return;
        }
        android.app.NotificationManager notifications = context.getSystemService(android.app.NotificationManager.class);
        if (notifications == null) return;
        String channel = "qos_boot";
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            notifications.createNotificationChannel(new android.app.NotificationChannel(channel,
                context.getString(R.string.qos_boot_channel), android.app.NotificationManager.IMPORTANCE_HIGH));
        android.app.PendingIntent tap = android.app.PendingIntent.getActivity(context, 0, open,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        notifications.notify(0x9051, new androidx.core.app.NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle(context.getString(R.string.qos_boot_title))
            .setContentText(context.getString(R.string.qos_boot_text))
            .setContentIntent(tap).setAutoCancel(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .build());
    }

    /** Whether the Home button opens the desktop rather than the terminal. */
    public static boolean opensDesktop(Context context) {
        return "desktop".equals(PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_HOME_OPENS, "terminal"));
    }

    /** The phone's other home screens: every Home app but qos. */
    private static List<ActivityInfo> otherHomes(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<ActivityInfo> homes = new ArrayList<>();
        for (ResolveInfo info : pm.queryIntentActivities(home, 0)) {
            if (!info.activityInfo.packageName.equals(context.getPackageName())) homes.add(info.activityInfo);
        }
        return homes;
    }

    /**
     * Open Android's home screen: the one chosen before, the only one there is, or, from an
     * activity, one picked from a list and remembered. Returns false if a choice is needed and
     * there is no activity to ask from.
     */
    public static boolean openAndroidHome(Context context) {
        List<ActivityInfo> homes = otherHomes(context);
        if (homes.isEmpty()) {
            Toast.makeText(context, R.string.android_home_none, Toast.LENGTH_LONG).show();
            return true;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String chosen = prefs.getString(PREF_ANDROID_HOME, null);
        for (ActivityInfo home : homes) {
            if (homes.size() == 1 || flat(home).equals(chosen)) return start(context, home);
        }
        if (!(context instanceof Activity)) return false;

        PackageManager pm = context.getPackageManager();
        CharSequence[] labels = new CharSequence[homes.size()];
        for (int i = 0; i < homes.size(); i++) labels[i] = homes.get(i).loadLabel(pm);
        new AlertDialog.Builder(context)
            .setTitle(R.string.android_home_choose)
            .setItems(labels, (d, which) -> {
                prefs.edit().putString(PREF_ANDROID_HOME, flat(homes.get(which))).apply();
                start(context, homes.get(which));
            })
            .show();
        return true;
    }

    private static String flat(ActivityInfo info) {
        return new ComponentName(info.packageName, info.name).flattenToString();
    }

    private static boolean start(Context context, ActivityInfo home) {
        try {
            context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClassName(home.packageName, home.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, R.string.android_home_none, Toast.LENGTH_LONG).show();
        }
        return true;
    }
}
