package com.castpro.phone

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale

data class Opt(val label: String, val index: Int)
data class Probe(val audio: List<Opt>, val text: List<Opt>, val durationMs: Long)

private fun label(f: Format, n: Int, audio: Boolean): String {
    val lang = f.language?.takeIf { it != "und" }?.let { Locale.forLanguageTag(it).displayLanguage }
    val base = f.label ?: lang ?: "Track ${n + 1}"
    val extra = if (audio && f.channelCount > 0) when (f.channelCount) {
        1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${f.channelCount}ch"
    } else null
    return listOfNotNull(base, extra).joinToString(" · ")
}

/** Opens the file with a silent ExoPlayer just long enough to read its audio / subtitle tracks. */
fun probeTracks(ctx: Context, uri: Uri, done: (Probe) -> Unit) {
    val main = Handler(Looper.getMainLooper())
    val p = ExoPlayer.Builder(ctx).build()
    var finished = false
    fun finish() {
        if (finished) return
        finished = true
        val g = p.currentTracks.groups
        val audio = g.filter { it.type == C.TRACK_TYPE_AUDIO }.mapIndexed { i, x -> Opt(label(x.mediaTrackGroup.getFormat(0), i, true), i) }
        val text = g.filter { it.type == C.TRACK_TYPE_TEXT }.mapIndexed { i, x -> Opt(label(x.mediaTrackGroup.getFormat(0), i, false), i) }
        val d = if (p.duration > 0) p.duration else 0L
        p.release()
        done(Probe(audio, text, d))
    }
    p.addListener(object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) finish() }
        override fun onPlayerError(error: PlaybackException) { finish() }
    })
    p.setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
    p.playWhenReady = false
    p.prepare()
    main.postDelayed({ finish() }, 8000)
}
