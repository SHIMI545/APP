package com.nagen.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nagen.player.data.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

data class BackupPayload(
    val tracks: List<TrackEntity>,
    val playlists: List<PlaylistEntity>,
    val playlistTracks: List<PlaylistTrackEntity>,
    val queues: List<QueueEntity>,
    val settings: List<SettingEntity>
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val queueDao: QueueDao,
    private val settingsDao: SettingsDao,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val tracks = trackDao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val favorites = trackDao.favorites().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val topTracks = trackDao.top(30).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val recentTracks = trackDao.recent(30).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val resumedTracks = trackDao.resumed(20).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playlists = playlistDao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val queues = queueDao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings = settingsDao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            if (queueDao.all().first().isEmpty()) {
                listOf("עבודה", "נסיעות", "ערב").forEach {
                    queueDao.save(QueueEntity(it, "[]"))
                }
            }
            if (playlistDao.all().first().none { it.smartType.isNotBlank() }) {
                listOf(
                    "הכי אהובים" to "favorites",
                    "הכי מושמעים" to "top",
                    "גילוי מחדש" to "rediscover",
                    "פנינים נסתרות" to "hidden",
                    "מיקס יומי" to "daily"
                ).forEach { (name, type) -> playlistDao.create(PlaylistEntity(name, type)) }
            }
        }
    }

    fun scanDevice() {
        viewModelScope.launch(Dispatchers.IO) {
            val rows = mutableListOf<TrackEntity>()
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATA
            )
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC}=1",
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val added = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val data = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (c.moveToNext()) {
                    val path = c.getString(data).orEmpty()
                    rows += TrackEntity(
                        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(id)).toString(),
                        title = c.getString(title).orEmpty().ifBlank { "שיר ללא שם" },
                        artist = c.getString(artist).orEmpty().ifBlank { "אמן לא ידוע" },
                        album = c.getString(album).orEmpty().ifBlank { "אלבום לא ידוע" },
                        folder = path.substringBeforeLast('/', ""),
                        durationMs = c.getLong(duration),
                        dateAdded = c.getLong(added) * 1000L
                    )
                }
            }
            trackDao.upsertAll(rows)
        }
    }

    fun importUris(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val imported = uris.mapNotNull { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                createFromUri(uri, DocumentFile.fromSingleUri(context, uri)?.name)
            }
            trackDao.upsertAll(imported)
        }
    }

    fun importFolder(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val docs = collectAudio(DocumentFile.fromTreeUri(context, uri))
            trackDao.upsertAll(docs.mapNotNull { createFromUri(it.uri, it.name) })
        }
    }

    private fun collectAudio(root: DocumentFile?): List<DocumentFile> {
        if (root == null) return emptyList()
        val out = mutableListOf<DocumentFile>()
        root.listFiles().forEach { file ->
            if (file.isDirectory) out += collectAudio(file)
            else if (isAudio(file)) out += file
        }
        return out
    }

    private fun isAudio(file: DocumentFile): Boolean {
        val mime = file.type.orEmpty()
        val n = file.name.orEmpty().lowercase(Locale.getDefault())
        return mime.startsWith("audio/") || listOf(".mp3",".flac",".m4a",".aac",".wav",".ogg",".opus").any(n::endsWith)
    }

    private fun createFromUri(uri: Uri, fallbackName: String?): TrackEntity {
        var title = fallbackName?.substringBeforeLast('.') ?: "שיר מיובא"
        var artist = "אמן לא ידוע"
        var album = "אלבום לא ידוע"
        var duration = 0L
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { title = it }
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { artist = it }
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { album = it }
            duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
        } finally { runCatching { retriever.release() } }
        return TrackEntity(uri.toString(), title, artist, album, "מיובא", duration, System.currentTimeMillis())
    }

    fun toggleFavorite(track: TrackEntity) {
        viewModelScope.launch(Dispatchers.IO) { trackDao.favorite(track.uri, !track.favorite) }
    }

    fun setRating(track: TrackEntity, rating: Int) {
        viewModelScope.launch(Dispatchers.IO) { trackDao.rating(track.uri, rating.coerceIn(0, 5)) }
    }

    fun registerPlay(track: TrackEntity) {
        viewModelScope.launch(Dispatchers.IO) { trackDao.played(track.uri, System.currentTimeMillis()) }
    }

    fun registerSkip(track: TrackEntity) {
        viewModelScope.launch(Dispatchers.IO) { trackDao.skipped(track.uri) }
    }

    fun addListenTime(track: TrackEntity, delta: Long) {
        viewModelScope.launch(Dispatchers.IO) { trackDao.listen(track.uri, delta.coerceAtLeast(0)) }
    }

    fun saveQueue(name: String, uris: List<String>, currentIndex: Int, positionMs: Long, shuffle: Boolean, history: List<String>) {
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            queueDao.save(
                QueueEntity(
                    name.trim(),
                    Gson().toJson(uris),
                    currentIndex,
                    positionMs,
                    shuffle,
                    Gson().toJson(history)
                )
            )
        }
    }

    fun createPlaylist(name: String, initial: TrackEntity? = null) {
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            playlistDao.create(PlaylistEntity(name.trim()))
            if (initial != null) {
                playlistDao.add(PlaylistTrackEntity(name.trim(), initial.uri, 0))
            }
        }
    }

    fun addToPlaylist(name: String, track: TrackEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = playlistDao.tracks(name)
            if (current.none { it.trackUri == track.uri }) {
                playlistDao.add(PlaylistTrackEntity(name, track.uri, current.size))
            }
        }
    }

    fun removeFromPlaylist(name: String, track: TrackEntity) {
        viewModelScope.launch(Dispatchers.IO) { playlistDao.remove(name, track.uri) }
    }

    fun deletePlaylist(name: String) {
        viewModelScope.launch(Dispatchers.IO) { playlistDao.delete(name) }
    }

    fun tracksForPlaylist(playlist: PlaylistEntity): Flow<List<TrackEntity>> {
        return if (playlist.smartType.isBlank()) {
            flow {
                val links = playlistDao.tracks(playlist.name).map { it.trackUri }.toSet()
                emit(tracks.first().filter { it.uri in links })
            }
        } else {
            when (playlist.smartType) {
                "favorites" -> favorites
                "top" -> topTracks
                "rediscover" -> tracks.map { list -> list.filter { it.playCount in 1..3 }.sortedByDescending { it.lastPlayedAt }.take(30) }
                "hidden" -> tracks.map { list -> list.filter { !it.favorite && it.playCount in 0..2 }.sortedBy { it.playCount }.take(30) }
                else -> tracks.map { list -> list.sortedByDescending { it.dateAdded }.take(30) }
            }
        }
    }

    suspend fun backupJson(): String = withContext(Dispatchers.IO) {
        val allPlaylists = playlistDao.all().first()
        val links = allPlaylists.flatMap { playlistDao.tracks(it.name) }
        Gson().toJson(BackupPayload(tracks.first(), allPlaylists, links, queues.first(), settings.first()))
    }

    fun restoreJson(json: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val type = object : TypeToken<BackupPayload>() {}.type
            val payload: BackupPayload = Gson().fromJson(json, type)
            trackDao.upsertAll(payload.tracks)
            payload.playlists.forEach { playlistDao.create(it) }
            payload.playlistTracks.forEach { playlistDao.add(it) }
            payload.queues.forEach { queueDao.save(it) }
            payload.settings.forEach { settingsDao.set(it) }
        }
    }

    fun setSetting(key: String, value: String) {
        viewModelScope.launch(Dispatchers.IO) { settingsDao.set(SettingEntity(key, value)) }
    }
}