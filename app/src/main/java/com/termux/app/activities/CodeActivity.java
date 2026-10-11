package com.termux.app.activities;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import com.termux.R;
import com.termux.app.TermuxService;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;

/**
 * qos: VS Code (code-server) inside the app.
 *
 * Starts code-server in the background if it is not already listening, then
 * logs the WebView in with the random password code-server keeps in its own
 * config. Password auth stays on because 127.0.0.1 is reachable by every app
 * on the device, and code-server is a shell.
 */
public final class CodeActivity extends AppCompatActivity {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 8080;
    private static final String URL = "http://" + HOST + ":" + PORT;
    private static final int START_TIMEOUT_MS = 60_000;

    private static final String CODE_SERVER = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/code-server";
    private static final String CONFIG = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.config/code-server/config.yaml";

    private WebView mWebView;
    private TextView mStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mStatus = new TextView(this);
        mStatus.setGravity(Gravity.CENTER);
        mStatus.setPadding(48, 48, 48, 48);
        setContentView(mStatus);

        if (!new File(CODE_SERVER).exists()) {
            mStatus.setText(R.string.code_not_installed);
            return;
        }

        mWebView = new WebView(this);
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith(URL)) return false;
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                return true;
            }
        });

        mStatus.setText(R.string.code_starting);
        new Thread(this::startAndLogIn).start();
    }

    private void startAndLogIn() {
        if (!isListening()) {
            startCodeServer();
            long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
            while (!isListening()) {
                if (System.currentTimeMillis() > deadline) {
                    runOnUiThread(() -> mStatus.setText(R.string.code_failed));
                    return;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        String password = readPassword();
        runOnUiThread(() -> {
            setContentView(mWebView);
            if (password == null) {
                mWebView.loadUrl(URL);
                return;
            }
            try {
                mWebView.postUrl(URL + "/login", ("password=" + URLEncoder.encode(password, "UTF-8")).getBytes());
            } catch (UnsupportedEncodingException e) {
                mWebView.loadUrl(URL);
            }
        });
    }

    private void startCodeServer() {
        Uri executable = new Uri.Builder().scheme(TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE).path(CODE_SERVER).build();
        Intent intent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, executable);
        intent.setClass(this, TermuxService.class);
        intent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, new String[]{"--bind-addr", HOST + ":" + PORT});
        intent.putExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, true);
        intent.putExtra(TERMUX_SERVICE.EXTRA_COMMAND_LABEL, "code-server");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(intent);
        else
            startService(intent);
    }

    private static boolean isListening() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** The {@code password:} line code-server writes to its config on first start. */
    private static String readPassword() {
        try (BufferedReader reader = new BufferedReader(new FileReader(CONFIG))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("password:"))
                    return line.substring("password:".length()).trim();
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    @Override
    public void onBackPressed() {
        if (mWebView != null && mWebView.canGoBack())
            mWebView.goBack();
        else
            super.onBackPressed();
    }

}
