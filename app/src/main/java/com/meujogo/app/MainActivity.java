package com.meujogo.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
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

import com.unity3d.ads.IUnityAdsInitializationListener;
import com.unity3d.ads.IUnityAdsLoadListener;
import com.unity3d.ads.IUnityAdsShowListener;
import com.unity3d.ads.UnityAds;
import com.unity3d.ads.UnityAdsShowOptions;

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
    private String home;          // ex.: http://smashworldgo.duckdns.org:8080/ (nome do DuckDNS: trocar de VPS = so mudar o IP no duckdns.org)
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
        lerManifestoApk();

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
        baixarFundo();
        baixarVersao();
        configurarAnuncios();
        try { Notificacoes.criarCanal(this); } catch (Exception e) { }
        if (Build.VERSION.SDK_INT >= 33) { try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7); } catch (Exception e) { } }
        // notificacoes: pega o codigo deste celular (o jogo manda para o servidor junto com o jogador)
        try {
            if (!com.google.firebase.FirebaseApp.getApps(this).isEmpty()) {
                com.google.firebase.messaging.FirebaseMessaging fm = com.google.firebase.messaging.FirebaseMessaging.getInstance();
                fm.subscribeToTopic("todos");
                fm.getToken().addOnCompleteListener(t -> { try { if (t.isSuccessful()) Notificacoes.guardarToken(this, t.getResult()); } catch (Throwable e) { } });
            }
        } catch (Throwable e) { }
        notifClicada(getIntent());
        web.postDelayed(relogioAd, 30000);
    }

    @Override
    protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); notifClicada(i); }

    // a pessoa abriu o jogo tocando numa notificacao: conta como "clicada" no painel
    private void notifClicada(Intent i) {
        try { String id = i == null ? null : i.getStringExtra("notif_id"); if (id != null) { i.removeExtra("notif_id"); Notificacoes.avisar(this, id, "clicado"); } } catch (Throwable e) { }
    }

    private void baixarVersao() {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(home + "versao_jogo.txt?t=" + System.currentTimeMillis()).openConnection();
                c.setConnectTimeout(6000); c.setReadTimeout(8000);
                if (c.getResponseCode() == 200) {
                    String v = lerTexto(c.getInputStream()).trim().replaceAll("^[vV]", "");
                    if (!v.isEmpty() && v.length() <= 20) {
                        getSharedPreferences("app", MODE_PRIVATE).edit().putString("versao_jogo", v).apply();
                        js("try{document.getElementById('ver').textContent='v'+" + q(v) + "}catch(e){}");
                    }
                }
                c.disconnect();
            } catch (Exception e) { }
        }).start();
    }

    // imagem da tela inicial trocada pelo servidor: game\app_fundo.jpg
    private void baixarFundo() {
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                File f = new File(getFilesDir(), "app_fundo.jpg");
                c = (HttpURLConnection) new URL(home + "app_fundo.jpg").openConnection();
                c.setConnectTimeout(6000); c.setReadTimeout(20000);
                if (f.isFile()) c.setIfModifiedSince(f.lastModified());
                int cod = c.getResponseCode();
                if (cod == 404) { if (f.delete()) js("fundo('bg.jpg')"); return; }  // tirou a imagem do servidor: volta a padrao
                if (cod != 200) return;
                File tmp = new File(getFilesDir(), "app_fundo.tmp");
                InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp);
                byte[] buf = new byte[32768]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close(); in.close();
                long lm = c.getLastModified();
                f.delete(); tmp.renameTo(f);
                if (lm > 0) f.setLastModified(lm);
                js("fundo(" + q("file://" + f.getAbsolutePath() + "?v=" + f.lastModified()) + ")");
            } catch (Exception e) { } finally { if (c != null) c.disconnect(); }
        }).start();
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

    // jogo que ja vem dentro do APK (assets/jogo): arquivo -> assinatura
    private final Map<String, String> doApk = new HashMap<>();
    private void lerManifestoApk() {
        try {
            JSONObject o = new JSONObject(lerTexto(getAssets().open("jogo_manifesto.json"))).getJSONObject("arquivos");
            Iterator<String> it = o.keys();
            while (it.hasNext()) { String k = it.next(); doApk.put(k, o.getJSONArray(k).getString(1)); }
        } catch (Exception e) { }
    }
    /** copia do proprio APK quando o arquivo do servidor e o mesmo (nao gasta internet) */
    private boolean copiarDoApk(String rel, String assin, AtomicLong feitosB) {
        if (assin == null || !assin.equals(doApk.get(rel))) return false;
        try {
            File destino = new File(pasta, rel);
            destino.getParentFile().mkdirs();
            File tmp = new File(destino.getPath() + ".copiando");
            InputStream in = getAssets().open("jogo/" + rel);
            OutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); feitosB.addAndGet(n); }
            out.close(); in.close();
            if (destino.exists()) destino.delete();
            return tmp.renameTo(destino);
        } catch (Exception e) { return false; }
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
                    js("falhou(" + q("Não foi possível conectar ao servidor. Tente novamente mais tarde.") + ")"); return;
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
                if (baixar.isEmpty()) { salvarManifestoLocal(); js("pronto()"); return; }

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
                        ok = copiarDoApk(k, novos.get(k), feitosB);
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
                js("pronto()");
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
    // ---- Pagamento (cartao Asaas/Stripe) numa tela DENTRO do app, por cima do jogo ----
    private Dialog pagDlg = null;
    private void abrirPagamento(String url) {
        if (url == null || !(url.startsWith("https://"))) return;
        fecharPagamento();
        final float dp = getResources().getDisplayMetrics().density;
        String host = ""; try { host = Uri.parse(url).getHost(); if (host != null && host.startsWith("www.")) host = host.substring(4); } catch (Exception e) { }
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setBackgroundColor(Color.WHITE);
        // barra de cima (verde, igual a do jogo)
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable gv = new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Color.rgb(55, 139, 121), Color.rgb(25, 92, 82)});
        bar.setBackground(gv); bar.setPadding((int) (10 * dp), (int) (8 * dp), (int) (10 * dp), (int) (8 * dp));
        TextView volta = new TextView(this); volta.setText("\u2190 Voltar ao jogo"); volta.setTextColor(Color.rgb(73, 61, 36)); volta.setTextSize(14); volta.setTypeface(null, android.graphics.Typeface.BOLD);
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable(); pill.setColor(Color.rgb(246, 237, 206)); pill.setCornerRadius(20 * dp); pill.setStroke((int) (2 * dp), Color.rgb(215, 188, 112));
        volta.setBackground(pill); volta.setPadding((int) (14 * dp), (int) (7 * dp), (int) (14 * dp), (int) (7 * dp));
        volta.setOnClickListener(v -> fecharPagamento());
        TextView tt = new TextView(this); tt.setText("\uD83D\uDD12 Pagamento seguro" + (host != null && host.length() > 0 ? " \u2022 " + host : "")); tt.setTextColor(Color.rgb(255, 248, 223)); tt.setTextSize(14); tt.setTypeface(null, android.graphics.Typeface.BOLD); tt.setGravity(Gravity.CENTER);
        TextView fora = new TextView(this); fora.setText("\u2197"); fora.setTextColor(Color.rgb(255, 248, 223)); fora.setTextSize(20); fora.setPadding((int) (10 * dp), 0, (int) (4 * dp), 0);
        fora.setOnClickListener(v -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { } });
        bar.addView(volta); bar.addView(tt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); bar.addView(fora);
        View linha = new View(this); linha.setBackgroundColor(Color.rgb(170, 144, 91));
        // pagina do pagamento
        WebView w = new WebView(this);
        WebSettings ws = w.getSettings(); ws.setJavaScriptEnabled(true); ws.setDomStorageEnabled(true);
        w.setWebChromeClient(new WebChromeClient());
        w.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                String s2 = req.getUrl().toString();
                if (s2.startsWith("http://") || s2.startsWith("https://")) return false; // fica na tela de pagamento
                try { startActivity(new Intent(Intent.ACTION_VIEW, req.getUrl())); } catch (Exception e) { } // app do banco etc.
                return true;
            }
        });
        // barra de baixo (bege)
        TextView rod = new TextView(this); rod.setText("Depois de pagar, esta tela fecha sozinha e o pacote chega no seu Correio. Se a p\u00e1gina n\u00e3o carregar, toque em \u2197.");
        rod.setTextColor(Color.rgb(107, 88, 58)); rod.setTextSize(12); rod.setGravity(Gravity.CENTER); rod.setBackgroundColor(Color.rgb(239, 229, 207)); rod.setPadding((int) (10 * dp), (int) (7 * dp), (int) (10 * dp), (int) (7 * dp));
        box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(linha, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (3 * dp)));
        box.addView(w, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        box.addView(rod, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        d.setContentView(box);
        d.setOnCancelListener(x -> fecharPagamento());
        w.loadUrl(url);
        pagDlg = d; d.show();
    }
    private void fecharPagamento() {
        if (pagDlg != null) { try { pagDlg.dismiss(); } catch (Exception e) { } pagDlg = null; }
    }

    private class Ponte {
        @JavascriptInterface public void abrirPagamento(String url) { runOnUiThread(() -> MainActivity.this.abrirPagamento(url)); }
        @JavascriptInterface public void fecharPagamento() { runOnUiThread(() -> MainActivity.this.fecharPagamento()); }
        @JavascriptInterface public void atualizar() { MainActivity.this.atualizar(); }
        @JavascriptInterface public String fcmToken() { return Notificacoes.token(MainActivity.this); }
        @JavascriptInterface public void entrar() { entrarNoJogo(); }
        @JavascriptInterface public boolean anuncioPronto() { if (!adCarregado) runOnUiThread(() -> carregarAnuncio()); return adLigado && adIniciado && adCarregado; }
        @JavascriptInterface public String anuncioMotivo() { return adMotivo; }
        @JavascriptInterface public void mostrarAnuncio(String id) { MainActivity.this.mostrarAnuncio(id); }
        @JavascriptInterface public boolean anuncioTeste() { return adTeste; }
        // versao mostrada na tela: vem do servidor (game\\versao_jogo.txt); padrao 1.2.7
        @JavascriptInterface public String versaoApp() {
            return getSharedPreferences("app", MODE_PRIVATE).getString("versao_jogo", "1.2.7");
        }
        @JavascriptInterface public boolean temInternet() {
            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
                return ni != null && ni.isConnected();
            } catch (Exception e) { return true; }
        }
        @JavascriptInterface public String fundoLocal() {
            File f = new File(getFilesDir(), "app_fundo.jpg");
            return f.isFile() ? "file://" + f.getAbsolutePath() + "?v=" + f.lastModified() : "";
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


    // ---------- Anuncios Unity (configurados no painel: game\anuncios.json) ----------
    private volatile boolean adLigado = false, adIniciado = false, adCarregado = false, adCarregando = false;
    private volatile String adGameId = "", adPlacement = "Rewarded_Android", adMotivo = "Carregando anúncio...";
    private volatile boolean adTeste = true;
    // tela cheia automatico (intersticial)
    private volatile String isPlacement = "Interstitial_Android";
    private volatile int isAbrirCada = 0, isMinutos = 0;
    private volatile boolean isCarregado = false, isCarregando = false, mostrandoAd = false, noJogo = false;
    private volatile long ultimoAd = System.currentTimeMillis();

    private void configurarAnuncios() {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(home + "anuncios.json?t=" + System.currentTimeMillis()).openConnection();
                c.setConnectTimeout(8000); c.setReadTimeout(10000);
                if (c.getResponseCode() != 200) { adMotivo = "Anúncios não configurados no painel."; c.disconnect(); return; }
                JSONObject o = new JSONObject(lerTexto(c.getInputStream())); c.disconnect();
                adLigado = o.optBoolean("ligado", false);
                adTeste = o.optBoolean("teste", true);
                adGameId = o.optString("gameId", "").trim();
                String pl = o.optString("recompensa", "").trim();
                if (!pl.isEmpty()) adPlacement = pl;
                String ip = o.optString("intersticial", "").trim();
                if (!ip.isEmpty()) isPlacement = ip;
                isAbrirCada = Math.max(0, o.optInt("abrirCada", 0));
                isMinutos = Math.max(0, o.optInt("minutosJogo", 0));
                if (!adLigado) { adMotivo = "Anúncios desligados no momento."; return; }
                if (adGameId.isEmpty()) { adMotivo = "Anúncios sem Game ID (painel)."; return; }
                adMotivo = "Iniciando anúncios... [gameId=" + adGameId + "]";
                runOnUiThread(() -> UnityAds.initialize(getApplicationContext(), adGameId, adTeste, new IUnityAdsInitializationListener() {
                    @Override public void onInitializationComplete() { adIniciado = true; carregarAnuncio(); carregarIntersticial(); }
                    @Override public void onInitializationFailed(UnityAds.UnityAdsInitializationError e, String m) { adMotivo = "Erro ao iniciar anúncios [Unity: " + e + " - " + m + " | gameId=" + adGameId + "]"; }
                }));
            } catch (Exception e) { adMotivo = "Sem conexão para carregar anúncios [" + e + "]"; }
        }).start();
    }

    private void carregarAnuncio() {
        if (!adIniciado || adCarregando || adCarregado) return;
        adCarregando = true; adMotivo = "Carregando anúncio, tente em alguns segundos. [gameId=" + adGameId + " placement=" + adPlacement + "]";
        UnityAds.load(adPlacement, new IUnityAdsLoadListener() {
            @Override public void onUnityAdsAdLoaded(String p) { adCarregando = false; adCarregado = true; adMotivo = ""; }
            @Override public void onUnityAdsFailedToLoad(String p, UnityAds.UnityAdsLoadError e, String m) {
                adCarregando = false; adCarregado = false; adMotivo = "Nenhum anúncio disponível agora. Tente mais tarde. [Unity: " + e + " - " + m + " | placement=" + adPlacement + " gameId=" + adGameId + (adTeste ? " TESTE" : " REAL") + "]";
                web.postDelayed(() -> carregarAnuncio(), 30000);
            }
        });
    }

    private void carregarIntersticial() {
        if (!adIniciado || isCarregando || isCarregado || (isAbrirCada == 0 && isMinutos == 0)) return;
        isCarregando = true;
        UnityAds.load(isPlacement, new IUnityAdsLoadListener() {
            @Override public void onUnityAdsAdLoaded(String p) { isCarregando = false; isCarregado = true; }
            @Override public void onUnityAdsFailedToLoad(String p, UnityAds.UnityAdsLoadError e, String m) {
                isCarregando = false; isCarregado = false; web.postDelayed(() -> carregarIntersticial(), 60000);
            }
        });
    }

    /** mostra tela cheia se carregado; depois chama "depois" (pode ser null) */
    private void mostrarIntersticial(final Runnable depois) {
        runOnUiThread(() -> {
            if (!adLigado || !adIniciado || !isCarregado || mostrandoAd) { if (depois != null) depois.run(); carregarIntersticial(); return; }
            isCarregado = false; mostrandoAd = true;
            UnityAds.show(MainActivity.this, isPlacement, new UnityAdsShowOptions(), new IUnityAdsShowListener() {
                void fim() { mostrandoAd = false; ultimoAd = System.currentTimeMillis(); hideBars(); carregarIntersticial(); if (depois != null) depois.run(); }
                @Override public void onUnityAdsShowFailure(String p, UnityAds.UnityAdsShowError e, String m) { fim(); }
                @Override public void onUnityAdsShowStart(String p) { }
                @Override public void onUnityAdsShowClick(String p) { }
                @Override public void onUnityAdsShowComplete(String p, UnityAds.UnityAdsShowCompletionState st) { fim(); }
            });
        });
    }

    // a cada X minutos jogando (configurado no painel)
    private final Runnable relogioAd = new Runnable() {
        @Override public void run() {
            try {
                if (noJogo && isMinutos > 0 && !mostrandoAd && System.currentTimeMillis() - ultimoAd >= isMinutos * 60000L) mostrarIntersticial(null);
            } catch (Exception e) { }
            web.postDelayed(this, 30000);
        }
    };

    private void entrarNoJogo() {
        int n = getSharedPreferences("app", MODE_PRIVATE).getInt("aberturas", 0) + 1;
        getSharedPreferences("app", MODE_PRIVATE).edit().putInt("aberturas", n).apply();
        Runnable abrir = () -> { noJogo = true; ultimoAd = System.currentTimeMillis(); abrirJogo(); };
        if (isAbrirCada > 0 && n % isAbrirCada == 0) mostrarIntersticial(abrir); else runOnUiThread(abrir);
    }

    private void mostrarAnuncio(final String id) {
        runOnUiThread(() -> {
            if (!adLigado || !adIniciado || !adCarregado) { fimAnuncio(id, false, adMotivo); carregarAnuncio(); return; }
            adCarregado = false; mostrandoAd = true;
            UnityAds.show(MainActivity.this, adPlacement, new UnityAdsShowOptions(), new IUnityAdsShowListener() {
                @Override public void onUnityAdsShowFailure(String p, UnityAds.UnityAdsShowError e, String m) { mostrandoAd = false; fimAnuncio(id, false, "Falha ao mostrar o anúncio. Tente de novo."); carregarAnuncio(); }
                @Override public void onUnityAdsShowStart(String p) { }
                @Override public void onUnityAdsShowClick(String p) { }
                @Override public void onUnityAdsShowComplete(String p, UnityAds.UnityAdsShowCompletionState st) {
                    mostrandoAd = false; ultimoAd = System.currentTimeMillis();
                    boolean ok = st == UnityAds.UnityAdsShowCompletionState.COMPLETED;
                    fimAnuncio(id, ok, ok ? "" : "Você pulou o anúncio. Assista até o fim para ganhar.");
                    hideBars(); carregarAnuncio();
                }
            });
        });
    }

    private void fimAnuncio(String id, boolean ok, String msg) {
        js("window._smashAdFim&&window._smashAdFim(" + q(id) + "," + ok + "," + q(msg) + ")");
    }

    // ---------- telas ----------

    private void tela(String modo) {
        String html = "<html><body style='background:#0d1b2c'></body></html>";
        try {
            html = lerTexto(getAssets().open("offline.html")).replace("__MODO__", modo).replace("__URL__", home);
        } catch (Exception e) { }
        web.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null);
    }

    private void offline() { noJogo = false; runOnUiThread(() -> tela("inicio")); }

    private void hideBars() {
        //PT: mostra a barra de cima (hora, bateria, sinal) e esconde so os botoes de baixo; o jogo fica logo abaixo da barra
        try {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
            if (android.os.Build.VERSION.SDK_INT >= 21) getWindow().setStatusBarColor(0xFF0D1B2C);
        } catch (Exception e) { }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
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
