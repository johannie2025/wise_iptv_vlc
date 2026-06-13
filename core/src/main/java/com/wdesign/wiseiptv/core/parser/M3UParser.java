package com.wdesign.wiseiptv.core.parser;
import android.util.Log;
import com.wdesign.wiseiptv.core.db.entity.ChannelEntity;
import java.io.*;
import java.util.*;
public class M3UParser {
    private static final String TAG = "M3UParser";
    public static List<ChannelEntity> parse(InputStream is) throws IOException {
        List<ChannelEntity> list = new ArrayList<>();
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        String line; ChannelEntity cur = null; int order = 0;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("#EXTINF")) {
                cur = new ChannelEntity();
                cur.sortOrder = order++; cur.lastUpdated = System.currentTimeMillis();
                parseExtInf(line, cur);
            } else if (!line.isEmpty() && !line.startsWith("#") && cur != null) {
                cur.streamUrl = line; cur.contentType = detectType(cur.groupTitle);
                list.add(cur); cur = null;
            }
        }
        br.close(); Log.i(TAG, "Parsed " + list.size() + " channels"); return list;
    }
    private static void parseExtInf(String line, ChannelEntity e) {
        try {
            int comma = line.lastIndexOf(',');
            if (comma >= 0) e.name = line.substring(comma + 1).trim();
            if (e.name == null || e.name.isEmpty()) e.name = "Channel";
            e.logoUrl = attr(line, "tvg-logo"); e.epgChannelId = attr(line, "tvg-id");
            e.country = attr(line, "tvg-country"); e.language = attr(line, "tvg-language");
            e.groupTitle = attr(line, "group-title"); e.category = e.groupTitle;
        } catch (Exception ex) { Log.w(TAG, ex.getMessage()); }
    }
    private static String attr(String line, String key) {
        String search = key + "=\""; int s = line.indexOf(search);
        if (s < 0) return null; s += search.length(); int e = line.indexOf('"', s);
        if (e < 0) return null; String v = line.substring(s, e).trim();
        return v.isEmpty() ? null : v;
    }
    private static int detectType(String g) {
        if (g == null) return ChannelEntity.TYPE_LIVE;
        String l = g.toLowerCase();
        if (l.contains("film") || l.contains("movie") || l.contains("vod") || l.contains("cinema")) return ChannelEntity.TYPE_FILM;
        if (l.contains("serie") || l.contains("series") || l.contains("show") || l.contains("saison")) return ChannelEntity.TYPE_SERIES;
        return ChannelEntity.TYPE_LIVE;
    }
}
