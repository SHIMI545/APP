package com.aureon.music

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Track(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String
)

data class Playlist(
    val name: String,
    val trackUris: MutableList<String> = mutableListOf()
)

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("aureon_prefs", Context.MODE_PRIVATE) }
    private val gson = Gson()

    private var tracks by mutableStateOf<List<Track>>(emptyList())
    private var favoriteUris by mutableStateOf<Set<String>>(emptySet())
    private var playlists by mutableStateOf<List<Playlist>>(emptyList())
    private var currentTrack by mutableStateOf<Track?>(null)
    private var isPlaying by mutableStateOf(false)
    private var shuffleEnabled by mutableStateOf(false)
    private var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    private var queue by mutableStateOf<List<Track>>(emptyList())
    private var queueVisible by mutableStateOf(false)
    private var playlistPickerTrack by mutableStateOf<Track?>(null)
    private var createPlaylistVisible by mutableStateOf(false)
    private var createPlaylistForTrack by mutableStateOf<Track?>(null)
    private var playlistDetails by mutableStateOf<Playlist?>(null)

    private var controller: MediaController? = null

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        lifecycleScope.launch(Dispatchers.IO) {
            val imported = uris.map { buildTrackFromUri(it) }
            withContext(Dispatchers.Main) { mergeTracks(imported) }
        }
    }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val imported = collectAudioFiles(DocumentFile.fromTreeUri(this@MainActivity, uri))
                    .map { buildTrackFromDocument(it) }
                withContext(Dispatchers.Main) { mergeTracks(imported) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreState()
        requestAudioPermission()

        val token = SessionToken(
            this,
            android.content.ComponentName(this, PlaybackService::class.java)
        )
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener(
            {
                controller = future.get()
                controller?.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        val uri = mediaItem?.localConfiguration?.uri?.toString()
                        currentTrack = tracks.firstOrNull { it.uri == uri }
                        if (currentTrack == null && mediaItem != null) {
                            currentTrack = Track(
                                uri ?: "",
                                mediaItem.mediaMetadata.title?.toString() ?: "Unknown",
                                mediaItem.mediaMetadata.artist?.toString() ?: "Unknown artist",
                                mediaItem.mediaMetadata.albumTitle?.toString() ?: "Unknown album"
                            )
                        }
                    }

                    override fun onShuffleModeEnabledChanged(shuffle: Boolean) {
                        shuffleEnabled = shuffle
                    }

                    override fun onRepeatModeChanged(mode: Int) {
                        repeatMode = mode
                    }
                })
                isPlaying = controller?.isPlaying == true
                shuffleEnabled = controller?.shuffleModeEnabled == true
                repeatMode = controller?.repeatMode ?: Player.REPEAT_MODE_OFF
            },
            MoreExecutors.directExecutor()
        )

        setContent {
            AureonApp(
                tracks = tracks,
                favorites = favoriteUris,
                playlists = playlists,
                currentTrack = currentTrack,
                isPlaying = isPlaying,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                queue = queue,
                queueVisible = queueVisible,
                playlistPickerTrack = playlistPickerTrack,
                createPlaylistVisible = createPlaylistVisible,
                playlistDetails = playlistDetails,
                onImportFiles = { filePicker.launch(arrayOf("audio/*")) },
                onImportFolder = { folderPicker.launch(null) },
                onRefresh = { scanDevice() },
                onPlay = { playTrack(it, tracks) },
                onPlayList = { list, index -> playTrack(list[index], list) },
                onToggleFavorite = { toggleFavorite(it) },
                onAddToQueue = { addToQueue(it) },
                onToggleShuffle = { toggleShuffle() },
                onCycleRepeat = { cycleRepeat() },
                onPlayPause = { togglePlayback() },
                onNext = { controller?.seekToNextMediaItem() },
                onOpenQueue = { queueVisible = true },
                onCloseQueue = { queueVisible = false },
                onClearQueue = { queue = emptyList(); controller?.clearMediaItems() },
                onCreatePlaylist = { name, track ->
                    createPlaylist(name, track)
                    createPlaylistVisible = false
                    createPlaylistForTrack = null
                },
                onOpenPlaylistPicker = { playlistPickerTrack = it },
                onClosePlaylistPicker = { playlistPickerTrack = null },
                onAddTrackToPlaylist = { playlistName, track -> addTrackToPlaylist(playlistName, track) },
                onNewPlaylistForTrack = {
                    createPlaylistForTrack = playlistPickerTrack
                    playlistPickerTrack = null
                    createPlaylistVisible = true
                },
                onOpenPlaylist = { playlistDetails = it },
                onClosePlaylist = { playlistDetails = null },
                onDeletePlaylist = { deletePlaylist(it) },
                onRemoveFromPlaylist = { p, t -> removeFromPlaylist(p, t) }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasAudioPermission()) scanDevice()
    }

    private fun hasAudioPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestAudioPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (!hasAudioPermission()) requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_AUDIO), 10)
            else scanDevice()
        } else {
            if (!hasAudioPermission()) requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 10)
            else scanDevice()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            scanDevice()
        }
    }

    private fun restoreState() {
        favoriteUris = prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()
        val type = object : TypeToken<List<Playlist>>() {}.type
        playlists = runCatching {
            gson.fromJson<List<Playlist>>(prefs.getString("playlists", "[]"), type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun persistState() {
        prefs.edit()
            .putStringSet("favorites", favoriteUris)
            .putString("playlists", gson.toJson(playlists))
            .apply()
    }

    private fun toggleFavorite(track: Track) {
        favoriteUris = if (track.uri in favoriteUris) favoriteUris - track.uri else favoriteUris + track.uri
        persistState()
    }

    private fun createPlaylist(name: String, initialTrack: Track?) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        if (playlists.any { it.name.equals(clean, ignoreCase = true) }) return
        val p = Playlist(clean)
        if (initialTrack != null) p.trackUris.add(initialTrack.uri)
        playlists = playlists + p
        persistState()
    }

    private fun addTrackToPlaylist(name: String, track: Track) {
        playlists = playlists.map {
            if (it.name == name && track.uri !in it.trackUris) {
                it.copy(trackUris = (it.trackUris + track.uri).toMutableList())
            } else it
        }
        persistState()
    }

    private fun removeFromPlaylist(playlist: Playlist, track: Track) {
        playlists = playlists.map {
            if (it.name == playlist.name) {
                it.copy(trackUris = it.trackUris.filterNot { u -> u == track.uri }.toMutableList())
            } else it
        }
        playlistDetails = playlists.firstOrNull { it.name == playlist.name }
        persistState()
    }

    private fun deletePlaylist(playlist: Playlist) {
        playlists = playlists.filterNot { it.name == playlist.name }
        playlistDetails = null
        persistState()
    }

    private fun scanDevice() {
        lifecycleScope.launch(Dispatchers.IO) {
            val output = mutableListOf<Track>()
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM
            )
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC}=1",
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                while (cursor.moveToNext()) {
                    output += Track(
                        ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            cursor.getLong(id)
                        ).toString(),
                        cursor.getString(title).orEmpty().ifBlank { "Unknown song" },
                        cursor.getString(artist).orEmpty().ifBlank { "Unknown artist" },
                        cursor.getString(album).orEmpty().ifBlank { "Unknown album" }
                    )
                }
            }
            withContext(Dispatchers.Main) { mergeTracks(output, replace = true) }
        }
    }

    private fun mergeTracks(incoming: List<Track>, replace: Boolean = false) {
        val combined = if (replace) incoming else tracks + incoming
        tracks = combined.distinctBy { it.uri }.sortedBy { it.title.lowercase() }
    }

    private fun buildTrackFromUri(uri: Uri): Track {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        return extractMetadata(uri, DocumentFile.fromSingleUri(this, uri)?.name)
    }

    private fun buildTrackFromDocument(doc: DocumentFile): Track =
        extractMetadata(doc.uri, doc.name)

    private fun extractMetadata(uri: Uri, fallbackName: String?): Track {
        val fallback = fallbackName?.substringBeforeLast(".")?.ifBlank { null } ?: "Imported song"
        var title = fallback
        var artist = "Unknown artist"
        var album = "Imported"
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { title = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { artist = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { album = it }
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }
        return Track(uri.toString(), title, artist, album)
    }

    private fun collectAudioFiles(folder: DocumentFile?): List<DocumentFile> {
        if (folder == null) return emptyList()
        val result = mutableListOf<DocumentFile>()
        for (file in folder.listFiles()) {
            if (file.isDirectory) {
                result += collectAudioFiles(file)
            } else if (file.isFile && isAudio(file)) {
                result += file
            }
        }
        return result
    }

    private fun isAudio(file: DocumentFile): Boolean {
        val mime = file.type.orEmpty()
        val name = file.name.orEmpty().lowercase()
        return mime.startsWith("audio/") || name.endsWith(".mp3") || name.endsWith(".flac") ||
            name.endsWith(".m4a") || name.endsWith(".aac") || name.endsWith(".wav") ||
            name.endsWith(".ogg") || name.endsWith(".opus")
    }

    private fun toMediaItem(track: Track): MediaItem =
        MediaItem.Builder()
            .setUri(track.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .build()
            )
            .build()

    private fun playTrack(track: Track, list: List<Track>) {
        if (list.isEmpty()) return
        val startIndex = list.indexOfFirst { it.uri == track.uri }.coerceAtLeast(0)
        queue = list
        controller?.apply {
            setMediaItems(list.map(::toMediaItem), startIndex, 0L)
            shuffleModeEnabled = shuffleEnabled
            repeatMode = repeatMode
            prepare()
            play()
        }
    }

    private fun addToQueue(track: Track) {
        queue = (queue + track).distinctBy { it.uri }
        controller?.addMediaItem(toMediaItem(track))
    }

    private fun toggleShuffle() {
        val value = !(controller?.shuffleModeEnabled ?: shuffleEnabled)
        shuffleEnabled = value
        controller?.shuffleModeEnabled = value
    }

    private fun cycleRepeat() {
        val next = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        repeatMode = next
        controller?.repeatMode = next
    }

    private fun togglePlayback() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    override fun onDestroy() {
        controller?.release()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AureonApp(
    tracks: List<Track>,
    favorites: Set<String>,
    playlists: List<Playlist>,
    currentTrack: Track?,
    isPlaying: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    queue: List<Track>,
    queueVisible: Boolean,
    playlistPickerTrack: Track?,
    createPlaylistVisible: Boolean,
    playlistDetails: Playlist?,
    onImportFiles: () -> Unit,
    onImportFolder: () -> Unit,
    onRefresh: () -> Unit,
    onPlay: (Track) -> Unit,
    onPlayList: (List<Track>, Int) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onOpenQueue: () -> Unit,
    onCloseQueue: () -> Unit,
    onClearQueue: () -> Unit,
    onCreatePlaylist: (String, Track?) -> Unit,
    onOpenPlaylistPicker: (Track) -> Unit,
    onClosePlaylistPicker: () -> Unit,
    onAddTrackToPlaylist: (String, Track) -> Unit,
    onNewPlaylistForTrack: () -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onClosePlaylist: () -> Unit,
    onDeletePlaylist: (Playlist) -> Unit,
    onRemoveFromPlaylist: (Playlist, Track) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }

    val filtered = remember(tracks, query) {
        tracks.filter {
            it.title.contains(query, true) ||
                it.artist.contains(query, true) ||
                it.album.contains(query, true)
        }
    }
    val favoriteTracks = remember(tracks, favorites, query) {
        tracks.filter { it.uri in favorites && (
            it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true)
        )}
    }

    val scheme = darkColorScheme(
        primary = Color(0xFFD6B36A),
        onPrimary = Color(0xFF121218),
        background = Color(0xFF08090D),
        surface = Color(0xFF11141B),
        surfaceVariant = Color(0xFF1A1E27),
        onSurface = Color(0xFFF5F2EA),
        onSurfaceVariant = Color(0xFFA9ADB8)
    )

    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            containerColor = scheme.background,
            bottomBar = {
                Column(Modifier.navigationBarsPadding()) {
                    if (currentTrack != null) {
                        MiniPlayer(
                            track = currentTrack,
                            isPlaying = isPlaying,
                            onPlayPause = onPlayPause,
                            onNext = onNext,
                            onQueue = onOpenQueue
                        )
                    }
                    NavigationBar(containerColor = Color(0xFF0D1016)) {
                        listOf(
                            Triple("Library", Icons.Default.LibraryMusic, 0),
                            Triple("Favorites", Icons.Default.FavoriteBorder, 1),
                            Triple("Playlists", Icons.Default.QueueMusic, 2)
                        ).forEach { (label, icon, index) ->
                            NavigationBarItem(
                                selected = tab == index,
                                onClick = { tab = index },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 18.dp)
                ) {
                    Spacer(Modifier.height(18.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "AUREON",
                                letterSpacing = 4.sp,
                                fontSize = 12.sp,
                                color = scheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Your music, offline.",
                                fontSize = 27.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${tracks.size} songs on this device",
                                fontSize = 12.sp,
                                color = scheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, "Refresh library")
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        placeholder = { Text("Search songs, artists or albums") },
                        leadingIcon = { Icon(Icons.Default.Search, null) }
                    )

                    Spacer(Modifier.height(11.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = onImportFiles,
                            label = { Text("Files") },
                            leadingIcon = { Icon(Icons.Default.FileOpen, null) }
                        )
                        AssistChip(
                            onClick = onImportFolder,
                            label = { Text("Folder") },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, null) }
                        )
                        AssistChip(
                            onClick = onOpenQueue,
                            label = { Text("Queue ${queue.size}") },
                            leadingIcon = { Icon(Icons.Default.QueueMusic, null) }
                        )
                    }

                    Spacer(Modifier.height(15.dp))
                    when (tab) {
                        0 -> SongList(
                            list = filtered,
                            favorites = favorites,
                            onPlay = onPlay,
                            onFavorite = onToggleFavorite,
                            onAddToQueue = onAddToQueue,
                            onOpenPlaylistPicker = onOpenPlaylistPicker
                        )
                        1 -> SongList(
                            list = favoriteTracks,
                            favorites = favorites,
                            title = "Favorites",
                            onPlay = onPlay,
                            onFavorite = onToggleFavorite,
                            onAddToQueue = onAddToQueue,
                            onOpenPlaylistPicker = onOpenPlaylistPicker
                        )
                        else -> PlaylistList(
                            playlists = playlists,
                            tracks = tracks,
                            onOpen = onOpenPlaylist,
                            onCreate = { onCreatePlaylist(it, null) }
                        )
                    }
                }

                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (tab == 0 && filtered.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = Color(0xFF151922)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = onToggleShuffle) {
                                    Icon(
                                        Icons.Default.Shuffle,
                                        "Shuffle",
                                        tint = if (shuffleEnabled) scheme.primary else scheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = onCycleRepeat) {
                                    Icon(
                                        Icons.Default.Repeat,
                                        "Repeat",
                                        tint = if (repeatMode != Player.REPEAT_MODE_OFF) scheme.primary else scheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (queueVisible) {
            QueueDialog(
                queue = queue,
                current = currentTrack,
                onClose = onCloseQueue,
                onClear = onClearQueue,
                onPlay = onPlay
            )
        }

        if (playlistPickerTrack != null) {
            PlaylistPickerDialog(
                playlists = playlists,
                track = playlistPickerTrack,
                onClose = onClosePlaylistPicker,
                onChoose = { name ->
                    onAddTrackToPlaylist(name, playlistPickerTrack)
                    onClosePlaylistPicker()
                },
                onNew = onNewPlaylistForTrack
            )
        }

        if (createPlaylistVisible) {
            CreatePlaylistDialog(
                initialTrack = createPlaylistForTrack,
                onDismiss = { onCreatePlaylist("", null) },
                onCreate = { name -> onCreatePlaylist(name, createPlaylistForTrack) }
            )
        }

        if (playlistDetails != null) {
            PlaylistDetailDialog(
                playlist = playlistDetails,
                tracks = tracks,
                onClose = onClosePlaylist,
                onDelete = { onDeletePlaylist(playlistDetails) },
                onPlay = onPlayList,
                onRemove = onRemoveFromPlaylist
            )
        }
    }
}

@Composable
private fun SongList(
    list: List<Track>,
    favorites: Set<String>,
    title: String? = null,
    onPlay: (Track) -> Unit,
    onFavorite: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onOpenPlaylistPicker: (Track) -> Unit
) {
    if (title != null) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
    }
    Text(
        "${list.size} songs",
        color = Color(0xFF8F94A0),
        fontSize = 12.sp
    )
    Spacer(Modifier.height(4.dp))

    if (list.isEmpty()) {
        EmptyState("No music here yet", "Import audio files or a folder to get started.")
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(7.dp),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            items(list, key = { it.uri }) { track ->
                TrackRow(
                    track = track,
                    favorite = track.uri in favorites,
                    onPlay = { onPlay(track) },
                    onFavorite = { onFavorite(track) },
                    onAddToQueue = { onAddToQueue(track) },
                    onAddToPlaylist = { onOpenPlaylistPicker(track) }
                )
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    favorite: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(17.dp))
            .background(Color(0xFF11141B))
            .clickable(onClick = onPlay)
            .padding(11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF292D39), Color(0xFF151821))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.MusicNote, null, tint = Color(0xFFD6B36A))
        }

        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, fontWeight = FontWeight.Medium)
            Text(
                "${track.artist} • ${track.album}",
                maxLines = 1,
                fontSize = 12.sp,
                color = Color(0xFF8F94A0)
            )
        }

        IconButton(onClick = onFavorite) {
            Icon(
                if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                null,
                tint = if (favorite) Color(0xFFD6B36A) else Color(0xFF8F94A0)
            )
        }

        Box {
            IconButton(onClick = { menuOpen = !menuOpen }) {
                Icon(Icons.Default.MoreVert, null)
            }
            if (menuOpen) {
                Surface(
                    Modifier.fillMaxHeight().width(170.dp),
                    color = Color(0xFF1C2029),
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column {
                        TextButton(onClick = { menuOpen = false; onAddToQueue() }) {
                            Icon(Icons.Default.QueueMusic, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add to queue")
                        }
                        TextButton(onClick = { menuOpen = false; onAddToPlaylist() }) {
                            Icon(Icons.Default.AddCircleOutline, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add to playlist")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistList(
    playlists: List<Playlist>,
    tracks: List<Track>,
    onOpen: (Playlist) -> Unit,
    onCreate: (String) -> Unit
) {
    var creating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxHeight()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Playlists", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("${playlists.size} playlists", color = Color(0xFF8F94A0), fontSize = 12.sp)
            }
            FloatingActionButton(
                onClick = { creating = true },
                containerColor = Color(0xFFD6B36A),
                contentColor = Color(0xFF101117)
            ) {
                Icon(Icons.Default.Add, null)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (playlists.isEmpty()) {
            EmptyState("Create your first playlist", "Keep favorite mixes ready for offline listening.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(playlists, key = { it.name }) { playlist ->
                    val count = playlist.trackUris.count { u -> tracks.any { it.uri == u } }
                    Card(
                        Modifier.fillMaxWidth().clickable { onOpen(playlist) },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF11141B)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(48.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFF242833)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.QueueMusic, null, tint = Color(0xFFD6B36A))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(playlist.name, fontWeight = FontWeight.Medium)
                                Text("$count songs", color = Color(0xFF8F94A0), fontSize = 12.sp)
                            }
                            Icon(Icons.Default.ArrowBack, null)
                        }
                    }
                }
            }
        }
    }
    if (creating) {
        CreatePlaylistDialog(
            initialTrack = null,
            onDismiss = { creating = false },
            onCreate = { name -> creating = false; onCreate(name) }
        )
    }
}

