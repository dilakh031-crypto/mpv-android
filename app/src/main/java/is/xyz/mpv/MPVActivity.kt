package `is`.xyz.mpv

import `is`.xyz.mpv.databinding.PlayerBinding
import `is`.xyz.mpv.MPVLib.MpvEvent
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import androidx.appcompat.app.AlertDialog
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.graphics.Color
import android.util.Log
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.*
import android.preference.PreferenceManager.getDefaultSharedPreferences
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Rational
import androidx.core.content.ContextCompat
import android.view.*
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Button
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.annotation.RequiresApi
import java.security.MessageDigest
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import java.io.File
import java.lang.IllegalArgumentException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

typealias ActivityResultCallback = (Int, Intent?) -> Unit
typealias StateRestoreCallback = () -> Unit

class MPVActivity : AppCompatActivity(), MPVLib.EventObserver, TouchGesturesObserver {
    // for calls to eventUi() and eventPropertyUi()
    private val eventUiHandler = Handler(Looper.getMainLooper())
    // for use with fadeRunnable1..3
    private val fadeHandler = Handler(Looper.getMainLooper())
    // for use with stopServiceRunnable
    private val stopServiceHandler = Handler(Looper.getMainLooper())
    // Per-file state callbacks must keep running while the UI is backgrounded. eventUiHandler is
    // intentionally cleared in onPause(), so state persistence uses its own handler.
    private val perFileStateHandler = Handler(Looper.getMainLooper())
    // Delayed single-tap toggling (we wait a bit so a faster double-tap can be recognized
    // without flashing the control UI).
    private val tapToggleHandler = Handler(Looper.getMainLooper())
    private var pendingTapToggleRunnable: Runnable? = null

    // Restores immersive mode after temporary system UI interruptions such as Google Assistant
    // or the soft keyboard. A separate handler keeps these retries independent from player/event
    // callbacks, which may be cleared while the activity is paused.
    private val immersiveHandler = Handler(Looper.getMainLooper())
    private var playerWindowLostFocus = false
    private var manualSystemBarsRevealPending = false
    private val clearManualSystemBarsRevealRunnable = Runnable {
        manualSystemBarsRevealPending = false
    }
    private val restorePlayerImmersiveRunnable = Runnable {
        applyPlayerImmersiveModeIfPossible()
    }

    // We intentionally do *not* try to predict a double-tap here. Instead, we only cancel the
    // pending single-tap toggle if TouchGestures actually confirms and handles a double-tap
    // (PlayPause / SeekFixed / Custom). This avoids a "dead zone" where two quick taps that do
    // not qualify as a double-tap would otherwise cancel the single-tap toggle and do nothing.

    /**
     * DO NOT USE THIS
     */
    private var activityIsStopped = false

    private var activityIsForeground = true
    private var didResumeBackgroundPlayback = false
    private var userIsOperatingSeekbar = false


    // Scrub seeking (freeze frame while moving; seek only on idle/release).
    // This keeps exact seeking while avoiding the massive slowdown caused by spamming seeks.
    private val scrubSeekHandler = Handler(Looper.getMainLooper())
    private var scrubSeekInFlight = false
    private var resumeAfterScrubSeek = false
    private var scrubAsyncCounter = 1L
    private var lastScrubAsyncUserdata = 0L

    private var gestureScrubActive = false
    private var pendingGestureSeekSec: Int? = null
    private var lastIssuedGestureSeekSec: Int? = null

    private var seekbarScrubActive = false
    private var pendingSeekbarSeekPos: Double? = null
    private var lastIssuedSeekbarSeekPos: Double? = null

    /** 0 = initial, 1 = paused, 2 = was already paused */
    private var pausedForSeekbar = 0

    private val gestureIdleSeekRunnable = Runnable { performGestureIdleSeek() }
    private val seekbarIdleSeekRunnable = Runnable { performSeekbarIdleSeek() }

    private var toast: Toast? = null
    private val toastHandler = Handler(Looper.getMainLooper())
    private var toastCancelRunnable: Runnable? = null

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequestCompat? = null
    private var audioFocusRestore: () -> Unit = {}

    
    // Orientation smoothing / fast rotation
    private var entryConfigOrientation: Int = Configuration.ORIENTATION_UNDEFINED
    private var finishPending: Boolean = false
    private var pendingFinishAfterRotate: Boolean = false
    private var exitRequestedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var lastOrientationProbePath: String? = null

    // When auto-rotation is enabled, mpv can briefly report an unknown/square aspect ratio
    // (especially during startup / demuxer init). If we immediately react to that by setting
    // SCREEN_ORIENTATION_UNSPECIFIED, Android may rotate back to portrait and "stick" there.
    //
    // We therefore keep a short "stability lock" where we *refuse* to change orientation
    // away from a known desired orientation until we have a reliable aspect ratio.
    private var orientationStabilityLockUntilMs: Long = 0L
    private var orientationStabilityLockValue: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    private fun lockOrientationStability(desired: Int, durationMs: Long = 1600L) {
        if (autoRotationMode != "auto")
            return
        if (desired != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE &&
            desired != ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        ) return

        orientationStabilityLockValue = desired
        orientationStabilityLockUntilMs = SystemClock.uptimeMillis() + durationMs
    }

    private fun isWithinOrientationStabilityLock(): Boolean {
        return SystemClock.uptimeMillis() < orientationStabilityLockUntilMs
    }

    // Startup orientation pre-probe / deferred player init
    private var startupFilePath: String? = null
    private var startupDesiredConfigOrientation: Int = Configuration.ORIENTATION_UNDEFINED
    private var deferPlayerInit: Boolean = false
    private var uiInitialized: Boolean = false

    // One owner for hiding the mpv texture while a new file/reconfig is waiting
    // for reliable video geometry. Aspect changes from the in-app menu bypass this
    // blackout and use predictive geometry instead.
    private var videoGeometryBlackoutActive = true
    private var videoGeometryBlackoutGeneration = 0
    private var videoGeometryBlackoutRevealArmed = false
    private var videoGeometryBlackoutFileLoadedSeen = false
    private var suppressAspectMenuGeometrySyncUntilMs = 0L

    private val psc = Utils.PlaybackStateCache()
    private var mediaSession: MediaSessionCompat? = null

    private lateinit var binding: PlayerBinding
    private lateinit var gestures: TouchGestures
    private lateinit var zoomGestures: VideoZoomGestures

    // convenience alias
    private val player get() = binding.player

    private val seekBarChangeListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (!fromUser) return

            // Freeze the current frame while the user is dragging.
            // We only perform an exact seek when the finger stops moving (idle) or on release.
            // Quantize to whole seconds (reduces decode pressure and keeps UI stable).
            val targetSec = (progress / SEEK_BAR_PRECISION).toDouble()
            pendingSeekbarSeekPos = targetSec
            // Cancel any in-flight scrub seek so no new frame appears while moving.
            if (lastScrubAsyncUserdata != 0L) {
                abortLastScrubSeek()
            }

            val posText = Utils.prettyTime(targetSec.toInt())
            if (binding.gestureTextView.visibility != View.VISIBLE)
                refreshPlayerOverlay()
            fadeHandler.removeCallbacks(fadeRunnable3)
            binding.gestureTextView.visibility = View.VISIBLE
            binding.gestureTextView.text = posText

            // Re-schedule idle exact seek.
            scrubSeekHandler.removeCallbacks(seekbarIdleSeekRunnable)
            scrubSeekHandler.postDelayed(seekbarIdleSeekRunnable, SCRUB_IDLE_SEEK_DELAY_MS)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {
            refreshPlayerOverlay()
            userIsOperatingSeekbar = true
            seekbarScrubActive = true
            pendingSeekbarSeekPos = null
            lastIssuedSeekbarSeekPos = null

            // Pause while scrubbing (keep paused if it already was).
            pausedForSeekbar = if (psc.pause) 2 else 1
            if (pausedForSeekbar == 1) player.paused = true

            fadeHandler.removeCallbacks(fadeRunnable3)
            binding.gestureTextView.visibility = View.VISIBLE
            binding.gestureTextView.text = ""
        }

