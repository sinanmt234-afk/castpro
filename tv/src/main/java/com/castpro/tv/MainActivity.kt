package com.castpro.tv

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.castpro.core.CP
import com.castpro.core.Cast
import com.castpro.core.PlayPauseIcon
import com.castpro.core.fmt
import org.json.JSONObject
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var receiver: Receiver
    private val main = Handler(Looper.getMainLooper())

    private var active by mutableStateOf(false)
    private var title by mutableStateOf("")
    private var playing by mutableStateOf(false)
    private var pos by mutableLongStateOf(0L)
    private var dur by mutableLongStateOf(0L)
    private var overlay by mutableStateOf(false)
    private var toast by mutableStateOf("")
    private var linked by mutableStateOf(false)
    private val ip = Receiver.localIp()

    private var pendingAudio: Int? = null
    private var pendingText = -1
    private var curAudio = 0
    private var curText = -1

    private val hide = Runnable { overlay = false; toast = "" }

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            val a = pendingAudio ?: return
            if (tracks.groups.isEmpty()) return
            pendingAudio = null
            applyTracks(a, pendingText)
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playing = playWhenReady }
        override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) push(ended = true) }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (active) {
                pos = player.currentPosition
                dur = if (player.duration > 0) player.duration else 0L
                push()
            }
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        player = ExoPlayer.Builder(this).build().apply { addListener(listener) }
        receiver = Receiver(this, ::onCommand) { linked = it; if (!it && active) stopPlayback() }
        receiver.start()
        main.post(tick)
        setContent { Screen() }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        receiver.stop()
        player.release()
        super.onDestroy()
    }

    // ---------- commands from the sender ----------
    private fun onCommand(c: JSONObject) {
        when (c.optString("t")) {
            "load" -> {
                val b = MediaItem.Builder().setUri(c.getString("url"))
                val sub = c.optString("sub")
                if (sub.isNotEmpty()) {
                    b.setSubtitleConfigurations(listOf(
                        MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub))
                            .setId(Cast.SUB_ID)
                            .setMimeType(c.optString("submime", MimeTypes.APPLICATION_SUBRIP))
                            .build()
                    ))
                }
                title = c.optString("title")
                pendingAudio = c.optInt("audio", 0)
                pendingText = c.optInt("text", -1)
                player.setMediaItem(b.build())
                player.prepare()
                player.playWhenReady = true
                playing = true
                active = true
                showOverlay()
            }
            "play" -> player.play()
            "pause" -> player.pause()
            "seek" -> player.seekTo(c.optLong("ms"))
            "audio" -> applyTracks(c.optInt("i"), null)
            "text" -> applyTracks(null, c.optInt("i"))
            "stop" -> stopPlayback()
        }
    }

    private fun groups(type: Int) = player.currentTracks.groups.filter { it.type == type }

    private fun applyTracks(audio: Int?, text: Int?) {
        val b = player.trackSelectionParameters.buildUpon()
        if (audio != null) {
            groups(C.TRACK_TYPE_AUDIO).getOrNull(audio)?.let {
                b.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0)); curAudio = audio
            }
        }
        if (text != null) {
            curText = text
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, text == -1)
            if (text != -1) {
                val tg = groups(C.TRACK_TYPE_TEXT)
                val g = if (text == -2) tg.firstOrNull { it.mediaTrackGroup.getFormat(0).id == Cast.SUB_ID } else tg.getOrNull(text)
                g?.let { b.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0)) }
            }
        }
        player.trackSelectionParameters = b.build()
    }

    private fun label(f: Format): String =
        f.label ?: f.language?.takeIf { it != "und" }?.let { Locale.forLanguageTag(it).displayLanguage } ?: "Track"

    private fun cycleAudio() {
        val g = groups(C.TRACK_TYPE_AUDIO)
        if (g.size < 2) { say("Only one audio track"); return }
        val n = (curAudio + 1) % g.size
        applyTracks(n, null)
        say("Audio · " + label(g[n].mediaTrackGroup.getFormat(0)))
        push()
    }

    private fun cycleText() {
        val g = groups(C.TRACK_TYPE_TEXT)
        if (g.isEmpty()) { say("No subtitles"); return }
        val cur = if (curText == -2) g.indexOfFirst { it.mediaTrackGroup.getFormat(0).id == Cast.SUB_ID } else curText
        val n = if (cur + 1 >= g.size) -1 else cur + 1
        applyTracks(null, n)
        say(if (n == -1) "Subtitles · Off" else "Subtitles · " + label(g[n].mediaTrackGroup.getFormat(0)))
        push()
    }

    private fun say(t: String) { toast = t; showOverlay() }

    private fun push(ended: Boolean = false) {
        receiver.send(JSONObject()
            .put("t", "state").put("active", active).put("playing", playing)
            .put("pos", player.currentPosition).put("dur", if (player.duration > 0) player.duration else 0L)
            .put("ended", ended).put("audio", curAudio).put("text", curText))
    }

    private fun stopPlayback() {
        player.stop(); player.clearMediaItems()
        active = false; overlay = false; playing = false
        push()
    }

    private fun showOverlay() {
        overlay = true
        main.removeCallbacks(hide)
        main.postDelayed(hide, 4000)
    }

    private fun seekBy(d: Long) {
        val max = if (player.duration > 0) player.duration else Long.MAX_VALUE
        player.seekTo((player.currentPosition + d).coerceIn(0L, max))
    }

    // ---------- TV remote ----------
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!active) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { stopPlayback(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> player.playWhenReady = !player.playWhenReady
            KeyEvent.KEYCODE_MEDIA_PLAY -> player.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> player.pause()
            KeyEvent.KEYCODE_DPAD_RIGHT -> seekBy(10_000)
            KeyEvent.KEYCODE_DPAD_LEFT -> seekBy(-10_000)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> seekBy(30_000)
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> seekBy(-30_000)
            KeyEvent.KEYCODE_DPAD_UP -> cycleAudio()
            KeyEvent.KEYCODE_DPAD_DOWN -> cycleText()
            else -> return super.onKeyDown(keyCode, event)
        }
        playing = player.playWhenReady
        showOverlay()
        push()
        return true
    }

    // ---------- UI ----------
    @Composable
    private fun Screen() {
        Box(Modifier.fillMaxSize().background(CP.Night)) {
            if (!active) Idle() else {
                AndroidView(
                    factory = { c -> PlayerView(c).apply { useController = false; player = this@MainActivity.player } },
                    modifier = Modifier.fillMaxSize()
                )
                if (overlay || !playing) Overlay()
            }
        }
    }

    @Composable
    private fun Idle() {
        Box(Modifier.fillMaxSize().background(CP.Backdrop), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("CastPro", color = CP.Peach, fontSize = 72.sp, fontWeight = FontWeight.Light, letterSpacing = 8.sp)
                Spacer(Modifier.height(12.dp))
                Text(if (linked) "Connected" else "Ready to cast", color = CP.Peach.copy(alpha = .7f), fontSize = 22.sp, letterSpacing = 3.sp)
                Spacer(Modifier.height(40.dp))
                Column(
                    Modifier.clip(RoundedCornerShape(24.dp)).background(CP.Deep)
                        .border(1.dp, CP.Slate, RoundedCornerShape(24.dp)).padding(horizontal = 40.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(Build.MODEL, color = CP.Peach, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text(ip, color = CP.Peach.copy(alpha = .7f), fontSize = 18.sp)
                }
                Spacer(Modifier.height(28.dp))
                Text("Open CastPro on your phone or Windows PC", color = CP.Peach.copy(alpha = .5f), fontSize = 16.sp)
            }
        }
    }

    @Composable
    private fun Overlay() {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to CP.Night.copy(alpha = .95f)))) {
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 64.dp, vertical = 48.dp)) {
                Text(title, color = CP.Peach, fontSize = 28.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                if (toast.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(toast, color = CP.Peach.copy(alpha = .75f), fontSize = 18.sp)
                }
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlayPauseIcon(playing, CP.Peach, Modifier.size(34.dp))
                    Spacer(Modifier.width(22.dp))
                    Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(CP.Slate)) {
                        val f = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                        Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(CP.Crimson))
                    }
                    Spacer(Modifier.width(22.dp))
                    Text(fmt(pos) + " / " + fmt(dur), color = CP.Peach, fontSize = 18.sp)
                }
            }
        }
    }
}
