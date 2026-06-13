package com.wdesign.wiseiptv.tv.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.*;
import androidx.fragment.app.FragmentActivity;
import com.wdesign.wiseiptv.core.security.DeviceSecurity;
import com.wdesign.wiseiptv.tv.R;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * TvActivationActivity — vérifie l'activation, puis transfère à TvMainActivity.
 * Le téléchargement des playlists se fait dans TvMainActivity (non bloquant).
 */
public class TvActivationActivity extends FragmentActivity {

    private static final String PREFS = "wise_activation_tv";

    private TextView    tvDeviceKey, tvStatus, tvStatusDetail;
    private TextView    tvLogin, tvPassword, tvExpiry, tvProvider;
    private View        cardProvider;
    private Button      btnCheck, btnAccess;
    private ProgressBar progressBar;
    private ImageView   imgLogo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (isActiveAndNotExpired()) {
            goToMain(false, null);
            return;
        }

        setContentView(R.layout.activity_tv_activation);

        imgLogo        = findViewById(R.id.img_logo);
        tvDeviceKey    = findViewById(R.id.tv_device_key);
        tvStatus       = findViewById(R.id.tv_status);
        tvStatusDetail = findViewById(R.id.tv_status_detail);
        tvLogin        = findViewById(R.id.tv_login);
        tvPassword     = findViewById(R.id.tv_password);
        tvExpiry       = findViewById(R.id.tv_expiry);
        tvProvider     = findViewById(R.id.tv_provider_label);
        cardProvider   = findViewById(R.id.card_provider);
        btnCheck       = findViewById(R.id.btn_check);
        btnAccess      = findViewById(R.id.btn_access);
        progressBar    = findViewById(R.id.progress_bar);

        if (imgLogo != null) imgLogo.setImageResource(R.mipmap.ic_launcher);
        tvDeviceKey.setText(DeviceSecurity.getOrCreateKey(this));

        btnCheck.setOnClickListener(v -> checkActivation(false));
        btnAccess.setOnClickListener(v -> checkActivation(true));
        btnCheck.requestFocus();

        String saved = getPrefs().getString("status", "");
        if ("EXPIRED".equals(saved) || "DISABLED".equals(saved)) showInactive(saved);

