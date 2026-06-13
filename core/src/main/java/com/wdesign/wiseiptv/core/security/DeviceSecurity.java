package com.wdesign.wiseiptv.core.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * DeviceSecurity — Génère une device_key unique (Code court à 6 caractères) + interroge le panel d'activation.
 */
public final class DeviceSecurity {

    private static final String TAG        = "DeviceSecurity";
    // AJOUTEZ explicitement '?action=check-device' pour correspondre à 100% avec l'en-tête de l'api
	private static final String PANEL_URL = "https://wise.alwaysdata.net/iptv/api/index.php?action=check-device";	
    private static final String PREFS_NAME = "wise_device";
    private static final String KEY_DEVICE = "device_key";
    private static final int    TIMEOUT_MS = 15_000;

    private static final Executor EXEC = Executors.newSingleThreadExecutor();

    // ── Résultat d'activation ─────────────────────────────────────
    public static class ActivationResult {
        public final String deviceKey;
        public final String login;
        public final String password;
        public final String expiresAt;
        public final List<DnsEntry> dnsServers;

        public ActivationResult(String dk, String l, String p, String exp, List<DnsEntry> dns) {
            deviceKey = dk; login = l; password = p; expiresAt = exp; dnsServers = dns;
        }
        /** DNS principal (priorité 0) */
        public String primaryDns() {
            return dnsServers.isEmpty() ? "" : dnsServers.get(0).url;
        }
        /** URL M3U Xtream construite à partir du premier DNS */
        public String buildM3uUrl() {
            String dns = primaryDns();
            if (dns.isEmpty()) return "";
            String base = dns.endsWith("/") ? dns : dns + "/";
            return base + "get.php?username=" + login + "&password=" + password + "&type=m3u_plus&output=ts";
        }
    }

    public static class DnsEntry {
        public final String url;
        public final String epgUrl;
        public final int    priority;
        public DnsEntry(String u, String e, int p) { url=u; epgUrl=e; priority=p; }
    }

    // ── Callback ──────────────────────────────────────────────────
    public interface Callback {
        void onActive(ActivationResult result);
        void onInactive(String status, String message);
        void onError(String message);
    }

    // ── Point d'entrée principal ───────────────────────────────────
    public static void check(Context ctx, Callback cb) {
        final String key = getOrCreateKey(ctx);
        EXEC.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("device_key", key);
                String response = postJson(PANEL_URL, payload.toString());
                JSONObject json = new JSONObject(response);
                String status = json.optString("status", "ERROR");

                if ("ACTIVE".equals(status)) {
                    List<DnsEntry> dns = new ArrayList<>();
                    if (json.has("dns_servers")) {
                        JSONArray arr = json.getJSONArray("dns_servers");
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject e = arr.getJSONObject(i);
                            dns.add(new DnsEntry(
                                e.optString("url",""),
                                e.optString("epg_url",""),
                                e.optInt("priority", i)
                            ));
                    }}
                    ActivationResult r = new ActivationResult(
                        key,
                        json.optString("login",""),
                        json.optString("password",""),
                        json.optString("expires_at",""),
                        dns
                    );
                    cb.onActive(r);
                } else {
                    cb.onInactive(status, json.optString("message", status));
                }
            } catch (IOException e) {
                Log.w(TAG, "Network error: " + e.getMessage());
                cb.onError("Connexion impossible. Vérifiez votre réseau.");
            } catch (Exception e) {
                Log.e(TAG, "Error: " + e.getMessage(), e);
                cb.onError("Erreur inattendue: " + e.getMessage());
            }
        });
    }

    // ── Récupérer ou générer la device_key ────────────────────────
    public static String getOrCreateKey(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String stored = prefs.getString(KEY_DEVICE, null);
        
        // CORRECTION : Accepte maintenant uniquement le format court à 6 caractères
        if (stored != null && stored.length() == 6) return stored;
        
        String key = generateKey(ctx);
        prefs.edit().putString(KEY_DEVICE, key).apply();
        return key;
    }

    /**
     * CORRECTION : Génère un identifiant unique court à 6 caractères (ex: A6J0B8)
     * Déterministe et stable par rapport au matériel (téléphone ou Box TV).
     */
    private static String generateKey(Context ctx) {
        String androidId = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        String fingerprint = Build.FINGERPRINT;
        @SuppressWarnings("deprecation")
        String serial = Build.SERIAL != null && !Build.SERIAL.equals(Build.UNKNOWN) ? Build.SERIAL : "";
        String raw = "WISE:" + androidId + "|" + fingerprint + "|" + serial;
        
        try {
            // Hachage MD5 suffisant et performant pour condenser la clé matérielle
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            
            // Conversion déterministe en Base 36 alphanumérique
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hash.length && sb.length() < 6; i++) {
                int value = Math.abs(hash[i] & 0xFF);
                sb.append(Integer.toString(value % 36, 36));
            }
            
            // Formatage final en majuscules
            String code = sb.toString().toUpperCase();
            
            // Remplacement des caractères ambigus pour éviter les erreurs de lecture
            code = code.replace("O", "0").replace("I", "1");
            
            // Ajustement dynamique si la chaîne générée fait moins de 6 caractères
            while (code.length() < 6) {
                code += "X";
            }
            
            return code.substring(0, 6); // Retourne exactement 6 caractères (ex: A6J0B8)
            
        } catch (NoSuchAlgorithmException e) {
            // Sécurité de repli déterministe basée sur le HashCode
            long codeLong = Math.abs((long) raw.hashCode());
            String fallback = Long.toString(codeLong, 36).toUpperCase();
            fallback = fallback.replace("O", "0").replace("I", "1") + "XXXXXX";
            return fallback.substring(0, 6);
        }
    }

    // ── HTTP POST JSON minimaliste (pas de dépendance OkHttp) ──────
    private static String postJson(String url, String json) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("User-Agent", "WiseIPTV-APK/2.0");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) throw new IOException("Réponse vide (HTTP " + code + ")");
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }
}