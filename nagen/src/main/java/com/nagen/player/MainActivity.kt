package com.nagen.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.nagen.player.data.PlaylistEntity
import com.nagen.player.data.QueueEntity
import com.nagen.player.data.TrackEntity
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private val Gold = Color(0xFFD7B36A)
private val Bg = Color(0xFF08090D)
private val Panel = Color(0xFF11141B)
private val Muted = Color(0xFF8E94A1)

class MainActivity : ComponentActivity() {
    private var controller: MediaController? = null
    private var currentUri by mutableStateOf<String?>(null)
    private var playing by mutableStateOf(false)
    private var currentIndex by mutableIntStateOf(0)
    private var positionMs by mutableLongStateOf(0L)
    private var queueUris by mutableStateOf<List<String>>(emptyList())

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) viewModelForLaunch?.scanDevice()
    }
    private var viewModelForLaunch: PlayerViewModel? = null

    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModelForLaunch?.importUris(uris)
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModelForLaunch?.importFolder(uri)
        }
    }
    private val backupCreate = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) writeBackup(uri)
    }
    private val backupOpen = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readBackup(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = SessionToken(this, android.content.ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            controller = future.get()
            controller?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    currentUri = mediaItem?.localConfiguration?.uri?.toString()
                    currentIndex = controller?.currentMediaItemIndex ?: 0
                }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    positionMs = newPosition.positionMs
                    currentIndex = newPosition.mediaItemIndex
                }
                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {}
            })
            queueUris = (0 until (controller?.mediaItemCount ?: 0)).mapNotNull { controller?.getMediaItemAt(it)?.localConfiguration?.uri?.toString() }
            playing = controller?.isPlaying == true
            currentUri = controller?.currentMediaItem?.localConfiguration?.uri?.toString()
        }, MoreExecutors.directExecutor())

        setContent {
            val vm: PlayerViewModel = hiltViewModel()
            viewModelForLaunch = vm
            NagenApp(
                vm = vm,
                controller = controller,
                currentUri = currentUri,
                playing = playing,
                currentIndex = currentIndex,
                positionMs = positionMs,
                queueUris = queueUris,
                onPickFiles = { audioPicker.launch(arrayOf("audio/*")) },
                onPickFolder = { folderPicker.launch(null) },
                onCreateBackup = { backupCreate.launch("nagen-backup.json") },
                onRestoreBackup = { backupOpen.launch(arrayOf("application/json", "text/*")) },
                onPlay = { list, index -> playList(list, index, vm) },
                onTogglePlay = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                onNext = { controller?.seekToNextMediaItem() },
                onPrevious = { controller?.seekToPreviousMediaItem() },
                onSaveQueue = { name -> saveQueue(name, vm) },
                onLoadQueue = { queue -> loadQueue(queue, vm) }
            )
            LaunchedEffect(Unit) {
                while (true) {
                    positionMs = controller?.currentPosition ?: 0L
                    delay(500)
                }
            }
        }
    }

    private fun playList(list: List<TrackEntity>, index: Int, vm: PlayerViewModel) {
        if (list.isEmpty() || controller == null) return
        val items = list.map { item ->
            MediaItem.Builder().setUri(item.uri).setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(item.title).setArtist(item.artist).setAlbumTitle(item.album).build()
            ).build()
        }
        queueUris = list.map { it.uri }
        controller!!.setMediaItems(items, index.coerceIn(list.indices), 0L)
        controller!!.prepare()
        controller!!.play()
        vm.registerPlay(list[index.coerceIn(list.indices)])
    }

    private fun saveQueue(name: String, vm: PlayerViewModel) {
        vm.saveQueue(
            name = name,
            uris = queueUris,
            currentIndex = currentIndex,
            positionMs = positionMs,
            shuffle = controller?.shuffleModeEnabled == true,
            history = emptyList()
        )
    }

    private fun loadQueue(queue: QueueEntity, vm: PlayerViewModel) {
        val all = vm.tracks.value
        val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
        val uris: List<String> = runCatching {
            com.google.gson.Gson().fromJson<List<String>>(queue.orderJson, type) ?: emptyList()
        }.getOrDefault(emptyList())
        val tracks = uris.mapNotNull { u -> all.firstOrNull { it.uri == u } }
        if (tracks.isNotEmpty()) playList(tracks, queue.currentIndex.coerceIn(tracks.indices), vm)
        controller?.shuffleModeEnabled = queue.shuffle
        controller?.seekTo(queue.positionMs.coerceAtLeast(0L))
    }

    private fun writeBackup(uri: Uri) {
        val vm = viewModelForLaunch ?: return
        lifecycleScope.launch {
            runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(vm.backupJson().toByteArray(Charsets.UTF_8)) }
            }
        }
    }

    private fun readBackup(uri: Uri) {
        val vm = viewModelForLaunch ?: return
        lifecycleScope.launch {
            runCatching {
                val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@runCatching
                vm.restoreJson(json)
            }
        }
    }

    override fun onDestroy() {
        controller?.release()
        super.onDestroy()
    }
}

