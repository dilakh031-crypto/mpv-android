package `is`.xyz.mpv

import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import kotlin.math.*

enum class PropertyChange {
    Init,
    Seek,
    Volume,
    Bright,
    Finalize,

    /* Tap gestures */
    SeekFixed,
    PlayPause,
    Custom,
}

internal interface TouchGesturesObserver {
    fun onPropertyChange(p: PropertyChange, diff: Float)
}

internal class TouchGestures(private val observer: TouchGesturesObserver) {

    private enum class State {
        Up,
        Down,
        ControlSeek,
        ControlVolume,
        ControlBright,
    }

    private var state = State.Up
    // relevant movement direction for the current state (0=H, 1=V)
    private var stateDirection = 0

    // timestamp of the last tap (ACTION_UP)
    private var lastTapTime = 0L
    // when the current gesture began
    private var lastDownTime = 0L

    // where user initially placed their finger (ACTION_DOWN)
    private var initialPos = PointF()
    // last non-throttled processed position
    private var lastPos = PointF()

    // Seek movement is accumulated separately so equal distances can produce
    // different offsets depending on how quickly the finger moves.
    private var seekLastX = 0f
    private var seekLastEventTime = 0L
    private var seekVelocityScreensPerSec = 0f
    private var seekAccumulatedSec = 0f
    private var seekDirection = 0

    private var width = 0f
    private var height = 0f
    // minimum movement which triggers a Control state
    private var trigger = 0f

    // which property change should be invoked where
    private var gestureHoriz = State.Down
    private var gestureVertLeft = State.Down
    private var gestureVertRight = State.Down
    private var tapGestureLeft : PropertyChange? = null
    private var tapGestureCenter : PropertyChange? = null
    private var tapGestureRight : PropertyChange? = null

    private inline fun checkFloat(vararg n: Float): Boolean {
        return !n.any { it.isInfinite() || it.isNaN() }
    }
    private inline fun assertFloat(vararg n: Float) {
        if (!checkFloat(*n))
            throw IllegalArgumentException()
    }

    fun setMetrics(width: Float, height: Float) {
        assertFloat(width, height)
        this.width = width
        this.height = height
        trigger = min(width, height) / TRIGGER_RATE
    }

    companion object {
        private const val TAG = "mpv"

        // ratio for trigger, 1/Xth of minimum dimension
        // for tap gestures this is the distance that must *not* be moved for it to trigger
        private const val TRIGGER_RATE = 30

        // maximum duration between taps (ms) for a double tap to count
        private const val TAP_DURATION = 225L

        // full sweep from left side to right side is 2:30
        private const val CONTROL_SEEK_MAX = 150f

        // same as below, we rescale it inside MPVActivity
        private const val CONTROL_VOLUME_MAX = 1.5f

        // brightness is scaled 0..1; max's not 1f so that user does not have to start from the bottom
        // if they want to go from none to full brightness
        private const val CONTROL_BRIGHT_MAX = 1.5f

        // do not trigger on X% of screen top/bottom
        // this is so that user can open android status bar
        private const val DEADZONE = 5

        // Seeking should feel like 1s steps on slow drag (Samsung-like).
        // We achieve this by sending seek updates more frequently than volume/brightness.
        private const val SEEK_THROTTLE_DIV = 24

        // Keep the original 150-second full-sweep scale as the reference, but vary its gain
        // with horizontal finger velocity. Speed is measured in screen widths per second,
        // which keeps the response consistent across resolutions.
        private const val SEEK_MIN_GAIN = 0.10f
        private const val SEEK_MAX_GAIN = 2.00f
        private const val SEEK_ACCELERATION_START = 0.15f
        private const val SEEK_ACCELERATION_END = 1.00f
        private const val SEEK_MAX_TRACKED_SPEED = 4.00f
        private const val SEEK_VELOCITY_SMOOTHING_MS = 80f

        // Only an extremely small movement remains at +00:00. Every meaningful movement
        // below one accumulated second is exposed as +/-00:01 instead of a broad zero step.
        private const val SEEK_ZERO_DEADZONE_SEC = 0.08f

        // Require gestures to be clearly horizontal/vertical before locking to that axis.
        // This prevents accidental seeks when the user swipes mostly vertically.
        private const val DIRECTION_LOCK_RATIO = 1.25f
    }

    fun cancel() {
        // If a control gesture was active, finalize it.
        if (state != State.Up && state != State.Down)
            sendPropertyChange(PropertyChange.Finalize, 0f)
        state = State.Up
        lastTapTime = 0L
        resetSeekAcceleration()
    }

