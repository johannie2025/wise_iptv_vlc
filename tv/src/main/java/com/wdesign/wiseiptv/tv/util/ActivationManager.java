package com.wdesign.wiseiptv.tv.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.wdesign.wiseiptv.core.db.AppDatabase;
import com.wdesign.wiseiptv.core.db.entity.ChannelEntity;
import com.wdesign.wiseiptv.core.db.entity.PlaylistEntity;
import com.wdesign.wiseiptv.core.security.DeviceSecurity;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/** ActivationManager TV — même logique que le module mobile */
public class ActivationManager {

    private static final String TAG   = "TvActivationManager";
    private static final String PREFS = "wise_activation_tv";

    public interface OnResult {
        void onActivated(DeviceSecurity.ActivationResult r);
        void onExpired(String status);
        void onError(String msg);
    }

    public interface DownloadCallback {
        void onProgress(String playlistName);
        void onDone(int totalChannels);
        void onError(String msg);
    }

    public static void checkAndSync(Context ctx, OnResult cb) {
        AppDatabase db = AppDatabase.get(ctx);
        Executors.newSingleThreadExecutor().execute(() ->
            DeviceSecurity.check(ctx, new DeviceSecurity.Callback() {
                @Override public void onActive(DeviceSecurity.ActivationResult r) {
                    savePrefs(ctx, "ACTIVE", r.expiresAt, r.login);
                    upsertAndDownloadAll(ctx, db, r, new DownloadCallback() {
                        @Override public void onProgress(String n) {}
                        @Override public void onDone(int t) { Log.d(TAG, "Sync OK: "+t); }
                        @Override public void onError(String m) { Log.w(TAG, "Sync err: "+m); }
                    });
                    cb.onActivated(r);
                }
                @Override public void onInactive(String status, String msg) {
                    purge(ctx, db);
                    savePrefs(ctx, status, "", "");
                    cb.onExpired(status);
                }
                @Override public void onError(String msg) { cb.onError(msg); }
            })
        );
    }

    public static void upsertAndDownloadAll(Context ctx, AppDatabase db,
                                             DeviceSecurity.ActivationResult r,
                                             DownloadCallback cb) {
        Executors.newSingleThreadExecutor().execute(() -> {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            List<Long> existingIds = parseLongs(prefs.getString("playlist_ids", ""));
            List<Long> newIds = new ArrayList<>();
            int total = 0; String lastErr = null;

            for (int i = 0; i < r.dnsServers.size(); i++) {
                DeviceSecurity.DnsEntry dns = r.dnsServers.get(i);
                if (dns.url == null || dns.url.isEmpty()) continue;

                long eid = i < existingIds.size() ? existingIds.get(i) : 0;
                PlaylistEntity pl = eid > 0 ? db.playlistDao().findById(eid) : null;
                if (pl == null) pl = new PlaylistEntity();

                pl.name       = r.dnsServers.size() == 1 ? "Abonnement IPTV" : "IPTV #" + (i+1);
                pl.type       = PlaylistEntity.TYPE_XTREAM;
                pl.url        = dns.url;
                pl.username   = r.login;
                pl.password   = r.password;
                pl.isActive   = true;
                pl.lastUpdated= 0;

                if (pl.id == 0) pl.id = db.playlistDao().insert(pl);
                else db.playlistDao().update(pl);
                newIds.add(pl.id);

                cb.onProgress(pl.name);

                try {
                    List<ChannelEntity> chs = PlaylistLoader.loadXtreamSync(pl);
                    if (chs != null && !chs.isEmpty()) {
                        for (ChannelEntity c : chs) c.playlistId = pl.id;
                        final List<ChannelEntity> fc = chs; final long fid = pl.id;
                        db.runInTransaction(() -> {
                            db.channelDao().deleteByPlaylist(fid);
                            db.channelDao().insertAll(fc);
                        });
                        db.playlistDao().updateTimestamp(pl.id, System.currentTimeMillis());
                        total += chs.size();
                    }
                } catch (Exception e) { Log.w(TAG, e.getMessage()); lastErr = e.getMessage(); }
            }

            // Purger anciens DNS supprimés
            for (long oid : existingIds) {
                if (!newIds.contains(oid)) {
                    db.channelDao().deleteByPlaylist(oid);
                    PlaylistEntity old = db.playlistDao().findById(oid);
                    if (old != null) db.playlistDao().delete(old);
                }
            }
            prefs.edit().putString("playlist_ids", joinLongs(newIds)).apply();

            if (total > 0) cb.onDone(total);
            else if (lastErr != null) cb.onError(lastErr);
            else cb.onError("Aucune chaîne");
        });
    }

    private static void purge(Context ctx, AppDatabase db) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (long id : parseLongs(p.getString("playlist_ids", ""))) {
            db.channelDao().deleteByPlaylist(id);
            PlaylistEntity pl = db.playlistDao().findById(id);
            if (pl != null) db.playlistDao().delete(pl);
        }
        p.edit().remove("playlist_ids").apply();
    }

    private static void savePrefs(Context ctx, String s, String exp, String login) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("status", s).putString("expires_at", exp)
            .putString("login", login).apply();
    }

    public static String getSavedStatus(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("status", "NONE");
    }

    private static List<Long> parseLongs(String csv) {
        List<Long> r = new ArrayList<>();
        if (csv == null || csv.isEmpty()) return r;
        for (String s : csv.split(",")) {
            try { r.add(Long.parseLong(s.trim())); } catch (NumberFormatException ignored) {}
        }
        return r;
    }
    private static String joinLongs(List<Long> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) { if (i>0) sb.append(","); sb.append(list.get(i)); }
        return sb.toString();
    }
}
