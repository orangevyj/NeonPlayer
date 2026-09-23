package com.orangestudio.neonplayer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.orangestudio.neonplayer.data.AnalysisStore
import com.orangestudio.neonplayer.data.AudioAnalysis
import com.orangestudio.neonplayer.data.AudioDecoder
import com.orangestudio.neonplayer.data.AudioEngine
import com.orangestudio.neonplayer.data.ConfigStore
import com.orangestudio.neonplayer.data.CoverArt
import com.orangestudio.neonplayer.data.LibraryAnalysis
import com.orangestudio.neonplayer.data.LoadedAudio
import com.orangestudio.neonplayer.data.MusicScanner
import com.orangestudio.neonplayer.data.MusicTrack
import com.orangestudio.neonplayer.data.NowPlaying
import com.orangestudio.neonplayer.data.PlaybackBridge
import com.orangestudio.neonplayer.data.PlaybackCommand
import com.orangestudio.neonplayer.data.PlaybackMode
import com.orangestudio.neonplayer.data.PlaybackQueue
import com.orangestudio.neonplayer.data.PlaybackService
import com.orangestudio.neonplayer.data.ProcessingLock
import com.orangestudio.neonplayer.data.SpectralProfile
import com.orangestudio.neonplayer.ui.AnalysisProgress
import com.orangestudio.neonplayer.ui.InfoScreen
import com.orangestudio.neonplayer.ui.MiniBarCorner
import com.orangestudio.neonplayer.ui.MiniBarGap
import com.orangestudio.neonplayer.ui.MiniBarHeight
import com.orangestudio.neonplayer.ui.MusicScreen
import com.orangestudio.neonplayer.ui.NeonMiniBar
import com.orangestudio.neonplayer.ui.NeonPlayerBottomBar
import com.orangestudio.neonplayer.ui.NeonPlayerTab
import com.orangestudio.neonplayer.ui.NeonPlayerScreen
import com.orangestudio.neonplayer.ui.NeonPlayerStatus
import com.orangestudio.neonplayer.ui.NeonPulse
import com.orangestudio.neonplayer.ui.PlayerExitEnd
import com.orangestudio.neonplayer.ui.SettingsScreen
import com.orangestudio.neonplayer.ui.SetupScreen
import com.orangestudio.neonplayer.ui.rememberNeonPulse
import com.orangestudio.neonplayer.ui.theme.AccentedTheme
import com.orangestudio.neonplayer.ui.theme.NeonPlayerTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Read here rather than inside [NeonPlayerApp]: the theme sits above every screen, setup
        // included, so the colour has to be in hand before the first frame is composed.
        val config = ConfigStore(this)

        setContent {
            var accent by remember { mutableStateOf(Color(config.neonColor())) }

            NeonPlayerTheme {
                // The base theme brings the neutral surfaces and the type scale; this layer swaps
                // the accent band for the colour the user picked, which the glow then burns in.
                AccentedTheme(accent = accent) {
                    NeonPlayerApp(
                        config = config,
                        accent = accent,
                        onAccentChange = { picked ->
                            accent = picked
                            config.setNeonColor(picked.toArgb())
                        },
                    )
                }
            }
        }
    }
}

/** The permissions the app asks for, which differ by platform level. */
private fun audioPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

private fun hasAudioPermission(context: Context): Boolean =
    audioPermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

/** How the player is getting on with the track it was asked to play. */
private sealed interface LoadState {

    data object Idle : LoadState

    /** Being decoded; [fraction] runs `0..1`, or is null while the length is unknown. */
    data class Decoding(val fraction: Float?) : LoadState

    data object Ready : LoadState

    data object Failed : LoadState
}