        override fun onStopTrackingTouch(seekBar: SeekBar) {
            userIsOperatingSeekbar = false
            seekbarScrubActive = false

            scrubSeekHandler.removeCallbacks(seekbarIdleSeekRunnable)

            val target = pendingSeekbarSeekPos
            val shouldResume = (pausedForSeekbar == 1)
            if (shouldResume) resumeAfterScrubSeek = true

            if (target != null && lastIssuedSeekbarSeekPos != target) {
                lastIssuedSeekbarSeekPos = target
                sendScrubSeek(target, exact = true)
            }

            // If nothing is in-flight, resume immediately.
            if (shouldResume && !scrubSeekInFlight) {
                resumeAfterScrubSeek = false
                player.paused = false
            }

            binding.gestureTextView.visibility = View.GONE
            showControls() // re-trigger display timeout
        }
    }

    private var becomingNoisyReceiverRegistered = false
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS, "noisy")
            }
        }
    }

    // Fade out controls
    private val fadeRunnable = object : Runnable {
        var hasStarted = false
        private val listener = object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) { hasStarted = true }

            override fun onAnimationCancel(animation: Animator) { hasStarted = false }

            override fun onAnimationEnd(animation: Animator) {
                if (hasStarted)
                    hideControls()
                hasStarted = false
            }
        }

        override fun run() {
            binding.topControls.animate().alpha(0f).setDuration(CONTROLS_FADE_OUT_DURATION)
            binding.controls.animate().alpha(0f).setDuration(CONTROLS_FADE_OUT_DURATION).setListener(listener)
        }
    }

    // Fade out unlock button
    private val fadeRunnable2 = object : Runnable {
        private val listener = object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                binding.unlockBtn.visibility = View.GONE
            }
        }

        override fun run() {
            binding.unlockBtn.animate().alpha(0f).setDuration(CONTROLS_FADE_OUT_DURATION).setListener(listener)
        }
    }

    // Fade out gesture text
    private val fadeRunnable3 = object : Runnable {
        // okay this doesn't actually fade...
        override fun run() {
            binding.gestureTextView.visibility = View.GONE
        }
    }

    private val stopServiceRunnable = Runnable {
        val intent = Intent(this, BackgroundPlaybackService::class.java)
        applicationContext.stopService(intent)
    }

    /* Settings */
    private var statsFPS = false
    private var statsLuaMode = 0 // ==0 disabled, >0 page number

    private var backgroundPlayMode = ""
    private var noUIPauseMode = ""

    private var shouldSavePosition = false
    @Volatile private var currentFilePath: String? = null
    @Volatile private var fileStateReady = false
    @Volatile private var restoringPerFileState = false
    private var lastPositionAutosaveSecond = -1L
    private var explicitStartForNextFile = false
    private var explicitStartMediaPath: String? = null
    @Volatile private var pendingPrimarySubtitleSelection: Int? = null
    @Volatile private var pendingSecondarySubtitleSelection: Int? = null
    @Volatile private var pendingAudioSelection: Int? = null
    @Volatile private var pendingPrimarySubtitleExternal: String? = null
    @Volatile private var pendingSecondarySubtitleExternal: String? = null
    @Volatile private var pendingAudioExternal: String? = null
    private val trackStateCheckpointRunnable = Runnable {
        checkpointCurrentFileState()
    }

    private var autoRotationMode = ""

    private var controlsAtBottom = true
    private var showMediaTitle = false
    private var useTimeRemaining = false

    private var ignoreAudioFocus = false
    private var playlistExitWarning = true
    private var newIntentReplace = false

    private var smoothSeekGesture = false
    /* * */

    @SuppressLint("ClickableViewAccessibility")
    private fun initListeners() {
        with (binding) {
            prevBtn.setOnClickListener { playlistPrev() }
            nextBtn.setOnClickListener { playlistNext() }
            cycleAudioBtn.setOnClickListener { cycleAudio() }
            cycleSubsBtn.setOnClickListener { cycleSub() }
            playBtn.setOnClickListener { player.cyclePause() }
            cycleDecoderBtn.setOnClickListener { player.cycleHwdec() }
            cycleSpeedBtn.setOnClickListener { cycleSpeed() }
            topLockBtn.setOnClickListener { lockUI() }
            topPiPBtn.setOnClickListener { goIntoPiP() }
            topMenuBtn.setOnClickListener { openTopMenu() }
            unlockBtn.setOnClickListener { unlockUI() }
            playbackDurationTxt.setOnClickListener {
                useTimeRemaining = !useTimeRemaining
                updatePlaybackPos(psc.positionSec)
                updatePlaybackDuration(psc.durationSec)
            }

            cycleAudioBtn.setOnLongClickListener { pickAudio(); true }
            cycleSpeedBtn.setOnLongClickListener { pickSpeed(); true }
            cycleSubsBtn.setOnLongClickListener { pickSub(); true }
            prevBtn.setOnLongClickListener { openPlaylistMenu(pauseForDialog()); true }
            nextBtn.setOnLongClickListener { openPlaylistMenu(pauseForDialog()); true }
            cycleDecoderBtn.setOnLongClickListener { pickDecoder(); true }

            playbackSeekbar.setOnSeekBarChangeListener(seekBarChangeListener)
        }

        // NOTE: touch events must come from an untransformed overlay view (gestureLayer).
        // The player view is transformed for zoom/pan, so attaching gestures directly to it
        // would inverse-transform MotionEvents and create feedback/jitter.
        binding.gestureLayer.setOnTouchListener { _, e ->
            if (lockedUI)
                return@setOnTouchListener false

            if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN)
                gestures.cancel()

            val blockDefault = zoomGestures.shouldBlockOtherGestures(e)
            val handledByZoom = zoomGestures.onTouchEvent(e)

            when {
                blockDefault -> handledByZoom
                else -> gestures.onTouchEvent(e)
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.outside) { _, windowInsets ->
            // guidance: https://medium.com/androiddevelopers/gesture-navigation-handling-visual-overlaps-4aed565c134c
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val insets2 = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            binding.outside.updateLayoutParams<MarginLayoutParams> {
                // avoid system bars and cutout
                leftMargin = Math.max(insets.left, insets2.left)
                topMargin = Math.max(insets.top, insets2.top)
                bottomMargin = Math.max(insets.bottom, insets2.bottom)
                rightMargin = Math.max(insets.right, insets2.right)
            }
            WindowInsetsCompat.CONSUMED
        }

        onBackPressedDispatcher.addCallback(this) {
            onBackPressedImpl()
        }

        addOnPictureInPictureModeChangedListener { info ->
            onPiPModeChangedImpl(info.isInPictureInPictureMode)
        }
    }

    private var playbackHasStarted = false
    private var onloadCommands = mutableListOf<Array<String>>()

    // Activity lifetime

        override fun onCreate(icicle: Bundle?) {
            super.onCreate(icicle)

            // Remember the orientation we entered the player in, so we can restore it on exit.
            entryConfigOrientation = resources.configuration.orientation
            exitRequestedOrientation = when (entryConfigOrientation) {
                Configuration.ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                Configuration.ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }

            // Prepare binding/gestures early so onConfigurationChanged is safe even if we change orientation immediately.
            binding = PlayerBinding.inflate(layoutInflater)
            gestures = TouchGestures(this)
            zoomGestures = VideoZoomGestures(binding.player)
            binding.player.onSurfaceTextureFrameAvailable = { onPlayerSurfaceFrameAvailable() }

            // Do these here and not in MainActivity because mpv can be launched from a file browser.
            Utils.copyAssets(this)
            BackgroundPlaybackService.createNotificationChannel(this)

            // Parse intent early so we can force the correct orientation before mpv starts.
            if (intent.action == Intent.ACTION_VIEW)
                parseIntentExtras(intent.extras)
            val filepath = parsePathFromIntent(intent)
            if (filepath == null) {
                Log.e(TAG, "No file given, exiting")
                showToast(getString(R.string.error_no_file))
                finishWithResult(RESULT_CANCELED)
                return
            }
            if (explicitStartForNextFile)
                explicitStartMediaPath = filepath

            // Read only the auto-rotation setting early (full settings are read later).
            run {
                val prefs = getDefaultSharedPreferences(applicationContext)
                val defaultMode = resources.getString(R.string.pref_auto_rotation_default)
                val mode = prefs.getString("auto_rotation", defaultMode) ?: defaultMode
                if (autoRotationMode != "manual")
                    autoRotationMode = mode
            }

            // If we're in auto mode, probe the file's orientation BEFORE starting mpv.
            // This avoids showing the first video frame in portrait and then rotating to landscape.
            if (autoRotationMode == "auto" &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_SCREEN_PORTRAIT)
            ) {
                val probed = probeOrientationFromMetadata(filepath)
                val desired = when (probed) {
                    ProbedOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    ProbedOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    ProbedOrientation.SQUARE -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    ProbedOrientation.UNKNOWN -> null
                }

                if (desired != null) {
                    // If we're already portrait and the app is not locked, don't force-lock portrait.
                    val skipPortrait =
                        desired == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT &&
                            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                            requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

                    if (!skipPortrait) {
                        lastOrientationProbePath = filepath
                        if (requestedOrientation != desired)
                            requestedOrientation = desired

                        // Hold the chosen orientation briefly so we don't get a late flip back
                        // to portrait if mpv reports an unknown/square aspect during startup.
                        lockOrientationStability(desired, 2200L)

                        startupDesiredConfigOrientation = when (desired) {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> Configuration.ORIENTATION_LANDSCAPE
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT -> Configuration.ORIENTATION_PORTRAIT
                            else -> Configuration.ORIENTATION_UNDEFINED
                        }

                        deferPlayerInit =
                            startupDesiredConfigOrientation != Configuration.ORIENTATION_UNDEFINED &&
                                resources.configuration.orientation != startupDesiredConfigOrientation
                    }
                }
            }

            if (deferPlayerInit) {
                startupFilePath = filepath

                // Avoid briefly showing the UI in the wrong orientation (portrait -> landscape flash).
                // Show a simple black placeholder until Android applies the requested orientation.
                window.decorView.setBackgroundColor(Color.BLACK)
                setContentView(View(this).apply { setBackgroundColor(Color.BLACK) })
                return
            }

            setupUiAndStart(filepath)
        }

    private fun setupUiAndStart(filepath: String) {
        if (uiInitialized)
            return

        setContentView(binding.root)
        uiInitialized = true
        refreshPlayerOverlay()

        // Init controls to be hidden and view fullscreen
        hideControls()

        // Initialize listeners for the player view
        initListeners()
        installGestureMetricsUpdater()

        // Read full settings and update UI
        readSettings()
        onConfigurationChanged(resources.configuration)

        // Edge-to-edge / immersive behavior
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        installImeDismissImmersiveRestore(window, binding.root)

        // Hide PiP / lock buttons on devices that don't support them
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
            binding.topPiPBtn.visibility = View.GONE
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN))
            binding.topLockBtn.visibility = View.GONE

        if (showMediaTitle)
            binding.controlsTitleGroup.visibility = View.VISIBLE

        updateOrientation(true)

        setVideoGeometryBlackout(true)

        startPlayback(filepath)
    }


    private var playbackInitialized: Boolean = false

    private fun startPlayback(filepath: String) {
        if (playbackInitialized)
            return
        playbackInitialized = true

        player.onFileLocalOptionChanged = { name, value ->
            rememberFileLocalOption(name, value)
        }
        player.onPersistCurrentFileState = {
            scheduleTrackStateCheckpoint()
        }
        player.addObserver(this)
        player.initialize(filesDir.path, cacheDir.path)
        player.playFile(filepath)

        mediaSession = initMediaSession()
        updateMediaSession()
        BackgroundPlaybackService.mediaToken = mediaSession?.sessionToken

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val audioSessionId = audioManager!!.generateAudioSessionId()
        if (audioSessionId != AudioManager.ERROR)
            player.setAudioSessionId(audioSessionId)
        else
            Log.w(TAG, "AudioManager.generateAudioSessionId() returned error")

        volumeControlStream = STREAM_TYPE
    }

    private fun maybeStartDeferredPlayback() {
        if (!deferPlayerInit)
            return

        val desired = startupDesiredConfigOrientation
        if (desired == Configuration.ORIENTATION_UNDEFINED || resources.configuration.orientation == desired) {
            // Clear the deferred state FIRST to avoid re-entrancy if we call onConfigurationChanged() manually.
            val fp = startupFilePath
            startupFilePath = null
            deferPlayerInit = false

            if (fp != null) {
                if (!uiInitialized)
                    setupUiAndStart(fp)
                else
                    startPlayback(fp)
            }
        }
    }

    private fun setVideoGeometryBlackout(visible: Boolean) {
        if (visible) {
            videoGeometryBlackoutGeneration += 1
            videoGeometryBlackoutRevealArmed = false
            videoGeometryBlackoutFileLoadedSeen = false
        }
        videoGeometryBlackoutActive = visible
        if (!::binding.isInitialized || !uiInitialized)
            return

        binding.videoBlackoutOverlay.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun beginVideoGeometryBlackout() {
        setVideoGeometryBlackout(true)
        if (::zoomGestures.isInitialized) {
            try { zoomGestures.resetForNewFile() } catch (_: Throwable) {}
        }
    }

    private fun armVideoGeometryBlackoutReveal() {
        if (!videoGeometryBlackoutActive || videoGeometryBlackoutRevealArmed)
            return
        videoGeometryBlackoutRevealArmed = true
    }

    private fun onPlayerSurfaceFrameAvailable() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            eventUiHandler.post { onPlayerSurfaceFrameAvailable() }
            return
        }

        if (!videoGeometryBlackoutActive || !videoGeometryBlackoutRevealArmed)
            return
        if (videoGeometryBlackoutActive && !videoGeometryBlackoutFileLoadedSeen)
            return
        if (!hasDisplayableVideoGeometry())
            return

        val generation = videoGeometryBlackoutGeneration
        ViewCompat.postOnAnimation(binding.player) {
            if (videoGeometryBlackoutActive &&
                videoGeometryBlackoutRevealArmed &&
                generation == videoGeometryBlackoutGeneration &&
                videoGeometryBlackoutFileLoadedSeen &&
                hasDisplayableVideoGeometry()
            ) {
                videoGeometryBlackoutRevealArmed = false
                setVideoGeometryBlackout(false)
            }
        }
    }

    private fun hasDisplayableVideoGeometry(): Boolean {
        val aspect = try { player.getEffectiveVideoAspect() ?: 0.0 } catch (_: Throwable) { 0.0 }
        val size = try { player.getVideoPixelSize() } catch (_: Throwable) { null }
        return aspect > 0.001 && size != null
    }

    private fun isAspectMenuGeometrySyncSuppressed(): Boolean {
        return !videoGeometryBlackoutActive &&
            SystemClock.uptimeMillis() < suppressAspectMenuGeometrySyncUntilMs
    }

    private fun syncZoomVideoGeometry(
        prepareNormalSurface: Boolean = false,
        immediate: Boolean = false,
    ) {
        if (!::zoomGestures.isInitialized || isAspectMenuGeometrySyncSuppressed())
            return

        val aspect = try { player.getEffectiveVideoAspect() } catch (_: Throwable) { null }
        val size = try { player.getVideoPixelSize() } catch (_: Throwable) { null }
        val pan = try { player.getPanscan() } catch (_: Throwable) { 0.0 }

        try {
            zoomGestures.setVideoGeometry(
                aspect = aspect,
                pixelSize = size,
                panscanValue = pan,
                prepareNormalSurface = prepareNormalSurface,
                immediate = immediate,
            )
        } catch (_: Throwable) {}
    }

    private fun prepareZoomSurfaceAndRevealWhenReady() {
        if (!::zoomGestures.isInitialized)
            return

        if (videoGeometryBlackoutActive && !videoGeometryBlackoutFileLoadedSeen)
            return
        if (!hasDisplayableVideoGeometry())
            return

        // Pull all geometry at once while the blackout is still covering mpv.
        // The blackout is removed only after TextureView reports a real frame
        // update with this geometry, which avoids revealing a stale fullscreen
        // or old-aspect buffer on heavy images/videos.
        syncZoomVideoGeometry(prepareNormalSurface = true, immediate = true)
        try { zoomGestures.prepareForVisibleMedia() } catch (_: Throwable) {}
        armVideoGeometryBlackoutReveal()
    }

    private fun prepareZoomSurfaceForWindowExit() {
        if (!::zoomGestures.isInitialized || !::binding.isInitialized)
            return

        try {
            zoomGestures.prepareForWindowExit()
        } catch (_: Throwable) {
            // ignore; finish must continue
        }
    }

    private fun finishWithResult(code: Int, includeTimePos: Boolean = false) {
        // Refer to http://mpv-android.github.io/mpv-android/intent.html
        // FIXME: should track end-file events to accurately report OK vs CANCELED
        if (isFinishing || finishPending) // only count first call
            return
        finishPending = true

        val result = Intent(RESULT_INTENT)
        result.data = if (intent.data?.scheme == "file") null else intent.data
        if (includeTimePos) {
            result.putExtra("position", psc.position.toInt())
            result.putExtra("duration", psc.duration.toInt())
        }
        setResult(code, result)

        // Avoid letting Android's activity/window transition animate a transformed
        // TextureView. The player is about to close, so return it to the plain
        // mpv surface before finish/rotation starts.
        prepareZoomSurfaceForWindowExit()

        // Restore the orientation we entered with. This also bypasses the system auto-rotate lock,
        // so the next activity does not briefly appear in the wrong orientation.
        val needRestore = packageManager.hasSystemFeature(PackageManager.FEATURE_SCREEN_PORTRAIT) &&
                exitRequestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED &&
                resources.configuration.orientation != entryConfigOrientation

        if (needRestore) {
            pendingFinishAfterRotate = true
            // Hide the player UI so the user doesn't see the intermediate rotation.
            try {
                binding.root.alpha = 0f
            } catch (_: Throwable) { /* ignore */ }
            requestedOrientation = exitRequestedOrientation
            // Fallback in case we don't receive a configuration callback.
            eventUiHandler.postDelayed({
                if (pendingFinishAfterRotate) {
                    pendingFinishAfterRotate = false
                    finish()
                }
            }, 600)
            return
        }

        finish()
    }

    override fun onDestroy() {
        Log.v(TAG, "Exiting.")

        // Suppress any further callbacks
        activityIsForeground = false
        immersiveHandler.removeCallbacksAndMessages(null)

        if (becomingNoisyReceiverRegistered) {
            unregisterReceiver(becomingNoisyReceiver)
            becomingNoisyReceiverRegistered = false
        }

        BackgroundPlaybackService.mediaToken = null
        mediaSession?.let {
            it.isActive = false
            it.release()
        }
        mediaSession = null

        audioFocusRequest?.let {
            AudioManagerCompat.abandonAudioFocusRequest(audioManager!!, it)
        }
        audioFocusRequest = null

        // take the background service with us
        stopServiceRunnable.run()

        perFileStateHandler.removeCallbacksAndMessages(null)
        player.onFileLocalOptionChanged = null
        player.onPersistCurrentFileState = null
        player.removeObserver(this)
        player.destroy()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        Log.v(TAG, "onNewIntent($intent)")
        super.onNewIntent(intent)

        // Happens when mpv is still running (not necessarily playing) and the user selects a new
        // file to be played from another app
        val filepath = intent?.let { parsePathFromIntent(it) }
        if (filepath == null) {
            return
        }

        intent?.let {
            if (it.action == Intent.ACTION_VIEW) {
                parseIntentExtras(it.extras)
                if (explicitStartForNextFile)
                    explicitStartMediaPath = filepath
            }
        }

        if (!activityIsForeground && didResumeBackgroundPlayback) {
            if (this.newIntentReplace) {
                persistBeforeFileReplacement()
                MPVLib.command(arrayOf("loadfile", filepath, "replace"))
                showToast(getString(R.string.notice_file_play))
            } else {
                MPVLib.command(arrayOf("loadfile", filepath, "append"))
                showToast(getString(R.string.notice_file_appended))
            }
            moveTaskToBack(true)
        } else {
            persistBeforeFileReplacement()
            MPVLib.command(arrayOf("loadfile", filepath))
        }
    }

    private fun updateAudioPresence() {
        val haveAudio = MPVLib.getPropertyBoolean("current-tracks/audio/selected")
        if (haveAudio == null) {
            // If we *don't know* if there's an active audio track then don't update to avoid
            // spurious UI changes. The property will become available again later.
            return
        }
        isPlayingAudio = (haveAudio && MPVLib.getPropertyBoolean("mute") != true)
    }

    private fun isPlayingAudioOnly(): Boolean {
        if (!isPlayingAudio)
            return false
        val image = MPVLib.getPropertyString("current-tracks/video/image")
        return image.isNullOrEmpty() || image == "yes"
    }

    private fun shouldBackground(): Boolean {
        if (isFinishing) // about to exit?
            return false
        return when (backgroundPlayMode) {
            "always" -> true
            "audio-only" -> isPlayingAudioOnly()
            else -> false // "never"
        }
    }

    override fun onPause() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (isInMultiWindowMode || isInPictureInPictureMode) {
                Log.v(TAG, "Going into multi-window mode")
                super.onPause()
                return
            }
        }

        onPauseImpl()
    }

    private fun tryStartForegroundService(intent: Intent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                ContextCompat.startForegroundService(this, intent)
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, e)
                return false
            }
        } else {
            ContextCompat.startForegroundService(this, intent)
        }
        return true
    }

    private fun onPauseImpl() {
        val fmt = MPVLib.getPropertyString("video-format")
        val shouldBackground = shouldBackground()
        if (shouldBackground && !fmt.isNullOrEmpty())
            BackgroundPlaybackService.thumbnail = MPVLib.grabThumbnail(THUMB_SIZE)
        else
            BackgroundPlaybackService.thumbnail = null
        // media session uses the same thumbnail
        updateMediaSession()

        activityIsForeground = false
        eventUiHandler.removeCallbacksAndMessages(null)
        cancelPendingTapToggle()
        if (isFinishing) {
            savePosition()
            fileStateReady = false
            clearPendingTrackSelections()
            perFileStateHandler.removeCallbacks(trackStateCheckpointRunnable)
            // tell mpv to shut down so that any other property changes or such are ignored,
            // preventing useless busywork
            MPVLib.command(arrayOf("stop"))
        } else if (!shouldBackground) {
            player.paused = true
            // Persist per-file state even if the process is later killed (Home -> kill).
            savePosition()
        } else {
            // Background playback mode: still persist state once when leaving UI.
            savePosition()
        }
        writeSettings()
        super.onPause()

        didResumeBackgroundPlayback = shouldBackground
        if (shouldBackground) {
            Log.v(TAG, "Resuming playback in background")
            stopServiceHandler.removeCallbacks(stopServiceRunnable)
            val serviceIntent = Intent(this, BackgroundPlaybackService::class.java)
            if (!tryStartForegroundService(serviceIntent)) {
                didResumeBackgroundPlayback = false
                player.paused = true
            }
        }
    }

    private fun readSettings() {
        // FIXME: settings should be in their own class completely
        val prefs = getDefaultSharedPreferences(applicationContext)
        val getString: (String, Int) -> String = { key, defaultRes ->
            prefs.getString(key, resources.getString(defaultRes))!!
        }

        gestures.syncSettings(prefs, resources)

        val statsMode = prefs.getString("stats_mode", "") ?: ""
        this.statsFPS = statsMode == "native_fps"
        this.statsLuaMode = if (statsMode.startsWith("lua"))
            statsMode.removePrefix("lua").toInt()
        else
            0
        this.backgroundPlayMode = getString("background_play", R.string.pref_background_play_default)
        this.noUIPauseMode = getString("no_ui_pause", R.string.pref_no_ui_pause_default)
        this.shouldSavePosition = prefs.getBoolean("save_position", false)
        if (!this.shouldSavePosition && playbackInitialized)
            currentFilePath?.let { clearStoredPlaybackPosition(applicationContext, it) }
        if (this.autoRotationMode != "manual") // don't reset
            this.autoRotationMode = getString("auto_rotation", R.string.pref_auto_rotation_default)
        this.controlsAtBottom = prefs.getBoolean("bottom_controls", true)
        this.showMediaTitle = prefs.getBoolean("display_media_title", false)
        this.useTimeRemaining = prefs.getBoolean("use_time_remaining", false)
        this.ignoreAudioFocus = prefs.getBoolean("ignore_audio_focus", false)
        this.playlistExitWarning = prefs.getBoolean("playlist_exit_warning", true)
        this.newIntentReplace = prefs.getBoolean("new_intent_replace", false)
        this.smoothSeekGesture = prefs.getBoolean("seek_gesture_smooth", false)
    }

    private fun writeSettings() {
        val prefs = getDefaultSharedPreferences(applicationContext)

        with (prefs.edit()) {
            putBoolean("use_time_remaining", useTimeRemaining)
            commit()
        }
    }

    override fun onStart() {
        super.onStart()
        activityIsStopped = false
    }

    override fun onStop() {
        super.onStop()
        activityIsStopped = true

        // Extra safety: persist state when the UI is gone, even if the process is killed.
        // Skip configuration changes (rotation) to avoid needless writes.
        if (!isFinishing && !isChangingConfigurations)
            try { savePosition() } catch (_: Throwable) {}
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Last chance before the system reclaims memory / kills background processes.
        if (level >= TRIM_MEMORY_UI_HIDDEN && !isFinishing)
            try { savePosition() } catch (_: Throwable) {}
    }

    override fun onResume() {
        // If we weren't actually in the background (e.g. multi window mode), don't reinitialize stuff
        if (activityIsForeground) {
            super.onResume()
            refreshPlayerOverlay()
            return
        }

        if (lockedUI) { // precaution
            Log.w(TAG, "resumed with locked UI, unlocking")
            unlockUI()
        }

        // Init controls to be hidden and view fullscreen
        hideControls()
        readSettings()

        activityIsForeground = true
        // stop background service with a delay
        stopServiceHandler.removeCallbacks(stopServiceRunnable)
        stopServiceHandler.postDelayed(stopServiceRunnable, 1000L)

        refreshUi()
        refreshPlayerOverlay()

        super.onResume()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            playerWindowLostFocus = true

            // A top-edge swipe may hand focus to the notification shade. Preserve that manual
            // reveal until focus returns so Android can keep its existing transient-bar behavior.
            if (manualSystemBarsRevealPending)
                immersiveHandler.removeCallbacks(clearManualSystemBarsRevealRunnable)
            return
        }

        if (hasFocus) {
            refreshPlayerOverlay()

            val preserveManualReveal = playerWindowLostFocus && manualSystemBarsRevealPending
            playerWindowLostFocus = false
            manualSystemBarsRevealPending = false
            immersiveHandler.removeCallbacks(clearManualSystemBarsRevealRunnable)

            // Assistant/dialog windows can make the status bar visible after onResume() has
            // already run. Re-apply immersive mode when player focus is restored, except when
            // the focus change came from the user's own top-edge system-bar swipe.
            if (!preserveManualReveal)
                requestPlayerImmersiveRestore()
        }
    }

    private fun savePosition() {
        checkpointCurrentFileState()
        persistCurrentPlaybackPosition()
    }

    /**
     * Requests or abandons audio focus and noisy receiver depending on the playback state.
     * @warning Call from event thread, not UI thread
     */
    private fun handleAudioFocus() {
        if ((psc.pause && !psc.cachePause) || !isPlayingAudio) {
            if (becomingNoisyReceiverRegistered)
                unregisterReceiver(becomingNoisyReceiver)
            becomingNoisyReceiverRegistered = false
            // TODO: could abandon audio focus after a timeout
        } else {
            if (!becomingNoisyReceiverRegistered)
                registerReceiver(
                    becomingNoisyReceiver,
                    IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                )
            becomingNoisyReceiverRegistered = true
            // (re-)request audio focus
            // Note that this will actually request focus every time the user unpauses, refer to discussion in #1066
            if (requestAudioFocus()) {
                onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN, "request")
            } else {
                onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS, "request")
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val manager = audioManager ?: return false
        val req = audioFocusRequest ?:
            with(AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)) {
            setAudioAttributes(with(AudioAttributesCompat.Builder()) {
                // N.B.: libmpv may use different values in ao_audiotrack, but here we always pretend to be music.
                setUsage(AudioAttributesCompat.USAGE_MEDIA)
                setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
                build()
            })
            setOnAudioFocusChangeListener {
                onAudioFocusChange(it, "callback")
            }
            build()
        }
        val res = AudioManagerCompat.requestAudioFocus(manager, req)
        if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusRequest = req
            return true
        }
        return false
    }

    // This handles both "real" audio focus changes by the callbacks, which aren't
    // really used anymore after Android 12 (except for AUDIOFOCUS_LOSS),
    // as well as actions equivalent to a focus change that we make up ourselves.
    private fun onAudioFocusChange(type: Int, source: String) {
        Log.v(TAG, "Audio focus changed: $type ($source)")
        if (ignoreAudioFocus || isFinishing)
            return
        when (type) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // loss can occur in addition to ducking, so remember the old callback
                val oldRestore = audioFocusRestore
                val wasPlayerPaused = player.paused ?: false
                player.paused = true
                audioFocusRestore = {
                    oldRestore()
                    if (!wasPlayerPaused) player.paused = false
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                MPVLib.command(arrayOf("multiply", "volume", AUDIO_FOCUS_DUCKING.toString()))
                audioFocusRestore = {
                    val inv = 1f / AUDIO_FOCUS_DUCKING
                    MPVLib.command(arrayOf("multiply", "volume", inv.toString()))
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                audioFocusRestore()
                audioFocusRestore = {}
            }
        }
    }

    // UI

    /** dpad navigation */
    private var btnSelected = -1

    private var mightWantToToggleControls = false
    private var tapDownX = 0f
    private var tapDownY = 0f

    // Prevent accidental single-tap UI toggle while user swipes from the very top to open
    // Android's notification shade / status bar.
    private var statusBarSwipeCandidate = false
    private var statusBarSwipeStartY = 0f
    private var statusBarSwipeCanceledToggle = false

    private fun isInTopSystemGestureDeadzone(y: Float): Boolean {
        // Use the gesture layer height if available (covers edge-to-edge/immersive scenarios).
        val h = when {
            ::binding.isInitialized && binding.gestureLayer.height > 0 -> binding.gestureLayer.height
            (window?.decorView?.height ?: 0) > 0 -> window.decorView.height
            else -> 0
        }
        if (h <= 0) return false
        return y <= h * STATUS_BAR_DEADZONE_PERCENT / 100f
    }

    private fun statusBarSwipeCancelPx(): Float {
        return STATUS_BAR_SWIPE_CANCEL_DP * resources.displayMetrics.density
    }

    /** true if we're actually outputting any audio (includes the mute state, but not pausing) */
    private var isPlayingAudio = false

    private var useAudioUI = false

    private var lockedUI = false

    private fun pauseForDialog(): StateRestoreCallback {
        // Keep playback running while UI dialogs/menus are open.
        // We still set keep-open so mpv doesn't exit at EOF while the user is interacting with UI.
        val oldValue = MPVLib.getPropertyString("keep-open")
        MPVLib.setPropertyBoolean("keep-open", true)
        return {
            oldValue?.also { MPVLib.setPropertyString("keep-open", it) }
        }
    }

    private fun updateStats() {
        if (!statsFPS)
            return
        binding.statsTextView.text = getString(R.string.ui_fps, player.estimatedVfFps)
    }

    /**
     * Keeps the Android UI overlay above the video layer and forces a redraw.
     *
     * On some devices the TextureView can momentarily win composition/z-order during player
     * startup. Touch still reaches gestureLayer, so seeking works, but controls/gestureTextView
     * do not become visible until the window is redrawn by something external (for example
     * pulling the notification shade). Poking the overlay here makes that redraw deterministic.
     */
    private fun refreshPlayerOverlay() {
        if (!::binding.isInitialized)
            return

        // Keep the touch layer above the video and the actual UI above the touch layer.
        ViewCompat.setElevation(binding.gestureLayer, 1f)
        ViewCompat.setElevation(binding.outside, 2f)
        binding.gestureLayer.bringToFront()
        binding.outside.bringToFront()

        binding.outside.invalidate()
        binding.root.invalidate()

        binding.root.post {
            if (!::binding.isInitialized)
                return@post
            binding.gestureLayer.bringToFront()
            binding.outside.bringToFront()
            binding.outside.requestLayout()
            binding.outside.invalidate()
            binding.root.invalidate()
        }
    }

    private fun controlsShouldBeVisible(): Boolean {
        if (lockedUI)
            return false
        return btnSelected != -1 || userIsOperatingSeekbar
    }

    /** Make controls visible. */
    private fun showControls() {
        if (lockedUI) {
            Log.w(TAG, "cannot show UI in locked mode")
            return
        }

        refreshPlayerOverlay()

        // Cancel any pending fade-out.
        fadeHandler.removeCallbacks(fadeRunnable)
        binding.controls.animate().setListener(null).cancel()
        binding.topControls.animate().setListener(null).cancel()
        binding.statsTextView.animate().setListener(null).cancel()

        val wasHidden = binding.controls.visibility != View.VISIBLE
        val wasDimmed = binding.controls.alpha < 1f || binding.topControls.alpha < 1f

        if (wasHidden) {
            // Start from transparent so we can fade-in quickly (Samsung-like feel).
            binding.controls.alpha = 0f
            binding.topControls.alpha = 0f

            binding.controls.visibility = View.VISIBLE
            binding.topControls.visibility = View.VISIBLE

            if (this.statsFPS) {
                updateStats()
                binding.statsTextView.alpha = 0f
                binding.statsTextView.visibility = View.VISIBLE
            }

            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.show(WindowInsetsCompat.Type.navigationBars())
        }

        if (wasHidden || wasDimmed) {
            binding.controls.animate().alpha(1f).setDuration(CONTROLS_FADE_IN_DURATION)
            binding.topControls.animate().alpha(1f).setDuration(CONTROLS_FADE_IN_DURATION)
            if (this.statsFPS)
                binding.statsTextView.animate().alpha(1f).setDuration(CONTROLS_FADE_IN_DURATION)
        } else {
            // Ensure fully visible in case we were interrupted mid-animation.
            binding.controls.alpha = 1f
            binding.topControls.alpha = 1f
            if (this.statsFPS)
                binding.statsTextView.alpha = 1f
        }

        // Intentionally do NOT auto-hide controls.
        // The user must explicitly toggle them off (single tap), or they will be hidden when
        // opening any in-player menu/dialog.
    }

    /**
     * Force-hide controls regardless of the current UI state.
     *
     * This is used when opening in-player menus/dialogs: controls must disappear immediately,
     * and they must NOT reappear when the menu/dialog is dismissed.
     */
    private fun hideControlsForMenu() {
        // Cancel any pending fade-out so the runnable cannot interfere.
        fadeHandler.removeCallbacks(fadeRunnable)
        binding.controls.animate().setListener(null).cancel()
        binding.topControls.animate().setListener(null).cancel()
        binding.statsTextView.animate().setListener(null).cancel()

        // Hide instantly (use GONE because of SurfaceView bug, see hideControls()).
        binding.controls.visibility = View.GONE
        binding.topControls.visibility = View.GONE
        binding.statsTextView.visibility = View.GONE

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** Hide controls instantly */
    fun hideControls() {
        if (controlsShouldBeVisible())
            return
        // use GONE here instead of INVISIBLE (which makes more sense) because of Android bug with surface views
        // see http://stackoverflow.com/a/12655713/2606891
        binding.controls.visibility = View.GONE
        binding.topControls.visibility = View.GONE
        binding.statsTextView.visibility = View.GONE

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    /**
     * Some devices/OEMs temporarily show the status bar when a dialog/menu window gains focus.
     * We want the player to stay immersive, and only allow revealing system bars via swipe.
     *
     * IMPORTANT: to prevent even a brief "flash" of the status bar, we must apply immersive
     * flags BEFORE the dialog becomes focusable (Samsung/OEM behavior).
     */
    private fun applyImmersiveToWindow(w: Window) {
        WindowCompat.setDecorFitsSystemWindows(w, false)
        val controller = WindowCompat.getInsetsController(w, w.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** Apply immersive mode only to the active video player activity. */
    private fun applyPlayerImmersiveModeIfPossible() {
        if (!uiInitialized || !activityIsForeground || isFinishing || isDestroyed)
            return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode)
            return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode)
            return

        applyImmersiveToWindow(window)
    }

    /**
     * Restore immersive mode immediately and once more after the current window-focus/insets
     * transaction. The second pass handles OEMs that re-show the status bar just after focus is
     * returned from Assistant or an input window.
     */
    private fun requestPlayerImmersiveRestore() {
        immersiveHandler.removeCallbacks(restorePlayerImmersiveRunnable)
        applyPlayerImmersiveModeIfPossible()
        immersiveHandler.postDelayed(
            restorePlayerImmersiveRunnable,
            IMMERSIVE_RESTORE_RETRY_MS
        )
    }

    /**
     * Re-hide system bars when the soft keyboard changes from visible to hidden. This listener is
     * installed only on windows belonging to MPVActivity and its in-player dialogs.
     */
    private fun installImeDismissImmersiveRestore(w: Window, insetsHost: View) {
        var imeWasVisible =
            ViewCompat.getRootWindowInsets(insetsHost)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true

        ViewCompat.setOnApplyWindowInsetsListener(insetsHost) { view, insets ->
            val imeIsVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeWasVisible && !imeIsVisible) {
                view.post {
                    if (view.isAttachedToWindow)
                        applyImmersiveToWindow(w)
                    requestPlayerImmersiveRestore()
                }
            }
            imeWasVisible = imeIsVisible
            insets
        }
        ViewCompat.requestApplyInsets(insetsHost)
    }

    private fun markManualSystemBarsReveal() {
        manualSystemBarsRevealPending = true
        immersiveHandler.removeCallbacks(clearManualSystemBarsRevealRunnable)
        immersiveHandler.postDelayed(
            clearManualSystemBarsRevealRunnable,
            MANUAL_SYSTEM_BARS_GESTURE_WINDOW_MS
        )
    }

    /**
     * Show an AlertDialog in true immersive mode (prevents the status bar from appearing at all).
     * This uses FLAG_NOT_FOCUSABLE to keep the dialog from taking focus until after we apply
     * the same immersive flags as the activity window.
     */
    private fun showImmersiveDialog(dialog: AlertDialog) {
        // Requirement: when *any* in-player menu/dialog opens, controls must hide immediately,
        // and must not automatically reappear when the dialog closes.
        hideControlsForMenu()

        // Prevent the dialog window from taking focus first (avoids system bars flashing in).
        dialog.window?.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )

        dialog.show()

        val w = dialog.window ?: return
        // Copy the current immersive flags from the activity.
        @Suppress("DEPRECATION")
        w.decorView.systemUiVisibility = window.decorView.systemUiVisibility

        applyImmersiveToWindow(w)

        val insetsHost = w.findViewById<View>(android.R.id.content) ?: w.decorView
        installImeDismissImmersiveRestore(w, insetsHost)

        // Dismissing a keyboard-backed dialog can detach its window before a final IME-insets
        // callback arrives. Restore the player window as soon as that dialog view is detached.
        w.decorView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                requestPlayerImmersiveRestore()
            }
        })

        // Now allow focus/input again.
        w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

        // Re-apply once after focus is restored (extra safety on some ROMs).
        applyImmersiveToWindow(w)
    }

    // Backwards-compatible helper for any existing onShow listeners.
    private fun applyImmersiveToDialog(dialog: AlertDialog) {
        dialog.window?.let { applyImmersiveToWindow(it) }
    }



    /** Start fading out the controls */
    private fun hideControlsFade() {
        fadeHandler.removeCallbacks(fadeRunnable)
        fadeHandler.post(fadeRunnable)
    }

    /**
     * Toggle visibility of controls (if allowed)
     * @return future visibility state
     */
    private fun toggleControls(): Boolean {
        if (lockedUI)
            return false
        if (controlsShouldBeVisible())
            return true
        return if (binding.controls.visibility == View.VISIBLE && !fadeRunnable.hasStarted) {
            hideControlsFade()
            false
        } else {
            showControls()
            true
        }
    }

    private fun showUnlockControls() {
        fadeHandler.removeCallbacks(fadeRunnable2)
        binding.unlockBtn.animate().setListener(null).cancel()

        binding.unlockBtn.alpha = 1f
        binding.unlockBtn.visibility = View.VISIBLE

        fadeHandler.postDelayed(fadeRunnable2, CONTROLS_DISPLAY_TIMEOUT)
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (lockedUI) {
            showUnlockControls()
            return super.dispatchKeyEvent(ev)
        }

        // try built-in event handler first, forward all other events to libmpv
        val handled = interceptDpad(ev) ||
                (ev.action == KeyEvent.ACTION_DOWN && interceptKeyDown(ev)) ||
                player.onKey(ev)
        if (handled) {
            return true
        }
        return super.dispatchKeyEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean {
        if (lockedUI)
            return super.dispatchGenericMotionEvent(ev)

        if (ev != null && ev.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            if (player.onPointerEvent(ev))
                return true
            // keep controls visible when mouse moves
            if (ev.actionMasked == MotionEvent.ACTION_HOVER_MOVE)
                showControls()
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (lockedUI) {
            if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_DOWN)
                showUnlockControls()
            return super.dispatchTouchEvent(ev)
        }

        // For tap-to-toggle, we delay the single-tap action slightly.
        // We DO NOT cancel on the 2nd tap preemptively. Instead, we cancel only if TouchGestures
        // actually confirms and handles a double-tap (see onPropertyChange for PlayPause/SeekFixed/Custom).
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Zoom mode uses double-tap to reset zoom (handled by VideoZoomGestures), not TouchGestures.
                // Cancel any pending single-tap toggle from the previous tap so the UI won't flash/appear.
                if (::zoomGestures.isInitialized && zoomGestures.shouldBlockOtherGestures(ev)) {
                    cancelPendingTapToggle()
                }
                mightWantToToggleControls = true
                tapDownX = ev.x
                tapDownY = ev.y

                // If the gesture starts from the very top, treat it as a possible status-bar swipe.
                // We'll only cancel the tap-to-toggle if the finger moves down noticeably.
                statusBarSwipeCandidate = isInTopSystemGestureDeadzone(ev.y)
                statusBarSwipeStartY = ev.y
                statusBarSwipeCanceledToggle = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Multi-touch/pinch gestures are not taps; never toggle controls for them.
                cancelPendingTapToggle()
                mightWantToToggleControls = false
            }
        }

        if (ev.actionMasked == MotionEvent.ACTION_MOVE) {
            // Any meaningful movement turns the interaction into a drag/swipe, so controls should
            // only toggle for a real tap and never for horizontal or vertical swipes.
            if (mightWantToToggleControls) {
                val dx = ev.x - tapDownX
                val dy = ev.y - tapDownY
                val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
                if (dx * dx + dy * dy > touchSlop * touchSlop) {
                    mightWantToToggleControls = false
                    cancelPendingTapToggle()
                }
            }

            if (statusBarSwipeCandidate && !statusBarSwipeCanceledToggle) {
                // User is likely pulling down the notification shade; don't show player controls.
                if (ev.y - statusBarSwipeStartY > statusBarSwipeCancelPx()) {
                    statusBarSwipeCanceledToggle = true
                    markManualSystemBarsReveal()
                    mightWantToToggleControls = false
                    cancelPendingTapToggle()
                }
            }
        }

        if (super.dispatchTouchEvent(ev)) {
            // reset delay if the event has been handled
            // ideally we'd want to know if the event was delivered to controls, but we can't
            if (binding.controls.visibility == View.VISIBLE && !fadeRunnable.hasStarted)
                showControls()
            // Always reset status-bar swipe tracking when a gesture ends, even if a child view
            // handled the event.
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                statusBarSwipeCandidate = false
                statusBarSwipeCanceledToggle = false
            }
            if (ev.action == MotionEvent.ACTION_UP)
                return true
        }

        if (ev.actionMasked == MotionEvent.ACTION_UP) {
            // Reset status-bar swipe tracking.
            statusBarSwipeCandidate = false
            statusBarSwipeCanceledToggle = false

            if (!mightWantToToggleControls)
                return true

            // Delay the single-tap toggle slightly so TouchGestures can recognize and handle
            // a possible double-tap. If a double-tap *is* handled, onPropertyChange will cancel.
            scheduleSingleTapToggle()
            mightWantToToggleControls = false
        }
        if (ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            cancelPendingTapToggle()
            mightWantToToggleControls = false
            statusBarSwipeCandidate = false
            statusBarSwipeCanceledToggle = false
        }
        return true
    }
    private fun cancelPendingTapToggle() {
        pendingTapToggleRunnable?.let { tapToggleHandler.removeCallbacks(it) }
        pendingTapToggleRunnable = null
    }

    private fun scheduleSingleTapToggle() {
        cancelPendingTapToggle()
        val r = Runnable {
            pendingTapToggleRunnable = null
            toggleControls()
        }
        pendingTapToggleRunnable = r
        tapToggleHandler.postDelayed(r, SINGLE_TAP_TOGGLE_DELAY_MS)
    }

    /**
     * Returns views eligible for dpad button navigation
     */
    private fun dpadButtons(): Sequence<View> {
        val groups = arrayOf(binding.controlsButtonGroup, binding.topControls)
        return sequence {
            for (g in groups) {
                for (i in 0 until g.childCount) {
                    val view = g.getChildAt(i)
                    if (view.isEnabled && view.isVisible && view.isFocusable)
                        yield(view)
                }
            }
        }
    }

    private fun interceptDpad(ev: KeyEvent): Boolean {
        if (btnSelected == -1) { // UP and DOWN are always grabbed and overridden
            when (ev.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (ev.action == KeyEvent.ACTION_DOWN) { // activate dpad navigation
                        btnSelected = 0
                        updateSelectedDpadButton()
                        showControls()
                    }
                    return true
                }
            }
            return false
        }

        // this runs when dpad navigation is active:
        when (ev.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (ev.action == KeyEvent.ACTION_DOWN) { // deactivate dpad navigation
                    btnSelected = -1
                    updateSelectedDpadButton()
                    hideControlsFade()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (ev.action == KeyEvent.ACTION_DOWN) {
                    btnSelected = (btnSelected + 1) % dpadButtons().count()
                    updateSelectedDpadButton()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (ev.action == KeyEvent.ACTION_DOWN) {
                    val count = dpadButtons().count()
                    btnSelected = (count + btnSelected - 1) % count
                    updateSelectedDpadButton()
                }
                return true
            }
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (ev.action == KeyEvent.ACTION_UP) {
                    val view = dpadButtons().elementAtOrNull(btnSelected)
                    // 500ms appears to be the standard
                    if (ev.eventTime - ev.downTime > 500L)
                        view?.performLongClick()
                    else
                        view?.performClick()
                }
                return true
            }
        }
        return false
    }

    private fun updateSelectedDpadButton() {
        val colorFocused = ContextCompat.getColor(this, R.color.tint_btn_bg_focused)
        val colorNoFocus = ContextCompat.getColor(this, R.color.tint_btn_bg_nofocus)

        dpadButtons().forEachIndexed { i, child ->
            if (i == btnSelected)
                child.setBackgroundColor(colorFocused)
            else
                child.setBackgroundColor(colorNoFocus)
        }
    }

    private fun interceptKeyDown(event: KeyEvent): Boolean {
        // intercept some keys to provide functionality "native" to
        // mpv-android even if libmpv already implements these
        var unhandled = 0

        when (event.unicodeChar.toChar()) {
            // (overrides default bindings)
            'j' -> cycleSub()
            '#' -> cycleAudio()
            '<' -> playlistPrev()
            '>' -> playlistNext()

            else -> unhandled++
        }
        // Note: dpad center is bound according to how Android TV apps should generally behave,
        // see <https://developer.android.com/docs/quality-guidelines/tv-app-quality>.
        // Due to implementation inconsistencies enter and numpad enter need to perform the same
        // function (issue #963).
        when (event.keyCode) {
            // (no default binding)
            KeyEvent.KEYCODE_CAPTIONS -> cycleSub()
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> cycleAudio()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> playlistPrev()
            KeyEvent.KEYCODE_MEDIA_NEXT -> playlistNext()
            KeyEvent.KEYCODE_INFO -> toggleControls()
            KeyEvent.KEYCODE_MENU -> openTopMenu()
            KeyEvent.KEYCODE_GUIDE -> openTopMenu()
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> player.cyclePause()

            // (overrides a default binding)
            KeyEvent.KEYCODE_ENTER -> player.cyclePause()

            else -> unhandled++
        }

        return unhandled < 2
    }

    private fun onBackPressedImpl() {
        if (lockedUI)
            return showUnlockControls()

        val notYetPlayed = psc.playlistCount - psc.playlistPos - 1
        if (notYetPlayed <= 0 || !playlistExitWarning) {
            finishWithResult(RESULT_OK, true)
            return
        }

        val restore = pauseForDialog()
        with (AlertDialog.Builder(this)) {
            setMessage(getString(R.string.exit_warning_playlist, notYetPlayed))
            setPositiveButton(R.string.dialog_yes) { dialog, _ ->
                dialog.dismiss()
                finishWithResult(RESULT_OK, true)
            }
            setNegativeButton(R.string.dialog_no) { dialog, _ ->
                dialog.dismiss()
                restore()
            }
            val dialog = create()
            this@MPVActivity.showImmersiveDialog(dialog)
        }
    }

    private fun installGestureMetricsUpdater() {
        binding.gestureLayer.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.width > 1 && v.height > 1) {
                gestures.setMetrics(v.width.toFloat(), v.height.toFloat())
                zoomGestures.setMetrics(v.width.toFloat(), v.height.toFloat())
            }
        }
        binding.gestureLayer.post { updateGestureMetricsFromView() }
    }

    private fun updateGestureMetricsFromView() {
        if (!::binding.isInitialized || !::gestures.isInitialized || !::zoomGestures.isInitialized)
            return

        val w = when {
            binding.gestureLayer.width > 1 -> binding.gestureLayer.width
            binding.player.width > 1 -> binding.player.width
            else -> resources.displayMetrics.widthPixels
        }
        val h = when {
            binding.gestureLayer.height > 1 -> binding.gestureLayer.height
            binding.player.height > 1 -> binding.player.height
            else -> resources.displayMetrics.heightPixels
        }

        if (w > 1 && h > 1) {
            gestures.setMetrics(w.toFloat(), h.toFloat())
            zoomGestures.setMetrics(w.toFloat(), h.toFloat())
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // If we're exiting and requested an orientation restore, wait until Android applies it
        // so the caller doesn't flash in the wrong orientation.
        if (pendingFinishAfterRotate && newConfig.orientation == entryConfigOrientation) {
            pendingFinishAfterRotate = false
            finish()
            return
        }

        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE

        updateGestureMetricsFromView()

        // Adjust control margins (only after the player UI is attached)
        if (uiInitialized) {
            binding.controls.updateLayoutParams<MarginLayoutParams> {
                bottomMargin = if (!controlsAtBottom) {
                    Utils.convertDp(this@MPVActivity, 60f)
                } else {
                    0
                }
                leftMargin = if (!controlsAtBottom) {
                    Utils.convertDp(this@MPVActivity, if (isLandscape) 60f else 24f)
                } else {
                    0
                }
                rightMargin = leftMargin
            }
        }

        // If we deferred startup playback until the forced orientation is applied, start now.
        maybeStartDeferredPlayback()
    }

    private fun onPiPModeChangedImpl(state: Boolean) {
        Log.v(TAG, "onPiPModeChanged($state)")
        if (state) {
            lockedUI = true
            hideControls()
            return
        }

        unlockUI()
        // For whatever stupid reason Android provides no good detection for when PiP is exited
        // so we have to do this shit <https://stackoverflow.com/questions/43174507/#answer-56127742>
        // If we don't exit the activity here it will stick around and not be retrievable from the
        // recents screen, or react to onNewIntent().
        if (activityIsStopped) {
            // Note: On Android 12 or older there's another bug with this: the result will not
            // be delivered to the calling activity and is instead instantly returned the next
            // time, which makes it looks like the file picker is broken.
            finishWithResult(RESULT_OK, true)
        }
    }

    private fun persistBeforePlaylistJump() {
        persistBeforeFileReplacement()
    }

    private fun playlistPrev() {
        persistBeforePlaylistJump()
        MPVLib.command(arrayOf("playlist-prev"))
    }

    private fun playlistNext() {
        persistBeforePlaylistJump()
        MPVLib.command(arrayOf("playlist-next"))
    }

    private fun playPlaylistItem(index: Int) {
        if (MPVLib.getPropertyInt("playlist-pos") == index)
            return
        persistBeforePlaylistJump()
        MPVLib.setPropertyInt("playlist-pos", index)
    }

    private fun showToast(msg: String, cancel: Boolean = false, durationMs: Long? = null) {
        toastCancelRunnable?.let(toastHandler::removeCallbacks)
        toastCancelRunnable = null

        if (cancel)
            toast?.cancel()

        val shownToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).apply {
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 0)
            show()
        }
        toast = shownToast

        if (durationMs != null) {
            toastCancelRunnable = Runnable {
                if (toast === shownToast) {
                    shownToast.cancel()
                    toast = null
                }
                toastCancelRunnable = null
            }.also { toastHandler.postDelayed(it, durationMs) }
        }
    }

    // Intent/Uri parsing

    private fun parsePathFromIntent(intent: Intent): String? {
        fun safeResolveUri(u: Uri?): String? {
            return if (u != null && u.isHierarchical && !u.isRelative)
                resolveUri(u)
            else null
        }

        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                // Normal file open or URL view
                intent.data?.let { resolveUri(it) }
            }

            Intent.ACTION_SEND -> {
                // Handle single shared file or text link
                var parsed = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (parsed == null) {
                    parsed = intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                        Uri.parse(it.trim())
                    }
                }

                safeResolveUri(parsed)
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                // Multiple shared files
                val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (!uris.isNullOrEmpty()) {
                    val paths = uris.mapNotNull { uri ->
                        safeResolveUri(uri)
                    }
                    if (paths.size == 1) {
                        return paths[0]
                    } else if (!paths.isEmpty()) {
                        // Use a memory playlist
                        val memoryUri = "memory://#EXTM3U\n${paths.joinToString("\n")}\n"
                        Log.v(TAG, "Created memory playlist URI (${paths.size})")
                        return memoryUri
                    }
                }
                return null
            }

            else -> {
                // Custom intent from MainScreenFragment
                intent.getStringExtra("filepath")
            }
        }
    }

    private fun resolveUri(data: Uri): String? {
        val filepath = when (data.scheme) {
            "file" -> data.path
            "content" -> translateContentUri(data)
            // mpv supports data URIs but needs data:// to pass it through correctly
            "data" -> "data://${data.schemeSpecificPart}"
            "http", "https", "rtmp", "rtmps", "rtp", "rtsp", "mms", "mmst", "mmsh",
            "tcp", "udp", "lavf", "ftp"
            -> data.toString()
            else -> null
        }

        if (filepath == null)
            Log.e(TAG, "unknown scheme: ${data.scheme}")
        return filepath
    }

    private fun translateContentUri(uri: Uri): String {
        val resolver = applicationContext.contentResolver
        Log.v(TAG, "Resolving content URI: $uri")
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // The URI may come from a tree grant, a provider without persistable grants, or an
            // already-persisted permission. Opening it below is still the authoritative check.
        }
        try {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                // See if we can skip the indirection and read the real file directly
                val path = Utils.findRealPath(pfd.fd)
                if (path != null) {
                    Log.v(TAG, "Found real file path: $path")
                    return path
                }
            }
        } catch(e: Exception) {
            Log.e(TAG, "Failed to open content fd: $e")
        }

        // Otherwise, just let mpv open the content URI directly via ffmpeg
        return uri.toString()
    }

    // --- Per-file state persistence ---

    /**
     * Return the path only while a fully loaded file owns the currently visible property values.
     * Explicit jumps disarm this before unloading; observed changes are debounced so END_FILE can
     * disarm it before any reset notification is committed.
     */
    private fun activeStatePath(): String? {
        if (!fileStateReady || restoringPerFileState)
            return null
        val path = currentFilePath ?: return null
        return path.takeIf { MPVLib.getPropertyString("path") == it }
    }

    private fun rememberFileLocalOption(name: String, value: String) {
        if (name !in PERSISTED_PER_FILE_OPTIONS)
            return
        val mediaPath = activeStatePath() ?: return
        getDefaultSharedPreferences(applicationContext).edit()
            .putString(perFileKey("$PREF_OPTION_PREFIX$name", mediaPath), value)
            .commit()
    }

    private fun checkpointCurrentFileState() {
        val mediaPath = activeStatePath() ?: return
        if (MPVLib.getPropertyBoolean("eof-reached") == true)
            return

        val prefs = getDefaultSharedPreferences(applicationContext)
        val editor = prefs.edit()
        for (name in PERSISTED_PER_FILE_OPTIONS) {
            val value = MPVLib.getPropertyString(name) ?: continue
            editor.putString(perFileKey("$PREF_OPTION_PREFIX$name", mediaPath), value)
        }
        editor.commit()
        checkpointTrackAndExternalState()
    }

    private fun restoreFileLocalOptions(mediaPath: String) {
        val prefs = getDefaultSharedPreferences(applicationContext)
        for (name in PERSISTED_PER_FILE_OPTIONS) {
            val key = perFileKey("$PREF_OPTION_PREFIX$name", mediaPath)
            if (!prefs.contains(key))
                continue
            val value = prefs.getString(key, null) ?: continue
            player.setFileLocalString(name, value)
        }
    }

    private fun filenamesMatch(left: String, right: String): Boolean {
        if (left == right)
            return true
        if (left.contains("://") || right.contains("://"))
            return false
        return try {
            File(left).canonicalPath == File(right).canonicalPath
        } catch (_: Throwable) {
            false
        }
    }

    private fun findExternalTrackId(type: String, filename: String): Int? {
        val count = MPVLib.getPropertyInt("track-list/count") ?: return null
        for (i in 0 until count) {
            if (MPVLib.getPropertyString("track-list/$i/type") != type)
                continue
            if (MPVLib.getPropertyBoolean("track-list/$i/external") != true)
                continue
            val candidate = MPVLib.getPropertyString("track-list/$i/external-filename")
                ?: continue
            if (!filenamesMatch(candidate, filename))
                continue
            return MPVLib.getPropertyInt("track-list/$i/id")
        }
        return null
    }

    private fun findExternalTrackFilename(type: String, id: Int): String? {
        if (id < 0)
            return null
        val count = MPVLib.getPropertyInt("track-list/count") ?: return null
        for (i in 0 until count) {
            if (MPVLib.getPropertyString("track-list/$i/type") != type)
                continue
            if (MPVLib.getPropertyInt("track-list/$i/id") != id)
                continue
            if (MPVLib.getPropertyBoolean("track-list/$i/external") != true)
                return null
            return MPVLib.getPropertyString("track-list/$i/external-filename")
        }
        return null
    }

    private fun addExternalPathToSet(mediaPath: String, suffix: String, path: String) {
        val prefs = getDefaultSharedPreferences(applicationContext)
        val key = perFileKey(suffix, mediaPath)
        val paths = prefs.getStringSet(key, emptySet<String>())?.toMutableSet()
            ?: mutableSetOf<String>()
        if (paths.add(path))
            prefs.edit().putStringSet(key, paths).commit()
    }

    private fun rememberSubtitleSelectionForCurrentFile(
        secondary: Boolean = false,
        selectedSid: Int? = null,
        markPending: Boolean = true
    ) {
        val mediaPath = activeStatePath() ?: return
        if (selectedSid != null && markPending) {
            if (secondary) {
                pendingSecondarySubtitleSelection = selectedSid
                pendingSecondarySubtitleExternal = null
            } else {
                pendingPrimarySubtitleSelection = selectedSid
                pendingPrimarySubtitleExternal = null
            }
        }
        val sidProp = if (secondary) "secondary-sid" else "sid"
        val kindKey = if (secondary) PREF_SUB2_KIND else PREF_SUB_KIND
        val extKey = if (secondary) PREF_SUB2_EXTERNAL else PREF_SUB_EXTERNAL
        val sidKey = if (secondary) PREF_SUB2_SID else PREF_SUB_SID
        val sid = selectedSid ?: MPVLib.getPropertyString(sidProp)?.toIntOrNull() ?: -1
        val external = findExternalTrackFilename("sub", sid)
        val prefs = getDefaultSharedPreferences(applicationContext)

        with (prefs.edit()) {
            if (external != null) {
                putString(perFileKey(kindKey, mediaPath), PREF_TRACK_KIND_EXTERNAL)
                putString(perFileKey(extKey, mediaPath), external)
                remove(perFileKey(sidKey, mediaPath))
            } else {
                putString(perFileKey(kindKey, mediaPath), PREF_TRACK_KIND_ID)
                putInt(perFileKey(sidKey, mediaPath), sid)
                remove(perFileKey(extKey, mediaPath))
            }
            commit()
        }
        if (external != null)
            addExternalPathToSet(mediaPath, PREF_EXTERNAL_SUB_FILES, external)
    }

    private fun rememberAudioSelectionForCurrentFile(
        selectedAid: Int? = null,
        markPending: Boolean = true
    ) {
        val mediaPath = activeStatePath() ?: return
        if (selectedAid != null && markPending) {
            pendingAudioSelection = selectedAid
            pendingAudioExternal = null
        }
        val aid = selectedAid ?: MPVLib.getPropertyString("aid")?.toIntOrNull() ?: -1
        val external = findExternalTrackFilename("audio", aid)
        val prefs = getDefaultSharedPreferences(applicationContext)

        with (prefs.edit()) {
            if (external != null) {
                putString(perFileKey(PREF_AUD_KIND, mediaPath), PREF_TRACK_KIND_EXTERNAL)
                putString(perFileKey(PREF_AUD_EXTERNAL, mediaPath), external)
                remove(perFileKey(PREF_AUD_SID, mediaPath))
            } else {
                putString(perFileKey(PREF_AUD_KIND, mediaPath), PREF_TRACK_KIND_ID)
                putInt(perFileKey(PREF_AUD_SID, mediaPath), aid)
                remove(perFileKey(PREF_AUD_EXTERNAL, mediaPath))
            }
            commit()
        }
        if (external != null)
            addExternalPathToSet(mediaPath, PREF_EXTERNAL_AUDIO_FILES, external)
    }

    private fun rememberExternalSubtitleForCurrentFile(subPath: String) {
        val mediaPath = activeStatePath() ?: return
        pendingPrimarySubtitleSelection = null
        pendingPrimarySubtitleExternal = subPath
        val prefs = getDefaultSharedPreferences(applicationContext)
        addExternalPathToSet(mediaPath, PREF_EXTERNAL_SUB_FILES, subPath)
        with (prefs.edit()) {
            // sub-add with "cached" selects the new track as primary.
            putString(perFileKey(PREF_SUB_KIND, mediaPath), PREF_TRACK_KIND_EXTERNAL)
            putString(perFileKey(PREF_SUB_EXTERNAL, mediaPath), subPath)
            remove(perFileKey(PREF_SUB_SID, mediaPath))
            commit()
        }
    }

    private fun rememberExternalAudioForCurrentFile(audioPath: String) {
        val mediaPath = activeStatePath() ?: return
        pendingAudioSelection = null
        pendingAudioExternal = audioPath
        val prefs = getDefaultSharedPreferences(applicationContext)
        addExternalPathToSet(mediaPath, PREF_EXTERNAL_AUDIO_FILES, audioPath)
        with (prefs.edit()) {
            putString(perFileKey(PREF_AUD_KIND, mediaPath), PREF_TRACK_KIND_EXTERNAL)
            putString(perFileKey(PREF_AUD_EXTERNAL, mediaPath), audioPath)
            remove(perFileKey(PREF_AUD_SID, mediaPath))
            commit()
        }
    }

    private fun rememberLoadedExternalTracks(mediaPath: String) {
        val prefs = getDefaultSharedPreferences(applicationContext)
        val subKey = perFileKey(PREF_EXTERNAL_SUB_FILES, mediaPath)
        val audioKey = perFileKey(PREF_EXTERNAL_AUDIO_FILES, mediaPath)
        val subtitles = prefs.getStringSet(subKey, emptySet<String>())?.toMutableSet()
            ?: mutableSetOf<String>()
        val audio = prefs.getStringSet(audioKey, emptySet<String>())?.toMutableSet()
            ?: mutableSetOf<String>()
        val count = MPVLib.getPropertyInt("track-list/count") ?: return

        for (i in 0 until count) {
            if (MPVLib.getPropertyBoolean("track-list/$i/external") != true)
                continue
            val filename = MPVLib.getPropertyString("track-list/$i/external-filename")
                ?: continue
            when (MPVLib.getPropertyString("track-list/$i/type")) {
                "sub" -> subtitles.add(filename)
                "audio" -> audio.add(filename)
            }
        }

        prefs.edit()
            .putStringSet(subKey, subtitles)
            .putStringSet(audioKey, audio)
            .commit()
    }

    private fun checkpointTrackAndExternalState() {
        val mediaPath = activeStatePath() ?: return
        if (MPVLib.getPropertyBoolean("eof-reached") == true)
            return

        val actualPrimary = MPVLib.getPropertyString("sid")?.toIntOrNull() ?: -1
        val actualSecondary =
            MPVLib.getPropertyString("secondary-sid")?.toIntOrNull() ?: -1
        val actualAudio = MPVLib.getPropertyString("aid")?.toIntOrNull() ?: -1

        val pendingPrimaryExternal = pendingPrimarySubtitleExternal
        if (pendingPrimaryExternal != null) {
            val actualExternal = findExternalTrackFilename("sub", actualPrimary)
            if (actualExternal != null &&
                filenamesMatch(actualExternal, pendingPrimaryExternal)
            ) {
                pendingPrimarySubtitleExternal = null
            }
            // The external path was already committed explicitly. Do not let a temporarily old
            // sid replace it while mpv is still finishing sub-add.
        } else {
            val wanted = pendingPrimarySubtitleSelection ?: actualPrimary
            rememberSubtitleSelectionForCurrentFile(
                secondary = false,
                selectedSid = wanted,
                markPending = false
            )
            if (pendingPrimarySubtitleSelection == actualPrimary)
                pendingPrimarySubtitleSelection = null
        }

        val pendingSecondaryExternal = pendingSecondarySubtitleExternal
        if (pendingSecondaryExternal != null) {
            val actualExternal = findExternalTrackFilename("sub", actualSecondary)
            if (actualExternal != null &&
                filenamesMatch(actualExternal, pendingSecondaryExternal)
            ) {
                pendingSecondarySubtitleExternal = null
            }
        } else {
            val wantedSecondary = pendingSecondarySubtitleSelection ?: actualSecondary
            rememberSubtitleSelectionForCurrentFile(
                secondary = true,
                selectedSid = wantedSecondary,
                markPending = false
            )
            if (pendingSecondarySubtitleSelection == actualSecondary)
                pendingSecondarySubtitleSelection = null
        }

        val pendingExternalAudio = pendingAudioExternal
        if (pendingExternalAudio != null) {
            val actualExternal = findExternalTrackFilename("audio", actualAudio)
            if (actualExternal != null && filenamesMatch(actualExternal, pendingExternalAudio))
                pendingAudioExternal = null
        } else {
            val wanted = pendingAudioSelection ?: actualAudio
            rememberAudioSelectionForCurrentFile(
                selectedAid = wanted,
                markPending = false
            )
            if (pendingAudioSelection == actualAudio)
                pendingAudioSelection = null
        }
        rememberLoadedExternalTracks(mediaPath)
    }

    private fun scheduleTrackStateCheckpoint() {
        perFileStateHandler.removeCallbacks(trackStateCheckpointRunnable)
        perFileStateHandler.postDelayed(
            trackStateCheckpointRunnable,
            TRACK_STATE_SETTLE_DELAY_MS
        )
    }

    private fun clearPendingTrackSelections() {
        pendingPrimarySubtitleSelection = null
        pendingSecondarySubtitleSelection = null
        pendingAudioSelection = null
        pendingPrimarySubtitleExternal = null
        pendingSecondarySubtitleExternal = null
        pendingAudioExternal = null
    }

    private fun persistedExternalPaths(mediaPath: String, type: String): List<String> {
        val prefs = getDefaultSharedPreferences(applicationContext)
        val setSuffix = if (type == "sub") PREF_EXTERNAL_SUB_FILES else PREF_EXTERNAL_AUDIO_FILES
        val paths = prefs.getStringSet(
            perFileKey(setSuffix, mediaPath),
            emptySet<String>()
        )?.toMutableSet() ?: mutableSetOf<String>()

        val legacyKeys = if (type == "sub")
            arrayOf(PREF_SUB_EXTERNAL, PREF_SUB2_EXTERNAL)
        else
            arrayOf(PREF_AUD_EXTERNAL)
        for (suffix in legacyKeys) {
            prefs.getString(perFileKey(suffix, mediaPath), null)?.let(paths::add)
        }
        return paths.filter { it.isNotBlank() }.sorted()
    }

    private fun loadPersistedExternalTracks(mediaPath: String, type: String) {
        val command = if (type == "sub") "sub-add" else "audio-add"
        for (path in persistedExternalPaths(mediaPath, type)) {
            if (findExternalTrackId(type, path) != null)
                continue
            // "auto" keeps primary/secondary selection untouched. Selection is restored
            // explicitly only after every external track has been loaded.
            MPVLib.command(arrayOf(command, path, "auto"))
        }
    }

    private fun setTrackProperty(property: String, id: Int) {
        if (id < 0)
            player.setFileLocalString(property, "no")
        else
            player.setFileLocalInt(property, id)
    }

    private fun restoreSubtitleSelection(mediaPath: String, secondary: Boolean) {
        val prefs = getDefaultSharedPreferences(applicationContext)
        val property = if (secondary) "secondary-sid" else "sid"
        val kindKey = if (secondary) PREF_SUB2_KIND else PREF_SUB_KIND
        val extKey = if (secondary) PREF_SUB2_EXTERNAL else PREF_SUB_EXTERNAL
        val sidKey = if (secondary) PREF_SUB2_SID else PREF_SUB_SID
        when (prefs.getString(perFileKey(kindKey, mediaPath), null)) {
            PREF_TRACK_KIND_EXTERNAL -> {
                val filename = prefs.getString(perFileKey(extKey, mediaPath), null)
                if (secondary)
                    pendingSecondarySubtitleExternal = filename
                else
                    pendingPrimarySubtitleExternal = filename
                val id = filename?.let { findExternalTrackId("sub", it) }
                if (id != null)
                    setTrackProperty(property, id)
                else {
                    setTrackProperty(property, -1)
                    Log.w(TAG, "could not restore external $property for $mediaPath: $filename")
                }
            }
            PREF_TRACK_KIND_ID -> {
                if (prefs.contains(perFileKey(sidKey, mediaPath))) {
                    val id = prefs.getInt(perFileKey(sidKey, mediaPath), -1)
                    if (secondary)
                        pendingSecondarySubtitleSelection = id
                    else
                        pendingPrimarySubtitleSelection = id
                    setTrackProperty(property, id)
                }
            }
        }
    }

    private fun restoreAudioSelection(mediaPath: String) {
        val prefs = getDefaultSharedPreferences(applicationContext)
        when (prefs.getString(perFileKey(PREF_AUD_KIND, mediaPath), null)) {
            PREF_TRACK_KIND_EXTERNAL -> {
                val filename = prefs.getString(perFileKey(PREF_AUD_EXTERNAL, mediaPath), null)
                pendingAudioExternal = filename
                val id = filename?.let { findExternalTrackId("audio", it) }
                if (id != null)
                    setTrackProperty("aid", id)
                else
                    Log.w(TAG, "could not restore external aid for $mediaPath: $filename")
            }
            PREF_TRACK_KIND_ID -> {
                if (prefs.contains(perFileKey(PREF_AUD_SID, mediaPath))) {
                    val id = prefs.getInt(perFileKey(PREF_AUD_SID, mediaPath), -1)
                    pendingAudioSelection = id
                    setTrackProperty("aid", id)
                }
            }
        }
    }

    private fun restorePlaybackPosition(mediaPath: String, explicitStart: Boolean) {
        if (!shouldSavePosition) {
            clearStoredPlaybackPosition(applicationContext, mediaPath)
            return
        }
        if (explicitStart)
            return

        val prefs = getDefaultSharedPreferences(applicationContext)
        val key = perFileKey(PREF_PLAYBACK_POSITION, mediaPath)
        if (!prefs.contains(key))
            return
        val seconds = prefs.getLong(key, 0L) / 1000.0
        val duration = MPVLib.getPropertyDouble("duration/full")

        // If the process died in the tiny gap between the final time-pos notification and
        // END_FILE, do not resurrect a checkpoint on the last frame.
        if (seconds <= 0.0 ||
            (duration != null && duration.isFinite() &&
                seconds >= duration - EOF_POSITION_TOLERANCE_SECONDS)
        ) {
            clearStoredPlaybackPosition(applicationContext, mediaPath)
            return
        }
        player.timePos = seconds
    }

    private fun restorePerFileState(mediaPath: String) {
        val explicitPath = explicitStartMediaPath
        val explicitStart = explicitStartForNextFile &&
            (explicitPath == null || filenamesMatch(explicitPath, mediaPath))
        if (explicitStart) {
            explicitStartForNextFile = false
            explicitStartMediaPath = null
        }
        restoringPerFileState = true
        fileStateReady = false
        try {
            restoreFileLocalOptions(mediaPath)
            loadPersistedExternalTracks(mediaPath, "sub")
            loadPersistedExternalTracks(mediaPath, "audio")
            // All additions used "auto". Final explicit assignments make primary and secondary
            // independent and eliminate selection-order races.
            restoreSubtitleSelection(mediaPath, secondary = false)
            restoreSubtitleSelection(mediaPath, secondary = true)
            restoreAudioSelection(mediaPath)
            restorePlaybackPosition(mediaPath, explicitStart)
        } finally {
            restoringPerFileState = false
            fileStateReady = true
        }
        // FILE_LOADED is the first point at which track selection is authoritative. Capture it
        // synchronously so even a very short file cannot finish before its state is recorded.
        checkpointCurrentFileState()
        scheduleTrackStateCheckpoint()
    }

    private fun persistCurrentPlaybackPosition() {
        val mediaPath = activeStatePath() ?: return
        if (!shouldSavePosition || MPVLib.getPropertyBoolean("eof-reached") == true) {
            clearStoredPlaybackPosition(applicationContext, mediaPath)
            return
        }
        val seconds = MPVLib.getPropertyDouble("time-pos/full")
            ?: MPVLib.getPropertyDouble("time-pos")
            ?: return
        storePlaybackPosition(applicationContext, mediaPath, seconds)
    }

    private fun maybeAutosavePlaybackPosition(positionSecond: Long) {
        val mediaPath = activeStatePath() ?: return
        if (!shouldSavePosition)
            return
        if (MPVLib.getPropertyBoolean("eof-reached") == true)
            return
        if (lastPositionAutosaveSecond >= 0L &&
            abs(positionSecond - lastPositionAutosaveSecond) < POSITION_AUTOSAVE_INTERVAL_SECONDS
        ) return

        lastPositionAutosaveSecond = positionSecond
        storePlaybackPosition(applicationContext, mediaPath, positionSecond.toDouble())
    }

    private fun persistBeforeFileReplacement() {
        if (!playbackInitialized)
            return
        checkpointCurrentFileState()
        persistCurrentPlaybackPosition()
        fileStateReady = false
        clearPendingTrackSelections()
        perFileStateHandler.removeCallbacks(trackStateCheckpointRunnable)
    }

    private fun parseIntentExtras(extras: Bundle?) {
        onloadCommands.clear()
        explicitStartForNextFile = false
        explicitStartMediaPath = null
        if (extras == null)
            return

        fun pushOption(key: String, value: String) {
            onloadCommands.add(arrayOf("set", "file-local-options/${key}", value))
        }

        // Refer to http://mpv-android.github.io/mpv-android/intent.html
        // Note: these only apply to the first file, it's not clear what the semantics for a
        // playlist should be.

        if (extras.getByte("decode_mode") == 2.toByte())
            pushOption("hwdec", "no")
        if (extras.containsKey("subs")) {
            val subList = Utils.getParcelableArray<Uri>(extras, "subs")
            val subsToEnable = Utils.getParcelableArray<Uri>(extras, "subs.enable")

            for (suburi in subList) {
                val subfile = resolveUri(suburi) ?: continue
                val flag = if (subsToEnable.any { it == suburi }) "select" else "auto"

                Log.v(TAG, "Adding subtitles from intent extras: $subfile")
                onloadCommands.add(arrayOf("sub-add", subfile, flag))
            }
        }
        extras.getInt("position", 0).let {
            if (it > 0) {
                explicitStartForNextFile = true
                pushOption("start", "${it / 1000f}")
            }
        }
        extras.getString("title", "").let {
            if (!it.isNullOrEmpty())
                pushOption("force-media-title", it)
        }
        // TODO: `headers` would be good, maybe `tls_verify`
    }

    // UI (Part 2)

    data class TrackData(val trackId: Int, val trackType: String)
    private fun trackSwitchNotification(f: () -> TrackData) {
        val (track_id, track_type) = f()
        val trackPrefix = when (track_type) {
            "audio" -> getString(R.string.track_audio)
            "sub"   -> getString(R.string.track_subs)
            "video" -> "Video"
            else    -> "???"
        }

        val msg = if (track_id == -1) {
            "$trackPrefix ${getString(R.string.track_off)}"
        } else {
            val trackName = player.tracks[track_type]?.firstOrNull{ it.mpvId == track_id }?.name ?: "???"
            "$trackPrefix $trackName"
        }
        val durationMs = if (track_type == "audio" || track_type == "sub")
            TRACK_SWITCH_TOAST_DURATION_MS
        else
            null
        showToast(msg, true, durationMs)
    }

    private fun cycleAudio() = trackSwitchNotification {
        player.cycleAudio()
        player.persistCurrentFileState()
        TrackData(player.aid, "audio")
    }
    private fun cycleSub() = trackSwitchNotification {
        player.cycleSub()
        player.persistCurrentFileState()
        TrackData(player.sid, "sub")
    }

    private fun selectTrack(type: String, get: () -> Int, set: (Int) -> Unit) {
    val tracks = player.tracks.getValue(type)
    val selectedMpvId = get()
    val selectedIndex = tracks.indexOfFirst { it.mpvId == selectedMpvId }
    val restore = pauseForDialog()

    var handled = false
    val dialog = with (AlertDialog.Builder(this)) {
        setSingleChoiceItems(tracks.map { it.name }.toTypedArray(), selectedIndex) { _, item ->
            val trackId = tracks[item].mpvId

            set(trackId)
            if (type == "sub") {
                try {
                    rememberSubtitleSelectionForCurrentFile(selectedSid = trackId)
                } catch (_: Throwable) {}
            } else if (type == "audio") {
                try { rememberAudioSelectionForCurrentFile(trackId) } catch (_: Throwable) {}
            }
            player.persistCurrentFileState()
            trackSwitchNotification { TrackData(trackId, type) }
            // Keep dialog open (apply-in-place).
        }
        setNegativeButton(R.string.dialog_cancel) { d, _ -> d.cancel() }
        setOnCancelListener {
            handled = true
            restore()
        }
        setOnDismissListener {
            if (!handled)
                restore()
        }
        create()
    }
    showImmersiveDialog(dialog)
}