private enum class Nav { HOME, LIBRARY, QUEUES, FOR_YOU, SETTINGS }

@Composable
private fun NagenApp(
    vm: PlayerViewModel,
    controller: MediaController?,
    currentUri: String?,
    playing: Boolean,
    currentIndex: Int,
    positionMs: Long,
    queueUris: List<String>,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onPlay: (List<TrackEntity>, Int) -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSaveQueue: (String) -> Unit,
    onLoadQueue: (QueueEntity) -> Unit
) {
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val top by vm.topTracks.collectAsStateWithLifecycle()
    val recent by vm.recentTracks.collectAsStateWithLifecycle()
    val resumed by vm.resumedTracks.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val queues by vm.queues.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var nav by remember { mutableStateOf(Nav.HOME) }
    var search by remember { mutableStateOf("") }
    var playerOpen by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
    var libraryTab by remember { mutableIntStateOf(0) }
    var selectedPlaylist by remember { mutableStateOf<PlaylistEntity?>(null) }
    var playlistDialogTrack by remember { mutableStateOf<TrackEntity?>(null) }
    var createPlaylist by remember { mutableStateOf(false) }
    var saveQueueDialog by remember { mutableStateOf(false) }
    var lightMode by remember { mutableStateOf(settings.firstOrNull { it.key == "theme" }?.value == "light") }

    LaunchedEffect(settings) {
        lightMode = settings.firstOrNull { it.key == "theme" }?.value == "light"
        libraryTab = settings.firstOrNull { it.key == "library_default_tab" }?.value?.toIntOrNull()?.coerceIn(0, 4) ?: libraryTab
    }

    val current = tracks.firstOrNull { it.uri == currentUri }
    val displayed = remember(tracks, search) {
        if (search.isBlank()) tracks else tracks.filter {
            it.title.contains(search, true) || it.artist.contains(search, true) ||
                it.album.contains(search, true) || it.folder.contains(search, true)
        }
    }

    val colors = if (lightMode) lightColorScheme(primary = Color(0xFF8C681F)) else darkColorScheme(
        primary = Gold, onPrimary = Color(0xFF15120D), background = Bg, surface = Panel,
        surfaceVariant = Color(0xFF1B1F28), onSurface = Color(0xFFF4F1E8), onSurfaceVariant = Muted
    )

    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            Scaffold(
                containerColor = colors.background,
                bottomBar = {
                    Column(Modifier.navigationBarsPadding()) {
                        if (current != null && !playerOpen) {
                            MiniPlayer(
                                current = current,
                                playing = playing,
                                onOpen = { playerOpen = true },
                                onToggle = onTogglePlay,
                                onNext = onNext
                            )
                        }
                        NavigationBar(containerColor = if (lightMode) Color(0xFFF7F3EA) else Color(0xFF0D1016)) {
                            val items = listOf(
                                Nav.HOME to Pair("בית", Icons.Default.Home),
                                Nav.LIBRARY to Pair("ספרייה", Icons.Default.LibraryMusic),
                                Nav.QUEUES to Pair("תורים", Icons.Default.QueueMusic),
                                Nav.FOR_YOU to Pair("בשבילך", Icons.Default.AutoAwesome),
                                Nav.SETTINGS to Pair("הגדרות", Icons.Default.Settings)
                            )
                            items.forEach { (id, pair) ->
                                NavigationBarItem(
                                    selected = nav == id,
                                    onClick = { nav = id },
                                    icon = { Icon(pair.second, pair.first) },
                                    label = { Text(pair.first) }
                                )
                            }
                        }
                    }
                }
            ) { pad ->
                Box(Modifier.fillMaxSize().padding(pad)) {
                    Column(Modifier.fillMaxSize()) {
                        TopHeader(
                            title = when (nav) {
                                Nav.HOME -> "דף הבית"
                                Nav.LIBRARY -> "ספרייה"
                                Nav.QUEUES -> "תורים"
                                Nav.FOR_YOU -> "בשבילך"
                                Nav.SETTINGS -> "הגדרות"
                            },
                            search = search,
                            onSearchChange = { search = it },
                            showSearch = nav != Nav.SETTINGS,
                            onPickFiles = onPickFiles,
                            onPickFolder = onPickFolder
                        )

                        when (nav) {
                            Nav.HOME -> HomeScreen(
                                tracks = displayed,
                                favorites = favorites,
                                recent = recent,
                                resumed = resumed,
                                onPlay = onPlay,
                                onFavorite = vm::toggleFavorite,
                                onPlaylist = { playlistDialogTrack = it }
                            )
                            Nav.LIBRARY -> LibraryScreen(
                                tracks = displayed,
                                libraryTab = libraryTab,
                                onTab = { libraryTab = it },
                                onPlay = onPlay,
                                onFavorite = vm::toggleFavorite,
                                onPlaylist = { playlistDialogTrack = it }
                            )
                            Nav.QUEUES -> QueuesScreen(
                                queues = queues,
                                currentCount = queueUris.size,
                                onSave = { saveQueueDialog = true },
                                onLoad = { onLoadQueue(it) }
                            )
                            Nav.FOR_YOU -> ForYouScreen(
                                top = top,
                                favorites = favorites,
                                recent = recent,
                                tracks = tracks,
                                onPlay = onPlay,
                                onFavorite = vm::toggleFavorite
                            )
                            Nav.SETTINGS -> SettingsScreen(
                                lightMode = lightMode,
                                onToggleTheme = {
                                    lightMode = !lightMode
                                    vm.setSetting("theme", if (lightMode) "light" else "dark")
                                },
                                libraryTab = libraryTab,
                                onLibraryTab = { libraryTab = it; vm.setSetting("library_default_tab", it.toString()) },
                                onCreateBackup = onCreateBackup,
                                onRestoreBackup = onRestoreBackup
                            )
                        }
                    }

                    if (current != null && playerOpen) {
                        FullPlayer(
                            track = current,
                            allTracks = tracks,
                            playing = playing,
                            positionMs = positionMs,
                            controller = controller,
                            sheetOpen = sheetOpen,
                            onSheet = { sheetOpen = true },
                            onClose = { playerOpen = false },
                            onToggle = onTogglePlay,
                            onNext = onNext,
                            onPrevious = onPrevious,
                            favorite = current.favorite,
                            onFavorite = { vm.toggleFavorite(current) },
                            onRating = { vm.setRating(current, it) },
                            onPlaylist = { playlistDialogTrack = current },
                            onPlay = onPlay
                        )
                    }
                }
            }
        }

        if (playlistDialogTrack != null) {
            PlaylistPicker(
                track = playlistDialogTrack!!,
                playlists = playlists,
                onDismiss = { playlistDialogTrack = null },
                onAdd = { name -> vm.addToPlaylist(name, playlistDialogTrack!!); playlistDialogTrack = null },
                onNew = { createPlaylist = true }
            )
        }

        if (createPlaylist) {
            CreatePlaylistDialog(
                onDismiss = { createPlaylist = false },
                onCreate = { name ->
                    vm.createPlaylist(name, playlistDialogTrack)
                    createPlaylist = false
                    playlistDialogTrack = null
                }
            )
        }

        if (saveQueueDialog) {
            SaveQueueDialog(
                onDismiss = { saveQueueDialog = false },
                onSave = { name -> onSaveQueue(name); saveQueueDialog = false }
            )
        }

        if (selectedPlaylist != null) {
            PlaylistDetails(
                playlist = selectedPlaylist!!,
                vm = vm,
                onDismiss = { selectedPlaylist = null },
                onPlay = onPlay
            )
        }
    }
}