@Composable
private fun NeonPlayerApp(config: ConfigStore, accent: Color, onAccentChange: (Color) -> Unit) {
    val context = LocalContext.current

    // Latched once the user finishes setup, so the setup controls are seen exactly once per
    // install. Changing folders afterwards happens in Settings, not by re-running onboarding.
    var setupComplete by remember { mutableStateOf(config.isSetupComplete()) }
    var tab by remember { mutableStateOf(NeonPlayerTab.Music) }
    var neonEnabled by remember { mutableStateOf(config.neonEffects()) }

    // The glow strength and the bass response are read once and written straight back to the
    // config, the same way the accent is, so the stored value stays the only copy of them.
    var glowIntensity by remember { mutableStateOf(config.glowIntensity()) }
    var bassSensitivity by remember { mutableStateOf(config.bassSensitivity()) }

    // Where the player sits between the whole window and the little strip above the tabs, whether
    // its elements should run their entrance the next time they mount, and how tall the tabs are -
    // measured rather than assumed, so the strip can float exactly on top of them.
    var collapsed by remember { mutableStateOf(false) }
    var entranceAnimated by remember { mutableStateOf(true) }
    var tabsHeight by remember { mutableIntStateOf(0) }

    // A null entry is an "Open Directory" button that has been added but not filled in yet, so the
    // list can grow as far as the user wants. Only the filled-in folders are saved to the config.
    var slots by remember {
        mutableStateOf<List<Uri?>>(config.directories().ifEmpty { listOf(null) })
    }
    var tracks by remember { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(hasAudioPermission(context)) }
    var slotBeingPicked by remember { mutableStateOf<Int?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { hasPermission = hasAudioPermission(context) }

    val requestPermission: () -> Unit = { permissionLauncher.launch(audioPermissions()) }

    // Notifications are asked for on their own rather than added to [audioPermissions]: a library is
    // useless without the audio permission, while the player works perfectly well with no
    // notification at all, so a refusal here must not keep the user sitting in setup.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(hasPermission) {
        if (hasPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val directoryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { picked ->
        val index = slotBeingPicked
        slotBeingPicked = null
        if (picked == null || index == null) return@rememberLauncherForActivityResult

        // Persist the grant, so the folder still works after a restart and across app updates.
        try {
            context.contentResolver.takePersistableUriPermission(
                picked,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (ignored: SecurityException) {
            // Some providers hand out non-persistable grants; the folder still works for now.
        }

        slots = slots.toMutableList().apply { this[index] = picked }
    }

    val directories = slots.filterNotNull()

    val engine = remember { AudioEngine() }
    val analysisStore = remember { AnalysisStore(context) }

    // Where the pipeline runs. Deliberately not a `LaunchedEffect`: Compose cancels those when the
    // activity stops, and stopping is exactly what happens when the screen goes off, which is why a
    // track used to run out in silence and the queue only moved on once the app was opened again.
    // `Dispatchers.Main` is a plain handler on the main looper, so work queued here keeps running
    // with the window in the background, alive as long as the process is - and the media-playback
    // foreground service is what keeps the process alive.
    val playbackScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    // Decoding and measuring hold the CPU awake for as long as they take, so a phone that is asleep
    // still finishes the track it was handed rather than stalling it until it is picked up again.
    val processingLock = remember { ProcessingLock(context) }

    var playerOpen by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableIntStateOf(-1) }
    var loadState by remember { mutableStateOf<LoadState>(LoadState.Idle) }
    var playbackMode by remember { mutableStateOf(config.playbackMode()) }
    var profiles by remember { mutableStateOf<Map<Uri, SpectralProfile>>(emptyMap()) }
    var analysisProgress by remember { mutableStateOf<AnalysisProgress?>(null) }

    /** Where "previous" goes back to. */
    var history by remember { mutableStateOf<List<Int>>(emptyList()) }

    /** What has been heard this session, so the queue works through the library before repeating. */
    val played = remember { mutableSetOf<Int>() }

    /** The track the engine was last handed, so a rescan can tell that the library moved on. */
    var loadedUri by remember { mutableStateOf<Uri?>(null) }

    // The three long-running jobs: the folder scan, one decode and the measuring pass. Held in state
    // so a newer request can cancel the older one, and so nothing outside has to know they exist.
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var prepareJob by remember { mutableStateOf<Job?>(null) }
    var measureJob by remember { mutableStateOf<Job?>(null) }

    val currentTrack = tracks.getOrNull(currentIndex)

    /**
     * Decodes [track] and hands it to the engine.
     *
     * Imperative rather than keyed off the current track in a `LaunchedEffect`: the queue advances
     * from the engine's pump now, which can happen with nothing on screen, and a decode that only
     * began when something recomposed would leave the player silent until it was reopened.
     */
    val prepare: (MusicTrack) -> Unit = { track ->
        prepareJob?.cancel()
        loadState = LoadState.Decoding(null)

        prepareJob = playbackScope.launch {
            // The decoder reports from a background thread; the flow hands that back to the main
            // thread in one place instead of the decoder writing UI state from wherever it is.
            val reported = MutableStateFlow<Float?>(null)
            val watcher = launch { reported.collect { loadState = LoadState.Decoding(it) } }

            // The decoder loops on a blocking call with no suspension point of its own, so it has
            // to be told about a cancellation rather than left to notice one.
            val decoding = currentCoroutineContext()[Job]

            val decoded = try {
                processingLock.acquire()
                withContext(Dispatchers.IO) {
                    // Named arguments rather than a trailing lambda: the trailing position belongs
                    // to the cancellation check, not to the progress callback.
                    AudioDecoder.decode(
                        context = context,
                        uri = track.uri,
                        onProgress = { reported.value = it },
                        isCancelled = { decoding?.isActive != true },
                    )
                }.let { pcm -> LoadedAudio(pcm, AudioAnalysis.beatMap(pcm)) }
            } catch (cancelled: CancellationException) {
                // A newer request took over; that is not a failure worth showing anyone.
                throw cancelled
            } catch (error: Exception) {
                null
            } finally {
                watcher.cancel()
                processingLock.release()
            }

            // Cancelled mid-decode: the result belongs to a track nobody is showing any more.
            ensureActive()

            if (decoded == null) {
                loadState = LoadState.Failed
            } else {
                engine.load(decoded, autoPlay = true)
                loadedUri = track.uri
                loadState = LoadState.Ready
            }
        }
    }

    val playNext: () -> Unit = {
        val next = PlaybackQueue.next(
            mode = playbackMode,
            tracks = tracks,
            current = currentIndex,
            played = played,
            profiles = profiles,
        )

        if (next != null && next != currentIndex) {
            if (currentIndex >= 0) history = history + currentIndex
            currentIndex = next
            tracks.getOrNull(next)?.let(prepare)
        }
    }

    val playPrevious: () -> Unit = {
        val target = PlaybackQueue.previous(
            mode = playbackMode,
            tracks = tracks,
            current = currentIndex,
            history = history,
        )

        if (target != null) {
            if (history.lastOrNull() == target) history = history.dropLast(1)

            if (target == currentIndex) {
                // Nowhere to go: start the current track again rather than decoding it again.
                engine.seekTo(0f)
                engine.play()
            } else {
                currentIndex = target
                tracks.getOrNull(target)?.let(prepare)
            }
        }
    }

    val quitPlayer: () -> Unit = {
        playerOpen = false
        collapsed = false
        engine.pause()
    }

    // The notification and the system's media controls drive the player the screen is showing.
    // Commands arrive on the main thread - the session's callbacks and the notification's button
    // intents are both delivered there - so they can go straight into the engine.
    DisposableEffect(engine) {
        PlaybackBridge.onCommand = { command ->
            when (command) {
                PlaybackCommand.Play -> engine.play()
                PlaybackCommand.Pause -> engine.pause()
                PlaybackCommand.Toggle -> engine.toggle()
                PlaybackCommand.Next -> playNext()
                PlaybackCommand.Previous -> playPrevious()
                is PlaybackCommand.Seek -> engine.seekTo(command.seconds)
                PlaybackCommand.Stop -> {
                    quitPlayer()
                    // Stopping is the one command that also clears the notification: the player has
                    // nothing to show any more, so the controls go with the state.
                    PlaybackBridge.publish(null)
                }
            }
        }

        // Fired by the engine's pump, on the main thread, rather than watched as Compose state: a
        // track running out has to hand over to the next one whether or not anything is composed.
        engine.onFinished = playNext

        onDispose {
            PlaybackBridge.onCommand = null
            engine.onFinished = null
            // With the engine gone nothing can answer a command, so the notification comes down with
            // it rather than being left behind as a set of controls that do nothing.
            PlaybackBridge.publish(null)
            // Whatever the pipeline still has queued belongs to a player that no longer exists.
            playbackScope.cancel()
            engine.release()
        }
    }

    /**
     * Measures every track the adaptive queue has not seen yet.
     *
     * Only the unmeasured ones are touched and the result is cached, so after the first pass over a
     * library this settles down to nothing. It runs on [playbackScope] because a whole library is
     * worth minutes of work, which is longer than the app spends on screen.
     */
    val measureLibrary: () -> Unit = {
        measureJob?.cancel()
        if (playbackMode != PlaybackMode.Adaptive || tracks.isEmpty()) {
            analysisProgress = null
        } else {
            measureJob = playbackScope.launch {
                val store = analysisStore
                store.retainOnly(tracks.map { it.uri })

                profiles = tracks.mapNotNull { track ->
                    store.profileFor(track.uri)?.let { track.uri to it }
                }.toMap()

                val unmeasured = tracks.filter { store.profileFor(it.uri) == null }
                if (unmeasured.isEmpty()) {
                    analysisProgress = null
                } else {
                    analysisProgress = AnalysisProgress(done = 0, total = unmeasured.size)

                    unmeasured.forEachIndexed { index, track ->
                        val measured = try {
                            processingLock.acquire()
                            withContext(Dispatchers.IO) {
                                LibraryAnalysis.analyze(context, track.uri, store)
                            }
                        } finally {
                            processingLock.release()
                        }

                        if (measured != null) profiles = profiles + (track.uri to measured)
                        analysisProgress = AnalysisProgress(done = index + 1, total = unmeasured.size)
                    }

                    analysisProgress = null
                }
            }
        }
    }

    // How often the notification is compared against the engine: four times a second is often enough
    // for the position in it never to look stale, and an unchanged state costs one comparison.
    val mirrorPollMillis = 250L

    /**
     * Writes what is playing into the notification and the lock screen.
     *
     * A loop rather than a `LaunchedEffect`, for the same reason as the pump: this is the part that
     * has to keep working with the app off screen, where nothing recomposes. It only publishes when
     * something it shows has actually changed, so checking four times a second is not four times a
     * second of work.
     */
    val mirrorNowPlaying: suspend () -> Unit = {
        var shownTrack: MusicTrack? = null
        var shownArtwork: Bitmap? = null
        var published: NowPlaying? = null

        while (currentCoroutineContext().isActive) {
            val track = currentTrack

            if (track == null) {
                if (published != null) {
                    published = null
                    PlaybackBridge.publish(null)
                }
            } else {
                val playing = engine.isPlaying.value
                val newTrack = track !== shownTrack

                if (newTrack) {
                    shownTrack = track
                    // Decoded on this side rather than in the service: this side has the IO
                    // dispatcher, and a cover is no more than a 256px JPEG by the time the scanner
                    // has been through it.
                    shownArtwork = track.coverArt?.let { bytes ->
                        withContext(Dispatchers.IO) { CoverArt.decode(bytes) }
                    }
                }

                // The service is the only reason to be a foreground app, so it is promoted when a
                // new track arrives and again when playback starts back up after Stop took it down.
                // Asked for rather than assumed: the platform can turn a promotion down while the
                // process is in the background, and the player carries on either way.
                if (newTrack || (playing && published?.playing != true)) {
                    runCatching { PlaybackService.start(context) }
                }

                // Whole seconds while it plays: the lock screen extrapolates between updates from
                // the playback speed, so every wobble of the float would wake it for nothing. A
                // paused position goes as it is, because nothing is going to move it.
                val position = engine.positionSeconds.value
                val state = NowPlaying(
                    track = track,
                    playing = playing,
                    positionSeconds = if (playing) position.roundToInt().toFloat() else position,
                    durationSeconds = engine.loaded.value?.pcm?.durationSeconds ?: 0f,
                    accent = accent.toArgb(),
                    artwork = shownArtwork,
                )

                if (state != published) {
                    published = state
                    PlaybackBridge.publish(state)
                }
            }

            delay(mirrorPollMillis)
        }
    }

    /**
     * Rescans the folders the user picked.
     *
     * On [playbackScope] rather than inside the effect below, so a scan started just before the
     * screen goes off still lands instead of being cancelled and forgotten.
     */
    val scanFolders: (List<Uri>) -> Unit = { roots ->
        config.setDirectories(roots)
        scanJob?.cancel()
        if (roots.isEmpty()) {
            tracks = emptyList()
            isScanning = false
        } else {
            isScanning = true
            scanJob = playbackScope.launch {
                tracks = withContext(Dispatchers.IO) { MusicScanner.scan(context, roots) }
                isScanning = false
                // The library can have moved under the player: whatever is at the current index now
                // is what the engine should be holding.
                tracks.getOrNull(currentIndex)?.let { showing ->
                    if (showing.uri != loadedUri) prepare(showing)
                }
                measureLibrary()
            }
        }
    }

    // Started once, for as long as this screen exists: the pump is the engine's clock, and the
    // mirror is what keeps the notification honest. Neither may hang off a frame clock that stops
    // when the app goes off screen.
    LaunchedEffect(Unit) {
        engine.startPump(playbackScope)
        playbackScope.launch { mirrorNowPlaying() }
    }

    // Runs during setup as well as in the main UI, so the list is already populated by the time the
    // user gets past onboarding.
    LaunchedEffect(directories) { scanFolders(directories) }

    // Setup and Settings edit the same slots, so they share one set of handlers.
    val pickDirectory: (Int) -> Unit = { index ->
        slotBeingPicked = index
        directoryPicker.launch(null)
    }
    val addSlot: () -> Unit = { slots = slots + null }
    val removeDirectory: (Int) -> Unit = { index ->
        // Hand the folder grant back so grants do not pile up towards the per-app limit,
        // unless another slot still points at the same tree.
        val removed = slots.getOrNull(index)
        if (removed != null && slots.count { it == removed } == 1) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    removed,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (ignored: SecurityException) {
                // The grant was already gone, or was never persistable.
            }
        }

        val remaining = slots.toMutableList().apply { removeAt(index) }
        slots = if (remaining.isEmpty()) listOf(null) else remaining
    }

    if (!setupComplete) {
        SetupScreen(
            slots = slots,
            hasPermission = hasPermission,
            accent = accent,
            onAccentChange = onAccentChange,
            intensity = glowIntensity,
            onIntensityChange = { value ->
                glowIntensity = value
                config.setGlowIntensity(value)
            },
            sensitivity = bassSensitivity,
            onSensitivityChange = { value ->
                bassSensitivity = value
                config.setBassSensitivity(value)
            },
            onRequestPermission = requestPermission,
            onPickDirectory = pickDirectory,
            onAddSlot = addSlot,
            onRemoveDirectory = removeDirectory,
            onContinue = {
                config.setSetupComplete(true)
                setupComplete = true
            },
        )
        return
    }

    // One shared pulse, so the bottom bar, the "Music" sign and the Settings switch breathe
    // together. This holds the animation rather than its current value, so the pulse invalidates
    // only the tubes that draw it and never re-composes this screen or the track list.
    val neonPulse = rememberNeonPulse(
        enabled = neonEnabled,
        accent = accent,
        intensity = glowIntensity,
        sensitivity = bassSensitivity,
    )

    val status = when (val state = loadState) {
        is LoadState.Decoding -> NeonPlayerStatus.Preparing(state.fraction)
        LoadState.Ready -> NeonPlayerStatus.Ready
        LoadState.Failed -> NeonPlayerStatus.Failed
        LoadState.Idle -> NeonPlayerStatus.Preparing(null)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                NeonPlayerBottomBar(
                    selected = tab,
                    onSelect = { tab = it },
                    neonPulse = neonPulse,
                    // Measured rather than assumed: whatever height the platform gives the tabs, the
                    // little bar is placed on top of them.
                    modifier = Modifier.onSizeChanged { tabsHeight = it.height },
                )
            },
        ) { innerPadding ->
            when (tab) {
                NeonPlayerTab.Music -> MusicScreen(
                    tracks = tracks,
                    isScanning = isScanning,
                    hasDirectories = directories.isNotEmpty(),
                    neonPulse = neonPulse,
                    onTrackClick = { index ->
                        if (index == currentIndex && loadState == LoadState.Ready) {
                            // Already prepared and paused: just start it again.
                            engine.play()
                        } else {
                            if (index != currentIndex && currentIndex >= 0) {
                                history = history + currentIndex
                            }
                            if (index == currentIndex) {
                                // Tapped again: a fresh attempt at the track that is already
                                // showing, rather than selecting the same one and doing nothing.
                                tracks.getOrNull(index)?.let(prepare)
                            } else {
                                currentIndex = index
                                tracks.getOrNull(index)?.let(prepare)
                            }
                        }
                        playerOpen = true
                        collapsed = false
                        // Opened from the library, so the elements run their entrance.
                        entranceAnimated = true
                    },
                    modifier = Modifier.padding(innerPadding),
                )

                NeonPlayerTab.Settings -> SettingsScreen(
                    slots = slots,
                    hasPermission = hasPermission,
                    isScanning = isScanning,
                    trackCount = tracks.size,
                    neonPulse = neonPulse,
                    accent = accent,
                    onAccentChange = onAccentChange,
                    intensity = glowIntensity,
                    onIntensityChange = { value ->
                        glowIntensity = value
                        config.setGlowIntensity(value)
                    },
                    sensitivity = bassSensitivity,
                    onSensitivityChange = { value ->
                        bassSensitivity = value
                        config.setBassSensitivity(value)
                    },
                    onNeonChange = { enabled ->
                        neonEnabled = enabled
                        config.setNeonEffects(enabled)
                    },
                    onRequestPermission = requestPermission,
                    onPickDirectory = pickDirectory,
                    onAddSlot = addSlot,
                    onRemoveDirectory = removeDirectory,
                    modifier = Modifier.padding(innerPadding),
                )

                NeonPlayerTab.Info -> InfoScreen(modifier = Modifier.padding(innerPadding))
            }
        }

        // The player covers the tabs rather than replacing them, so the library keeps its scroll
        // position while a track is open.
        if (playerOpen && currentTrack != null) {
            PlayerOverlay(
                track = currentTrack,
                engine = engine,
                neonPulse = neonPulse,
                mode = playbackMode,
                status = status,
                progress = analysisProgress,
                collapsed = collapsed,
                tabsHeight = tabsHeight,
                entranceAnimated = entranceAnimated,
                onCollapse = { collapsed = true },
                onExpand = {
                    // Back from the bar: the elements belong where they are, and the wave that took
                    // them out is what brings them home.
                    entranceAnimated = false
                    collapsed = false
                },
                onDismiss = quitPlayer,
                onPrevious = playPrevious,
                onNext = playNext,
                onModeChange = { mode ->
                    playbackMode = mode
                    config.setPlaybackMode(mode)
                    // Choosing (or leaving) Adaptive is what the measuring pass is for, so it is
                    // asked for here rather than left to an effect that watches the mode.
                    measureLibrary()
                },
            )
        }
    }
}

