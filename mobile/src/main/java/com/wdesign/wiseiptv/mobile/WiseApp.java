package com.wdesign.wiseiptv.mobile;
import android.app.Application;
import android.preference.PreferenceManager;
public class WiseApp extends Application {
    public static final String PREF_PLAYLIST_URL = "playlist_url";
    public static final String PREF_PLAYLIST_NAME = "playlist_name";
    private static WiseApp instance;
    public static WiseApp get() { return instance; }
    @Override public void onCreate() { super.onCreate(); instance = this; }
    public String getPlaylistUrl() {
        return PreferenceManager.getDefaultSharedPreferences(this).getString(PREF_PLAYLIST_URL, "");
    }
    public void savePlaylist(String name, String url) {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString(PREF_PLAYLIST_URL, url).putString(PREF_PLAYLIST_NAME, name).apply();
    }
}
