package com.meujogo.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Smash World GO - app Android.
 * 1) Tela inicial confere se o servidor esta ONLINE (se nao, nao deixa jogar).
 * 2) Baixa o jogo completo para o celular na 1a vez; depois so o que mudou (versao_app.json do servidor).
 * 3) Abre o jogo do servidor, mas imagens/sons/scripts saem da memoria do celular (rapido, sem travar).
 *    Login, batalha, chat, loja etc. continuam 100% online no servidor.
 */
public class MainActivity extends Activity {
    private WebView web;
    private String home;          // ex.: http://104.234.63.109:8080/
    private String homeHost;
    private int homePort;
    private File pasta;           // onde o jogo fica guardado no celular
    private File manifestoArq;
    // arquivo -> assinatura (tamanho-data) dos arquivos ja baixados
    private final Map<String, String> locais = new ConcurrentHashMap<>();
    private final AtomicBoolean atualizando = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        home = getString(R.string.game_url);
        if (!home.endsWith("/")) home += "/";
        Uri hu = Uri.parse(home);
        homeHost = hu.getHost();
        homePort = hu.getPort() == -1 ? 80 : hu.getPort();
        pasta = new File(getFilesDir(), "jogo");
        pasta.mkdirs();
        manifestoArq = new File(getFilesDir(), "jogo_manifesto.json");
        lerManifestoLocal();

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
        s.setUserAgentString(s.getUserAgentString() + " SmashApp");
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setSupportMultipleWindows(false);
        web.addJavascriptInterface(new Ponte(), "SmashApp");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                String u = req.getUrl().toString();
                if (u.startsWith("file:") || u.startsWith("data:") || u.startsWith("javascript:")) return false;
                // links de pagamento (Stripe, banco, WhatsApp) abrem fora do jogo
                if (req.getUrl().getHost() != null && !req.getUrl().getHost().equals(homeHost)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, req.getUrl())); } catch (Exception e) { }
                    return true;
                }
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                try {
                    if (!"GET".equalsIgnoreCase(req.getMethod())) return null;
                    return doCelular(req.getUrl());
                } catch (Exception e) { return null; }
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) offline();
            }
        });
        tela("inicio");
        hideBars();
    }

    // ---------- arquivos guardados no celular ----------

    private WebResourceResponse doCelular(Uri u) throws Exception {
        if (u.getHost() == null || !u.getHost().equals(homeHost)) return null;
        int port = u.getPort() == -1 ? 80 : u.getPort();
        if (port != homePort) return null;
        String p = u.getPath();
        if (p == null) return null;
        if (p.startsWith("/")) p = p.substring(1);
        if (p.isEmpty()) p = "index.html";
        if (!locais.containsKey(p)) return null;     // nao esta no celular -> vem do servidor
        File f = new File(pasta, p);
        if (!f.isFile()) return null;
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", "no-cache");
        WebResourceResponse r = new WebResourceResponse(tipo(p), null, new FileInputStream(f));
        if (Build.VERSION.SDK_INT >= 21) { r.setStatusCodeAndReasonPhrase(200, "OK"); r.setResponseHeaders(h); }
        return r;
    }

    private static String tipo(String p) {
        String e = p.substring(p.lastIndexOf('.') + 1).toLowerCase();
        switch (e) {
            case "html": return "text/html";
            case "js": return "application/javascript";
            case "css": return "text/css";
            case "json": return "application/json";
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            case "svg": return "image/svg+xml";
            case "ico": return "image/x-icon";
            case "mp3": return "audio/mpeg";
            case "ogg": return "audio/ogg";
            case "wav": return "audio/wav";
            case "m4a": return "audio/mp4";
            case "mp4": return "video/mp4";
            case "ttf": return "font/ttf";
            case "otf": return "font/otf";
            case "woff": return "font/woff";
            case "woff2": return "font/woff2";
            case "plist": return "text/xml";
            case "xml": return "text/xml";
            case "atlas": case "txt": case "fnt": return "text/plain";
            case "wasm": return "application/wasm";
            default: return "application/octet-stream";
        }
    }

    private void lerManifestoLocal() {
        try {
            if (!manifestoArq.isFile()) return;
            JSONObject o = new JSONObject(lerTexto(new FileInputStream(manifestoArq)));
            Iterator<String> it = o.keys();
            while (it.hasNext()) { String k = it.next(); locais.put(k, o.getString(k)); }
        } catch (Exception e) { locais.clear(); }
    }

    private synchronized void salvarManifestoLocal() {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, String> e : locais.entrySet()) o.put(e.getKey(), e.getValue());
            File tmp = new File(getFilesDir(), "jogo_manifesto.tmp");
            FileOutputStream fo = new FileOutputStream(tmp);
            fo.write(o.toString().getBytes("UTF-8"));
            fo.close();
            tmp.renameTo(manifestoArq);
        } catch (Exception e) { }
    }

    // ---------- atualizacao ----------

    private void js(final String code) {
        runOnUiThread(() -> { try { web.evaluateJavascript(code, null); } catch (Exception e) { } });
    }

    private static String q(String s) { return JSONObject.quote(s == null ? "" : s); }

    private void atualizar() {
        if (!atualizando.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                String txt;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(home + "versao_app.json?t=" + System.currentTimeMillis()).openConnection();
                    c.setConnectTimeout(8000); c.setReadTimeout(20000);
                    if (c.getResponseCode() != 200) {
                        // servidor sem a lista de versao: NAO abre (avisa)
                        int cod = c.getResponseCode(); c.disconnect();
                        js("falhou(" + q("Servidor desatualizado: não achei a lista de versões do jogo (erro " + cod + "). Atualize o servidor (versao_app.cjs).") + ")");
                        return;
                    }
                    txt = lerTexto(c.getInputStream()); c.disconnect();
                } catch (Exception e) {
                    js("falhou(" + q("Sem conexão com o servidor. Tente de novo.") + ")"); return;
                }
                JSONObject arqs = new JSONObject(txt).getJSONObject("arquivos");
                final List<String> baixar = new ArrayList<>();
                final Map<String, String> novos = new HashMap<>();
                long bytesTotal = 0;
                Iterator<String> it = arqs.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONArray a = arqs.getJSONArray(k);
                    String assin = a.getString(1);
                    novos.put(k, assin);
                    if (!assin.equals(locais.get(k)) || !new File(pasta, k).isFile()) { baixar.add(k); bytesTotal += a.getLong(0); }
                }
                // apaga do celular o que saiu do jogo
                for (String k : new ArrayList<>(locais.keySet())) {
                    if (!novos.containsKey(k)) { locais.remove(k); new File(pasta, k).delete(); }
                }
                if (baixar.isEmpty()) { salvarManifestoLocal(); abrirJogo(); return; }

                final boolean primeira = locais.isEmpty();
                final long totalB = bytesTotal;
                final AtomicLong feitosB = new AtomicLong();
                final AtomicInteger feitos = new AtomicInteger();
                final AtomicInteger erros = new AtomicInteger();
                js("progresso(0," + baixar.size() + ",0," + totalB + "," + primeira + ")");
                ExecutorService pool = Executors.newFixedThreadPool(6);
                for (final String k : baixar) {
                    pool.execute(() -> {
                        boolean ok = false;
                        for (int t = 0; t < 3 && !ok; t++) ok = baixarArquivo(k, feitosB);
                        if (ok) locais.put(k, novos.get(k)); else erros.incrementAndGet();
                        int n = feitos.incrementAndGet();
                        if (n % 40 == 0) salvarManifestoLocal();
                        js("progresso(" + n + "," + baixar.size() + "," + feitosB.get() + "," + totalB + "," + primeira + ")");
                    });
                }
                pool.shutdown();
                pool.awaitTermination(6, TimeUnit.HOURS);
                salvarManifestoLocal();
                if (erros.get() > 0) {
                    js("falhou(" + q("Faltaram " + erros.get() + " arquivos. Toque em Iniciar jogo para continuar o download.") + ")");
                    return;
                }
                abrirJogo();
            } catch (Exception e) {
                js("falhou(" + q("Erro ao atualizar: " + e.getMessage()) + ")");
            } finally {
                atualizando.set(false);
            }
        }).start();
    }

    private boolean baixarArquivo(String rel, AtomicLong feitosB) {
        HttpURLConnection c = null;
        long contado = 0;
        try {
            StringBuilder url = new StringBuilder(home);
            String[] partes = rel.split("/");
            for (int i = 0; i < partes.length; i++) {
                if (i > 0) url.append('/');
                url.append(URLEncoder.encode(partes[i], "UTF-8").replace("+", "%20"));
            }
            c = (HttpURLConnection) new URL(url.toString()).openConnection();
            c.setConnectTimeout(10000); c.setReadTimeout(30000);
            c.setRequestProperty("Accept-Encoding", "identity");
            if (c.getResponseCode() != 200) return false;
            File destino = new File(pasta, rel);
            destino.getParentFile().mkdirs();
            File tmp = new File(destino.getPath() + ".baixando");
            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[32768]; int n;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); contado += n; feitosB.addAndGet(n); }
            out.close(); in.close();
            if (destino.exists()) destino.delete();
            return tmp.renameTo(destino);
        } catch (Exception e) {
            feitosB.addAndGet(-contado);
            return false;
        } finally { if (c != null) c.disconnect(); }
    }

    private static String lerTexto(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    private void abrirJogo() {
        runOnUiThread(() -> web.loadUrl(home));
    }

    /** chamado pela tela inicial (offline.html) */
    private class Ponte {
        @JavascriptInterface public void atualizar() { MainActivity.this.atualizar(); }
        @JavascriptInterface public String versaoApp() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
        }
        @JavascriptInterface public void limparJogo() {
            locais.clear(); salvarManifestoLocal(); apagar(pasta); pasta.mkdirs();
        }
    }

    private static void apagar(File f) {
        File[] l = f.listFiles();
        if (l != null) for (File x : l) apagar(x);
        f.delete();
    }

    // ---------- telas ----------

    private void tela(String modo) {
        String html = "<html><body style='background:#0d1b2c'></body></html>";
        try {
            html = lerTexto(getAssets().open("offline.html")).replace("__MODO__", modo).replace("__URL__", home);
        } catch (Exception e) { }
        web.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null);
    }

    private void offline() { runOnUiThread(() -> tela("inicio")); }

    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideBars(); }
    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }

    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this).setMessage("Sair do jogo?")
            .setPositiveButton("Sair", (d, w) -> finish())
            .setNegativeButton("Ficar", null).show();
    }
}
