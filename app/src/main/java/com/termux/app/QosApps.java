package com.termux.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * qos: the phone's Android apps, for the Apps screen, and Android's place on the desktop: the
 * Android menu (a merged menu file gathering qos's entries under "Android" in XFCE's
 * Applications menu) and the Android browsers XFCE can open links with (helpers).
 */
public final class QosApps {

    private static final String LOG_TAG = "QosApps";
    private static final String DESKTOP_PREFIX = "qos-android-";
    private static final int ICON_SIZE = 96;

    public static final class App {
        public final String packageName, label;
        public final ResolveInfo info;
        App(String packageName, String label, ResolveInfo info) {
            this.packageName = packageName; this.label = label; this.info = info;
        }
    }

    private QosApps() {}

    /** Every app with a launcher entry, other than qos itself, sorted by name. */
    public static List<App> list(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> seen = new HashSet<>();
        List<App> apps = new ArrayList<>();
        for (ResolveInfo info : pm.queryIntentActivities(launcher, 0)) {
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(context.getPackageName()) || !seen.add(pkg)) continue;
            apps.add(new App(pkg, String.valueOf(info.loadLabel(pm)), info));
        }
        Collator collator = Collator.getInstance();
        apps.sort((a, b) -> collator.compare(a.label, b.label));
        return apps;
    }

    /** Open an app as its launcher icon would. Returns false if it has no launcher entry. */
    public static boolean open(Context context, String packageName) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) return false;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        return true;
    }

    /**
     * Write Android's place on the desktop: the Android menu (Android's home screen, and the
     * Apps screen with every app; a menu of every app is too long to scroll by touch), and an
     * XFCE web-browser choice for each Android browser, with Android's default browser chosen
     * unless another one has been.
     */
    public static void syncDesktopMenu(Context context) {
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        File applications = new File(home + "/.local/share/applications");
        File icons = new File(home + "/.local/share/qos/android-icons");
        File helpers = new File(home + "/.local/share/xfce4/helpers");
        try {
            mkdirs(applications);
            mkdirs(icons);
            mkdirs(helpers);
            write(new File(home + "/.config/menus/applications-merged/qos-android.menu"),
                "<!DOCTYPE Menu PUBLIC \"-//freedesktop//DTD Menu 1.0//EN\"\n" +
                "  \"http://www.freedesktop.org/standards/menu-spec/1.0/menu.dtd\">\n" +
                "<Menu>\n" +
                "  <Name>Xfce</Name>\n" +
                "  <Menu>\n" +
                "    <Name>Android</Name>\n" +
                "    <Directory>qos-android.directory</Directory>\n" +
                "    <Include><Category>X-Qos-Android</Category></Include>\n" +
                "  </Menu>\n" +
                "</Menu>\n");
            write(new File(home + "/.local/share/desktop-directories/qos-android.directory"),
                "[Desktop Entry]\nVersion=1.0\nType=Directory\nName=Android\nName[ta]=ஆண்ட்ராய்டு\n" +
                "Icon=" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/share/pixmaps/qos.png\n");

            // The Android menu, also the dock's two Android launchers (qos-xsession).
            Set<String> entries = new HashSet<>();
            entries.add(DESKTOP_PREFIX + "home.desktop");
            write(new File(applications, DESKTOP_PREFIX + "home.desktop"),
                "[Desktop Entry]\nVersion=1.0\nType=Application\n" +
                "Name=Android home screen\nName[ta]=ஆண்ட்ராய்டு முகப்புத் திரை\n" +
                "Exec=qos-open --home\nIcon=go-home\nTerminal=false\nCategories=X-Qos-Android;\n");
            entries.add(DESKTOP_PREFIX + "apps.desktop");
            write(new File(applications, DESKTOP_PREFIX + "apps.desktop"),
                "[Desktop Entry]\nVersion=1.0\nType=Application\n" +
                "Name=Android apps\nName[ta]=ஆண்ட்ராய்டு செயலிகள்\n" +
                "Exec=qos-open --apps\nIcon=" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/share/pixmaps/qos.png\n" +
                "Terminal=false\nCategories=X-Qos-Android;\n");
            // Entries for single apps, written by qos 0.6 to 0.10, go.
            File[] old = applications.listFiles((dir, name) -> name.startsWith(DESKTOP_PREFIX) && !entries.contains(name));
            if (old != null) for (File f : old) f.delete();

            // Web browsers: Android's default, and each browser on the phone.
            Set<String> written = new HashSet<>();
            written.add(DESKTOP_PREFIX + "browser.desktop");
            write(new File(helpers, DESKTOP_PREFIX + "browser.desktop"),
                helper("Android's default browser", "qos-open --url"));
            PackageManager pm = context.getPackageManager();
            Intent web = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://qos.ae"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
            for (ResolveInfo info : pm.queryIntentActivities(web, 0)) {
                String pkg = info.activityInfo.packageName;
                if (pkg.equals(context.getPackageName()) || !written.add(DESKTOP_PREFIX + "browser-" + pkg + ".desktop")) continue;
                File icon = new File(icons, pkg + ".png");
                if (!icon.exists()) writeIcon(info.loadIcon(pm), icon);
                write(new File(helpers, DESKTOP_PREFIX + "browser-" + pkg + ".desktop"),
                    helper(String.valueOf(info.loadLabel(pm)).replace('\n', ' ') + " (Android)", "qos-open --url --package " + pkg)
                        + "Icon=" + icon.getAbsolutePath() + "\n");
            }
            File[] stale = helpers.listFiles((dir, name) -> name.startsWith(DESKTOP_PREFIX) && !written.contains(name));
            if (stale != null) for (File f : stale) f.delete();

            // Android's default browser is the desktop's, unless one has been chosen.
            File helpersRc = new File(home + "/.config/xfce4/helpers.rc");
            String rc = helpersRc.isFile() ? read(helpersRc) : "";
            if (!java.util.regex.Pattern.compile("^WebBrowser=\\S", java.util.regex.Pattern.MULTILINE).matcher(rc).find())
                write(helpersRc, rc + (rc.isEmpty() || rc.endsWith("\n") ? "" : "\n") + "WebBrowser=" + DESKTOP_PREFIX + "browser\n");
        } catch (IOException | RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Writing the desktop's Android menu failed", e);
        }
    }

    /** An XFCE helper (preferred application) for web browsing, opening links with command. */
    private static String helper(String name, String command) {
        return "[Desktop Entry]\nVersion=1.0\nType=X-XFCE-Helper\nNoDisplay=true\n" +
            "X-XFCE-Category=WebBrowser\nName=" + name + "\n" +
            "X-XFCE-Commands=" + command + "\nX-XFCE-CommandsWithParameter=" + command + " \"%s\"\n";
    }

    private static void writeIcon(Drawable drawable, File file) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, ICON_SIZE, ICON_SIZE);
        drawable.draw(canvas);
        try (OutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        bitmap.recycle();
    }

    private static String read(File file) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) != -1;) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void write(File file, String text) throws IOException {
        mkdirs(file.getParentFile());
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void mkdirs(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
    }
}
