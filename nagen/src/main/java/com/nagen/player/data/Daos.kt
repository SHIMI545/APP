package com.nagen.player.data
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE ASC") fun all(): Flow<List<TrackEntity>>
    @Query("SELECT * FROM tracks WHERE favorite = 1 ORDER BY lastPlayedAt DESC") fun favorites(): Flow<List<TrackEntity>>
    @Query("SELECT * FROM tracks ORDER BY playCount DESC, rating DESC, favorite DESC LIMIT :limit") fun top(limit:Int): Flow<List<TrackEntity>>
    @Query("SELECT * FROM tracks ORDER BY dateAdded DESC LIMIT :limit") fun recent(limit:Int): Flow<List<TrackEntity>>
    @Query("SELECT * FROM tracks ORDER BY lastPlayedAt DESC LIMIT :limit") fun resumed(limit:Int): Flow<List<TrackEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(items:List<TrackEntity>)
    @Query("UPDATE tracks SET playCount=playCount+1,lastPlayedAt=:now WHERE uri=:uri") suspend fun played(uri:String,now:Long)
    @Query("UPDATE tracks SET skipCount=skipCount+1 WHERE uri=:uri") suspend fun skipped(uri:String)
    @Query("UPDATE tracks SET favorite=:value WHERE uri=:uri") suspend fun favorite(uri:String,value:Boolean)
    @Query("UPDATE tracks SET rating=:value WHERE uri=:uri") suspend fun rating(uri:String,value:Int)
    @Query("UPDATE tracks SET totalListenMs=totalListenMs+:delta WHERE uri=:uri") suspend fun listen(uri:String,delta:Long)
}
@Dao interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE") fun all():Flow<List<PlaylistEntity>>
    @Query("SELECT * FROM playlist_tracks WHERE playlistName=:name ORDER BY position") suspend fun tracks(name:String):List<PlaylistTrackEntity>
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun create(item:PlaylistEntity)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun add(item:PlaylistTrackEntity)
    @Query("DELETE FROM playlist_tracks WHERE playlistName=:name AND trackUri=:uri") suspend fun remove(name:String,uri:String)
    @Query("DELETE FROM playlists WHERE name=:name") suspend fun delete(name:String)
}
@Dao interface QueueDao {
    @Query("SELECT * FROM queues ORDER BY name COLLATE NOCASE") fun all():Flow<List<QueueEntity>>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun save(item:QueueEntity)
}
@Dao interface SettingsDao {
    @Query("SELECT * FROM app_settings") fun all():Flow<List<SettingEntity>>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun set(item:SettingEntity)
}