package com.wdesign.wiseiptv.core.db;
import android.content.Context;
import androidx.room.*;
import com.wdesign.wiseiptv.core.db.dao.ChannelDao;
import com.wdesign.wiseiptv.core.db.dao.PlaylistDao;
import com.wdesign.wiseiptv.core.db.entity.ChannelEntity;
import com.wdesign.wiseiptv.core.db.entity.PlaylistEntity;
@Database(entities = {ChannelEntity.class, PlaylistEntity.class}, version = 2, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase INSTANCE;
    public abstract ChannelDao channelDao();
    public abstract PlaylistDao playlistDao();
    public static AppDatabase get(Context ctx) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(ctx.getApplicationContext(),
                            AppDatabase.class, "wiseiptv.db")
                        .fallbackToDestructiveMigration().build();
                }
            }
        }
        return INSTANCE;
    }
}
