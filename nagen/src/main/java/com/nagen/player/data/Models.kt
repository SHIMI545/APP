package com.nagen.player.data
import androidx.room.Entity

@Entity(tableName = "tracks")
data class TrackEntity(
    @androidx.room.PrimaryKey val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val folder: String,
    val durationMs: Long,
    val dateAdded: Long,
    val playCount: Int = 0,
    val skipCount: Int = 0,
    val rating: Int = 0,
    val favorite: Boolean = false,
    val totalListenMs: Long = 0,
    val lastPlayedAt: Long = 0
)

@Entity(tableName = "playlists")
data class PlaylistEntity(@androidx.room.PrimaryKey val name: String, val smartType: String = "")

@Entity(tableName = "playlist_tracks", primaryKeys = ["playlistName", "trackUri"])
data class PlaylistTrackEntity(val playlistName: String, val trackUri: String, val position: Int)

@Entity(tableName = "queues")
data class QueueEntity(
    @androidx.room.PrimaryKey val name: String,
    val orderJson: String,
    val currentIndex: Int = 0,
    val positionMs: Long = 0,
    val shuffle: Boolean = false,
    val historyJson: String = "[]"
)

@Entity(tableName = "app_settings")
data class SettingEntity(@androidx.room.PrimaryKey val key: String, val value: String)