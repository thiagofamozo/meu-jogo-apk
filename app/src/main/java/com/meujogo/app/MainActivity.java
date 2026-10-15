package com.meujogo.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.graphics.Color;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private WebView web;
    private String home;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        home = getString(R.string.game_url);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0d1b2c"));
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                String u = req.getUrl().toString();
                Uri h = Uri.parse(home);
                // links de pagamento (Stripe, banco, WhatsApp) abrem fora do jogo
                if (u.startsWith("file:") || u.startsWith("https://app.local")) return false;
                if (req.getUrl().getHost() != null && !req.getUrl().getHost().equals(h.getHost())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, req.getUrl())); } catch (Exception e) { }
                    return true;
                }
                return false;
            }
            @Override
            public void onReceivedError(WebView v, int code, String desc, String url) {
                if (url != null && !url.startsWith("file:") && !url.startsWith("https://app.local")) offline();
            }
            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) {
                    offline();
                }
            }
        });
        // window.open (pagamento) abre no navegador do celular
        web.getSettings().setSupportMultipleWindows(false);
        tela("carregando");
        abrirJogo();
        hideBars();
    }

    private void tela(String modo) {
        String html = "<html><body style='background:#0d1b2c'></body></html>";
        try {
            java.io.InputStream in = getAssets().open("offline.html");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n; while ((n = in.read(buf)) > 0) bo.write(buf, 0, n); in.close();
            html = bo.toString("UTF-8").replace("__MODO__", modo).replace("__URL__", home);
        } catch (Exception e) { }
        web.loadDataWithBaseURL("https://app.local/", html, "text/html", "utf-8", null);
    }

    private void offline() {
        runOnUiThread(() -> tela("offline"));
    }

    // confere se o servidor responde antes de abrir (evita a pagina branca de erro)
    private void abrirJogo() {
        new Thread(() -> {
            boolean ok = false;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(home).openConnection();
                c.setConnectTimeout(6000); c.setReadTimeout(6000); c.setRequestMethod("GET");
                ok = c.getResponseCode() < 500; c.disconnect();
            } catch (Exception e) { ok = false; }
            final boolean fOk = ok;
            runOnUiThread(() -> { if (fOk) web.loadUrl(home); else offline(); });
        }).start();
    }

    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideBars(); }
    @Override protected void onSaveInstanceState(Bundle o) { super.onSaveInstanceState(o); web.saveState(o); }
    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }

    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this).setMessage("Sair do jogo?")
            .setPositiveButton("Sair", (d, w) -> finish())
            .setNegativeButton("Ficar", null).show();
    }
}