private fun pickAudio() = selectTrack("audio", { player.aid }, { player.aid = it })

    private fun pickSub() {
    val restore = pauseForDialog()
    val impl = SubTrackDialog(player)
    lateinit var dialog: AlertDialog
    var handled = false

    impl.listener = { it, secondary ->
        if (secondary)
            player.secondarySid = it.mpvId
        else
            player.sid = it.mpvId

        try {
            rememberSubtitleSelectionForCurrentFile(
                secondary = secondary,
                selectedSid = it.mpvId
            )
        } catch (_: Throwable) {}
        player.persistCurrentFileState()
        trackSwitchNotification { TrackData(it.mpvId, SubTrackDialog.TRACK_TYPE) }
        // Keep dialog open (apply-in-place).
    }

    dialog = with(AlertDialog.Builder(this)) {
        val inflater = LayoutInflater.from(context)
        setView(impl.buildView(inflater))
        setOnCancelListener {
            handled = true
            restore()
        }
        setOnDismissListener {
            if (!handled)
                restore()
        }
        create()
    }
    showImmersiveDialog(dialog)
}

private fun openPlaylistMenu(restore: StateRestoreCallback, onBack: (() -> Unit)? = null) {
    val impl = PlaylistDialog(player)
    lateinit var dialog: AlertDialog

    val backAction: () -> Unit = onBack ?: restore
    var handled = false

    impl.listeners = object : PlaylistDialog.Listeners {
        private fun openFilePicker(skip: Int) {
            openFilePickerFor(RCODE_LOAD_FILE, "", skip) { result, data ->
                if (result == RESULT_OK) {
                    val path = data!!.getStringExtra("path")!!
                    MPVLib.command(arrayOf("loadfile", path, "append"))
                    impl.refresh()
                }
            }
        }
        override fun pickFile() = openFilePicker(FilePickerActivity.FILE_PICKER)

        override fun openUrl() {
            val helper = Utils.OpenUrlDialog(this@MPVActivity)
            // Apply without closing (stay in the URL dialog so the user can add multiple entries).
            val urlDialog = with(helper) {
                builder.setPositiveButton(R.string.dialog_ok, null)
                builder.setNegativeButton(R.string.dialog_cancel) { d, _ -> d.cancel() }
                create()
            }
            urlDialog.setOnShowListener {
                this@MPVActivity.applyImmersiveToDialog(urlDialog)
                urlDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val url = helper.text
                    if (url.isNotBlank()) {
                        MPVLib.command(arrayOf("loadfile", url, "append"))
                        impl.refresh()
                    }
                    // Keep dialog open.
                }
            }
            showImmersiveDialog(urlDialog)
        }

        override fun onItemPicked(item: MPVView.PlaylistItem) {
            playPlaylistItem(item.index)
            impl.refresh()
            // Keep dialog open (apply-in-place).
        }
    }

    dialog = with(AlertDialog.Builder(this)) {
        val inflater = LayoutInflater.from(context)
        setView(impl.buildView(inflater))

        // Tapping outside exits directly to video.
        setOnCancelListener {
            handled = true
            runIfActive(restore)
        }
        // Fallback for non-cancel dismissals.
        setOnDismissListener {
            if (!handled)
                runIfActive(restore)
        }
        create()
    }

    dialog.setCanceledOnTouchOutside(true)
    dialog.setOnKeyListener { _, keyCode, event ->
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                handled = true
                runIfActive(backAction)
                dialog.dismiss()
            }
            true
        } else {
            false
        }
    }

    showImmersiveDialog(dialog)
}