@Composable
private fun TopHeader(
    title: String,
    search: String,
    onSearchChange: (String) -> Unit,
    showSearch: Boolean,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("נגן", color = Gold, fontSize = 12.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
                Text(title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            }
            if (showSearch) {
                Box {
                    var open by remember { mutableStateOf(false) }
                    IconButton(onClick = { open = !open }) { Icon(Icons.Default.AddCircleOutline, "ייבוא") }
                    if (open) {
                        Surface(
                            Modifier.width(175.dp),
                            color = Panel,
                            shape = RoundedCornerShape(16.dp),
                            shadowElevation = 10.dp
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                TextButton(onClick = { open = false; onPickFiles() }) {
                                    Icon(Icons.Default.FileOpen, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("ייבוא קבצים")
                                }
                                TextButton(onClick = { open = false; onPickFolder() }) {
                                    Icon(Icons.Default.FolderOpen, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("ייבוא תיקייה")
                                }
                            }
                        }
                    }
                }
            }
        }
        if (showSearch) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = search,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                placeholder = { Text("חיפוש בשירים, אמנים, אלבומים, תיקיות ורשימות") },
                leadingIcon = { Icon(Icons.Default.Search, null) }
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(7.dp))
        content()
    }
}

@Composable
private fun HomeScreen(
    tracks: List<TrackEntity>,
    favorites: List<TrackEntity>,
    recent: List<TrackEntity>,
    resumed: List<TrackEntity>,
    onPlay: (List<TrackEntity>, Int) -> Unit,
    onFavorite: (TrackEntity) -> Unit,
    onPlaylist: (TrackEntity) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        if (resumed.isNotEmpty()) {
            item { Section("המשך האזנה") { TrackRow(resumed.first(), resumed.first().favorite, { onPlay(resumed, 0) }, { onFavorite(resumed.first()) }, { onPlaylist(resumed.first()) }) } }
        }
        item { QuickGrid() }
        if (favorites.isNotEmpty()) {
            item { TrackSection("מועדפים", favorites.take(8), onPlay, onFavorite, onPlaylist) }
        }
        item { TrackSection("נוספו לאחרונה", recent.take(8), onPlay, onFavorite, onPlaylist) }
        item { Text("הספרייה שלך", fontSize = 19.sp, fontWeight = FontWeight.SemiBold) }
        item { LibraryStats(tracks) }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun QuickGrid() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickCard("שירים", Icons.Default.MusicNote, Modifier.weight(1f))
        QuickCard("אלבומים", Icons.Default.Album, Modifier.weight(1f))
        QuickCard("אמנים", Icons.Default.Person, Modifier.weight(1f))
    }
}

