package com.wdesign.wiseiptv.mobile.util;

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
    private static final String TAG = "PlaylistLoader";

    public interface Callback { void onDone(int count); void onError(String msg); }

    public static void load(PlaylistEntity pl, AppDatabase db, Callback cb) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                List<ChannelEntity> list;
                if (pl.type == PlaylistEntity.TYPE_XTREAM) {
                    list = loadXtream(pl);
                } else {
                    InputStream is = openStream(pl);
                    list = M3UParser.parse(is);
                    is.close();
                }
                if (list == null || list.isEmpty()) { cb.onError("Playlist vide"); return; }
                for (ChannelEntity ch : list) ch.playlistId = pl.id;
                db.runInTransaction(() -> {
                    db.channelDao().deleteByPlaylist(pl.id);
                    db.channelDao().insertAll(list);
                });
                db.playlistDao().updateTimestamp(pl.id, System.currentTimeMillis());
                cb.onDone(list.size());
            } catch (Exception e) {
                Log.e(TAG, "" + e.getMessage(), e);
                cb.onError(e.getMessage() != null ? e.getMessage() : "Erreur réseau");
            }
        });
    }

    public static boolean needsRefresh(PlaylistEntity pl) {
        return (System.currentTimeMillis() - pl.lastUpdated) >= PlaylistEntity.REFRESH_INTERVAL_MS;
    }

    public static void refreshStaleIfNeeded(AppDatabase db, Callback cb) {
        Executors.newSingleThreadExecutor().execute(() -> {
            List<PlaylistEntity> all = db.playlistDao().getAllSync();
            int total = 0;
            for (PlaylistEntity pl : all) {
                if (!pl.isActive || !needsRefresh(pl)) continue;
                try {
                    List<ChannelEntity> list;
                    if (pl.type == PlaylistEntity.TYPE_XTREAM) list = loadXtream(pl);
                    else { InputStream is = openStream(pl); list = M3UParser.parse(is); is.close(); }
                    if (list != null && !list.isEmpty()) {
                        for (ChannelEntity ch : list) ch.playlistId = pl.id;
                        final List<ChannelEntity> fl  = list;
                        final long                fid = pl.id;
                        db.runInTransaction(() -> {
                            db.channelDao().deleteByPlaylist(fid);
                            db.channelDao().insertAll(fl);
                        });
                        db.playlistDao().updateTimestamp(pl.id, System.currentTimeMillis());
                        total += list.size();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "refresh failed for " + pl.name + ": " + e.getMessage());
                }
            }
            final int t = total;
            if (t > 0 && cb != null) cb.onDone(t);
        });
    }

    private static InputStream openStream(PlaylistEntity pl) throws IOException {
        String url = pl.url;
        if (url.startsWith("file://") || url.startsWith("/"))
            return new FileInputStream(url.replace("file://", ""));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000); c.setReadTimeout(60_000);
        c.setRequestProperty("User-Agent", "WiseIPTV/2.0");
        if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
        return c.getInputStream();
    }

    /**
     * Charge une playlist Xtream.
     *
     * Deux cas :
     *  1. URL COMPLÈTE avec "get.php" → utilisée directement.
     *     Ex: "https://tvradiozap.eu/get.php?username=d:tvrztv&password=public&type=m3u_plus"
     *
     *  2. URL de BASE serveur → on construit /get.php?username=...&password=...
     *     Ex: "http://myserver.com:8080"  +  login="user"  +  password="pass"
     *     → "http://myserver.com:8080/get.php?username=user&password=pass&type=m3u_plus&output=ts"
     */
    private static List<ChannelEntity> loadXtream(PlaylistEntity pl) throws IOException {
        String url = pl.url.trim();
        String m3uUrl;
        if (url.toLowerCase().contains("get.php")) {
            // URL complète → utiliser telle quelle
            m3uUrl = url;
        } else {
            // Base serveur → construire l'URL M3U
            if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            m3uUrl = url + "/get.php?username=" + encode(pl.username)
                         + "&password=" + encode(pl.password)
                         + "&type=m3u_plus&output=ts";
        }
        HttpURLConnection c = (HttpURLConnection) new URL(m3uUrl).openConnection();
        c.setConnectTimeout(15_000); c.setReadTimeout(120_000);
        c.setRequestProperty("User-Agent", "WiseIPTV/2.0");
        if (c.getResponseCode() != 200)
            throw new IOException("Xtream HTTP " + c.getResponseCode() + " → " + m3uUrl);
        InputStream is = c.getInputStream();
        List<ChannelEntity> list = M3UParser.parse(is);
        is.close();
        return list;
    }

    private static String encode(String s) {
        if (s == null) return "";
        try { return URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }
}
