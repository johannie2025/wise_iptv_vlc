package com.wdesign.wiseiptv.core.db.entity;
import androidx.annotation.NonNull;
import androidx.room.*;
@Entity(tableName = "playlists")
public class PlaylistEntity {
    @PrimaryKey(autoGenerate = true) public long id;
    @NonNull @ColumnInfo(name = "name")  public String name = "";
    @ColumnInfo(name = "type")           public int type;      // 0=M3U_URL, 1=M3U_FILE, 2=XTREAM
    @ColumnInfo(name = "url")            public String url;    // M3U url or Xtream server
    @ColumnInfo(name = "username")       public String username;
    @ColumnInfo(name = "password")       public String password;
    @ColumnInfo(name = "last_updated")   public long lastUpdated;
    @ColumnInfo(name = "is_active", defaultValue = "1") public boolean isActive;
    @ColumnInfo(name = "sort_order")     public int sortOrder;
    public static final int TYPE_M3U_URL  = 0;
    public static final int TYPE_M3U_FILE = 1;
    public static final int TYPE_XTREAM   = 2;
    /** Interval de refresh: 7 jours en ms */
    public static final long REFRESH_INTERVAL_MS = 7L * 24 * 3600 * 1000;
}