@Composable
private fun QuickCard(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = Panel) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = Gold, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, fontSize = 12.sp)
        }
    }
}

@Composable
private fun LibraryStats(tracks: List<TrackEntity>) {
    val artists = tracks.map { it.artist }.distinct().size
    val albums = tracks.map { it.album }.distinct().size
    val folders = tracks.map { it.folder }.distinct().count { it.isNotBlank() }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCard("${tracks.size}", "שירים", Modifier.weight(1f))
        StatCard("$albums", "אלבומים", Modifier.weight(1f))
        StatCard("$artists", "אמנים", Modifier.weight(1f))
        StatCard("$folders", "תיקיות", Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier) {
    Surface(modifier, color = Panel, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(11.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Gold)
            Text(label, fontSize = 10.sp, color = Muted)
        }
    }
}

@Composable
private fun LibraryScreen(
    tracks: List<TrackEntity>,
    libraryTab: Int,
    onTab: (Int) -> Unit,
    onPlay: (List<TrackEntity>, Int) -> Unit,
    onFavorite: (TrackEntity) -> Unit,
    onPlaylist: (TrackEntity) -> Unit
) {
    val labels = listOf("שירים","אלבומים","אמנים","תיקיות","רשימות")
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = libraryTab, edgePadding = 12.dp) {
            labels.forEachIndexed { index, label ->
                Tab(selected = libraryTab == index, onClick = { onTab(index) }, text = { Text(label) })
            }
        }
        when (libraryTab) {
            0 -> TrackList(tracks, onPlay, onFavorite, onPlaylist)
            1 -> GroupedList(
                groups = tracks.groupBy { it.album }.toList().sortedBy { it.first.lowercase(Locale.getDefault()) },
                icon = Icons.Default.Album,
                onPlay = { group -> onPlay(group, 0) }
            )
            2 -> GroupedList(
                groups = tracks.groupBy { it.artist }.toList().sortedBy { it.first.lowercase(Locale.getDefault()) },
                icon = Icons.Default.Person,
                onPlay = { group -> onPlay(group, 0) }
            )
            3 -> GroupedList(
                groups = tracks.groupBy { it.folder.ifBlank { "זיכרון פנימי / מקור מיובא" } }.toList().sortedBy { it.first.lowercase(Locale.getDefault()) },
                icon = Icons.Default.FolderOpen,
                onPlay = { group -> onPlay(group, 0) }
            )
            else -> Text("הרשימות המלאות מופיעות גם במסך התורים ובמסך בשבילך.", Modifier.padding(18.dp), color = Muted)
        }
    }
}

