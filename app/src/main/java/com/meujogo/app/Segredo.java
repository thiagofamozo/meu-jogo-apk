package com.meujogo.app;

import android.util.Base64;

/**
 * Endereco do servidor guardado embaralhado (nao aparece como texto no APK).
 * strings.xml -> game_url = texto embaralhado (gerado pelo script embaralhar_url.js).
 * Se game_url comecar com http, usa direto (compatibilidade com o jeito antigo).
 */
final class Segredo {
    private static final byte[] K = { 0x53, 0x6d, 0x57, 0x21, 0x67, 0x30, 0x23, 0x6b, 0x39, 0x14, 0x7e, 0x2c };

    static String endereco(String guardado) {
        if (guardado == null) return "";
        String g = guardado.trim();
        if (g.startsWith("http://") || g.startsWith("https://")) return g;
        try {
            byte[] b = Base64.decode(g, Base64.NO_WRAP);
            for (int i = 0; i < b.length; i++) b[i] = (byte) (b[i] ^ K[i % K.length] ^ (i * 7));
            return new String(b, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private Segredo() { }
}
