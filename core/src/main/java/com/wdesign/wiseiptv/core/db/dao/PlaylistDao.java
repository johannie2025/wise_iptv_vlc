package com.wdesign.wiseiptv.core.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.*;
import com.wdesign.wiseiptv.core.db.entity.PlaylistEntity;
import java.util.List;

@Dao 
public interface PlaylistDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) long insert(PlaylistEntity p);
    
    @Update void update(PlaylistEntity p);
    
    @Delete void delete(PlaylistEntity p);
    
    @Query("SELECT * FROM playlists ORDER BY sort_order ASC") LiveData<List<PlaylistEntity>> getAll();
    
    @Query("SELECT * FROM playlists ORDER BY sort_order ASC") List<PlaylistEntity> getAllSync();
    
    @Query("SELECT * FROM playlists WHERE id=:id LIMIT 1") PlaylistEntity findById(long id);
    
    @Query("UPDATE playlists SET last_updated=:ts WHERE id=:id") void updateTimestamp(long id, long ts);

    // ── AJOUT : Requis pour éviter les doublons lors de la synchronisation multi-DNS TV ──
    @Query("SELECT * FROM playlists WHERE url = :url AND username = :login LIMIT 1")
    PlaylistEntity findByUrlAndLogin(String url, String login);
}