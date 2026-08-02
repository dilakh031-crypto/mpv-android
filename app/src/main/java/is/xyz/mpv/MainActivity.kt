package `is`.xyz.mpv

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import java.lang.ref.WeakReference

class MainActivity : AppCompatActivity(R.layout.activity_main) {
    private var playerOrientationHandoffActive = false
    private var playerOrientationHandoffCompleted = false
    private var playerOrientationHandoffTarget = Configuration.ORIENTATION_UNDEFINED
    private var playerOrientationHandoffBaseRequest = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var playerOrientationReleaseGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        playerOrientationHandoffActive =
            savedInstanceState?.getBoolean(STATE_PLAYER_ORIENTATION_HANDOFF_ACTIVE) ?: false
        playerOrientationHandoffCompleted =
            savedInstanceState?.getBoolean(STATE_PLAYER_ORIENTATION_HANDOFF_COMPLETED) ?: false
        playerOrientationHandoffTarget = savedInstanceState?.getInt(
            STATE_PLAYER_ORIENTATION_HANDOFF_TARGET,
            Configuration.ORIENTATION_UNDEFINED,
        ) ?: Configuration.ORIENTATION_UNDEFINED
        playerOrientationHandoffBaseRequest = savedInstanceState?.getInt(
            STATE_PLAYER_ORIENTATION_HANDOFF_BASE_REQUEST,
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
        ) ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        registerOrientationHandoffHost(this)
        if (playerOrientationHandoffActive)
            applyPlayerOrientationHandoffLock()

        supportActionBar?.setTitle(R.string.mpv_activity)

        // The original plan was to have the file/doc picker live as fragments
        // under here but that requires refactoring I'm really not willing to figure out now.
        // ~sfan5, 2022-06-30

        if (savedInstanceState == null) {
            with (supportFragmentManager.beginTransaction()) {
                setReorderingAllowed(true)
                add(R.id.fragment_container_view, MainScreenFragment())
                commit()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(
            STATE_PLAYER_ORIENTATION_HANDOFF_ACTIVE,
            playerOrientationHandoffActive,
        )
        outState.putBoolean(
            STATE_PLAYER_ORIENTATION_HANDOFF_COMPLETED,
            playerOrientationHandoffCompleted,
        )
        outState.putInt(
            STATE_PLAYER_ORIENTATION_HANDOFF_TARGET,
            playerOrientationHandoffTarget,
        )
        outState.putInt(
            STATE_PLAYER_ORIENTATION_HANDOFF_BASE_REQUEST,
            playerOrientationHandoffBaseRequest,
        )
        super.onSaveInstanceState(outState)
    }

    override fun onPostResume() {
        super.onPostResume()
        schedulePlayerOrientationHandoffReleaseIfSafe()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus)
            schedulePlayerOrientationHandoffReleaseIfSafe()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // A stopped Activity can still receive the task's configuration while MPVActivity is on
        // top. Reassert the return family so Samsung's Android 9 user-rotation preference cannot
        // replace the saved landscape state with portrait before this Activity becomes visible.
        if (playerOrientationHandoffActive &&
            isConfigurationOrientation(playerOrientationHandoffTarget) &&
            newConfig.orientation != playerOrientationHandoffTarget
        ) {
            applyPlayerOrientationHandoffLock()
        }
    }

    override fun onDestroy() {
        unregisterOrientationHandoffHost(this)
        super.onDestroy()
    }

    private fun beginPlayerOrientationHandoff(targetOrientation: Int) {
        if (!supportsPlayerOrientationHandoff() ||
            !isConfigurationOrientation(targetOrientation)
        ) {
            return
        }

        // Keep the original request across consecutive player sessions. In Android 9 rotation-lock
        // mode the explicit family lock may need to remain active after the first return, because a
        // forced portrait player can permanently change the system's user rotation to portrait.
        if (!playerOrientationHandoffActive)
            playerOrientationHandoffBaseRequest = requestedOrientation

        playerOrientationReleaseGeneration += 1
        playerOrientationHandoffActive = true
        playerOrientationHandoffCompleted = false
        playerOrientationHandoffTarget = targetOrientation
        applyPlayerOrientationHandoffLock()
    }

    private fun completePlayerOrientationHandoff(targetOrientation: Int) {
        if (!supportsPlayerOrientationHandoff() ||
            !isConfigurationOrientation(targetOrientation)
        ) {
            return
        }

        if (!playerOrientationHandoffActive)
            playerOrientationHandoffBaseRequest = requestedOrientation

        playerOrientationReleaseGeneration += 1
        playerOrientationHandoffActive = true
        playerOrientationHandoffCompleted = true
        playerOrientationHandoffTarget = targetOrientation
        applyPlayerOrientationHandoffLock()
    }

