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
 * qos: the phone's Android apps, for the Apps screen and for qos Desktop's Android menu.
 *
 * The desktop menu is a .desktop file per app in ~/.local/share/applications, each running
 * qos-open with the app's package, its icon written next to it as a PNG, and a merged menu file
 * that gathers them under "Android" in XFCE's Applications menu.
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

    /** Write qos Desktop's Android menu: an entry and icon per app, and none for apps since removed. */
    public static void syncDesktopMenu(Context context) {
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        File applications = new File(home + "/.local/share/applications");
        File icons = new File(home + "/.local/share/qos/android-icons");
        try {
            mkdirs(applications);
            mkdirs(icons);
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

            PackageManager pm = context.getPackageManager();
            Set<String> current = new HashSet<>();
            for (App app : list(context)) {
                current.add(app.packageName);
                File icon = new File(icons, app.packageName + ".png");
                if (!icon.exists()) writeIcon(app.info.loadIcon(pm), icon);
                write(new File(applications, DESKTOP_PREFIX + app.packageName + ".desktop"),
                    "[Desktop Entry]\nVersion=1.0\nType=Application\n" +
                    "Name=" + app.label.replace('\n', ' ') + "\n" +
                    "Exec=qos-open " + app.packageName + "\n" +
                    "Icon=" + icon.getAbsolutePath() + "\n" +
                    "Terminal=false\nNoDisplay=false\nCategories=X-Qos-Android;\n");
            }
            File[] entries = applications.listFiles((dir, name) -> name.startsWith(DESKTOP_PREFIX));
            if (entries != null) {
                for (File entry : entries) {
                    String pkg = entry.getName().substring(DESKTOP_PREFIX.length()).replaceFirst("\\.desktop$", "");
                    if (!current.contains(pkg)) {
                        entry.delete();
                        new File(icons, pkg + ".png").delete();
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Writing the desktop's Android menu failed", e);
        }
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