private fun pickDecoder() {
    val restore = pauseForDialog()

    val items = mutableListOf(
        Pair("HW (mediacodec-copy)", "mediacodec-copy"),
        Pair("SW", "no")
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        items.add(0, Pair("HW+ (mediacodec)", "mediacodec"))

    val hwdecActive = player.hwdecActive
    val selectedIndex = items.indexOfFirst { it.second == hwdecActive }

    var handled = false
    val dialog = with(AlertDialog.Builder(this)) {
        setSingleChoiceItems(items.map { it.first }.toTypedArray(), selectedIndex) { _, idx ->
            player.setFileLocalString("hwdec", items[idx].second)
            player.persistCurrentFileState()
            // Keep dialog open (apply-in-place).
        }
        setNegativeButton(R.string.dialog_cancel) { d, _ -> d.cancel() }
        setOnCancelListener {
            handled = true
            restore()
        }
        setOnDismissListener {
            if (!handled)
                restore()
        }
        create()
    }
    showImmersiveDialog(dialog)
}

private fun cycleSpeed() {
        player.cycleSpeed()
    }

    private fun pickSpeed() {
        // TODO: replace this with SliderPickerDialog
        val picker = SpeedPickerDialog()

        val restore = pauseForDialog()
        genericPickerDialog(picker, R.string.title_speed_dialog, "speed", onBack = { restore() }, onExit = { restore() })
    }

    private fun goIntoPiP() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
            return
        updatePiPParams(true)
        enterPictureInPictureMode()
    }

    private fun lockUI() {
        lockedUI = true
        hideControlsFade()
    }

    private fun unlockUI() {
        binding.unlockBtn.visibility = View.GONE
        lockedUI = false
        showControls()
    }

    data class MenuItem(
    @IdRes val idRes: Int,
    /** If true, the current menu dialog will be dismissed after running [handler]. */
    val dismiss: Boolean = false,
    /** If true, [onBack] will be invoked immediately before dismissing the dialog. */
    val restoreOnDismiss: Boolean = false,
    val handler: () -> Unit
)