@Composable
private fun GroupedList(
    groups: List<Pair<String, List<TrackEntity>>>,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onPlay: (List<TrackEntity>) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(18.dp, 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(groups, key = { it.first }) { (name, items) ->
            Surface(
                Modifier.fillMaxWidth().clickable { onPlay(items) },
                color = Panel, shape = RoundedCornerShape(16.dp)
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(13.dp)).background(
                            Brush.linearGradient(listOf(Color(0xFF303442), Color(0xFF151820)))
                        ),
                        contentAlignment = Alignment.Center
                    ) { Icon(icon, null, tint = Gold) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name.ifBlank { "ללא שם" }, fontWeight = FontWeight.Medium)
                        Text("${items.size} שירים", color = Muted, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.ChevronLeft, null, tint = Muted)
                }
            }
        }
    }
}

@Composable
private fun ForYouScreen(
    top: List<TrackEntity>,
    favorites: List<TrackEntity>,
    recent: List<TrackEntity>,
    tracks: List<TrackEntity>,
    onPlay: (List<TrackEntity>, Int) -> Unit,
    onFavorite: (TrackEntity) -> Unit
) {
    val scoreSorted = remember(tracks) {
        tracks.sortedByDescending {
            it.playCount * 5 + it.rating * 4 + if (it.favorite) 12 else 0 -
                it.skipCount * 2 + minOf(20, (it.totalListenMs / 60000L).toInt())
        }
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item { HeroCard("המיקס היומי", "בחירה מקומית לפי ההרגלים שלך", scoreSorted.take(12).size, Icons.Default.AutoAwesome) }
        item { SmartStrip("הכי אהובים", favorites.take(8), onPlay, onFavorite) }
        item { SmartStrip("השמעות השבוע", top.take(8), onPlay, onFavorite) }
        item { SmartStrip("גילוי מחדש", tracks.filter { it.playCount in 1..3 }.sortedByDescending { it.lastPlayedAt }.take(8), onPlay, onFavorite) }
        item { SmartStrip("פנינים נסתרות", tracks.filter { !it.favorite && it.playCount <= 2 }.take(8), onPlay, onFavorite) }
        item { SmartStrip("נוספו לאחרונה", recent.take(8), onPlay, onFavorite) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun HeroCard(title: String, subtitle: String, count: Int, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(
        Modifier.fillMaxWidth(),
        color = Panel,
        shape = RoundedCornerShape(24.dp)
    ) {
        Box(Modifier.fillMaxWidth().height(150.dp).background(Brush.linearGradient(listOf(Color(0xFF252936), Color(0xFF11141B))))) {
            Column(Modifier.padding(20.dp)) {
                Icon(icon, null, tint = Gold, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(10.dp))
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Text("$count שירים שנבחרו", color = Gold, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SmartStrip(
    title: String,
    items: List<TrackEntity>,
    onPlay: (List<TrackEntity>, Int) -> Unit,
    onFavorite: (TrackEntity) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, Modifier.weight(1f))
            Text(text = items.size.toString(), color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(9.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.uri }) { item ->
                Surface(
                    Modifier.width(160.dp).clickable { onPlay(items, items.indexOf(item)) },
                    color = Panel, shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Artwork(item.uri, Modifier.fillMaxWidth().height(105.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(item.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Muted, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun QueuesScreen(
    queues: List<QueueEntity>,
    currentCount: Int,
    onSave: () -> Unit,
    onLoad: (QueueEntity) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("תורים מרובים", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("$currentCount שירים בתור הנוכחי", color = Muted, fontSize = 12.sp)
            }
            Button(onClick = onSave, colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF12100B))) {
                Icon(Icons.Default.Save, null)
                Spacer(Modifier.width(6.dp))
                Text("שמור תור")
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(queues, key = { it.name }) { queue ->
                Surface(
                    Modifier.fillMaxWidth().clickable { onLoad(queue) },
                    color = Panel, shape = RoundedCornerShape(16.dp)
                ) {
                    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.QueueMusic, null, tint = Gold)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(queue.name, fontWeight = FontWeight.Medium)
                            Text(if (queue.shuffle) "Shuffle פעיל" else "סדר רגיל", color = Muted, fontSize = 11.sp)
                        }
                        Icon(Icons.Default.PlayArrow, null)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    lightMode: Boolean,
    onToggleTheme: () -> Unit,
    libraryTab: Int,
    onLibraryTab: (Int) -> Unit,
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit
) {
    val labels = listOf("שירים","אלבומים","אמנים","תיקיות","רשימות")
    LazyColumn(
        contentPadding = PaddingValues(18.dp, 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { SettingsSection("מראה") }
        item {
            SettingRow(
                title = "מצב בהיר",
                subtitle = if (lightMode) "פעיל" else "מצב כהה כברירת מחדל",
                trailing = { Switch(checked = lightMode, onCheckedChange = { onToggleTheme() }) }
            )
        }
        item { SettingsSection("ספרייה") }
        item {
            Surface(color = Panel, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(15.dp)) {
                    Text("לשונית ברירת מחדל", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    labels.forEachIndexed { index, label ->
                        Row(Modifier.fillMaxWidth().clickable { onLibraryTab(index) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = libraryTab == index, onClick = { onLibraryTab(index) })
                            Text(label)
                        }
                    }
                }
            }
        }
        item { SettingsSection("גיבוי ושחזור") }
        item {
            Surface(color = Panel, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("גיבוי מקומי", fontWeight = FontWeight.Medium)
                    Text("מועדפים, דירוגים, תורים, רשימות, סטטיסטיקות והגדרות.", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onCreateBackup) { Icon(Icons.Default.Upload, null); Spacer(Modifier.width(6.dp)); Text("גיבוי") }
                        OutlinedButton(onClick = onRestoreBackup) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("שחזור") }
                    }
                }
            }
        }
        item { SettingsSection("פרטיות וביצועים") }
        item { SettingRow("אופליין מלא", "אין הרשאת אינטרנט ואין חשבון משתמש.", { Icon(Icons.Default.Lock, null, tint = Gold) }) }
        item { SettingRow("מהירות", "סריקה מקומית ו־Room לניהול נתונים מהיר.", { Icon(Icons.Default.Speed, null, tint = Gold) }) }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Surface(color = Panel, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(subtitle, color = Muted, fontSize = 11.sp)
            }
            trailing()
        }
    }
}

@Composable private fun SettingsSection(title: String) {
    Text(title, color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun TrackList(
    tracks: List<TrackEntity>,
    onPlay: (List<TrackEntity>, Int) -> Unit,
    onFavorite: (TrackEntity) -> Unit,
    onPlaylist: (TrackEntity) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(18.dp, 12.dp, 18.dp, 18.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        items(tracks, key = { it.uri }) { track ->
            TrackRow(track, track.favorite, { onPlay(tracks, tracks.indexOf(track)) }, { onFavorite(track) }, { onPlaylist(track) })
        }
    }
}

@Composable
private fun TrackSection(
    title: String,
    items: List<TrackEntity>,
    onPlay: (List<TrackEntity>, Int) -> Unit,
    onFavorite: (TrackEntity) -> Unit,
    onPlaylist: (TrackEntity) -> Unit
) {
    Column {
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        items.take(5).forEachIndexed { index, track ->
            TrackRow(track, track.favorite, { onPlay(items, index) }, { onFavorite(track) }, { onPlaylist(track) })
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun TrackRow(track: TrackEntity, favorite: Boolean, onPlay: () -> Unit, onFavorite: () -> Unit, onPlaylist: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).clickable(onClick = onPlay).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Artwork(track.uri, Modifier.size(50.dp))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text("${track.artist} • ${track.album}", maxLines = 1, overflow = TextOverflow.Ellipsis, color = Muted, fontSize = 11.sp)
        }
        IconButton(onClick = onFavorite) {
            Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (favorite) Gold else Muted)
        }
        IconButton(onClick = onPlaylist) { Icon(Icons.Default.AddCircleOutline, null, tint = Muted) }
    }
}

@Composable
private fun MiniPlayer(current: TrackEntity, playing: Boolean, onOpen: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick = onOpen), color = Color(0xFF171B23)) {
        Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(current.uri, Modifier.size(46.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(current.title, maxLines = 1, fontWeight = FontWeight.Medium)
                Text(current.artist, maxLines = 1, color = Muted, fontSize = 11.sp)
            }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, null) }
            IconButton(onClick = onToggle) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullPlayer(
    track: TrackEntity,
    allTracks: List<TrackEntity>,
    playing: Boolean,
    positionMs: Long,
    controller: MediaController?,
    sheetOpen: Boolean,
    onSheet: () -> Unit,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onRating: (Int) -> Unit,
    onPlaylist: () -> Unit,
    onPlay: (List<TrackEntity>, Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    val max = maxOf(1L, controller?.duration ?: track.durationMs)
    val progress = (positionMs.toFloat() / max.toFloat()).coerceIn(0f, 1f)

    Box(
        Modifier.fillMaxSize().background(Bg).pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragEnd = { },
                onVerticalDrag = { _, dragAmount ->
                    if (dragAmount < -35f) onSheet()
                }
            )
        }
    ) {
        Artwork(track.uri, Modifier.fillMaxSize().blur(45.dp))
        Box(Modifier.fillMaxSize().background(Color(0xB908090D)))
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, null) }
                Spacer(Modifier.weight(1f))
                Text("מנגן עכשיו", color = Gold, fontSize = 12.sp, letterSpacing = 1.5.sp)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onSheet) { Icon(Icons.Default.MoreVert, null) }
            }
            Spacer(Modifier.weight(0.55f))
            Artwork(track.uri, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(28.dp)))
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    Text(track.artist, color = Muted, fontSize = 14.sp)
                }
                IconButton(onClick = onFavorite) {
                    Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (favorite) Gold else Color.White)
                }
                IconButton(onClick = onPlaylist) { Icon(Icons.Default.QueueMusic, null) }
            }
            Spacer(Modifier.height(12.dp))
            Slider(
                value = progress,
                onValueChange = { controller?.seekTo((it * max).toLong()) },
                colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(positionMs), color = Muted, fontSize = 11.sp)
                Text(formatTime(max), color = Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(54.dp)) { Icon(Icons.Default.SkipPrevious, null, modifier = Modifier.size(34.dp)) }
                FilledIconButton(onClick = onToggle, modifier = Modifier.size(68.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Gold, contentColor = Color(0xFF16120C))) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(38.dp))
                }
                IconButton(onClick = onNext, modifier = Modifier.size(54.dp)) { Icon(Icons.Default.SkipNext, null, modifier = Modifier.size(34.dp)) }
            }
            Spacer(Modifier.height(10.dp))
            RatingRow(track.rating, onRating)
            Spacer(Modifier.weight(0.3f))
            Text("החלק למעלה להצגת התור, נתונים וסטטיסטיקות", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = { }, containerColor = Color(0xFF11141B)) {
            Column(Modifier.fillMaxWidth().padding(18.dp).verticalScroll(rememberScrollState())) {
                Text("פרטי השמעה", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                SheetRow("אלבום", track.album)
                SheetRow("תיקייה", track.folder.ifBlank { "מקור מיובא" })
                SheetRow("השמעות", track.playCount.toString())
                SheetRow("דילוגים", track.skipCount.toString())
                SheetRow("זמן האזנה", formatTime(track.totalListenMs))
                SheetRow("דירוג", if (track.rating == 0) "ללא דירוג" else "${track.rating}/5")
                Spacer(Modifier.height(16.dp))
                Text("פעולות נוספות", color = Gold, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onFavorite) { Text(if (favorite) "הסר מהמועדפים" else "הוסף למועדפים") }
                    OutlinedButton(onClick = onPlaylist) { Text("לרשימת השמעה") }
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { }) { Text("סגור", color = Gold) }
                }
            }
        }
    }
}

