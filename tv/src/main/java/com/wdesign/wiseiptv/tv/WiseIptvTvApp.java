package com.wdesign.wiseiptv.tv;

import android.app.Application;
import android.preference.PreferenceManager;
import android.util.Log;
import com.wdesign.wiseiptv.core.db.AppDatabase;
import com.wdesign.wiseiptv.core.security.DeviceSecurity;
import com.wdesign.wiseiptv.tv.util.ActivationManager;
import com.wdesign.wiseiptv.tv.util.PlaylistLoader;

public class WiseIptvTvApp extends Application {
    private static final String TAG = "WiseIptvTvApp";
    private static WiseIptvTvApp instance;
    public static WiseIptvTvApp get() { return instance; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        AppDatabase db = AppDatabase.get(this);

        // Vérification activation en background — ne bloque pas le démarrage
        ActivationManager.checkAndSync(this, new ActivationManager.OnResult() {
            @Override public void onActivated(DeviceSecurity.ActivationResult r) {
                Log.d(TAG, "Activé — DNS: " + r.primaryDns());
            }
            @Override public void onExpired(String status) {
                Log.w(TAG, "Expiré: " + status);
            }
            @Override public void onError(String msg) {
                // Pas de réseau → refresh hebdo depuis cache
                Log.e(TAG, "Panel inaccessible: " + msg);
                PlaylistLoader.refreshStaleIfNeeded(db, null);
            }
        });
    }

    public String getPlaylistUrl() {
        return PreferenceManager.getDefaultSharedPreferences(this)
            .getString("playlist_url", "");
    }

    public void savePlaylistUrl(String url) {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString("playlist_url", url).apply();
    }
}