/**
 * The player's own layer: the window at full size, and the little bar it closes into.
 *
 * The whole motion is one animated number, chased from `0` to `1`. Everything that depends on it is
 * read where it is used - the height in the layout pass, the colours in the draw pass, each
 * element's own transform in a graphics layer - so the sixty frames of a collapse never turn into
 * sixty recompositions of the player or of the bar. Only the two switches that mount and unmount
 * them are read while composing, and each of those flips once in either direction.
 */
@Composable
private fun PlayerOverlay(
    track: MusicTrack,
    engine: AudioEngine,
    neonPulse: NeonPulse,
    mode: PlaybackMode,
    status: NeonPlayerStatus,
    progress: AnalysisProgress?,
    collapsed: Boolean,
    tabsHeight: Int,
    entranceAnimated: Boolean,
    onCollapse: () -> Unit,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onModeChange: (PlaybackMode) -> Unit,
) {
    // Back while the window is open closes it onto the bar; back again puts the player away.
    BackHandler { if (collapsed) onDismiss() else onCollapse() }

    // How long the whole collapse takes, and the three windows of it as fractions of that one
    // number: the elements are gone by [PlayerExitEnd], the grey behind them has closed onto the bar
    // by the end of the second, and the bar's own controls arrive over the third, left to right.
    val collapseMillis = 620
    val morphStart = 0.30f
    val morphEnd = 0.80f
    val revealStart = 0.60f

    val haloLayers = 3
    val haloStep = 10.dp
    val hairline = 1.dp

    val collapse = animateFloatAsState(
        targetValue = if (collapsed) 1f else 0f,
        animationSpec = tween(durationMillis = collapseMillis, easing = FastOutSlowInEasing),
        label = "collapse",
    )

    // Switches rather than numbers: read while composing, these flip once each way, which is what
    // keeps the two faces out of the per-frame recomposition the motion would otherwise cause.
    val windowMounted by remember { derivedStateOf { collapse.value < PlayerExitEnd } }
    val barMounted by remember { derivedStateOf { collapse.value >= revealStart } }

    val leaving: () -> Float = { (collapse.value / PlayerExitEnd).coerceIn(0f, 1f) }
    val closing: () -> Float = {
        ((collapse.value - morphStart) / (morphEnd - morphStart)).coerceIn(0f, 1f)
    }
    val revealing: () -> Float = {
        ((collapse.value - revealStart) / (1f - revealStart)).coerceIn(0f, 1f)
    }

    val scheme = MaterialTheme.colorScheme

    // The tabs' height comes back in pixels, while everything laid out here is measured in dp.
    val bottomInset = with(LocalDensity.current) {
        (tabsHeight + MiniBarGap.roundToPx()).toDp()
    }

    // A plain Box rather than a Material3 Surface, and a published content colour rather than a
    // layer of paint. A Surface registers pointer input across its whole bounds, which swallowed
    // every touch aimed at the library behind the collapsed bar; the container has to be
    // see-through for touches as well as for paint, and the backdrop further down is what eats a
    // tap while the window is whole. The provider keeps what the Surface was really there for:
    // without a content colour LocalContentColor stays at its black default, and anything that
    // does not name a colour of its own (the title, the transport icons once neon is off) would be
    // drawn black on a dark background.
    CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomInset),
        ) {
            // The grey: the player's backdrop while the window is whole, the bar's own card at the
            // end of the motion. One surface for both, so nothing is ever painted twice, and it is
            // what a tap lands on while it closes.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .collapsingHeight(closing)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .drawBehind {
                        val closed = closing()
                        val corner = CornerRadius(MiniBarCorner.toPx() * closed)

                        // The bar's halo, in the same language as the icon halos: a few rounded
                        // layers fading out around the card, swelling with the bass so the whole
                        // strip breathes with the track under it.
                        val audio = engine.loaded.value
                        val bass = if (neonPulse.on && audio != null) {
                            neonPulse.beat(audio.beats.strengthAt(engine.positionSeconds.value))
                        } else {
                            0f
                        }
                        val glow = if (neonPulse.on) neonPulse.glow else 0f
                        val swell = closed * (0.35f + 0.65f * bass)
                        val out = haloStep.toPx()

                        for (layer in haloLayers downTo 1) {
                            val spread = out * layer
                            drawRoundRect(
                                color = neonPulse.glowColor.copy(
                                    alpha = (0.16f * glow * swell) / layer,
                                ),
                                topLeft = Offset(-spread, -spread),
                                size = Size(size.width + spread * 2f, size.height + spread * 2f),
                                cornerRadius = CornerRadius(corner.x + spread),
                            )
                        }

                        drawRoundRect(
                            color = lerp(scheme.background, scheme.surfaceVariant, closed),
                            cornerRadius = corner,
                        )

                        if (glow > 0.001f) {
                            drawRoundRect(
                                color = neonPulse.glowColor.copy(
                                    alpha = (0.22f * glow + 0.5f * swell).coerceAtMost(1f),
                                ),
                                cornerRadius = corner,
                                style = Stroke(width = hairline.toPx()),
                            )
                        }
                    },
            )

            // The player itself, in place until the last of its elements has gone.
            if (windowMounted) {
                NeonPlayerScreen(
                    track = track,
                    engine = engine,
                    neonPulse = neonPulse,
                    mode = mode,
                    status = status,
                    progress = progress,
                    exit = leaving,
                    animateEntrance = entranceAnimated,
                    onCollapse = onCollapse,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onModeChange = onModeChange,
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
                )
            }

            // The bar, over the last stretch of the way in.
            if (barMounted) {
                NeonMiniBar(
                    engine = engine,
                    pulse = neonPulse,
                    reveal = revealing,
                    onExpand = onExpand,
                    onDismiss = onDismiss,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/**
 * The collapsing backdrop: the whole window at the top of the motion, the little bar at the end.
 *
 * The height is read in the layout pass, which is the one place it can be read without recomposing
 * everything below it, and laid out from the bottom of the screen upwards so only the top edge of
 * the grey ever moves.
 */
private fun Modifier.collapsingHeight(progress: () -> Float): Modifier =
    layout { measurable, constraints ->
        val fraction = progress().coerceIn(0f, 1f)
        val whole = constraints.maxHeight
        val bar = MiniBarHeight.roundToPx().coerceAtMost(whole)
        val height = (whole + (bar - whole) * fraction).roundToInt()
        val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))

        layout(placeable.width, height) { placeable.place(0, 0) }
    }