private inline fun runIfActive(block: () -> Unit) {
    if (!isFinishing && !isDestroyed) block()
}

private fun genericMenu(
    @LayoutRes layoutRes: Int,
    buttons: List<MenuItem>,
    hiddenButtons: Set<Int>,
    onBack: () -> Unit,
    onExit: () -> Unit
) {
    lateinit var dialog: AlertDialog

    val builder = AlertDialog.Builder(this)
    val dialogView = LayoutInflater.from(builder.context).inflate(layoutRes, null)

    for (button in buttons) {
        val buttonView = dialogView.findViewById<Button>(button.idRes)
        buttonView.setOnClickListener {
            button.handler()
            if (button.dismiss) {
                if (button.restoreOnDismiss) runIfActive(onBack)
                dialog.dismiss()
            }
        }
    }

    hiddenButtons.forEach { dialogView.findViewById<View>(it).isVisible = false }

    if (Utils.visibleChildren(dialogView) == 0) {
        Log.w(TAG, "Not showing menu because it would be empty")
        runIfActive(onBack)
        return
    }

    Utils.handleInsetsAsPadding(dialogView)

    with(builder) {
        setView(dialogView)
        // Tapping outside should exit directly to video; Back should navigate "up" in the menu stack.
        setOnCancelListener { runIfActive(onExit) }
        dialog = create()
    }

    dialog.setCanceledOnTouchOutside(true)
    dialog.setOnKeyListener { _, keyCode, event ->
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                runIfActive(onBack)
                dialog.dismiss()
            }
            true
        } else {
            false
        }
    }

    showImmersiveDialog(dialog)
}