    private fun processTap(p: PointF): Boolean {
        if (state == State.Up) {
            lastDownTime = SystemClock.uptimeMillis()
            // 3 is another arbitrary value here that seems good enough
            if (PointF(lastPos.x - p.x, lastPos.y - p.y).length() > trigger * 3)
                lastTapTime = 0 // last tap was too far away, invalidate
            return true
        }
        // discard if any movement gesture took place
        if (state != State.Down)
            return false

        val now = SystemClock.uptimeMillis()
        if (now - lastDownTime >= TAP_DURATION) {
            lastTapTime = 0 // finger was held too long, reset
            return false
        }
        if (now - lastTapTime < TAP_DURATION) {
            // [ Left 28% ] [    Center    ] [ Right 28% ]
            if (p.x <= width * 0.28f)
                tapGestureLeft?.let { sendPropertyChange(it, -1f); return true }
            else if (p.x >= width * 0.72f)
                tapGestureRight?.let { sendPropertyChange(it, 1f); return true }
            else
                tapGestureCenter?.let { sendPropertyChange(it, 0f); return true }
            lastTapTime = 0
        } else {
            lastTapTime = now
        }
        return false
    }

    private fun resetSeekAcceleration() {
        seekLastX = 0f
        seekLastEventTime = 0L
        seekVelocityScreensPerSec = 0f
        seekAccumulatedSec = 0f
        seekDirection = 0
    }

    private fun beginSeekAcceleration(p: PointF, eventTime: Long) {
        seekLastX = p.x
        seekLastEventTime = eventTime
        seekVelocityScreensPerSec = 0f
        seekAccumulatedSec = 0f
        seekDirection = 0
    }

    private fun acceleratedSeekDiff(p: PointF, eventTime: Long): Float {
        val segmentPx = p.x - seekLastX
        val elapsedMs = (eventTime - seekLastEventTime).coerceAtLeast(1L)
        seekLastX = p.x
        seekLastEventTime = eventTime

        val direction = when {
            segmentPx < 0f -> -1
            segmentPx > 0f -> 1
            else -> 0
        }
        if (direction != 0 && direction != seekDirection) {
            // Restart the velocity ramp when reversing. This makes an equal-speed trip back
            // to the origin cancel the outward movement instead of inheriting its high gain.
            seekDirection = direction
            seekVelocityScreensPerSec = 0f
        }

        val instantSpeed = (
            abs(segmentPx) * 1000f / elapsedMs.toFloat() / width
        ).coerceAtMost(SEEK_MAX_TRACKED_SPEED)
        val smoothing = elapsedMs.toFloat() / (SEEK_VELOCITY_SMOOTHING_MS + elapsedMs)
        seekVelocityScreensPerSec +=
            (instantSpeed - seekVelocityScreensPerSec) * smoothing

        val accelerationProgress = (
            (seekVelocityScreensPerSec - SEEK_ACCELERATION_START) /
                (SEEK_ACCELERATION_END - SEEK_ACCELERATION_START)
        ).coerceIn(0f, 1f)
        val smoothProgress =
            accelerationProgress * accelerationProgress * (3f - 2f * accelerationProgress)
        val gain = SEEK_MIN_GAIN + (SEEK_MAX_GAIN - SEEK_MIN_GAIN) * smoothProgress

        seekAccumulatedSec = (
            seekAccumulatedSec + segmentPx / width * CONTROL_SEEK_MAX * gain
        ).coerceIn(-CONTROL_SEEK_MAX, CONTROL_SEEK_MAX)

        val magnitude = abs(seekAccumulatedSec)
        return when {
            magnitude < SEEK_ZERO_DEADZONE_SEC -> 0f
            magnitude < 1f -> if (seekAccumulatedSec < 0f) -1f else 1f
            else -> seekAccumulatedSec
        }
    }