@Composable
private fun MiniPlayer(
    track: Track,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onQueue: () -> Unit
) {
    Surface(
        Modifier.fillMaxWidth(),
        color = Color(0xFF151922)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF242833)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.MusicNote, null, tint = Color(0xFFD6B36A))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, maxLines = 1, fontWeight = FontWeight.Medium)
                Text(track.artist, maxLines = 1, color = Color(0xFF8F94A0), fontSize = 11.sp)
            }
            IconButton(onClick = onQueue) { Icon(Icons.Default.QueueMusic, null) }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, null) }
            IconButton(onClick = onPlayPause) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null)
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 42.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.LibraryMusic, null, Modifier.size(44.dp), tint = Color(0xFF4E5360))
        Spacer(Modifier.height(12.dp))
        Text(title, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        Text(subtitle, color = Color(0xFF8F94A0), fontSize = 12.sp)
    }
}

@Composable
private fun QueueDialog(
    queue: List<Track>,
    current: Track?,
    onClose: () -> Unit,
    onClear: () -> Unit,
    onPlay: (Track) -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Queue") },
        text = {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${queue.size} tracks", Modifier.weight(1f), color = Color(0xFF8F94A0))
                    TextButton(onClick = onClear) { Text("Clear") }
                }
                Spacer(Modifier.height(4.dp))
                LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                    items(queue, key = { it.uri }) { track ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPlay(track) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.MusicNote,
                                null,
                                tint = if (track.uri == current?.uri) Color(0xFFD6B36A) else Color(0xFF808692)
                            )
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(track.title, maxLines = 1)
                                Text(track.artist, maxLines = 1, color = Color(0xFF8F94A0), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

@Composable
private fun PlaylistPickerDialog(
    playlists: List<Playlist>,
    track: Track,
    onClose: () -> Unit,
    onChoose: (String) -> Unit,
    onNew: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Add to playlist") },
        text = {
            Column {
                if (playlists.isEmpty()) {
                    Text("No playlists yet.", color = Color(0xFF8F94A0))
                } else {
                    playlists.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onChoose(p.name) }.padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.QueueMusic, null, tint = Color(0xFFD6B36A))
                            Spacer(Modifier.width(10.dp))
                            Text(p.name, Modifier.weight(1f))
                            if (track.uri in p.trackUris) Icon(Icons.Default.Check, null)
                        }
                    }
                }
                Spacer(Modifier.height(7.dp))
                OutlinedButton(onClick = onNew) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(7.dp))
                    Text("New playlist")
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

