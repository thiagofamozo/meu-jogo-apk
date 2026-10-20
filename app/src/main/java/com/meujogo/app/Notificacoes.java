package com.meujogo.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/** Avisos enviados pelo site do Firebase (Cloud Messaging) para todos os jogadores. */
public class Notificacoes extends FirebaseMessagingService {
    static void criarCanal(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("avisos", "Avisos do jogo", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Eventos, ofertas e novidades do Smash World GO");
            ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    @Override
    public void onMessageReceived(RemoteMessage m) {
        // com o app aberto o Android nao mostra sozinho: mostramos aqui
        RemoteMessage.Notification n = m.getNotification();
        String titulo = n != null && n.getTitle() != null ? n.getTitle() : m.getData().get("titulo");
        String texto = n != null && n.getBody() != null ? n.getBody() : m.getData().get("texto");
        if (titulo == null && texto == null) return;
        criarCanal(this);
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new android.app.Notification.Builder(this, "avisos") : new android.app.Notification.Builder(this);
        b.setSmallIcon(R.mipmap.ic_launcher).setContentTitle(titulo == null ? "Smash World GO" : titulo).setContentText(texto == null ? "" : texto).setAutoCancel(true).setContentIntent(pi);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify((int) (System.currentTimeMillis() % 100000), b.build());
    }

    @Override
    public void onNewToken(String t) { }
}
