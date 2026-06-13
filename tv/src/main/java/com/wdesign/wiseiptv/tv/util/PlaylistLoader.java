package com.wdesign.wiseiptv.tv.util;

import android.util.Log;
import com.wdesign.wiseiptv.core.db.AppDatabase;
import com.wdesign.wiseiptv.core.db.entity.ChannelEntity;
import com.wdesign.wiseiptv.core.db.entity.PlaylistEntity;
import com.wdesign.wiseiptv.core.parser.M3UParser;
import java.io.*;
import java.net.*;
import java.util.List;
import java.util.concurrent.Executors;

public class PlaylistLoader {
    private static final String TAG = "TvPlaylistLoader";

    public interface Callback { void onDone(int count); void onError(String msg); }

    public static void load(PlaylistEntity pl, AppDatabase db, Callback cb) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                List<ChannelEntity> list = pl.type == PlaylistEntity.TYPE_XTREAM
                    ? loadXtreamSync(pl) : loadUrlSync(pl);
                if (list == null || list.isEmpty()) { cb.onError("Playlist vide"); return; }
                for (ChannelEntity ch : list) ch.playlistId = pl.id;
                db.runInTransaction(() -> {
                    db.channelDao().deleteByPlaylist(pl.id);
                    db.channelDao().insertAll(list);
                });
                db.playlistDao().updateTimestamp(pl.id, System.currentTimeMillis());
                cb.onDone(list.size());
            } catch (Exception e) {
                Log.e(TAG, e.getMessage(), e);
                cb.onError(e.getMessage() != null ? e.getMessage() : "Erreur");
            }
        });
    }

    /**
     * FIX HTTP 404 : essaie plusieurs formats d'URL Xtream dans l'ordre.
     * Certains serveurs n'ont pas /get.php mais répondent sur /player_api.php
     * ou directement sur la racine.
     */
    public static List<ChannelEntity> loadXtreamSync(PlaylistEntity pl) throws IOException {
        String base = pl.url.trim();
        // Normaliser la base : retirer /get.php ou /player_api.php s'ils sont dans l'URL
        base = base.replaceAll("(?i)/get\\.php.*$", "")
                   .replaceAll("(?i)/player_api\\.php.*$", "");
        if (!base.endsWith("/")) base += "/";

        String user = pl.username;
        String pass = pl.password;

        // Liste des formats à essayer dans l'ordre
        String[] candidates = {
            base + "get.php?username=" + user + "&password=" + pass + "&type=m3u_plus&output=ts",
            base + "get.php?username=" + user + "&password=" + pass + "&type=m3u_plus",
            base + "get.php?username=" + user + "&password=" + pass + "&type=m3u",
            base + user + "/" + pass + "/m3u_plus",      // format panel alternatif
            base + "apiget.php?username=" + user + "&password=" + pass + "&type=m3u_plus",
        };

        IOException lastException = null;
        for (String url : candidates) {
            try {
                Log.d(TAG, "Trying Xtream URL: " + url);
                List<ChannelEntity> result = parseUrl(url);
                if (result != null && !result.isEmpty()) {
                    Log.d(TAG, "Success with: " + url + " -> " + result.size() + " ch");
                    return result;
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed: " + url + " -> " + e.getMessage());
                lastException = e;
            }
        }
        throw lastException != null ? lastException : new IOException("Toutes les URLs Xtream ont échoué");
    }

    public static List<ChannelEntity> loadUrlSync(PlaylistEntity pl) throws IOException {
        String url = pl.url;
        if (url.startsWith("file://") || url.startsWith("/")) {
            InputStream is = new FileInputStream(url.replace("file://", ""));
            List<ChannelEntity> l = M3UParser.parse(is); is.close(); return l;
        }
        return parseUrl(url);
    }

    /** Charge depuis une URL M3U brute (mode legacy / dialog simple) */
    public static void load(String url, AppDatabase db, Callback cb) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                List<ChannelEntity> list = parseUrl(url);
                if (list.isEmpty()) { cb.onError("Playlist vide"); return; }
                db.runInTransaction(() -> { db.channelDao().deleteAll(); db.channelDao().insertAll(list); });
                cb.onDone(list.size());
            } catch (Exception e) { cb.onError(e.getMessage()); }
        });
    }

    public static boolean needsRefresh(PlaylistEntity pl) {
        long age = System.currentTimeMillis() - pl.lastUpdated;
        return age > PlaylistEntity.REFRESH_INTERVAL_MS;
    }

    private static List<ChannelEntity> parseUrl(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(120_000);
        c.setRequestProperty("User-Agent", "WiseIPTV/2.0");
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code);
        InputStream is = c.getInputStream();
        List<ChannelEntity> l = M3UParser.parse(is);
        is.close();
        return l;
    }

    public static void refreshStaleIfNeeded(AppDatabase db, Callback cb) {
        Executors.newSingleThreadExecutor().execute(() -> {
            List<PlaylistEntity> all = db.playlistDao().getAllSync();
            int total = 0;
            for (PlaylistEntity pl : all) {
                if (!pl.isActive || !needsRefresh(pl)) continue;
                try {
                    List<ChannelEntity> list = pl.type == PlaylistEntity.TYPE_XTREAM
                        ? loadXtreamSync(pl) : loadUrlSync(pl);
                    if (list != null && !list.isEmpty()) {
                        for (ChannelEntity ch : list) ch.playlistId = pl.id;
                        final List<ChannelEntity> fl = list; final long fid = pl.id;
                        db.runInTransaction(() -> { db.channelDao().deleteByPlaylist(fid); db.channelDao().insertAll(fl); });
                        db.playlistDao().updateTimestamp(pl.id, System.currentTimeMillis());
                        total += list.size();
                    }
                } catch (Exception e) { Log.w(TAG, "refresh stale: " + e.getMessage()); }
            }
            if (cb != null && total > 0) cb.onDone(total);
        });
    }
}
