package com.wdesign.wiseiptv.mobile.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.wdesign.wiseiptv.core.security.DeviceSecurity;
import com.wdesign.wiseiptv.mobile.R;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * ActivationActivity — premier écran de l'APK.
 *
 * Flux :
 *  1. Cache ACTIVE + non expiré → goToMain(null) immédiatement (transparent)
 *  2. Sinon → afficher la page + vérification réseau silencieuse
 *  3. Bouton "Vérifier" → check réseau + affichage état
 *  4. Bouton "Accéder" → goToMain(r) ou goToMainFromCache()
 *     MainActivity reçoit les DNS via extras et télécharge en background.
 *
 * Fix crash : isCheckInProgress empêche deux DeviceSecurity.check() simultanés.
 * Guard isFinishing()/isDestroyed() dans tous les callbacks.
 */
public class ActivationActivity extends AppCompatActivity {

    private static final String PREFS = "wise_activation";

    private TextView    tvDeviceKey, tvStatus, tvStatusDetail;
    private TextView    tvProviderLabel, tvLogin, tvPassword, tvExpiry;
    private View        cardProvider;
    private Button      btnCheck, btnAccess, btnCopy;
    private ProgressBar progressBar;

    private boolean isCheckInProgress = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Cache valide → skip l'écran d'activation complètement
        if (isActiveAndNotExpired()) {
            goToMainFromCache();
            return;
        }

        setContentView(R.layout.activity_activation);

        tvDeviceKey    = findViewById(R.id.tv_device_key);
        tvStatus       = findViewById(R.id.tv_status);
        tvStatusDetail = findViewById(R.id.tv_status_detail);
        tvProviderLabel= findViewById(R.id.tv_provider_label);
        tvLogin        = findViewById(R.id.tv_login);
        tvPassword     = findViewById(R.id.tv_password);
        tvExpiry       = findViewById(R.id.tv_expiry);
        cardProvider   = findViewById(R.id.card_provider);
        btnCheck       = findViewById(R.id.btn_check);
        btnAccess      = findViewById(R.id.btn_access);
        btnCopy        = findViewById(R.id.btn_copy_key);
        progressBar    = findViewById(R.id.progress_bar);