    private fun processMovement(p: PointF, eventTime: Long): Boolean {
        // throttle events: only send updates when there's some movement compared to last update
        // 3 here is arbitrary.
        // For seeking we want finer updates so the step size becomes ~1s on slow drag.
        val throttle = if (state == State.ControlSeek) trigger / SEEK_THROTTLE_DIV else trigger / 3
        if (PointF(lastPos.x - p.x, lastPos.y - p.y).length() < throttle)
            return false
        lastPos.set(p)

        assertFloat(initialPos.x, initialPos.y)
        val dx = p.x - initialPos.x
        val dy = p.y - initialPos.y
        val dr = if (stateDirection == 0) (dx / width) else (-dy / height)

        when (state) {
            State.Up -> {}
            State.Down -> {
                // We might enter one of the Control states if the user moves enough.
                // For seeking we want a shorter activation distance (Samsung-like), so the
                // first visible step can be ~1s instead of jumping multiple seconds.
                //
                // IMPORTANT: Seeking must be horizontal-only. We therefore require a
                // sufficiently "horizontal" gesture before locking to ControlSeek, and we
                // also refuse to activate ControlSeek from a vertical gesture even if the
                // user configured it that way.
                val seekTrigger = trigger / 4

                val absDx = abs(dx)
                val absDy = abs(dy)

                val horizThreshold = if (gestureHoriz == State.ControlSeek) seekTrigger else trigger
                val vertGesture = if (initialPos.x > width / 2) gestureVertRight else gestureVertLeft
                val vertThreshold = if (vertGesture == State.ControlSeek) seekTrigger else trigger

                // Horizontal activation: require the gesture to be clearly horizontal.
                val horizontalIntent =
                    absDx > horizThreshold &&
                    (absDy == 0f || absDx / absDy >= DIRECTION_LOCK_RATIO) &&
                    (gestureHoriz != State.ControlSeek || absDy < trigger / 2)

                // Vertical activation: require the gesture to be clearly vertical.
                val verticalIntent =
                    absDy > vertThreshold && (absDx == 0f || absDy / absDx >= DIRECTION_LOCK_RATIO)

                if (horizontalIntent) {
                    state = gestureHoriz
                    stateDirection = 0
                } else if (verticalIntent) {
                    val chosen = vertGesture
                    // Never seek from vertical swipes.
                    if (chosen != State.ControlSeek) {
                        state = chosen
                        stateDirection = 1
                    }
                }

                // Send Init so that it has a chance to cache values before we start modifying them.
                if (state != State.Down) {
                    sendPropertyChange(PropertyChange.Init, 0f)

                    // Avoid a "jump" on activation: once we commit to seek, treat the current
                    // point as the new origin so the first delta starts near 0.
                    if (state == State.ControlSeek) {
                        initialPos.set(p)
                        lastPos.set(p)
                        beginSeekAcceleration(p, eventTime)
                    }
                }
            }
            State.ControlSeek ->
                sendPropertyChange(PropertyChange.Seek, acceleratedSeekDiff(p, eventTime))
            State.ControlVolume ->
                sendPropertyChange(PropertyChange.Volume, CONTROL_VOLUME_MAX * dr)
            State.ControlBright ->
                sendPropertyChange(PropertyChange.Bright, CONTROL_BRIGHT_MAX * dr)
        }
        return state != State.Up && state != State.Down
    }

    private fun sendPropertyChange(p: PropertyChange, diff: Float) {
        observer.onPropertyChange(p, diff)
    }

    fun syncSettings(prefs: SharedPreferences, resources: Resources) {
        val get: (String, Int) -> String = { key, defaultRes ->
            val v = prefs.getString(key, "")
            if (v.isNullOrEmpty()) resources.getString(defaultRes) else v
        }
        val map = mapOf(
            "bright" to State.ControlBright,
            "seek" to State.ControlSeek,
            "volume" to State.ControlVolume
        )
        val map2 = mapOf(
            "seek" to PropertyChange.SeekFixed,
            "playpause" to PropertyChange.PlayPause,
            "custom" to PropertyChange.Custom
        )

        gestureHoriz = map[get("gesture_horiz", R.string.pref_gesture_horiz_default)] ?: State.Down
        gestureVertLeft = map[get("gesture_vert_left", R.string.pref_gesture_vert_left_default)] ?: State.Down
        gestureVertRight = map[get("gesture_vert_right", R.string.pref_gesture_vert_right_default)] ?: State.Down
        tapGestureLeft = map2[get("gesture_tap_left", R.string.pref_gesture_tap_left_default)]
        tapGestureCenter = map2[get("gesture_tap_center", R.string.pref_gesture_tap_center_default)]
        tapGestureRight = map2[get("gesture_tap_right", R.string.pref_gesture_tap_right_default)]
    }

    fun onTouchEvent(e: MotionEvent): Boolean {
        if (width < 1 || height < 1) {
            Log.w(TAG, "TouchGestures: width or height not set!")
            return false
        }
        if (!checkFloat(e.x, e.y)) {
            Log.w(TAG, "TouchGestures: ignoring invalid point ${e.x} ${e.y}")
            return false
        }
        var gestureHandled = false
        val point = PointF(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_UP -> {
                gestureHandled = processMovement(point, e.eventTime) or processTap(point)
                if (state != State.Down)
                    sendPropertyChange(PropertyChange.Finalize, 0f)
                state = State.Up
                resetSeekAcceleration()
            }
            MotionEvent.ACTION_DOWN -> {
                // deadzone on top/bottom
                if (e.y < height * DEADZONE / 100 || e.y > height * (100 - DEADZONE) / 100)
                    return false
                initialPos.set(point)
                processTap(point)
                lastPos.set(point)
                state = State.Down
                // always return true on ACTION_DOWN to continue receiving events
                gestureHandled = true
            }
            MotionEvent.ACTION_MOVE -> {
                gestureHandled = processMovement(point, e.eventTime)
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                cancel()
                gestureHandled = false
            }
        }
        return gestureHandled
    }
}
