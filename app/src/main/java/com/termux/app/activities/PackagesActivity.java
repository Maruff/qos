package com.termux.app.activities;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * qos: find, install and remove packages, one by one or as a kit for a kind of work.
 *
 * The lists come straight from apt's package lists and dpkg's status file, so nothing needs the
 * network to browse. Installing and removing run pkg in a visible qos session: this screen never
 * runs a command itself, it hands one back to TermuxActivity, which started it for a result.
 */
public final class PackagesActivity extends AppCompatActivity {

    /** The command for TermuxActivity to run, and the label for its session. */
    public static final String EXTRA_COMMAND = "com.termux.qos.packages.command";
    public static final String EXTRA_LABEL = "com.termux.qos.packages.label";

    private static final Pattern PACKAGE_NAME = Pattern.compile("[a-z0-9][a-z0-9+.-]*");
    private static final String PREFIX = TermuxConstants.TERMUX_PREFIX_DIR_PATH;

    /** A set of packages for one kind of work. Repos are enabled first when a kit needs them. */
    private static final class Kit {
        final int name, description;
        final String repos, packages;
        Kit(int name, int description, String repos, String packages) {
            this.name = name; this.description = description; this.repos = repos; this.packages = packages;
        }
    }

    private static final Kit[] KITS = {
        new Kit(R.string.kit_business, R.string.kit_business_desc, "", "python postgresql sqlite"),
        new Kit(R.string.kit_android, R.string.kit_android_desc, "", "kotlin gradle openjdk-21 aapt2 d8 apksigner android-tools"),
        new Kit(R.string.kit_games, R.string.kit_games_desc, "x11-repo tur-repo", "godot love raylib python-pygame"),
        new Kit(R.string.kit_animation, R.string.kit_animation_desc, "x11-repo", "krita inkscape gimp manim imagemagick"),
        new Kit(R.string.kit_film, R.string.kit_film_desc, "x11-repo", "ffmpeg kdenlive shotcut audacity"),
        new Kit(R.string.kit_iot, R.string.kit_iot_desc, "", "mosquitto picocom minicom python"),
        new Kit(R.string.kit_security, R.string.kit_security_desc, "", "nmap gnupg age wireguard-tools tor radare2 gdb yara clamav apktool jadx"),
        new Kit(R.string.kit_ai, R.string.kit_ai_desc, "", "llama-cpp ollama python-numpy python-scipy python-onnxruntime"),
    };

    private static final class Pkg {
        String name, version = "", description = "", installedVersion;
        long sizeKb;
    }

    private enum Tab { KITS, ALL, INSTALLED }