@Composable
private fun CreatePlaylistDialog(
    initialTrack: Track?,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Playlist name") },
                placeholder = { Text("e.g. Evening mix") }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name.trim()) }
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PlaylistDetailDialog(
    playlist: Playlist,
    tracks: List<Track>,
    onClose: () -> Unit,
    onDelete: (Playlist) -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onRemove: (Playlist, Track) -> Unit
) {
    val list = playlist.trackUris.mapNotNull { uri -> tracks.firstOrNull { it.uri == uri } }
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Column {
                Text(playlist.name)
                Text("${list.size} songs", color = Color(0xFF8F94A0), fontSize = 12.sp)
            }
        },
        text = {
            Column {
                if (list.isEmpty()) {
                    EmptyState("Playlist is empty", "Add songs from your library.")
                } else {
                    LazyColumn(Modifier.fillMaxWidth().height(330.dp)) {
                        items(list, key = { it.uri }) { track ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f).clickable { onPlay(list, list.indexOf(track)) }) {
                                    Text(track.title, maxLines = 1)
                                    Text(track.artist, color = Color(0xFF8F94A0), fontSize = 11.sp, maxLines = 1)
                                }
                                IconButton(onClick = { onRemove(playlist, track) }) {
                                    Icon(Icons.Default.DeleteOutline, "Remove")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onDelete(playlist) }) {
                    Text("Delete")
                }
                TextButton(onClick = onClose) {
                    Text("Close")
                }
            }
        }
    )
}
