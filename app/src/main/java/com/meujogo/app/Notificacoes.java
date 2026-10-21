package com.meujogo.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Notificacoes do jogo (Firebase Cloud Messaging).
 * O servidor manda so "dados" (titulo/texto/id): o app monta a notificacao aqui,
 * avisa o servidor que ela CHEGOU e, quando a pessoa toca, que foi CLICADA (painel admin mostra os numeros).
 */
public class Notificacoes extends FirebaseMessagingService {
    static final String PREF = "notif";

    static void criarCanal(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("avisos", "Avisos do jogo", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Arena, ofertas, missões e novidades do Smash World GO");
            ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    static void guardarToken(Context c, String t) {
        if (t != null && t.length() > 20) c.getSharedPreferences(PREF, MODE_PRIVATE).edit().putString("token", t).apply();
    }

    static String token(Context c) { return c.getSharedPreferences(PREF, MODE_PRIVATE).getString("token", ""); }

    /** avisa o servidor: ev = "recebido" ou "clicado" (roda numa thread separada) */
    static void avisar(Context c, String id, String ev) {
        final String tk = token(c);
        if (id == null || !id.matches("\\d{1,12}") || tk.isEmpty()) return;
        String base = c.getString(R.string.game_url);
        if (!base.endsWith("/")) base += "/";
        final String url = base + "api/notif/ev";
        final String corpo = "{\"token\":\"" + tk.replace("\"", "") + "\",\"id\":" + id + ",\"ev\":\"" + ev + "\"}";
        new Thread(() -> {
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
                h.setConnectTimeout(8000); h.setReadTimeout(8000); h.setDoOutput(true); h.setRequestMethod("POST");
                h.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = h.getOutputStream()) { o.write(corpo.getBytes(StandardCharsets.UTF_8)); }
                h.getResponseCode(); h.disconnect();
            } catch (Exception e) { }
        }).start();
    }

    @Override
    public void onMessageReceived(RemoteMessage m) {
        RemoteMessage.Notification n = m.getNotification();
        String titulo = m.getData().get("titulo"), texto = m.getData().get("texto"), id = m.getData().get("id");
        if (titulo == null && n != null) titulo = n.getTitle();
        if (texto == null && n != null) texto = n.getBody();
        if (titulo == null && texto == null) return;
        criarCanal(this);
        int nid = (int) (System.currentTimeMillis() % 1000000);
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (id != null) i.putExtra("notif_id", id);
        PendingIntent pi = PendingIntent.getActivity(this, nid, i, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new android.app.Notification.Builder(this, "avisos") : new android.app.Notification.Builder(this);
        String tx = texto == null ? "" : texto;
        b.setSmallIcon(R.drawable.ic_notificacao).setContentTitle(titulo == null ? "Smash World GO" : titulo).setContentText(tx)
                .setStyle(new android.app.Notification.BigTextStyle().bigText(tx)) // texto inteiro, sem cortar
                .setAutoCancel(true).setContentIntent(pi);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(android.app.Notification.PRIORITY_HIGH);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(nid, b.build());
        avisar(this, id, "recebido");
        // Exibe o texto primeiro: falhas na imagem nunca impedem a notificacao.
        Bitmap icone = baixarImagem(m.getData().get("icone"), 256);
        Bitmap imagem = baixarImagem(m.getData().get("imagem"), 1200);
        if (icone != null) b.setLargeIcon(icone);
        if (imagem != null) b.setStyle(new android.app.Notification.BigPictureStyle()
                .bigPicture(imagem).setSummaryText(tx));
        if (icone != null || imagem != null) {
            b.setOnlyAlertOnce(true);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(nid, b.build());
        }
    }

    private static Bitmap baixarImagem(String endereco, int limite) {
        if (endereco == null || endereco.isEmpty()) return null;
        HttpURLConnection h = null;
        try {
            URL u = new URL(endereco);
            if (!"https".equalsIgnoreCase(u.getProtocol()) || u.getUserInfo() != null) return null;
            h = (HttpURLConnection) u.openConnection();
            h.setInstanceFollowRedirects(false);
            h.setConnectTimeout(2500); h.setReadTimeout(2500);
            if (h.getResponseCode() != 200 || h.getContentLength() > 2 * 1024 * 1024) return null;
            String tipo = h.getContentType();
            if (tipo == null || !tipo.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) return null;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            long inicio = android.os.SystemClock.elapsedRealtime();
            try (InputStream in = h.getInputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) {
                    if (bytes.size() + n > 2 * 1024 * 1024 || android.os.SystemClock.elapsedRealtime() - inicio > 3000) return null;
                    bytes.write(buf, 0, n);
                }
            }
            byte[] dados = bytes.toByteArray();
            BitmapFactory.Options op = new BitmapFactory.Options(); op.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(dados, 0, dados.length, op);
            if (op.outWidth <= 0 || op.outHeight <= 0) return null;
            op.inSampleSize = 1;
            while (Math.max(op.outWidth, op.outHeight) / op.inSampleSize > limite) op.inSampleSize *= 2;
            op.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(dados, 0, dados.length, op);
        } catch (Exception e) { return null; }
        finally { if (h != null) h.disconnect(); }
    }

    @Override
    public void onNewToken(String t) { guardarToken(this, t); }
}
