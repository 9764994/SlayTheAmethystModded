package io.stamethyst.backend.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.Keep
import io.stamethyst.backend.diag.WebViewDiagnosticsLogStore
import io.stamethyst.ui.preferences.LauncherPreferences
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.WeakHashMap

/** A versioned, sample-only capability; no paths, arbitrary native calls or device-name rules. */
@Keep
class SlingNativeAudioBridge private constructor(context: Context) {
    private val application = context.applicationContext
    private var session = 0L
    private var closed = false
    private var active = true
    private var muted = true
    private var committed = false
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var focusRequested = false
    private var focusGranted = false
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change -> focusChanged(change) }, handler)
        .build()
    private val healthCheck = object : Runnable {
        override fun run() {
            synchronized(this@SlingNativeAudioBridge) {
                if (closed || session == 0L || !committed) return
                nativeMaintain(session)
                if (JSONObject(nativeInfo(session)).optBoolean("failed")) {
                    log("disconnected", nativeInfo(session))
                    // Close outside AAudio's error callback, including when the game is idle.
                    reset()
                } else handler.postDelayed(this, 1000)
            }
        }
    }

    private fun requestFocus() {
        if (focusRequested || !active || muted) return
        focusGranted = audioManager?.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusRequested = focusGranted
        log("focus_request", "granted=$focusGranted")
    }

    private fun abandonFocus() {
        if (focusRequested) audioManager?.abandonAudioFocusRequest(focusRequest)
        focusRequested = false; focusGranted = false
    }

    @Synchronized private fun focusChanged(change: Int) {
        if (closed || session == 0L || !focusRequested) return
        focusGranted = change == AudioManager.AUDIOFOCUS_GAIN
        if (change == AudioManager.AUDIOFOCUS_LOSS) focusRequested = false
        nativeMute(session, muted || !active || !focusGranted)
        log("focus_change", "change=$change granted=$focusGranted")
    }

    companion object {
        private const val INTERFACE = "SlingNativeAudio"
        private val instances = WeakHashMap<Any, SlingNativeAudioBridge>()
        private val libraryAvailable by lazy {
            runCatching { System.loadLibrary("sling_audio"); true }.getOrDefault(false)
        }
        fun create(context: Context): SlingNativeAudioBridge? =
            if (libraryAvailable) SlingNativeAudioBridge(context) else null

        // Registry and WebView operations run on the UI thread. Bridge calls are synchronized
        // with activity lifecycle calls; JNI also serializes stream control operations.
        private fun attachInternal(
            key: Any,
            context: Context,
            addJavascriptInterface: (Any, String) -> Unit,
            removeJavascriptInterface: (String) -> Unit
        ) {
            closeInternal(key, removeJavascriptInterface)
            if (!libraryAvailable) return
            val bridge = SlingNativeAudioBridge(context)
            instances[key] = bridge
            addJavascriptInterface(bridge, INTERFACE)
        }

        fun attach(view: WebView) = attachInternal(
            key = view,
            context = view.context,
            addJavascriptInterface = view::addJavascriptInterface,
            removeJavascriptInterface = view::removeJavascriptInterface
        )

        private fun closeInternal(key: Any, removeJavascriptInterface: (String) -> Unit) {
            instances.remove(key)?.shutdown()
            removeJavascriptInterface(INTERFACE)
        }

        fun pageStarted(view: WebView) { instances[view]?.reset() }
        fun setActive(view: WebView, active: Boolean) { instances[view]?.activate(active) }
        fun close(view: WebView) {
            closeInternal(view, view::removeJavascriptInterface)
        }

    }

    private fun log(event: String, detail: String) {
        if (LauncherPreferences.isSlingBreakAudioDebugModeEnabled(application)) {
            WebViewDiagnosticsLogStore.append(application, "native_audio_$event", detail)
        }
    }

    @Synchronized fun reset() {
        handler.removeCallbacks(healthCheck)
        abandonFocus()
        if (session != 0L) nativeRelease(session)
        session = 0L; committed = false; muted = true
    }

    @Synchronized fun shutdown() { closed = true; reset() }

    @Synchronized fun activate(value: Boolean) {
        active = value
        if (!value) abandonFocus()
        if (session == 0L) return
        if (!nativeActive(session, value)) log("lifecycle_failure", nativeInfo(session))
        else if (value && committed) {
            requestFocus()
            nativeMute(session, muted || !focusGranted)
        }
    }

    @JavascriptInterface @Synchronized fun begin(): String {
        if (closed || !active) return "{\"error\":\"host-inactive\"}"
        // A document owns one immutable sample bank. Stale documents cannot replace it.
        if (session != 0L) return "{\"error\":\"session-exists\"}"
        session = nativeCreate()
        val result = if (session == 0L) "{\"error\":\"no-small-buffer-stream\"}" else nativeInfo(session)
        log("opened", result)
        return result
    }

    @JavascriptInterface fun capabilities(): String =
        """{"version":2,"gain":true,"ui":true,"music":true,"batch":true,"backend":"native-aaudio"}"""

    @JavascriptInterface @Synchronized fun upload(token: Long, id: Int, base64: String): Boolean {
        if (closed || token != session || token == 0L || committed || id !in 0..511 || base64.length > 2_100_000) return false
        return runCatching {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            if (bytes.isEmpty() || bytes.size % 4 != 0 || bytes.size > 1_536_000) return false
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val pcm = FloatArray(buffer.remaining()); buffer.get(pcm)
            nativeUpload(token, id, pcm)
        }.getOrDefault(false)
    }

    @JavascriptInterface @Synchronized fun commit(token: Long): Boolean {
        if (closed || token == 0L || token != session || committed || !active) return false
        committed = nativeCommit(token)
        if (committed) handler.postDelayed(healthCheck, 1000)
        log(if (committed) "ready" else "start_failure", nativeInfo(token))
        return committed
    }

    /** Positive means queued, zero means intentionally dropped, negative means fallback. */
    @JavascriptInterface @Synchronized fun play(token: Long, payload: String): Int {
        if (closed || token == 0L || token != session || !committed) return -1
        if (!active || muted || !focusGranted) return 0
        if (payload.length > 8192) return -2
        return runCatching {
            val command = JSONObject(payload)
            val voices = command.getJSONArray("voices")
            if (voices.length() !in 1..16) return -2
            val ids = IntArray(voices.length())
            val params = FloatArray(voices.length() * 3)
            for (i in ids.indices) {
                val voice = voices.getJSONObject(i)
                ids[i] = voice.getInt("id")
                params[i * 3] = voice.getDouble("delay").toFloat()
                params[i * 3 + 1] = voice.getDouble("pan").toFloat()
                params[i * 3 + 2] = if (voice.optBoolean("wet")) 1f else 0f
            }
            // Recipes own sound names. The native protocol transports samples, not a stale catalogue.
            val result = nativePlay(token, command.optBoolean("ui"), command.optBoolean("priority"), ids, params)
            if (result < 0) log("play_failure", nativeInfo(token))
            result
        }.getOrDefault(-2)
    }

    @JavascriptInterface @Synchronized fun setState(token: Long, volume: Double, silent: Boolean, paused: Boolean): Boolean {
        if (closed || token == 0L || token != session || !committed || !volume.isFinite() || volume !in 0.0..1.0) return false
        muted = silent || volume == 0.0
        if (muted) abandonFocus() else requestFocus()
        return nativeState(token, volume.toFloat(), muted || !active || !focusGranted, paused)
    }

    @JavascriptInterface @Synchronized fun uploadMusic(token: Long, id: Int, base64: String): Boolean {
        if (closed || token == 0L || token != session || !committed || id < 0 || base64.length > 12 * 1024 * 1024) return false
        return runCatching {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            if (bytes.isEmpty() || bytes.size % 4 != 0 || bytes.size > 8 * 1024 * 1024) return false
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val pcm = FloatArray(buffer.remaining()); buffer.get(pcm)
            nativeUploadMusic(token, id, pcm)
        }.getOrDefault(false)
    }

    @JavascriptInterface @Synchronized fun playMusic(token: Long, id: Int, delay: Double, pan: Double, volume: Double, bus: String, wet: Boolean): Int {
        if (closed || token == 0L || token != session || !committed) return -1
        if (!active || muted || !focusGranted) return 0
        val instrument = listOf("pad", "bass", "drums", "arp", "lead", "bell", "fx").indexOf(bus)
        if (instrument < 0 || !delay.isFinite() || delay !in 0.0..3.0 || !pan.isFinite() || pan !in -1.0..1.0 || !volume.isFinite() || volume !in 0.0..1.0) return -1
        return nativePlayMusic(token, id, delay.toFloat(), pan.toFloat(), volume.toFloat(), instrument, wet)
    }

    @JavascriptInterface @Synchronized fun musicState(token: Long, encoded: String, cutoff: Double, stop: Boolean): Boolean {
        if (closed || token == 0L || token != session || !committed || encoded.length > 1024 || !cutoff.isFinite() || cutoff !in 30.0..22050.0) return false
        return runCatching {
            val json = JSONObject(encoded)
            val levels = listOf("pad", "bass", "drums", "arp", "lead", "bell", "fx").map {
                val value = json.optDouble(it, 0.0)
                if (!value.isFinite() || value !in 0.0..2.0) return false
                value.toFloat()
            }.toFloatArray()
            nativeMusicState(token, levels, cutoff.toFloat(), stop)
        }.getOrDefault(false)
    }

    @JavascriptInterface @Synchronized fun musicFade(token: Long, target: Double, seconds: Double): Boolean {
        if (closed || token == 0L || token != session || !committed || !target.isFinite() || target !in 0.0..1.0 || !seconds.isFinite() || seconds !in 0.0..10.0) return false
        return nativeMusicFade(token, target.toFloat(), seconds.toFloat())
    }

    @JavascriptInterface @Synchronized fun playBatch(token: Long, encoded: String): String {
        if (closed || token == 0L || token != session || !committed || encoded.length > 131072) return "[]"
        return runCatching {
            val requests = org.json.JSONArray(encoded)
            if (requests.length() > 96) return "[]"
            val results = org.json.JSONArray()
            for (i in 0 until requests.length()) {
                val result = runCatching {
                    val request = requests.getJSONObject(i)
                    if (request.optBoolean("music")) playMusic(token, request.getInt("id"), request.getDouble("delay"),
                        request.getDouble("pan"), request.getDouble("volume"), request.getString("bus"), request.optBoolean("wet"))
                    else play(token, request.toString())
                }.getOrDefault(-2)
                results.put(result)
            }
            results.toString()
        }.getOrDefault("[]")
    }

    @JavascriptInterface @Synchronized fun setMuted(token: Long, value: Boolean): Boolean {
        if (closed || token == 0L || token != session || !committed) return false
        muted = value
        if (value) abandonFocus() else requestFocus()
        return nativeMute(token, value || !active || !focusGranted)
    }

    @JavascriptInterface @Synchronized fun snapshot(token: Long): String {
        return if (!closed && token != 0L && token == session) JSONObject(nativeInfo(token))
            .put("hostActive", active).put("muted", muted).put("focusGranted", focusGranted).toString()
        else "{\"error\":\"no-session\"}"
    }

    @JavascriptInterface @Synchronized fun release(token: Long) {
        if (token != 0L && token == session) { log("closed", nativeInfo(token)); reset() }
    }

    private external fun nativeCreate(): Long
    private external fun nativeInfo(token: Long): String
    private external fun nativeUpload(token: Long, id: Int, pcm: FloatArray): Boolean
    private external fun nativeCommit(token: Long): Boolean
    private external fun nativePlay(token: Long, ui: Boolean, priority: Boolean, ids: IntArray, params: FloatArray): Int
    private external fun nativeState(token: Long, gain: Float, muted: Boolean, paused: Boolean): Boolean
    private external fun nativeUploadMusic(token: Long, id: Int, pcm: FloatArray): Boolean
    private external fun nativePlayMusic(token: Long, id: Int, delay: Float, pan: Float, volume: Float, bus: Int, wet: Boolean): Int
    private external fun nativeMusicState(token: Long, levels: FloatArray, cutoff: Float, stop: Boolean): Boolean
    private external fun nativeMusicFade(token: Long, target: Float, seconds: Float): Boolean
    private external fun nativeMute(token: Long, muted: Boolean): Boolean
    private external fun nativeActive(token: Long, active: Boolean): Boolean
    private external fun nativeMaintain(token: Long)
    private external fun nativeRelease(token: Long)
}