        checkActivation(false);
    }

    private void checkActivation(boolean goOnSuccess) {
        setLoading(true);
        DeviceSecurity.check(this, new DeviceSecurity.Callback() {
            @Override
            public void onActive(DeviceSecurity.ActivationResult r) {
                saveCache(r);
                runOnUiThread(() -> {
                    setLoading(false);
                    if (goOnSuccess) {
                        // Aller directement — pas de téléchargement ici
                        goToMain(true, r);
                    } else {
                        showActive(r);
                    }
                });
            }
            @Override
            public void onInactive(String status, String message) {
                clearCache(status);
                runOnUiThread(() -> { setLoading(false); showInactive(status); });
            }
            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    setLoading(false);
                    if (isActiveAndNotExpired()) showOffline();
                    else showPending(message);
                });
            }
        });
    }

    private void showActive(DeviceSecurity.ActivationResult r) {
        tvStatus.setText("✅ ACTIVÉ"); tvStatus.setTextColor(0xFF4CAF50);
        tvStatusDetail.setText("Abonnement actif");
        cardProvider.setVisibility(View.VISIBLE);
        if (!r.dnsServers.isEmpty()) tvProvider.setText("Provider : " + r.dnsServers.get(0).url);
        tvLogin.setText("Login : " + r.login);
        tvPassword.setText("Mot de passe : " + r.password);
        tvExpiry.setText("Expire : " + r.expiresAt);
        btnAccess.setVisibility(View.VISIBLE);
        btnAccess.setEnabled(true);
        btnAccess.setText("▶ Accéder au contenu");
        btnAccess.requestFocus();
    }

    private void showInactive(String status) {
        cardProvider.setVisibility(View.GONE);
        btnAccess.setVisibility(View.GONE);
        if (status.contains("EXPIRED")) {
            tvStatus.setText("⏰ EXPIRÉ"); tvStatus.setTextColor(0xFFFF9800);
            tvStatusDetail.setText("Abonnement expiré.\nContactez votre revendeur.");
        } else if (status.contains("DISABLED")) {
            tvStatus.setText("🚫 DÉSACTIVÉ"); tvStatus.setTextColor(0xFFF44336);
            tvStatusDetail.setText("Accès suspendu.\nContactez votre revendeur.");
        } else {
            tvStatus.setText("❌ NON ACTIVÉ"); tvStatus.setTextColor(0xFFF44336);
            tvStatusDetail.setText("Communiquez votre Device Key à votre revendeur.");
        }
    }

    private void showPending(String err) {
        cardProvider.setVisibility(View.GONE); btnAccess.setVisibility(View.GONE);
        tvStatus.setText("⏳ EN ATTENTE"); tvStatus.setTextColor(0xFFFFEB3B);
        tvStatusDetail.setText("Communiquez votre Device Key.\n(" + err + ")");
    }

    private void showOffline() {
        tvStatus.setText("📡 HORS LIGNE"); tvStatus.setTextColor(0xFF9E9E9E);
        tvStatusDetail.setText("Pas de connexion. Accès via le cache.");
        btnAccess.setVisibility(View.VISIBLE); btnAccess.setEnabled(true);
        btnAccess.setText("▶ Continuer hors ligne");
        btnAccess.setOnClickListener(v -> goToMain(true, null));
        btnAccess.requestFocus();
    }

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
        SharedPreferences.Editor ed = getPrefs().edit()
            .putString("status", "ACTIVE")
            .putString("expires_at", r.expiresAt)
            .putString("login", r.login)
            .putString("password", r.password);
        // Sauvegarder les DNS pour TvMainActivity
        StringBuilder urls = new StringBuilder(), epgs = new StringBuilder();
        for (int i = 0; i < r.dnsServers.size(); i++) {
            if (i > 0) { urls.append(","); epgs.append(","); }
            urls.append(r.dnsServers.get(i).url);
            String epg = r.dnsServers.get(i).epgUrl;
            epgs.append(epg != null ? epg : "");
        }
        ed.putString("dns_urls", urls.toString())
          .putString("dns_epg_urls", epgs.toString())
          .apply();
    }

    private void clearCache(String status) {
        getPrefs().edit().putString("status", status)
            .remove("expires_at").remove("login").remove("password")
            .remove("dns_urls").remove("dns_epg_urls").apply();
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Navigue vers TvMainActivity avec les infos d'activation en extras. */
    private void goToMain(boolean animate, DeviceSecurity.ActivationResult r) {
        Intent intent = new Intent(this, TvMainActivity.class);
        if (r != null) {
            intent.putExtra(TvMainActivity.EXTRA_ACT_LOGIN,    r.login);
            intent.putExtra(TvMainActivity.EXTRA_ACT_PASSWORD, r.password);
            intent.putExtra(TvMainActivity.EXTRA_ACT_EXPIRES,  r.expiresAt);
            String[] dnsUrls = new String[r.dnsServers.size()];
            String[] epgUrls = new String[r.dnsServers.size()];
            for (int i = 0; i < r.dnsServers.size(); i++) {
                dnsUrls[i] = r.dnsServers.get(i).url;
                String epg = r.dnsServers.get(i).epgUrl;
                epgUrls[i] = epg != null ? epg : "";
            }
            intent.putExtra(TvMainActivity.EXTRA_ACT_DNS_URLS,     dnsUrls);
            intent.putExtra(TvMainActivity.EXTRA_ACT_DNS_EPG_URLS, epgUrls);
        }
        startActivity(intent);
        if (!animate) overridePendingTransition(0, 0);
        finish();
    }

    private void setLoading(boolean on) {
        if (progressBar != null) progressBar.setVisibility(on ? View.VISIBLE : View.GONE);
        if (btnCheck != null) {
            btnCheck.setEnabled(!on);
            btnCheck.setText(on ? "Vérification…" : "🔄 Vérifier");
        }
    }

    @Override
    public boolean onKeyDown(int kc, KeyEvent e) {
        if (kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER) {
            View f = getCurrentFocus();
            if (f != null) f.performClick();
            return true;
        }
        return super.onKeyDown(kc, e);
    }
}
