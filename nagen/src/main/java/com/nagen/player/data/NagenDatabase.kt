package com.nagen.player.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        QueueEntity::class,
        SettingEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class NagenDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun playlists(): PlaylistDao
    abstract fun queues(): QueueDao
    abstract fun settings(): SettingsDao
}
