package com.wdesign.wiseiptv.core.db.dao;
import androidx.lifecycle.LiveData;
import androidx.room.*;
import com.wdesign.wiseiptv.core.db.entity.ChannelEntity;
import java.util.List;
@Dao public interface ChannelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) void insertAll(List<ChannelEntity> ch);
    @Insert(onConflict = OnConflictStrategy.REPLACE) void insert(ChannelEntity ch);
    @Query("SELECT * FROM channels ORDER BY group_title ASC, sort_order ASC") LiveData<List<ChannelEntity>> getAll();
    @Query("SELECT DISTINCT COALESCE(NULLIF(group_title,''),'-') as g FROM channels WHERE content_type=0 ORDER BY g ASC") LiveData<List<String>> getLiveGroups();
    @Query("SELECT DISTINCT COALESCE(NULLIF(group_title,''),'-') as g FROM channels WHERE content_type=1 ORDER BY g ASC") LiveData<List<String>> getFilmGroups();
    @Query("SELECT DISTINCT COALESCE(NULLIF(group_title,''),'-') as g FROM channels WHERE content_type=2 ORDER BY g ASC") LiveData<List<String>> getSeriesGroups();
    @Query("SELECT DISTINCT COALESCE(NULLIF(country,''),'-') as c FROM channels ORDER BY c ASC") LiveData<List<String>> getCountries();
    @Query("SELECT DISTINCT COALESCE(NULLIF(language,''),'-') as l FROM channels ORDER BY l ASC") LiveData<List<String>> getLanguages();
    @Query("SELECT * FROM channels WHERE content_type=0 ORDER BY group_title ASC, sort_order ASC") LiveData<List<ChannelEntity>> getLive();
    @Query("SELECT * FROM channels WHERE content_type=0 AND group_title=:g ORDER BY sort_order ASC") LiveData<List<ChannelEntity>> getLiveByGroup(String g);
    @Query("SELECT * FROM channels WHERE content_type=0 AND country=:c ORDER BY sort_order ASC") LiveData<List<ChannelEntity>> getLiveByCountry(String c);
    @Query("SELECT * FROM channels WHERE content_type=0 AND language=:l ORDER BY sort_order ASC") LiveData<List<ChannelEntity>> getLiveByLanguage(String l);
    @Query("SELECT * FROM channels WHERE content_type=1 ORDER BY name ASC") LiveData<List<ChannelEntity>> getFilms();
    @Query("SELECT * FROM channels WHERE content_type=1 AND group_title=:g ORDER BY name ASC") LiveData<List<ChannelEntity>> getFilmsByGroup(String g);
    @Query("SELECT * FROM channels WHERE content_type=2 ORDER BY name ASC") LiveData<List<ChannelEntity>> getSeries();
    @Query("SELECT * FROM channels WHERE content_type=2 AND group_title=:g ORDER BY name ASC") LiveData<List<ChannelEntity>> getSeriesByGroup(String g);
    @Query("SELECT * FROM channels WHERE is_favorite=1 ORDER BY name ASC") LiveData<List<ChannelEntity>> getFavorites();
    @Query("SELECT * FROM channels WHERE name LIKE '%'||:q||'%' OR group_title LIKE '%'||:q||'%' OR country LIKE '%'||:q||'%' ORDER BY content_type,name ASC") LiveData<List<ChannelEntity>> search(String q);
    @Query("UPDATE channels SET is_favorite=:fav WHERE id=:id") void setFavorite(long id, boolean fav);
 // ── AJOUT : Recherche synchrone (bloquante/directe) pour exécution en arrière-plan avec limite à 50 résultats ──
    @Query("SELECT * FROM channels WHERE name LIKE '%'||:q||'%' OR group_title LIKE '%'||:q||'%' ORDER BY content_type,name ASC LIMIT 50") 
    List<ChannelEntity> searchSync(String q);  
    @Query("DELETE FROM channels WHERE playlist_id=:pid") void deleteByPlaylist(long pid);
    @Query("DELETE FROM channels") void deleteAll();
    @Query("SELECT COUNT(*) FROM channels") int count();
    @Query("SELECT * FROM channels WHERE id=:id LIMIT 1") ChannelEntity findById(long id);
    // Zapping: prev/next dans le même group et content_type
    @Query("SELECT * FROM channels WHERE sort_order < :order AND content_type=:type AND (group_title=:grp OR :grp IS NULL) ORDER BY sort_order DESC LIMIT 1") ChannelEntity getPrev(int order, int type, String grp);
    @Query("SELECT * FROM channels WHERE sort_order > :order AND content_type=:type AND (group_title=:grp OR :grp IS NULL) ORDER BY sort_order ASC LIMIT 1") ChannelEntity getNext(int order, int type, String grp);
}