@Composable
private fun RatingRow(rating: Int, onRating: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        (1..5).forEach { value ->
            IconButton(onClick = { onRating(if (rating == value) 0 else value) }) {
                Icon(
                    if (value <= rating) Icons.Default.Star else Icons.Default.StarBorder,
                    null,
                    tint = if (value <= rating) Gold else Color(0xFF777D8A)
                )
            }
        }
    }
}

@Composable
private fun SheetRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, Modifier.width(90.dp), color = Muted, fontSize = 12.sp)
        Text(value, Modifier.weight(1f), fontSize = 12.sp)
    }
}

@Composable
private fun Artwork(uri: String, modifier: Modifier) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = runCatching {
            context.contentResolver.openInputStream(Uri.parse(uri)).use { input ->
                if (input == null) null else BitmapFactory.decodeStream(input)
            }
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(bitmap!!.asImageBitmap(), null, modifier = modifier.clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
    } else {
        Box(
            modifier.clip(RoundedCornerShape(16.dp)).background(
                Brush.linearGradient(listOf(Color(0xFF303442), Color(0xFF151820)))
            ),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.MusicNote, null, tint = Gold, modifier = Modifier.size(34.dp)) }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun PlaylistPicker(track: TrackEntity, playlists: List<PlaylistEntity>, onDismiss: () -> Unit, onAdd: (String) -> Unit, onNew: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("הוספה לרשימת השמעה") },
        text = {
            Column {
                if (playlists.isEmpty()) Text("אין רשימות עדיין.", color = Muted)
                playlists.filter { it.smartType.isBlank() }.forEach {
                    TextButton(onClick = { onAdd(it.name) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.QueueMusic, null, tint = Gold)
                        Spacer(Modifier.width(8.dp))
                        Text(it.name, Modifier.weight(1f))
                    }
                }
                OutlinedButton(onClick = onNew, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("רשימה חדשה")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("סגור") } }
    )
}