    private final List<Pkg> mPackages = new ArrayList<>();
    private Tab mView = Tab.KITS;
    private EditText mSearch;
    private TextView mStatus;
    private ArrayAdapter<Object> mAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.packages_title);

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad / 2, pad, 0);

        LinearLayout tabs = new LinearLayout(this);
        tabs.addView(tab(R.string.packages_kits, Tab.KITS), weight());
        tabs.addView(tab(R.string.packages_all, Tab.ALL), weight());
        tabs.addView(tab(R.string.packages_installed, Tab.INSTALLED), weight());
        Button refresh = new Button(this, null, android.R.attr.buttonBarButtonStyle);
        refresh.setText(R.string.packages_refresh);
        refresh.setOnClickListener(v -> finishWithCommand("pkg update -y", getString(R.string.packages_refresh)));
        tabs.addView(refresh, weight());
        root.addView(tabs);

        mSearch = new EditText(this);
        mSearch.setHint(R.string.packages_search);
        mSearch.setSingleLine(true);
        mSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { show(); }
        });
        root.addView(mSearch);

        mStatus = new TextView(this);
        mStatus.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(mStatus);

        ListView list = new ListView(this);
        mAdapter = new ArrayAdapter<Object>(this, android.R.layout.simple_list_item_2, android.R.id.text1) {
            @NonNull @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                Object item = getItem(position);
                TextView title = row.findViewById(android.R.id.text1);
                TextView detail = row.findViewById(android.R.id.text2);
                if (item instanceof Kit) {
                    Kit kit = (Kit) item;
                    title.setText(kit.name);
                    detail.setText(getString(kit.description) + "\n" + kit.packages);
                } else {
                    Pkg p = (Pkg) item;
                    title.setText(p.installedVersion != null
                        ? getString(R.string.packages_row_installed, p.name, p.installedVersion)
                        : p.name + "  " + p.version);
                    detail.setText(p.description);
                }
                return row;
            }
        };
        list.setAdapter(mAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Object item = mAdapter.getItem(position);
            if (item instanceof Kit) confirmKit((Kit) item);
            else confirmPackage((Pkg) item);
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        mStatus.setText(R.string.packages_loading);
        new Thread(() -> {
            List<Pkg> loaded = load();
            runOnUiThread(() -> { mPackages.clear(); mPackages.addAll(loaded); show(); });
        }).start();
    }

    private Button tab(int label, Tab view) {
        Button b = new Button(this, null, android.R.attr.buttonBarButtonStyle);
        b.setText(label);
        b.setOnClickListener(v -> { mView = view; show(); });
        return b;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    /** Fill the list for the current view and search. */
    private void show() {
        mAdapter.clear();
        mSearch.setVisibility(mView == Tab.KITS ? View.GONE : View.VISIBLE);
        if (mView == Tab.KITS) {
            for (Kit kit : KITS) mAdapter.add(kit);
            mStatus.setText(R.string.packages_kits_intro);
            return;
        }
        if (mPackages.isEmpty()) {
            mStatus.setText(R.string.packages_no_lists);
            return;
        }
        String query = mSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (Pkg p : mPackages) {
            if (mView == Tab.INSTALLED && p.installedVersion == null) continue;
            if (!query.isEmpty() && !p.name.contains(query) && !p.description.toLowerCase(Locale.ROOT).contains(query)) continue;
            mAdapter.add(p);
            if (++shown == 300) break; // enough to scroll; the search narrows the rest
        }
        mStatus.setText(getString(R.string.packages_count, shown));
    }

    private void confirmKit(Kit kit) {
        String command = (kit.repos.isEmpty() ? "" : "pkg install -y " + kit.repos + " && pkg update -y && ")
            + "pkg install -y " + kit.packages;
        new AlertDialog.Builder(this)
            .setTitle(kit.name)
            .setMessage(getString(kit.description) + "\n\n" + kit.packages)
            .setPositiveButton(R.string.packages_install, (d, w) -> finishWithCommand(command, getString(kit.name)))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void confirmPackage(Pkg p) {
        if (!PACKAGE_NAME.matcher(p.name).matches()) return;
        String message = p.description + "\n\n" + getString(R.string.packages_details, p.version, p.sizeKb / 1024.0);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(p.name).setMessage(message)
            .setNegativeButton(android.R.string.cancel, null);
        if (p.installedVersion == null)
            dialog.setPositiveButton(R.string.packages_install, (d, w) -> finishWithCommand("pkg install -y " + p.name, p.name));
        else
            dialog.setPositiveButton(R.string.packages_remove, (d, w) -> finishWithCommand("pkg uninstall -y " + p.name, p.name));
        dialog.show();
    }

    private void finishWithCommand(String command, String label) {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_COMMAND, command).putExtra(EXTRA_LABEL, label));
        finish();
    }

    /** Every package in apt's lists, with the installed version from dpkg's status, by name. */
    private static List<Pkg> load() {
        Map<String, Pkg> byName = new HashMap<>();
        File[] lists = new File(PREFIX + "/var/lib/apt/lists").listFiles((dir, name) -> name.endsWith("_Packages"));
        if (lists != null) {
            for (File f : lists) {
                for (Map<String, String> fields : stanzas(f)) {
                    String name = fields.get("Package");
                    if (name == null || byName.containsKey(name)) continue;
                    Pkg p = new Pkg();
                    p.name = name;
                    p.version = fields.getOrDefault("Version", "");
                    p.description = fields.getOrDefault("Description", "");
                    try { p.sizeKb = Long.parseLong(fields.getOrDefault("Installed-Size", "0")); } catch (NumberFormatException ignored) {}
                    byName.put(name, p);
                }
            }
        }
        for (Map<String, String> fields : stanzas(new File(PREFIX + "/var/lib/dpkg/status"))) {
            String status = fields.getOrDefault("Status", "");
            Pkg p = byName.get(fields.get("Package"));
            if (p != null && status.endsWith(" installed")) p.installedVersion = fields.getOrDefault("Version", "");
        }
        List<Pkg> all = new ArrayList<>(byName.values());
        all.sort((a, b) -> a.name.compareTo(b.name));
        return all;
    }

    /** The "Field: value" stanzas of a Debian control-style file; continuation lines are dropped. */
    private static List<Map<String, String>> stanzas(File file) {
        List<Map<String, String>> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            Map<String, String> current = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!current.isEmpty()) out.add(current);
                    current = new HashMap<>();
                } else if (!line.startsWith(" ") && line.indexOf(':') > 0) {
                    int colon = line.indexOf(':');
                    current.put(line.substring(0, colon), line.substring(colon + 1).trim());
                }
            }
            if (!current.isEmpty()) out.add(current);
        } catch (IOException ignored) {
        }
        return out;
    }
}
