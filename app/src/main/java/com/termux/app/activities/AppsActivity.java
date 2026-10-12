package com.termux.app.activities;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.app.QosApps;
import com.termux.app.QosHome;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * qos: the phone's Android apps, to open from qos, and Android's own home screen. Opening it
 * also refreshes qos Desktop's Android menu, so an app installed since shows up there too.
 */
public final class AppsActivity extends AppCompatActivity {

    private final List<QosApps.App> mApps = new ArrayList<>();
    private EditText mSearch;
    private ArrayAdapter<QosApps.App> mAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.apps_title);

        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        int iconSize = (int) (40 * density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad / 2, pad, 0);

        mSearch = new EditText(this);
        mSearch.setHint(R.string.apps_search);
        mSearch.setSingleLine(true);
        mSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { show(); }
        });
        root.addView(mSearch);

        // First: the way back to Android's own home screen, for when qos is the home screen.
        Button androidHome = new Button(this, null, android.R.attr.buttonBarButtonStyle);
        androidHome.setText(R.string.android_home);
        androidHome.setOnClickListener(v -> QosHome.openAndroidHome(this));
        root.addView(androidHome);

        PackageManager pm = getPackageManager();
        ListView list = new ListView(this);
        mAdapter = new ArrayAdapter<QosApps.App>(this, 0) {
            @NonNull @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                LinearLayout row = (LinearLayout) convertView;
                if (row == null) {
                    row = new LinearLayout(AppsActivity.this);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, pad / 2, 0, pad / 2);
                    ImageView icon = new ImageView(AppsActivity.this);
                    row.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
                    TextView label = new TextView(AppsActivity.this);
                    label.setPadding(pad, 0, 0, 0);
                    label.setTextSize(16);
                    row.addView(label);
                }
                QosApps.App app = getItem(position);
                ((ImageView) row.getChildAt(0)).setImageDrawable(app.info.loadIcon(pm));
                ((TextView) row.getChildAt(1)).setText(app.label);
                return row;
            }
        };
        list.setAdapter(mAdapter);
        list.setOnItemClickListener((parent, view, position, id) ->
            QosApps.open(this, mAdapter.getItem(position).packageName));
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        new Thread(() -> {
            List<QosApps.App> loaded = QosApps.list(this);
            runOnUiThread(() -> { mApps.clear(); mApps.addAll(loaded); show(); });
            QosApps.syncDesktopMenu(getApplicationContext());
        }).start();
    }

    private void show() {
        mAdapter.clear();
        String query = mSearch.getText().toString().trim().toLowerCase(Locale.getDefault());
        for (QosApps.App app : mApps) {
            if (query.isEmpty() || app.label.toLowerCase(Locale.getDefault()).contains(query)
                || app.packageName.contains(query))
                mAdapter.add(app);
        }
    }
}