@Composable
private fun CreatePlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("רשימת השמעה חדשה") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("שם") }) },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onCreate(name.trim()) }) { Text("צור") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } }
    )
}

@Composable
private fun SaveQueueDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("שמירת תור") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("שם התור") }) },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim()) }) { Text("שמור") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } }
    )
}

@Composable
private fun PlaylistDetails(playlist: PlaylistEntity, vm: PlayerViewModel, onDismiss: () -> Unit, onPlay: (List<TrackEntity>, Int) -> Unit) {
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val list by vm.tracksForPlaylist(playlist).collectAsStateWithLifecycle(initialValue = emptyList())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(playlist.name) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                itemsIndexed(list) { index, track ->
                    Row(Modifier.fillMaxWidth().clickable { onPlay(list, index) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(track.uri, Modifier.size(42.dp)); Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) { Text(track.title); Text(track.artist, color = Muted, fontSize = 11.sp) }
                        if (playlist.smartType.isBlank()) TextButton(onClick = { vm.removeFromPlaylist(playlist.name, track) }) { Text("הסר") }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (playlist.smartType.isBlank()) TextButton(onClick = { vm.deletePlaylist(playlist.name); onDismiss() }) { Text("מחק") }
                TextButton(onClick = onDismiss) { Text("סגור") }
            }
        }
    )
}
