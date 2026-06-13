package com.wdesign.wiseiptv.core.db.entity;
import androidx.annotation.NonNull;
import androidx.room.*;
@Entity(tableName = "channels", indices = {
    @Index("category"), @Index("is_favorite"), @Index("content_type"),
    @Index("group_title"), @Index("country"), @Index("language"), @Index("playlist_id")
})
public class ChannelEntity {
    @PrimaryKey(autoGenerate = true) public long id;
    @NonNull @ColumnInfo(name = "name")       public String name = "";
    @ColumnInfo(name = "stream_url")          public String streamUrl;
    @ColumnInfo(name = "logo_url")            public String logoUrl;
    @ColumnInfo(name = "category")            public String category;
    @ColumnInfo(name = "group_title")         public String groupTitle;
    @ColumnInfo(name = "country")             public String country;
    @ColumnInfo(name = "language")            public String language;
    @ColumnInfo(name = "epg_channel_id")      public String epgChannelId;
    @ColumnInfo(name = "content_type")        public int contentType;
    @ColumnInfo(name = "is_favorite", defaultValue = "0") public boolean isFavorite;
    @ColumnInfo(name = "sort_order")          public int sortOrder;
    @ColumnInfo(name = "last_updated")        public long lastUpdated;
    @ColumnInfo(name = "playlist_id", defaultValue = "0") public long playlistId;
    public static final int TYPE_LIVE = 0, TYPE_FILM = 1, TYPE_SERIES = 2;
}