private fun openTopMenu(existingRestoreState: StateRestoreCallback? = null) {
    val restoreState = existingRestoreState ?: pauseForDialog()

    fun addExternalThing(cmd: String, result: Int, data: Intent?) {
        if (result != RESULT_OK)
            return
        // file picker may return a content URI or a bare file path
        val path = data!!.getStringExtra("path")!!
        val path2 = if (path.startsWith("content://"))
            translateContentUri(Uri.parse(path))
        else
            path
        MPVLib.command(arrayOf(cmd, path2, "cached"))

        // Persist the chosen external track per video so it gets reloaded on reopen.
        if (cmd == "sub-add") {
            try { rememberExternalSubtitleForCurrentFile(path2) } catch (_: Throwable) {}
        } else if (cmd == "audio-add") {
            try { rememberExternalAudioForCurrentFile(path2) } catch (_: Throwable) {}
        }
        scheduleTrackStateCheckpoint()
    }

    fun openChapterListDialog() {
        val chapters = player.loadChapters()
        if (chapters.isEmpty()) {
            // Nothing to show; just stay in the top menu.
            openTopMenu(restoreState)
            return
        }
        val chapterArray = chapters.map {
            val timecode = Utils.prettyTime(it.time.roundToInt())
            if (!it.title.isNullOrEmpty())
                getString(R.string.ui_chapter, it.title, timecode)
            else
                getString(R.string.ui_chapter_fallback, it.index + 1, timecode)
        }.toTypedArray()

        val selectedIndex = MPVLib.getPropertyInt("chapter") ?: 0
        var handled = false
        val dialog = with(AlertDialog.Builder(this)) {
            setTitle(R.string.chapter_button)
            setSingleChoiceItems(chapterArray, selectedIndex) { _, item ->
                MPVLib.setPropertyInt("chapter", chapters[item].index)
            }
            // "Cancel" behaves like Back (up to the top menu).
            setNegativeButton(R.string.dialog_cancel) { _, _ ->
                handled = true
                openTopMenu(restoreState)
            }
            // Tapping outside exits directly to video.
            setOnCancelListener {
                handled = true
                restoreState()
            }
            setOnDismissListener {
                if (!handled)
                    restoreState()
            }
            create()
        }

        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    handled = true
                    openTopMenu(restoreState)
                    dialog.dismiss()
                }
                true
            } else {
                false
            }
        }

        showImmersiveDialog(dialog)
    }

    /******/
    val hiddenButtons = mutableSetOf<Int>()
    val buttons: MutableList<MenuItem> = mutableListOf(
        MenuItem(R.id.audioBtn, dismiss = true) {
            openFilePickerFor(RCODE_EXTERNAL_AUDIO, R.string.open_external_audio) { result, data ->
                addExternalThing("audio-add", result, data)
                restoreState()
            }
        },
        MenuItem(R.id.subBtn, dismiss = true) {
            openFilePickerFor(RCODE_EXTERNAL_SUB, R.string.open_external_sub) { result, data ->
                addExternalThing("sub-add", result, data)
                restoreState()
            }
        },
        MenuItem(R.id.playlistBtn, dismiss = true) {
            openPlaylistMenu(restoreState, onBack = { openTopMenu(restoreState) })
        },
        MenuItem(R.id.backgroundBtn, dismiss = true) {
            // Restoring state may (un)pause so do that first.
            restoreState()
            backgroundPlayMode = "always"
            player.paused = false
            moveTaskToBack(true)
        },
        MenuItem(R.id.chapterBtn, dismiss = true) {
            openChapterListDialog()
        },
        MenuItem(R.id.chapterPrev) {
            MPVLib.command(arrayOf("add", "chapter", "-1"))
        },
        MenuItem(R.id.chapterNext) {
            MPVLib.command(arrayOf("add", "chapter", "1"))
        },
        MenuItem(R.id.advancedBtn, dismiss = true) {
            openAdvancedMenu(restoreState)
        },
        MenuItem(R.id.orientationBtn) {
            autoRotationMode = "manual"
            cycleOrientation()
        }
    )

    if (!isPlayingAudio)
        hiddenButtons.add(R.id.backgroundBtn)
    if ((MPVLib.getPropertyInt("chapter-list/count") ?: 0) == 0)
        hiddenButtons.add(R.id.rowChapter)
    /******/

    genericMenu(R.layout.dialog_top_menu, buttons, hiddenButtons, onBack = { restoreState() }, onExit = { restoreState() })
}

    private fun genericPickerDialog(
    picker: PickerDialog,
    @StringRes titleRes: Int,
    property: String,
    onBack: () -> Unit,
    onExit: () -> Unit
) {
    lateinit var dialog: AlertDialog
    var handled = false

    dialog = with(AlertDialog.Builder(this)) {
        setTitle(titleRes)
        val inflater = LayoutInflater.from(context)
        setView(picker.buildView(inflater))

        // Apply without closing: we'll override the click listener after show().
        setPositiveButton(R.string.dialog_ok, null)
        // "Cancel" behaves like Back (up one menu level).
        setNegativeButton(R.string.dialog_cancel) { _, _ ->
            handled = true
            runIfActive(onBack)
        }

        // Tapping outside exits directly to video.
        setOnCancelListener {
            handled = true
            runIfActive(onExit)
        }
        // Fallback for non-cancel dismissals.
        setOnDismissListener {
            if (!handled)
                runIfActive(onExit)
        }
        create()
    }

    picker.number = MPVLib.getPropertyDouble(property)

    dialog.setCanceledOnTouchOutside(true)
    dialog.setOnKeyListener { _, keyCode, event ->
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                handled = true
                runIfActive(onBack)
                dialog.dismiss()
            }
            true
        } else {
            false
        }
    }

    dialog.setOnShowListener {

        applyImmersiveToDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            picker.number?.let {
                if (picker.isInteger())
                    player.setFileLocalInt(property, it.toInt())
                else
                    player.setFileLocalDouble(property, it)
                player.persistCurrentFileState()
            }
            // Keep dialog open (apply-in-place).
        }
    }

    showImmersiveDialog(dialog)
}