        String key = DeviceSecurity.getOrCreateKey(this);
        tvDeviceKey.setText(key);

        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("device_key", key));
            Toast.makeText(this, "Clé copiée !", Toast.LENGTH_SHORT).show();
        });

        // Vérification manuelle → affiche l'état seulement
        btnCheck.setOnClickListener(v -> checkActivation(false));

        // Accéder au contenu → ouvre MainActivity qui télécharge en background
        btnAccess.setOnClickListener(v -> {
            if (isCheckInProgress) return;
            String saved = getPrefs().getString("status", "");
            if ("ACTIVE".equals(saved)) {
                goToMainFromCache();
            } else {
                checkActivation(true);
            }
        });

        // Afficher état expiré/désactivé depuis le cache
        String savedStatus = getPrefs().getString("status", "");
        if ("EXPIRED".equals(savedStatus) || "DISABLED".equals(savedStatus)) {
            showInactive(savedStatus);
        }

        // Vérification silencieuse au démarrage
        checkActivation(false);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void checkActivation(boolean goOnSuccess) {
        if (isCheckInProgress) return;
        isCheckInProgress = true;
        setLoading(true);

        DeviceSecurity.check(this, new DeviceSecurity.Callback() {
            @Override
            public void onActive(DeviceSecurity.ActivationResult r) {
                saveCache(r);
                runOnUiThread(() -> {
                    isCheckInProgress = false;
                    if (isFinishing() || isDestroyed()) return;
                    setLoading(false);
                    if (goOnSuccess) goToMain(r);
                    else showActive(r);
                });
            }

            @Override
            public void onInactive(String status, String message) {
                clearCache(status);
                runOnUiThread(() -> {
                    isCheckInProgress = false;
                    if (isFinishing() || isDestroyed()) return;
                    setLoading(false);
                    showInactive(status);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    isCheckInProgress = false;
                    if (isFinishing() || isDestroyed()) return;
                    setLoading(false);
                    if (isActiveAndNotExpired()) showOffline();
                    else showPending(message);
                });
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Navigation → MainActivity avec extras DNS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Résultat frais du serveur → passer les DNS en extras.
     * MainActivity les reçoit et lance downloadDnsSequentially() en background.
     */
    private void goToMain(DeviceSecurity.ActivationResult r) {
        if (isFinishing() || isDestroyed()) return;
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_ACT_LOGIN,    r.login);
        intent.putExtra(MainActivity.EXTRA_ACT_PASSWORD, r.password != null ? r.password : "");
        intent.putExtra(MainActivity.EXTRA_ACT_EXPIRES,  r.expiresAt != null ? r.expiresAt : "");
        if (r.dnsServers != null && !r.dnsServers.isEmpty()) {
            String[] urls = new String[r.dnsServers.size()];
            String[] epgs = new String[r.dnsServers.size()];
            for (int i = 0; i < r.dnsServers.size(); i++) {
                urls[i] = r.dnsServers.get(i).url;
                epgs[i] = r.dnsServers.get(i).epgUrl != null ? r.dnsServers.get(i).epgUrl : "";
            }
            intent.putExtra(MainActivity.EXTRA_ACT_DNS_URLS,     urls);
            intent.putExtra(MainActivity.EXTRA_ACT_DNS_EPG_URLS, epgs);
        }
        startActivity(intent);
        finish();
    }

    /**
     * Depuis le cache SharedPreferences (btnAccess avec état ACTIVE en cache,
     * ou cache valide au démarrage).
     */
    private void goToMainFromCache() {
        if (isFinishing() || isDestroyed()) return;
        SharedPreferences p = getPrefs();
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_ACT_LOGIN,    p.getString("login",      ""));
        intent.putExtra(MainActivity.EXTRA_ACT_PASSWORD, p.getString("password",   ""));
        intent.putExtra(MainActivity.EXTRA_ACT_EXPIRES,  p.getString("expires_at", ""));
        String dnsRaw = p.getString("dns_urls", "");
        String epgRaw = p.getString("dns_epg_urls", "");
        if (!dnsRaw.isEmpty()) {
            intent.putExtra(MainActivity.EXTRA_ACT_DNS_URLS,     dnsRaw.split(","));
            intent.putExtra(MainActivity.EXTRA_ACT_DNS_EPG_URLS, epgRaw.split(","));
        }
        startActivity(intent);
        finish();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // États UI
    // ─────────────────────────────────────────────────────────────────────────

    private void showActive(DeviceSecurity.ActivationResult r) {
        tvStatus.setText("✅ ACTIVÉ");
        tvStatus.setTextColor(0xFF4CAF50);
        tvStatusDetail.setText("Votre abonnement est actif");
        cardProvider.setVisibility(View.VISIBLE);
        tvLogin.setText("Login : " + r.login);
        tvPassword.setText("Mot de passe : " + r.password);
        tvExpiry.setText("Expire le : " + r.expiresAt);
        if (r.dnsServers != null && !r.dnsServers.isEmpty())
            tvProviderLabel.setText("Provider : " + r.dnsServers.get(0).url);
        btnAccess.setVisibility(View.VISIBLE);
        btnAccess.setEnabled(true);
        btnAccess.setText("▶ Accéder au contenu");
    }

    private void showInactive(String status) {
        cardProvider.setVisibility(View.GONE);
        btnAccess.setVisibility(View.GONE);
        if (status.contains("EXPIRED")) {
            tvStatus.setText("⏰ EXPIRÉ");
            tvStatus.setTextColor(0xFFFF9800);
            tvStatusDetail.setText("Abonnement expiré.\nContactez votre revendeur pour renouveler.");
        } else if (status.contains("DISABLED")) {
            tvStatus.setText("🚫 DÉSACTIVÉ");
            tvStatus.setTextColor(0xFFF44336);
            tvStatusDetail.setText("Accès suspendu.\nContactez votre revendeur.");
        } else {
            tvStatus.setText("❌ NON ACTIVÉ");
            tvStatus.setTextColor(0xFFF44336);
            tvStatusDetail.setText("Communiquez votre Device Key à votre revendeur.");
        }
    }

    private void showPending(String err) {
        cardProvider.setVisibility(View.GONE);
        btnAccess.setVisibility(View.GONE);
        tvStatus.setText("⏳ EN ATTENTE");
        tvStatus.setTextColor(0xFFFFEB3B);
        tvStatusDetail.setText("Communiquez votre Device Key à votre revendeur.\n(" + err + ")");
    }

    private void showOffline() {
        tvStatus.setText("📡 HORS LIGNE");
        tvStatus.setTextColor(0xFF9E9E9E);
        tvStatusDetail.setText("Pas de connexion. Accès via le cache.");
        btnAccess.setVisibility(View.VISIBLE);
        btnAccess.setEnabled(true);
        btnAccess.setText("▶ Continuer hors ligne");
        btnAccess.setOnClickListener(v -> goToMainFromCache());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cache SharedPreferences
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isActiveAndNotExpired() {
        SharedPreferences p = getPrefs();
        if (!"ACTIVE".equals(p.getString("status", ""))) return false;
        String exp = p.getString("expires_at", "");
        if (exp.isEmpty()) return false;
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(exp);
            return d != null && new Date().before(d);
        } catch (Exception e) { return false; }
    }

    private void saveCache(DeviceSecurity.ActivationResult r) {
        if (r == null) return;
        SharedPreferences.Editor ed = getPrefs().edit()
            .putString("status",     "ACTIVE")
            .putString("expires_at", r.expiresAt != null ? r.expiresAt : "")
            .putString("login",      r.login     != null ? r.login     : "")
            .putString("password",   r.password  != null ? r.password  : "");
        if (r.dnsServers != null && !r.dnsServers.isEmpty()) {
            StringBuilder urls = new StringBuilder();
            StringBuilder epgs = new StringBuilder();
            for (int i = 0; i < r.dnsServers.size(); i++) {
                if (i > 0) { urls.append(","); epgs.append(","); }
                urls.append(r.dnsServers.get(i).url);
                String epg = r.dnsServers.get(i).epgUrl;
                epgs.append(epg != null ? epg : "");
            }
            ed.putString("dns_urls",     urls.toString())
              .putString("dns_epg_urls", epgs.toString());
        }
        ed.apply();
    }

    private void clearCache(String status) {
        getPrefs().edit()
            .putString("status", status)
            .remove("expires_at").remove("login").remove("password")
            .remove("dns_urls").remove("dns_epg_urls")
            .apply();
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void setLoading(boolean on) {
        if (progressBar == null || btnCheck == null) return;
        progressBar.setVisibility(on ? View.VISIBLE : View.GONE);
        btnCheck.setEnabled(!on);
        btnCheck.setText(on ? "Vérification…" : "🔄 Vérifier l'activation");
    }
}