    private fun applyPlayerOrientationHandoffLock() {
        val desired = when (playerOrientationHandoffTarget) {
            Configuration.ORIENTATION_LANDSCAPE ->
                ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE

            Configuration.ORIENTATION_PORTRAIT ->
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT

            else -> return
        }

        if (requestedOrientation == desired)
            return

        try {
            requestedOrientation = desired
        } catch (e: IllegalStateException) {
            // Vendor Android 8/9 builds can briefly reject requests while task windows are being
            // reordered. MPVActivity repeats the same handoff immediately before finish.
            Log.w(TAG, "Main orientation handoff rejected: $desired", e)
        }
    }

    private fun schedulePlayerOrientationHandoffReleaseIfSafe() {
        if (!playerOrientationHandoffActive || !playerOrientationHandoffCompleted)
            return

        // In Android 9 rotation-lock mode, releasing to UNSPECIFIED would immediately apply the
        // user rotation preference that a forced portrait player may have changed to portrait.
        // Retain the saved family in that mode. If the user enables auto-rotate later, focus is
        // regained after closing Quick Settings and this method releases the temporary hold.
        if (!isSystemAutoRotateEnabled())
            return
        if (resources.configuration.orientation != playerOrientationHandoffTarget) {
            applyPlayerOrientationHandoffLock()
            return
        }

        val generation = ++playerOrientationReleaseGeneration
        val decor = window.decorView

        // Two frame boundaries are not a timed delay: they ensure the returning Activity has been
        // attached and drawn under the handoff lock before restoring its normal orientation policy.
        decor.postOnAnimation {
            decor.postOnAnimation {
                val mayRelease =
                    generation == playerOrientationReleaseGeneration &&
                        playerOrientationHandoffActive &&
                        playerOrientationHandoffCompleted &&
                        isSystemAutoRotateEnabled() &&
                        resources.configuration.orientation == playerOrientationHandoffTarget

                if (mayRelease) {
                    val restoreRequest = playerOrientationHandoffBaseRequest
                    playerOrientationHandoffActive = false
                    playerOrientationHandoffCompleted = false
                    playerOrientationHandoffTarget = Configuration.ORIENTATION_UNDEFINED
                    playerOrientationReleaseGeneration += 1

                    if (requestedOrientation != restoreRequest) {
                        try {
                            requestedOrientation = restoreRequest
                        } catch (e: IllegalStateException) {
                            Log.w(TAG, "Main orientation release rejected: $restoreRequest", e)
                        }
                    }
                }
            }
        }
    }

    private fun supportsPlayerOrientationHandoff(): Boolean {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_SCREEN_PORTRAIT))
            return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode)
            return false
        return true
    }

    private fun isSystemAutoRotateEnabled(): Boolean {
        return try {
            Settings.System.getInt(
                contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                0,
            ) == 1
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        private const val TAG = "mpv"
        private const val STATE_PLAYER_ORIENTATION_HANDOFF_ACTIVE =
            "player_orientation_handoff_active"
        private const val STATE_PLAYER_ORIENTATION_HANDOFF_COMPLETED =
            "player_orientation_handoff_completed"
        private const val STATE_PLAYER_ORIENTATION_HANDOFF_TARGET =
            "player_orientation_handoff_target"
        private const val STATE_PLAYER_ORIENTATION_HANDOFF_BASE_REQUEST =
            "player_orientation_handoff_base_request"

        private var orientationHandoffHost: WeakReference<MainActivity>? = null

        private fun registerOrientationHandoffHost(activity: MainActivity) {
            orientationHandoffHost = WeakReference(activity)
        }

        private fun unregisterOrientationHandoffHost(activity: MainActivity) {
            if (orientationHandoffHost?.get() === activity)
                orientationHandoffHost?.clear()
        }

        internal fun beginPlayerOrientationHandoff(taskId: Int, targetOrientation: Int) {
            val host = orientationHandoffHost?.get() ?: return
            if (host.taskId != taskId || host.isFinishing || host.isDestroyed)
                return
            host.beginPlayerOrientationHandoff(targetOrientation)
        }

        internal fun completePlayerOrientationHandoff(taskId: Int, targetOrientation: Int) {
            val host = orientationHandoffHost?.get() ?: return
            if (host.taskId != taskId || host.isFinishing || host.isDestroyed)
                return
            host.completePlayerOrientationHandoff(targetOrientation)
        }

        private fun isConfigurationOrientation(orientation: Int): Boolean {
            return orientation == Configuration.ORIENTATION_LANDSCAPE ||
                orientation == Configuration.ORIENTATION_PORTRAIT
        }
    }
}