private fun openAdvancedMenu(restoreState: StateRestoreCallback) {
    fun openAspectRatioDialog() {
        val ratios = resources.getStringArray(R.array.aspect_ratios)
        val names = resources.getStringArray(R.array.aspect_ratio_names)

        fun parseAspectRatio(value: String): Double? {
            val trimmed = value.trim()
            if (trimmed.isEmpty() || trimmed == "panscan" || trimmed == "-1" || trimmed.equals("no", true))
                return null

            val parts = trimmed.split(':', limit = 2)
            return if (parts.size == 2) {
                val width = parts[0].toDoubleOrNull()
                val height = parts[1].toDoubleOrNull()
                if (width != null && height != null && height != 0.0)
                    width / height
                else
                    null
            } else {
                trimmed.toDoubleOrNull()
            }
        }

        fun aspectRatioMatches(current: String, ratio: String): Boolean {
            if (current == ratio)
                return true
            val currentValue = parseAspectRatio(current) ?: return false
            val ratioValue = parseAspectRatio(ratio) ?: return false
            return abs(currentValue - ratioValue) < 0.001
        }

        val currentPanscan = MPVLib.getPropertyDouble("panscan") ?: 0.0
        val currentOverride = MPVLib.getPropertyString("video-aspect-override")?.trim() ?: ""
        val panscanIndex = ratios.indexOf("panscan")
        val originalIndex = ratios.indexOf("-1").takeIf { it >= 0 } ?: 0
        var selectedIndex = if (currentPanscan > 0.0 && panscanIndex >= 0) {
            panscanIndex
        } else {
            ratios.indexOfFirst { ratio ->
                ratio != "panscan" && aspectRatioMatches(currentOverride, ratio)
            }
        }
        if (selectedIndex < 0) selectedIndex = originalIndex

        var handled = false
        val dialog = with(AlertDialog.Builder(this)) {
            setSingleChoiceItems(names, selectedIndex) { _, item ->
                val ratio = ratios[item]
                val targetPanscan = if (ratio == "panscan") 1.0 else 0.0
                val targetAspect = if (ratio == "panscan") {
                    try { player.getVideoAspect() } catch (_: Throwable) { null }
                } else {
                    parseAspectRatio(ratio) ?: try { player.getVideoAspect() } catch (_: Throwable) { null }
                }
                val targetPixelSize = try { player.getVideoPixelSize() } catch (_: Throwable) { null }

                // Menu selections are the only transition where we already know the
                // requested geometry. Apply it before asking mpv to redraw so the
                // user never sees the temporary fullscreen/base layout.
                if (::zoomGestures.isInitialized) {
                    try {
                        zoomGestures.applyPredictedAspectMenuGeometry(
                            aspect = targetAspect,
                            pixelSize = targetPixelSize,
                            panscanValue = targetPanscan,
                        )
                    } catch (_: Throwable) {}
                }

                val suppressUntil = SystemClock.uptimeMillis() + ASPECT_MENU_PREDICTIVE_SYNC_GRACE_MS
                suppressAspectMenuGeometrySyncUntilMs = suppressUntil
                eventUiHandler.postDelayed({
                    if (SystemClock.uptimeMillis() >= suppressUntil)
                        syncZoomVideoGeometry(prepareNormalSurface = true, immediate = true)
                }, ASPECT_MENU_PREDICTIVE_SYNC_GRACE_MS + 20L)

                if (ratio == "panscan") {
                    player.setFileLocalString("video-aspect-override", "-1")
                    player.setFileLocalDouble("panscan", 1.0)
                } else {
                    player.setFileLocalString("video-aspect-override", ratio)
                    player.setFileLocalDouble("panscan", 0.0)
                }
                player.persistCurrentFileState()
                // Keep dialog open (apply-in-place).
            }
            // "Cancel" behaves like Back (up to the advanced menu).
            setNegativeButton(R.string.dialog_cancel) { _, _ ->
                handled = true
                openAdvancedMenu(restoreState)
            }
            // Tapping outside exits directly to video.
            setOnCancelListener {
                handled = true
                restoreState()
            }
            setOnDismissListener {
                if (!handled)
                    restoreState()
            }
            create()
        }

        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    handled = true
                    openAdvancedMenu(restoreState)
                    dialog.dismiss()
                }
                true
            } else {
                false
            }
        }

        showImmersiveDialog(dialog)
    }

    fun openSubDelayDialog() {
        val picker = SubDelayDialog(-600.0, 600.0)
        lateinit var dialog: AlertDialog
        var handled = false

        dialog = with(AlertDialog.Builder(this)) {
            setTitle(R.string.sub_delay)
            val inflater = LayoutInflater.from(context)
            setView(picker.buildView(inflater))

            setPositiveButton(R.string.dialog_ok, null)
            // "Cancel" behaves like Back (up to the advanced menu).
            setNegativeButton(R.string.dialog_cancel) { _, _ ->
                handled = true
                openAdvancedMenu(restoreState)
            }

            // Tapping outside exits directly to video.
            setOnCancelListener {
                handled = true
                restoreState()
            }
            setOnDismissListener {
                if (!handled)
                    restoreState()
            }
            create()
        }

        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    handled = true
                    openAdvancedMenu(restoreState)
                    dialog.dismiss()
                }
                true
            } else {
                false
            }
        }

        picker.delay1 = player.subDelay ?: 0.0
        picker.delay2 = if (player.secondarySid != -1) (player.secondarySubDelay ?: 0.0) else null

        dialog.setOnShowListener {

            applyImmersiveToDialog(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                picker.delay1?.let { player.subDelay = it }
                picker.delay2?.let { player.secondarySubDelay = it }
                player.persistCurrentFileState()
                // Keep dialog open (apply-in-place).
            }
        }

        showImmersiveDialog(dialog)
    }

    /******/
    val hiddenButtons = mutableSetOf<Int>()
    val buttons: MutableList<MenuItem> = mutableListOf(
        MenuItem(R.id.subSeekPrev) {
            MPVLib.command(arrayOf("sub-seek", "-1"))
        },
        MenuItem(R.id.subSeekNext) {
            MPVLib.command(arrayOf("sub-seek", "1"))
        },
        MenuItem(R.id.statsBtn) {
            MPVLib.command(arrayOf("script-binding", "stats/display-stats-toggle"))
        },
        MenuItem(R.id.aspectBtn, dismiss = true) {
            openAspectRatioDialog()
        }
        )

    val statsButtons = arrayOf(R.id.statsBtn1, R.id.statsBtn2, R.id.statsBtn3)
    for (i in 1..3) {
        buttons.add(MenuItem(statsButtons[i - 1]) {
            MPVLib.command(arrayOf("script-binding", "stats/display-page-$i"))
        })
    }

    // contrast, brightness and others get a -100 to 100 slider
    val basicIds = arrayOf(R.id.contrastBtn, R.id.brightnessBtn, R.id.gammaBtn, R.id.saturationBtn)
    val basicProps = arrayOf("contrast", "brightness", "gamma", "saturation")
    val basicTitles = arrayOf(R.string.contrast, R.string.video_brightness, R.string.gamma, R.string.saturation)
    basicIds.forEachIndexed { index, id ->
        buttons.add(MenuItem(id, dismiss = true) {
            val slider = SliderPickerDialog(-100.0, 100.0, 1, R.string.format_fixed_number)
            genericPickerDialog(slider, basicTitles[index], basicProps[index], onBack = { openAdvancedMenu(restoreState) }, onExit = { restoreState() })
        })
    }

    // audio delay get a decimal picker
    buttons.add(MenuItem(R.id.audioDelayBtn, dismiss = true) {
        val picker = DecimalPickerDialog(-600.0, 600.0)
        genericPickerDialog(picker, R.string.audio_delay, "audio-delay", onBack = { openAdvancedMenu(restoreState) }, onExit = { restoreState() })
    })

    // sub delay (primary/secondary) dialog
    buttons.add(MenuItem(R.id.subDelayBtn, dismiss = true) {
        openSubDelayDialog()
    })

    if (player.vid == -1)
        hiddenButtons.addAll(arrayOf(R.id.rowVideo1, R.id.rowVideo2, R.id.aspectBtn))
    if (player.aid == -1 || player.vid == -1)
        hiddenButtons.add(R.id.audioDelayBtn)
    // Subtitle controls should be available whenever *any* subtitle track is active.
    // Previously this was gated only on the primary subtitle (sid), which made the
    // subtitle delay dialog disappear when only the secondary subtitle (secondary-sid)
    // was enabled.
    if (player.sid == -1 && player.secondarySid == -1)
        hiddenButtons.addAll(arrayOf(R.id.subDelayBtn, R.id.rowSubSeek))
    /******/

    genericMenu(R.layout.dialog_advanced_menu, buttons, hiddenButtons, onBack = { openTopMenu(restoreState) }, onExit = { restoreState() })
}

    private fun cycleOrientation() {
        requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private var activityResultCallbacks: MutableMap<Int, ActivityResultCallback> = mutableMapOf()
    private fun openFilePickerFor(requestCode: Int, title: String, skip: Int?, callback: ActivityResultCallback) {
        val intent = Intent(this, FilePickerActivity::class.java)
        intent.putExtra("title", title)
        intent.putExtra("allow_document", true)
        skip?.let { intent.putExtra("skip", it) }
        // start file picker at directory of current file
        val path = MPVLib.getPropertyString("path") ?: ""
        if (path.startsWith('/'))
            intent.putExtra("default_path", File(path).parent)

        activityResultCallbacks[requestCode] = callback
        startActivityForResult(intent, requestCode)
    }
    private fun openFilePickerFor(requestCode: Int, @StringRes titleRes: Int, callback: ActivityResultCallback) {
        openFilePickerFor(requestCode, getString(titleRes), null, callback)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        activityResultCallbacks.remove(requestCode)?.invoke(resultCode, data)
    }

    private fun refreshUi() {
        // forces update of entire UI, used when resuming the activity
        updatePlaybackStatus(psc.pause)
        updatePlaybackPos(psc.positionSec)
        updatePlaybackDuration(psc.durationSec)
        updateAudioUI()
        updateOrientation()
        updateMetadataDisplay()
        updateDecoderButton()
        updateSpeedButton()
        updatePlaylistButtons()
        player.loadTracks()
    }

    private fun updateAudioUI() {
        val audioButtons = arrayOf(R.id.prevBtn, R.id.cycleAudioBtn, R.id.playBtn,
                R.id.cycleSpeedBtn, R.id.nextBtn)
        val videoButtons = arrayOf(R.id.cycleAudioBtn, R.id.cycleSubsBtn, R.id.playBtn,
                R.id.cycleDecoderBtn, R.id.cycleSpeedBtn)

        val shouldUseAudioUI = isPlayingAudioOnly()
        if (shouldUseAudioUI == useAudioUI)
            return
        useAudioUI = shouldUseAudioUI
        Log.v(TAG, "Audio UI: $useAudioUI")

        val seekbarGroup = binding.controlsSeekbarGroup
        val buttonGroup = binding.controlsButtonGroup

        if (useAudioUI) {
            // Move prev/next file from seekbar group to buttons group
            Utils.viewGroupMove(seekbarGroup, R.id.prevBtn, buttonGroup, 0)
            Utils.viewGroupMove(seekbarGroup, R.id.nextBtn, buttonGroup, -1)

            // Change button layout of buttons group
            Utils.viewGroupReorder(buttonGroup, audioButtons)

            // Show song title and more metadata
            binding.controlsTitleGroup.visibility = View.VISIBLE
            Utils.viewGroupReorder(binding.controlsTitleGroup, arrayOf(R.id.titleTextView, R.id.minorTitleTextView))
            updateMetadataDisplay()
        } else {
            Utils.viewGroupMove(buttonGroup, R.id.prevBtn, seekbarGroup, 0)
            Utils.viewGroupMove(buttonGroup, R.id.nextBtn, seekbarGroup, -1)

            Utils.viewGroupReorder(buttonGroup, videoButtons)

            // Show title only depending on settings
            if (showMediaTitle) {
                binding.controlsTitleGroup.visibility = View.VISIBLE
                Utils.viewGroupReorder(binding.controlsTitleGroup, arrayOf(R.id.fullTitleTextView))
                updateMetadataDisplay()
            } else {
                binding.controlsTitleGroup.visibility = View.GONE
            }

        }

        // Visibility might have changed, so update
        updatePlaylistButtons()
    }

    private fun updateMetadataDisplay() {
        if (!useAudioUI) {
            if (showMediaTitle)
                binding.fullTitleTextView.text = psc.meta.formatTitle()
        } else {
            binding.titleTextView.text = psc.meta.formatTitle()
            binding.minorTitleTextView.text = psc.meta.formatArtistAlbum()
        }
    }

    private fun updatePlaybackPos(position: Int) {
        binding.playbackPositionTxt.text = Utils.prettyTime(position)
        if (useTimeRemaining) {
            val diff = psc.durationSec - position
            binding.playbackDurationTxt.text = if (diff <= 0)
                "-00:00"
            else
                Utils.prettyTime(-diff, true)
        }
        if (!userIsOperatingSeekbar)
            binding.playbackSeekbar.progress = position * SEEK_BAR_PRECISION

        // Note: do NOT add other update functions here just because this is called every second.
        // Use property observation instead.
        updateStats()
    }

    private fun updatePlaybackDuration(duration: Int) {
        if (!useTimeRemaining)
            binding.playbackDurationTxt.text = Utils.prettyTime(duration)
        if (!userIsOperatingSeekbar)
            binding.playbackSeekbar.max = duration * SEEK_BAR_PRECISION
    }

    private fun updatePlaybackStatus(paused: Boolean) {
        val r = if (paused) R.drawable.ic_play_arrow_black_24dp else R.drawable.ic_pause_black_24dp
        binding.playBtn.setImageResource(r)

        updatePiPParams()
        if (paused) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun updateDecoderButton() {
        binding.cycleDecoderBtn.text = when (player.hwdecActive) {
            "mediacodec" -> "HW+"
            "no" -> "SW"
            else -> "HW"
        }
    }

    private fun updateSpeedButton() {
        binding.cycleSpeedBtn.text = getString(R.string.ui_speed, psc.speed)
    }

    private fun updatePlaylistButtons() {
        val plCount = psc.playlistCount
        val plPos = psc.playlistPos

        if (!useAudioUI && plCount == 1) {
            // use View.GONE so the buttons won't take up any space
            binding.prevBtn.visibility = View.GONE
            binding.nextBtn.visibility = View.GONE
            return
        }
        binding.prevBtn.visibility = View.VISIBLE
        binding.nextBtn.visibility = View.VISIBLE

        val g = ContextCompat.getColor(this, R.color.tint_disabled)
        val w = ContextCompat.getColor(this, R.color.tint_normal)
        binding.prevBtn.imageTintList = ColorStateList.valueOf(if (plPos == 0) g else w)
        binding.nextBtn.imageTintList = ColorStateList.valueOf(if (plPos == plCount-1) g else w)
    }

    private fun updateOrientation(initial: Boolean = false) {
        // screen orientation is fixed (Android TV)
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_SCREEN_PORTRAIT))
            return

        if (autoRotationMode != "auto") {
            if (!initial)
                return // don't reset at runtime
            requestedOrientation = when (autoRotationMode) {
                "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        if (initial || player.vid == -1)
            return

        // Base automatic orientation on the media's native pixel dimensions, not on mpv's
        // displayed aspect ratio. The latter can change when the user selects an aspect-ratio
        // override, and that UI-only choice must never rotate the Android screen.
        val pixelSize = player.getVideoPixelSize() ?: return
        val ratio = pixelSize.first.toFloat() / pixelSize.second.toFloat()

        // If the dimensions are unavailable/invalid, don't change orientation. In practice this
        // can happen briefly while mpv is still probing the file (and reacting to it can cause a
        // portrait "bounce" that sometimes sticks).
        if (!ratio.isFinite() || ratio == 0f)
            return

        if (ratio in (1f / ASPECT_RATIO_MIN) .. ASPECT_RATIO_MIN) {
            // video is square, let Android do what it wants — but don't break an in-progress
            // startup rotation that we intentionally forced for a landscape/portrait file.
            if (isWithinOrientationStabilityLock())
                return
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            return
        }

        val desired = if (ratio > 1f)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT

        // Once we have a reliable non-square aspect ratio, clear the stability lock so future
        // files / reconfigs can update normally.
        if (isWithinOrientationStabilityLock()) {
            orientationStabilityLockUntilMs = 0L
            orientationStabilityLockValue = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        requestedOrientation = desired
    }

    private enum class ProbedOrientation { LANDSCAPE, PORTRAIT, SQUARE, UNKNOWN }

    private fun probeOrientationFromMetadata(path: String): ProbedOrientation {
        // Skip unsupported / remote schemes (mpv will update orientation later from video-params).
        if (path.startsWith("http://") || path.startsWith("https://") ||
            path.startsWith("rtsp://") || path.startsWith("rtmp://") ||
            path.startsWith("rtmps://") || path.startsWith("udp://") ||
            path.startsWith("tcp://") || path.startsWith("memory://") ||
            path.startsWith("data://") || path.startsWith("lavf://")
        ) return ProbedOrientation.UNKNOWN

        val mmr = MediaMetadataRetriever()
        try {
            if (path.startsWith("content://")) {
                mmr.setDataSource(this, Uri.parse(path))
            } else if (path.startsWith("file://")) {
                mmr.setDataSource(Uri.parse(path).path)
            } else {
                // Assume local filesystem path
                mmr.setDataSource(path)
            }

            val w0 = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h0 = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if (w0 == null || h0 == null || w0 <= 0 || h0 <= 0)
                return ProbedOrientation.UNKNOWN

            // Apply rotation metadata (common on phone recordings)
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val (w, h) = if (rot % 180 != 0) Pair(h0, w0) else Pair(w0, h0)

            val ratio = w.toFloat() / h.toFloat()
            if (ratio == 0f || ratio in (1f / ASPECT_RATIO_MIN) .. ASPECT_RATIO_MIN)
                return ProbedOrientation.SQUARE
            return if (ratio > 1f) ProbedOrientation.LANDSCAPE else ProbedOrientation.PORTRAIT
        } catch (_: Throwable) {
            return ProbedOrientation.UNKNOWN
        } finally {
            try { mmr.release() } catch (_: Throwable) {}
        }
    }

    private fun applyOrientationFromMetadata(path: String, isStartup: Boolean = false) {
        // screen orientation is fixed (Android TV)
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_SCREEN_PORTRAIT))
            return

        // Avoid probing the same path repeatedly (e.g., playlist refreshes).
        if (path == lastOrientationProbePath && !isStartup)
            return
        lastOrientationProbePath = path

        val probed = probeOrientationFromMetadata(path)
        val desired = when (probed) {
            ProbedOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ProbedOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            ProbedOrientation.SQUARE -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ProbedOrientation.UNKNOWN -> return
        }


        // If we're already in portrait with an unspecified orientation, don't force-lock it.
        // This avoids unnecessary churn when everything is already portrait.
        if (desired == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT &&
            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
            requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        ) return

        // Only change if needed to prevent redundant config churn.
        if (requestedOrientation != desired)
            requestedOrientation = desired

        // Hold the chosen orientation briefly so transient "square/unknown" aspect updates
        // from mpv won't bounce us back to portrait during startup/reconfig.
        lockOrientationStability(desired, if (isStartup) 2200L else 1600L)
    }



    @RequiresApi(26)
    private fun makeRemoteAction(@DrawableRes icon: Int, @StringRes title: Int, intentAction: String): RemoteAction {
        val intent = NotificationButtonReceiver.createIntent(this, intentAction)
        return RemoteAction(Icon.createWithResource(this, icon), getString(title), "", intent)
    }

    /**
     * Update Picture-in-picture parameters. Will only run if in PiP mode unless
     * `force` is set.
     */
    private fun updatePiPParams(force: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return
        if (!isInPictureInPictureMode && !force)
            return

        val playPauseAction = if (psc.pause)
            makeRemoteAction(R.drawable.ic_play_arrow_black_24dp, R.string.btn_play, "PLAY_PAUSE")
        else
            makeRemoteAction(R.drawable.ic_pause_black_24dp, R.string.btn_pause, "PLAY_PAUSE")
        val actions = mutableListOf<RemoteAction>()
        if (psc.playlistCount > 1) {
            actions.add(makeRemoteAction(
                R.drawable.ic_skip_previous_black_24dp, R.string.dialog_prev, "ACTION_PREV"
            ))
            actions.add(playPauseAction)
            actions.add(makeRemoteAction(
                R.drawable.ic_skip_next_black_24dp, R.string.dialog_next, "ACTION_NEXT"
            ))
        } else {
            actions.add(playPauseAction)
        }

        val params = with(PictureInPictureParams.Builder()) {
            val aspect = player.getVideoAspect() ?: 0.0
            setAspectRatio(Rational(aspect.times(10000).toInt(), 10000))
            setActions(actions)
        }
        try {
            setPictureInPictureParams(params.build())
        } catch (e: IllegalArgumentException) {
            // Android has some limits of what the aspect ratio can be
            params.setAspectRatio(Rational(1, 1))
            setPictureInPictureParams(params.build())
        }
    }

    // Media Session handling

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPause() {
            player.paused = true
        }
        override fun onPlay() {
            player.paused = false
        }
        override fun onSeekTo(pos: Long) {
            player.timePos = (pos / 1000.0)
        }
        override fun onSkipToNext() = playlistNext()
        override fun onSkipToPrevious() = playlistPrev()
        override fun onSetRepeatMode(repeatMode: Int) {
            MPVLib.setPropertyString("loop-playlist",
                if (repeatMode == PlaybackStateCompat.REPEAT_MODE_ALL) "inf" else "no")
            MPVLib.setPropertyString("loop-file",
                if (repeatMode == PlaybackStateCompat.REPEAT_MODE_ONE) "inf" else "no")
        }
        override fun onSetShuffleMode(shuffleMode: Int) {
            player.changeShuffle(false, shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL)
        }
    }

    private fun initMediaSession(): MediaSessionCompat {
        /*
            https://developer.android.com/guide/topics/media-apps/working-with-a-media-session
            https://developer.android.com/guide/topics/media-apps/audio-app/mediasession-callbacks
            https://developer.android.com/reference/android/support/v4/media/session/MediaSessionCompat
         */
        val session = MediaSessionCompat(this, TAG)
        session.setFlags(0)
        session.setCallback(mediaSessionCallback)
        return session
    }

    private fun updateMediaSession() {
        synchronized (psc) {
            mediaSession?.let { psc.write(it) }
        }
    }

    // mpv events

    private fun eventPropertyUi(property: String, dummy: Any?, metaUpdated: Boolean) {
        if (!activityIsForeground) return
        when (property) {
            "track-list" -> player.loadTracks()
            "current-tracks/audio/selected", "current-tracks/video/image" -> updateAudioUI()
            "hwdec-current" -> updateDecoderButton()
        }
        if (metaUpdated)
            updateMetadataDisplay()
    }

    private fun eventPropertyUi(property: String, value: Boolean) {
        if (!activityIsForeground) return
        when (property) {
            "pause" -> updatePlaybackStatus(value)
            "mute" -> { // indirectly from updateAudioPresence()
                updateAudioUI()
            }
        }
    }

    private fun eventPropertyUi(property: String, value: Long) {
        if (!activityIsForeground) return
        when (property) {
            "time-pos" -> updatePlaybackPos(psc.positionSec)
            "playlist-pos", "playlist-count" -> updatePlaylistButtons()
            "video-params/w", "video-params/h" -> {
                syncZoomVideoGeometry()
                prepareZoomSurfaceAndRevealWhenReady()
            }
        }
    }

    private fun eventPropertyUi(property: String, value: Double) {
        if (!activityIsForeground) return
        when (property) {
            "duration/full" -> updatePlaybackDuration(psc.durationSec)
            "video-params/aspect", "video-params/rotate" -> {
                updateOrientation()
                updatePiPParams()
                syncZoomVideoGeometry()
                prepareZoomSurfaceAndRevealWhenReady()
            }
            "panscan" -> {
                syncZoomVideoGeometry()
                prepareZoomSurfaceAndRevealWhenReady()
            }
        }
    }

    private fun eventPropertyUi(property: String, value: String, metaUpdated: Boolean) {
        if (!activityIsForeground) return
        when (property) {
            "speed" -> updateSpeedButton()
            "video-aspect-override" -> {
                syncZoomVideoGeometry()
                prepareZoomSurfaceAndRevealWhenReady()
            }
        }
        if (metaUpdated)
            updateMetadataDisplay()
    }

    private fun eventUi(eventId: Int) {
        if (!activityIsForeground) return
        // empty
    }

    override fun eventProperty(property: String) {
        val metaUpdated = psc.update(property)
        if (metaUpdated)
            updateMediaSession()
        if (property == "track-list")
            scheduleTrackStateCheckpoint()
        if (property == "loop-file" || property == "loop-playlist") {
            mediaSession?.setRepeatMode(when (player.getRepeat()) {
                2 -> PlaybackStateCompat.REPEAT_MODE_ONE
                1 -> PlaybackStateCompat.REPEAT_MODE_ALL
                else -> PlaybackStateCompat.REPEAT_MODE_NONE
            })
        } else if (property == "current-tracks/audio/selected") {
            updateAudioPresence()
        }

        if (property == "pause" || property == "current-tracks/audio/selected")
            handleAudioFocus()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, null, metaUpdated) }
    }

    override fun eventProperty(property: String, value: Boolean) {
        val metaUpdated = psc.update(property, value)
        if (metaUpdated)
            updateMediaSession()
        if (property == "eof-reached" && value) {
            (currentFilePath ?: MPVLib.getPropertyString("path"))?.let {
                clearStoredPlaybackPosition(applicationContext, it)
                Log.d(TAG, "cleared EOF position while file remains loaded: $it")
            }
            lastPositionAutosaveSecond = -1L
        }
        if (property == "shuffle") {
            mediaSession?.setShuffleMode(if (value)
                PlaybackStateCompat.SHUFFLE_MODE_ALL
            else
                PlaybackStateCompat.SHUFFLE_MODE_NONE)
        } else if (property == "mute") {
            updateAudioPresence()
        }

        if (metaUpdated || property == "mute")
            handleAudioFocus()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, value) }
    }

    override fun eventProperty(property: String, value: Long) {
        if (psc.update(property, value))
            updateMediaSession()
        if (property == "time-pos")
            maybeAutosavePlaybackPosition(value)

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, value) }
    }

    override fun eventProperty(property: String, value: Double) {
        if (psc.update(property, value))
            updateMediaSession()
        if (property in PERSISTED_PER_FILE_OPTIONS)
            scheduleTrackStateCheckpoint()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, value) }
    }

    override fun eventProperty(property: String, value: String) {
        val metaUpdated = psc.update(property, value)
        if (metaUpdated)
            updateMediaSession()
        if (property in PERSISTED_PER_FILE_OPTIONS ||
            property == "sid" ||
            property == "secondary-sid" ||
            property == "aid"
        ) {
            // Debouncing is intentional. File-local properties reset during on_unload, before
            // END_FILE. By the time this runs, END_FILE/START_FILE has disarmed fileStateReady,
            // so a reset notification cannot replace the user's last real value.
            scheduleTrackStateCheckpoint()
        }

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, value, metaUpdated) }
    }

    override fun eventEndFile(reachedEof: Boolean) {
        val endedPath = currentFilePath ?: MPVLib.getPropertyString("path")
        fileStateReady = false
        restoringPerFileState = false
        clearPendingTrackSelections()
        perFileStateHandler.removeCallbacks(trackStateCheckpointRunnable)

        if (reachedEof && endedPath != null) {
            // EOF deletes only the independent resume checkpoint. All per-file options, track
            // selections, and external-file lists deliberately remain untouched.
            clearStoredPlaybackPosition(applicationContext, endedPath)
            // Clean up stale files created by older app versions. resume-playback=no makes this
            // non-authoritative, but deleting it prevents an old final-frame checkpoint from
            // resurfacing if the user later changes mpv.conf.
            MPVLib.command(arrayOf("delete-watch-later-config", endedPath))
            Log.d(TAG, "cleared completed-file position: $endedPath")
        }
        currentFilePath = null
        lastPositionAutosaveSecond = -1L

        event(MpvEvent.MPV_EVENT_END_FILE)
    }

    override fun event(eventId: Int) {
        if (eventId == MpvEvent.MPV_EVENT_END_FILE) {
            psc.eof()
            updateMediaSession()
        }

        if (eventId == MpvEvent.MPV_EVENT_SHUTDOWN)
            finishWithResult(if (playbackHasStarted) RESULT_OK else RESULT_CANCELED)

        if (eventId == MpvEvent.MPV_EVENT_PLAYBACK_RESTART) {
            // This also follows completed seeks. Save the precise new position instead of waiting
            // for the next five-second autosave tick.
            persistCurrentPlaybackPosition()
            // A seek completed. If the user has released the finger, resume playback now.
            scrubSeekInFlight = false
            lastScrubAsyncUserdata = 0L
            if (resumeAfterScrubSeek && !gestureScrubActive && !seekbarScrubActive) {
                resumeAfterScrubSeek = false
                eventUiHandler.post { player.paused = false }
            }
        }

        if (eventId == MpvEvent.MPV_EVENT_FILE_LOADED) {
            val loadedPath = MPVLib.getPropertyString("path")
            currentFilePath = loadedPath
            lastPositionAutosaveSecond = -1L
            if (loadedPath != null) {
                try {
                    restorePerFileState(loadedPath)
                } catch (error: Throwable) {
                    restoringPerFileState = false
                    fileStateReady = true
                    Log.e(TAG, "failed to restore per-file state for $loadedPath", error)
                }
            } else {
                fileStateReady = false
            }
            eventUiHandler.post {
                videoGeometryBlackoutFileLoadedSeen = true
                prepareZoomSurfaceAndRevealWhenReady()
            }
        }

        if (eventId == MpvEvent.MPV_EVENT_VIDEO_RECONFIG) {
            eventUiHandler.post { prepareZoomSurfaceAndRevealWhenReady() }
        }

        if (eventId == MpvEvent.MPV_EVENT_START_FILE) {
            fileStateReady = false
            restoringPerFileState = false
            lastPositionAutosaveSecond = -1L
            clearPendingTrackSelections()
            perFileStateHandler.removeCallbacks(trackStateCheckpointRunnable)
            // Reset any view-level zoom/pan when a new file starts.

            // Apply orientation as early as possible for playlist items, so we don't show the wrong orientation first.
            // Must run on the UI thread.
            if (autoRotationMode == "auto") {
                val p = MPVLib.getPropertyString("path")
                if (p != null) eventUiHandler.post { try { applyOrientationFromMetadata(p) } catch (_: Throwable) {} }
            }

            eventUiHandler.postAtFrontOfQueue { beginVideoGeometryBlackout() }
            try {
                MPVLib.setPropertyDouble("video-zoom", 0.0)
                MPVLib.setPropertyDouble("video-pan-x", 0.0)
                MPVLib.setPropertyDouble("video-pan-y", 0.0)
            } catch (_: Throwable) {
                // ignore
            }

            val cmds = onloadCommands.toTypedArray()
            onloadCommands.clear()
            for (c in cmds)
                MPVLib.command(c)

            if (this.statsLuaMode > 0 && !playbackHasStarted) {
                MPVLib.command(arrayOf("script-binding", "stats/display-page-${this.statsLuaMode}-toggle"))
            }

            playbackHasStarted = true
        }

        if (!activityIsForeground) return
        eventUiHandler.post { eventUi(eventId) }
    }


    // --- Scrub seek helpers ---
    // We keep the frame frozen while the finger is moving, then do a single exact seek on idle/release.
    private fun abortLastScrubSeek() {
        val ud = lastScrubAsyncUserdata
        if (ud != 0L) {
            try { MPVLib.abortAsyncCommand(ud) } catch (_: Throwable) {}
            lastScrubAsyncUserdata = 0L
            scrubSeekInFlight = false
        }
    }

    private fun sendScrubSeek(targetSec: Double, exact: Boolean) {
        // Cancel the previous async seek so the latest target wins.
        abortLastScrubSeek()
        val ud = scrubAsyncCounter++
        lastScrubAsyncUserdata = ud
        val mode = if (exact) "absolute+exact" else "absolute+keyframes"
        MPVLib.commandAsync(arrayOf("seek", targetSec.toString(), mode), ud)
        scrubSeekInFlight = true
    }

    private fun performGestureIdleSeek() {
        if (!gestureScrubActive) return
        val target = pendingGestureSeekSec ?: return
        if (lastIssuedGestureSeekSec == target) return
        lastIssuedGestureSeekSec = target
        sendScrubSeek(target.toDouble(), exact = smoothSeekGesture)
    }

    private fun performSeekbarIdleSeek() {
        if (!seekbarScrubActive) return
        val target = pendingSeekbarSeekPos ?: return
        if (lastIssuedSeekbarSeekPos == target) return
        lastIssuedSeekbarSeekPos = target
        sendScrubSeek(target, exact = true)
    }

    // Gesture handler

    private var initialSeek = 0f
    private var initialBright = 0f
    private var initialVolume = 0
    private var maxVolume = 0
    /** 0 = initial, 1 = paused, 2 = was already paused */
    private var pausedForSeek = 0

    // Keeps gesture seeking responsive after hitting either edge of the video.
    // Any drag distance beyond 0/duration is folded into this offset, so the
    // next 1-second movement in the opposite direction immediately leaves the edge.
    private var gestureSeekDeltaOffsetSec = 0

    private fun fadeGestureText() {
        refreshPlayerOverlay()
        fadeHandler.removeCallbacks(fadeRunnable3)
        binding.gestureTextView.visibility = View.VISIBLE

        fadeHandler.postDelayed(fadeRunnable3, 500L)
    }

    override fun onPropertyChange(p: PropertyChange, diff: Float) {
        val gestureTextView = binding.gestureTextView
        when (p) {
            /* Drag gestures */
            PropertyChange.Init -> {
                mightWantToToggleControls = false
                cancelPendingTapToggle()
                refreshPlayerOverlay()

                initialSeek = (psc.position / 1000f)
                initialBright = Utils.getScreenBrightness(this) ?: 0.5f
                with (audioManager!!) {
                    initialVolume = getStreamVolume(STREAM_TYPE)
                    maxVolume = if (isVolumeFixed)
                        0
                    else
                        getStreamMaxVolume(STREAM_TYPE)
                }
                if (!isPlayingAudio)
                    maxVolume = 0 // disallow volume gesture if no audio
                pausedForSeek = 0
                gestureSeekDeltaOffsetSec = 0

                fadeHandler.removeCallbacks(fadeRunnable3)
                gestureTextView.visibility = View.VISIBLE
                gestureTextView.text = ""
            }
            PropertyChange.Seek -> {
                // disable seeking when duration is unknown
                val duration = (psc.duration / 1000f)
                if (duration == 0f || initialSeek < 0)
                    return

                // Pause while seeking (finger still on screen) and only resume on release.
                // If playback was already paused, keep it paused.
                if (pausedForSeek == 0) {
                    pausedForSeek = if (psc.pause) 2 else 1
                    if (pausedForSeek == 1)
                        player.paused = true

                    // Start of a scrub gesture.
                    gestureScrubActive = true
                    pendingGestureSeekSec = null
                    lastIssuedGestureSeekSec = null
                    scrubSeekHandler.removeCallbacks(gestureIdleSeekRunnable)
                }

                // Quantize to 1 second steps. When the gesture reaches the
                // start/end of the video, absorb any extra drag into an offset.
                // This prevents "overscroll debt": moving 1 second back from
                // the edge should require the same small reverse movement as it
                // does anywhere else in the video.
                val startPos = initialSeek.roundToInt()
                val durationSec = duration.roundToInt()
                val rawDeltaSec = diff.roundToInt()
                val minDeltaSec = -startPos
                val maxDeltaSec = durationSec - startPos
                var deltaSec = rawDeltaSec - gestureSeekDeltaOffsetSec

                if (deltaSec > maxDeltaSec) {
                    gestureSeekDeltaOffsetSec = rawDeltaSec - maxDeltaSec
                    deltaSec = maxDeltaSec
                } else if (deltaSec < minDeltaSec) {
                    gestureSeekDeltaOffsetSec = rawDeltaSec - minDeltaSec
                    deltaSec = minDeltaSec
                }

                val newPos = startPos + deltaSec
                val newDiff = deltaSec

                // IMPORTANT: Do NOT seek while the finger is moving.
                // We keep the current frame frozen, and only perform an exact seek once the
                // finger stops moving (idle) or on release.
                // Cancel any in-flight scrub seek so no new frame appears while moving.
                if (lastScrubAsyncUserdata != 0L) {
                    abortLastScrubSeek()
                }

                pendingGestureSeekSec = newPos

                // Schedule idle exact seek.
                scrubSeekHandler.removeCallbacks(gestureIdleSeekRunnable)
                scrubSeekHandler.postDelayed(gestureIdleSeekRunnable, SCRUB_IDLE_SEEK_DELAY_MS)

                val posText = Utils.prettyTime(newPos)
                val diffText = Utils.prettyTime(newDiff, true)
                gestureTextView.text = getString(R.string.ui_seek_distance, posText, diffText)
            }
            PropertyChange.Volume -> {
                if (maxVolume == 0)
                    return
                val newVolume = (initialVolume + (diff * maxVolume).toInt()).coerceIn(0, maxVolume)
                val newVolumePercent = 100 * newVolume / maxVolume
                audioManager!!.setStreamVolume(STREAM_TYPE, newVolume, 0)

                gestureTextView.text = getString(R.string.ui_volume, newVolumePercent)
            }
            PropertyChange.Bright -> {
                val lp = window.attributes
                val newBright = (initialBright + diff).coerceIn(0f, 1f)
                lp.screenBrightness = newBright
                window.attributes = lp

                gestureTextView.text = getString(R.string.ui_brightness, (newBright * 100).roundToInt())
            }
            PropertyChange.Finalize -> {
                // End of scrub gesture.
                gestureScrubActive = false
                scrubSeekHandler.removeCallbacks(gestureIdleSeekRunnable)

                val shouldResume = (pausedForSeek == 1)
                if (shouldResume) resumeAfterScrubSeek = true

                val target = pendingGestureSeekSec
                if (target != null && lastIssuedGestureSeekSec != target) {
                    lastIssuedGestureSeekSec = target
                    sendScrubSeek(target.toDouble(), exact = smoothSeekGesture)
                }

                // If nothing is in-flight, resume immediately.
                if (shouldResume && !scrubSeekInFlight) {
                    resumeAfterScrubSeek = false
                    player.paused = false
                }

                pendingGestureSeekSec = null
                gestureSeekDeltaOffsetSec = 0
                gestureTextView.visibility = View.GONE
            }

            /* Tap gestures */
            PropertyChange.SeekFixed -> {
                // Double-tap seek should not toggle the control UI.
                cancelPendingTapToggle()
                mightWantToToggleControls = false

                val seekTime = diff * 10f
                val newPos = psc.positionSec + seekTime.toInt() // only for display
                MPVLib.command(arrayOf("seek", seekTime.toString(), "relative"))

                val diffText = Utils.prettyTime(seekTime.toInt(), true)
                gestureTextView.text = getString(R.string.ui_seek_distance, Utils.prettyTime(newPos), diffText)
                fadeGestureText()
            }
            PropertyChange.PlayPause -> {
                // Double-tap play/pause should not trigger control UI.
                cancelPendingTapToggle()
                mightWantToToggleControls = false
                player.cyclePause()
            }
            PropertyChange.Custom -> {
                // Double-tap custom action should not toggle the control UI.
                cancelPendingTapToggle()
                mightWantToToggleControls = false

                val keycode = 0x10002 + diff.toInt()
                MPVLib.command(arrayOf("keypress", "0x%x".format(keycode)))
            }
        }
    }

    companion object {
        private const val TAG = "mpv"
        // how long should controls be displayed on screen (ms)
        private const val CONTROLS_DISPLAY_TIMEOUT = 1500L
        // Android's short toast is roughly two seconds; track changes use one quarter of that.
        private const val TRACK_SWITCH_TOAST_DURATION_MS = 500L
        // Controls fade-in/out durations (ms). Keep them very fast but non-zero to avoid a harsh pop.
        private const val CONTROLS_FADE_IN_DURATION = 80L
        private const val CONTROLS_FADE_OUT_DURATION = 80L
        // Predictive aspect-menu geometry is held briefly so asynchronous mpv
        // property notifications cannot momentarily restore an intermediate state.
        private const val ASPECT_MENU_PREDICTIVE_SYNC_GRACE_MS = 120L

        // Tap timing (must match TouchGestures.TAP_DURATION).
        // - Double-tap gestures: fast window (ms)
        // - Single-tap control toggle: delayed slightly longer so double-tap can cancel it (ms)
        private const val DOUBLE_TAP_TIMEOUT_MS = 225L
        private const val SINGLE_TAP_TOGGLE_DELAY_MS = DOUBLE_TAP_TIMEOUT_MS + 20L
        private const val IMMERSIVE_RESTORE_RETRY_MS = 120L
        private const val MANUAL_SYSTEM_BARS_GESTURE_WINDOW_MS = 2000L

        // Reserve the very top portion of the screen for Android system gestures (notification
        // shade/status bar). We only suppress the tap-to-toggle if the finger *moves down*
        // meaningfully from this region.
        private const val STATUS_BAR_DEADZONE_PERCENT = 5f
        private const val STATUS_BAR_SWIPE_CANCEL_DP = 16f
        // resolution (px) of the thumbnail displayed with playback notification
        private const val THUMB_SIZE = 384
        // smallest aspect ratio that is considered non-square
        private const val ASPECT_RATIO_MIN = 1.2f // covers 5:4 and up
        // fraction to which audio volume is ducked on loss of audio focus
        private const val AUDIO_FOCUS_DUCKING = 0.5f
        // request codes for invoking other activities
        private const val RCODE_EXTERNAL_AUDIO = 1000
        private const val RCODE_EXTERNAL_SUB = 1001
        private const val RCODE_LOAD_FILE = 1002
        // action of result intent
        private const val RESULT_INTENT = "is.xyz.mpv.MPVActivity.result"
        // stream type used with AudioManager
        private const val STREAM_TYPE = AudioManager.STREAM_MUSIC
        // precision used by seekbar (1/s)
        private const val SEEK_BAR_PRECISION = 2

        // When scrubbing, wait briefly for the finger to stop moving before doing an exact seek.
        private const val SCRUB_IDLE_SEEK_DELAY_MS = 140L

        private const val POSITION_AUTOSAVE_INTERVAL_SECONDS = 5L
        private const val EOF_POSITION_TOLERANCE_SECONDS = 0.5
        private const val TRACK_STATE_SETTLE_DELAY_MS = 180L
        private const val PREF_PLAYBACK_POSITION = "playback_position_ms"
        private const val PREF_OPTION_PREFIX = "option_"
        private const val PREF_EXTERNAL_SUB_FILES = "external_sub_files"
        private const val PREF_EXTERNAL_AUDIO_FILES = "external_audio_files"

        // Per-file subtitle persistence keys
        private const val PREF_SUB_KIND = "sub_kind"
        private const val PREF_SUB_EXTERNAL = "sub_external"
        private const val PREF_SUB_SID = "sub_sid"
        private const val PREF_SUB2_KIND = "sub2_kind"
        private const val PREF_SUB2_EXTERNAL = "sub2_external"
        private const val PREF_SUB2_SID = "sub2_sid"

        // Per-file audio persistence keys
        private const val PREF_AUD_KIND = "aud_kind"
        private const val PREF_AUD_EXTERNAL = "aud_external"
        private const val PREF_AUD_SID = "aud_sid"
        private const val PREF_TRACK_KIND_EXTERNAL = "external"
        private const val PREF_TRACK_KIND_ID = "sid"

        private val TRACK_OPTION_NAMES = setOf("aid", "sid", "secondary-sid")
        private val PERSISTED_PER_FILE_OPTIONS =
            MPVView.PER_FILE_PLAYBACK_OPTIONS.filterNot { it in TRACK_OPTION_NAMES }.toSet()

        private fun sha1Hex(input: String): String {
            val bytes = MessageDigest.getInstance("SHA-1")
                .digest(input.toByteArray(Charsets.UTF_8))
            val result = StringBuilder(bytes.size * 2)
            for (byte in bytes)
                result.append(String.format("%02x", byte))
            return result.toString()
        }

        private fun perFileKey(suffix: String, path: String): String =
            "perfile_${suffix}_${sha1Hex(path)}"

        private fun storePlaybackPosition(context: Context, path: String, seconds: Double) {
            if (!seconds.isFinite() || seconds < 0.0)
                return
            getDefaultSharedPreferences(context.applicationContext).edit()
                .putLong(
                    perFileKey(PREF_PLAYBACK_POSITION, path),
                    (seconds * 1000.0).roundToLong()
                )
                .commit()
        }

        private fun clearStoredPlaybackPosition(context: Context, path: String) {
            getDefaultSharedPreferences(context.applicationContext).edit()
                .remove(perFileKey(PREF_PLAYBACK_POSITION, path))
                .commit()
        }

        private fun externalFilenameForTrack(type: String, id: Int): String? {
            if (id < 0)
                return null
            val count = MPVLib.getPropertyInt("track-list/count") ?: return null
            for (i in 0 until count) {
                if (MPVLib.getPropertyString("track-list/$i/type") != type)
                    continue
                if (MPVLib.getPropertyInt("track-list/$i/id") != id)
                    continue
                if (MPVLib.getPropertyBoolean("track-list/$i/external") != true)
                    return null
                return MPVLib.getPropertyString("track-list/$i/external-filename")
            }
            return null
        }

        private fun putTrackSelection(
            editor: android.content.SharedPreferences.Editor,
            path: String,
            property: String,
            type: String,
            kindSuffix: String,
            externalSuffix: String,
            idSuffix: String
        ) {
            val id = MPVLib.getPropertyString(property)?.toIntOrNull() ?: -1
            val external = externalFilenameForTrack(type, id)
            if (external != null) {
                editor.putString(perFileKey(kindSuffix, path), PREF_TRACK_KIND_EXTERNAL)
                editor.putString(perFileKey(externalSuffix, path), external)
                editor.remove(perFileKey(idSuffix, path))
            } else {
                editor.putString(perFileKey(kindSuffix, path), PREF_TRACK_KIND_ID)
                editor.putInt(perFileKey(idSuffix, path), id)
                editor.remove(perFileKey(externalSuffix, path))
            }
        }

        /**
         * Notification actions run without an Activity instance. Take a synchronous checkpoint
         * before changing the playlist so a process kill or immediate END_FILE cannot lose the
         * last position/settings.
         */
        internal fun persistCurrentFileCheckpoint(context: Context) {
            val path = MPVLib.getPropertyString("path") ?: return
            val prefs = getDefaultSharedPreferences(context.applicationContext)
            val eof = MPVLib.getPropertyBoolean("eof-reached") == true
            val editor = prefs.edit()

            if (!eof) {
                for (name in PERSISTED_PER_FILE_OPTIONS) {
                    MPVLib.getPropertyString(name)?.let {
                        editor.putString(perFileKey("$PREF_OPTION_PREFIX$name", path), it)
                    }
                }
                putTrackSelection(
                    editor, path, "sid", "sub",
                    PREF_SUB_KIND, PREF_SUB_EXTERNAL, PREF_SUB_SID
                )
                putTrackSelection(
                    editor, path, "secondary-sid", "sub",
                    PREF_SUB2_KIND, PREF_SUB2_EXTERNAL, PREF_SUB2_SID
                )
                putTrackSelection(
                    editor, path, "aid", "audio",
                    PREF_AUD_KIND, PREF_AUD_EXTERNAL, PREF_AUD_SID
                )

                val subKey = perFileKey(PREF_EXTERNAL_SUB_FILES, path)
                val audioKey = perFileKey(PREF_EXTERNAL_AUDIO_FILES, path)
                val subtitles = prefs.getStringSet(subKey, emptySet<String>())
                    ?.toMutableSet() ?: mutableSetOf<String>()
                val audio = prefs.getStringSet(audioKey, emptySet<String>())
                    ?.toMutableSet() ?: mutableSetOf<String>()
                val trackCount = MPVLib.getPropertyInt("track-list/count") ?: 0
                for (i in 0 until trackCount) {
                    if (MPVLib.getPropertyBoolean("track-list/$i/external") != true)
                        continue
                    val filename = MPVLib.getPropertyString(
                        "track-list/$i/external-filename"
                    ) ?: continue
                    when (MPVLib.getPropertyString("track-list/$i/type")) {
                        "sub" -> subtitles.add(filename)
                        "audio" -> audio.add(filename)
                    }
                }
                editor.putStringSet(subKey, subtitles)
                editor.putStringSet(audioKey, audio)
            }

            val positionKey = perFileKey(PREF_PLAYBACK_POSITION, path)
            if (!prefs.getBoolean("save_position", false) || eof) {
                editor.remove(positionKey)
            } else {
                val seconds = MPVLib.getPropertyDouble("time-pos/full")
                    ?: MPVLib.getPropertyDouble("time-pos")
                if (seconds != null && seconds.isFinite() && seconds >= 0.0)
                    editor.putLong(positionKey, (seconds * 1000.0).roundToLong())
            }
            editor.commit()
        }
    }
}
