package com.castpro.phone

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.castpro.core.CP
import com.castpro.core.Cast
import com.castpro.core.PlayPauseIcon
import com.castpro.core.fmt
import org.json.JSONObject

enum class Step { Home, Prepare, Playing }

class MainActivity : ComponentActivity() {
    private lateinit var discovery: Discovery
    private val server by lazy { MediaServer(contentResolver) }
    private val client = Client(onMsg = { onState(it) }, onClosed = { tv = null; if (step == Step.Playing) go(Step.Home); note = "Disconnected" })

    private var tvs by mutableStateOf(listOf<Tv>())
    private var tv by mutableStateOf<Tv?>(null)
    private var myIp = ""
    private var connecting by mutableStateOf(false)
    private var note by mutableStateOf("")
    private var step by mutableStateOf(Step.Home)

    private var fileUri: Uri? = null
    private var fileName by mutableStateOf("")
    private var probing by mutableStateOf(false)
    private var probe by mutableStateOf<Probe?>(null)
    private var audioSel by mutableIntStateOf(0)
    private var textSel by mutableIntStateOf(-1)   // -1 off, -2 external file, >=0 embedded
    private var subUri: Uri? = null
    private var subName by mutableStateOf("")

    private var playing by mutableStateOf(false)
    private var pos by mutableLongStateOf(0L)
    private var dur by mutableLongStateOf(0L)

    private val pickMedia = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { mediaPicked(it) } }
    private val pickSub = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u?.let {
            subUri = it; subName = nameOf(it); server.sub = it
            server.subMime = if (subName.endsWith(".vtt", true)) "text/vtt" else "application/x-subrip"
            textSel = -2
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        server.start()
        discovery = Discovery(this) { tvs = it }
        discovery.start()
        setContent { Root() }
    }

    override fun onDestroy() {
        discovery.stop(); server.stop(); client.close()
        super.onDestroy()
    }

    private fun go(s: Step) {
        step = s
        if (s == Step.Playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun nameOf(u: Uri): String =
        contentResolver.query(u, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: "Media"

    private fun connect(host: String, port: Int, name: String) {
        connecting = true; note = ""
        client.connect(host, port) { ip ->
            connecting = false
            if (ip == null) note = "Could not reach the TV"
            else { myIp = ip; tv = Tv(name, host, port) }
        }
    }

    private fun mediaPicked(u: Uri) {
        fileUri = u; fileName = nameOf(u)
        subUri = null; subName = ""; server.sub = null
        probe = null; probing = true; audioSel = 0; textSel = -1
        go(Step.Prepare)
        probeTracks(this, u) { p -> probe = p; probing = false; dur = p.durationMs }
    }

    private fun castNow() {
        val u = fileUri ?: return
        if (tv == null) return
        server.media = u
        val base = "http://$myIp:${Cast.MEDIA_PORT}"
        val o = JSONObject().put("t", "load").put("url", "$base/m").put("title", fileName.substringBeforeLast('.'))
            .put("audio", audioSel).put("text", textSel)
        if (subUri != null) o.put("sub", "$base/s").put("submime", server.subMime)
        client.send(o)
        playing = true; pos = 0
        go(Step.Playing)
    }

    private fun onState(o: JSONObject) {
        if (o.optString("t") != "state") return
        if (!o.optBoolean("active", true) || o.optBoolean("ended")) { if (step == Step.Playing) go(Step.Home); return }
        playing = o.optBoolean("playing"); pos = o.optLong("pos"); dur = o.optLong("dur")
        audioSel = o.optInt("audio", audioSel); textSel = o.optInt("text", textSel)
    }

    private fun cmd(t: String) = client.send(JSONObject().put("t", t))
    private fun seekTo(ms: Long) = client.send(JSONObject().put("t", "seek").put("ms", ms.coerceAtLeast(0)))
    private fun setAudio(i: Int) { audioSel = i; if (step == Step.Playing) client.send(JSONObject().put("t", "audio").put("i", i)) }
    private fun setText(i: Int) { textSel = i; if (step == Step.Playing) client.send(JSONObject().put("t", "text").put("i", i)) }

    // ---------------- UI ----------------
    @Composable
    private fun Root() {
        Column(Modifier.fillMaxSize().background(CP.Backdrop).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(22.dp)) {
            Text("CastPro", color = CP.Peach, fontSize = 34.sp, fontWeight = FontWeight.Light, letterSpacing = 5.sp)
            Spacer(Modifier.height(24.dp))
            when (step) {
                Step.Home -> Home()
                Step.Prepare -> Prepare()
                Step.Playing -> Playing()
            }
        }
    }

    @Composable
    private fun Home() {
        Heading("Your TV")
        val t = tv
        if (t != null) {
            Card { Text("Connected · ${t.name}", color = CP.Peach, fontSize = 17.sp, fontWeight = FontWeight.Medium) }
            Spacer(Modifier.height(20.dp))
            Primary("Choose video or audio") { pickMedia.launch(arrayOf("video/*", "audio/*")) }
        } else {
            if (tvs.isEmpty()) Dim("Searching for CastPro TVs on this WiFi…")
            tvs.forEach { x -> Option(x.name, false, "${x.host}") { if (!connecting) connect(x.host, x.port, x.name) } ; Spacer(Modifier.height(10.dp)) }
            Spacer(Modifier.height(14.dp))
            var ip by remember { mutableStateOf("") }
            Dim("Not showing up? Enter the IP shown on the TV")
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp)).background(CP.Deep)
                    .border(1.dp, CP.Slate, RoundedCornerShape(26.dp)).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                    BasicTextField(ip, { ip = it }, singleLine = true,
                        textStyle = TextStyle(color = CP.Peach, fontSize = 16.sp), cursorBrush = SolidColor(CP.Peach),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.height(52.dp).clip(RoundedCornerShape(26.dp)).background(CP.Crimson)
                    .clickable { if (ip.isNotBlank() && !connecting) connect(ip.trim(), Cast.CONTROL_PORT, ip.trim()) }
                    .padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                    Text(if (connecting) "…" else "Connect", color = CP.Peach, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (note.isNotEmpty()) { Spacer(Modifier.height(14.dp)); Dim(note) }
    }

    @Composable
    private fun Prepare() {
        Heading("Before you play")
        Card {
            Text(fileName, color = CP.Peach, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 2)
            if ((probe?.durationMs ?: 0L) > 0) { Spacer(Modifier.height(4.dp)); Dim(fmt(probe!!.durationMs)) }
        }
        Spacer(Modifier.height(20.dp))
        if (probing) {
            Dim("Reading audio and subtitle tracks…")
        } else {
            TrackPickers()
        }
        Spacer(Modifier.height(24.dp))
        if (tv == null) { Dim("Connect to a TV first"); Spacer(Modifier.height(10.dp)) }
        Primary("Cast to TV", enabled = !probing && tv != null) { castNow() }
        Spacer(Modifier.height(10.dp))
        Ghost("Back") { go(Step.Home) }
    }

    @Composable
    private fun TrackPickers() {
        val p = probe
        val audio = p?.audio.orEmpty()
        if (audio.isNotEmpty()) {
            Heading("Audio")
            audio.forEach { Option(it.label, audioSel == it.index) { setAudio(it.index) }; Spacer(Modifier.height(8.dp)) }
            Spacer(Modifier.height(14.dp))
        }
        Heading("Subtitles")
        Option("Off", textSel == -1) { setText(-1) }; Spacer(Modifier.height(8.dp))
        p?.text.orEmpty().forEach { Option(it.label, textSel == it.index) { setText(it.index) }; Spacer(Modifier.height(8.dp)) }
        if (subUri != null) { Option(subName, textSel == -2) { setText(-2) }; Spacer(Modifier.height(8.dp)) }
        if (step == Step.Prepare) Ghost("Add subtitle file…") { pickSub.launch(arrayOf("*/*")) }
    }

    @Composable
    private fun Playing() {
        Heading("Now playing")
        Card { Text(fileName.substringBeforeLast('.'), color = CP.Peach, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 2) }
        Spacer(Modifier.height(24.dp))
        var drag by remember { mutableStateOf<Float?>(null) }
        Slider(
            value = drag ?: if (dur > 0) pos.toFloat() else 0f,
            onValueChange = { drag = it },
            onValueChangeFinished = { drag?.let { seekTo(it.toLong()) }; drag = null },
            valueRange = 0f..(if (dur > 0) dur.toFloat() else 1f),
            colors = SliderDefaults.colors(thumbColor = CP.Peach, activeTrackColor = CP.Crimson, inactiveTrackColor = CP.Slate)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmt((drag ?: pos.toFloat()).toLong()), color = CP.Peach, fontSize = 14.sp)
            Text(fmt(dur), color = CP.Peach.copy(alpha = .7f), fontSize = 14.sp)
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RoundBtn(56) { seekTo(pos - 10_000) }
            Box(Modifier.size(84.dp).clip(CircleShape).background(CP.Crimson).clickable { cmd(if (playing) "pause" else "play"); playing = !playing },
                contentAlignment = Alignment.Center) { PlayPauseIcon(playing, CP.Peach, Modifier.size(38.dp)) }
            RoundBtn(56, forward = true) { seekTo(pos + 10_000) }
        }
        Spacer(Modifier.height(28.dp))
        TrackPickers()
        Spacer(Modifier.height(18.dp))
        Ghost("Stop casting") { cmd("stop"); go(Step.Home) }
    }

    @Composable
    private fun RoundBtn(size: Int, forward: Boolean = false, onClick: () -> Unit) {
        Box(Modifier.size(size.dp).clip(CircleShape).background(CP.Deep).border(1.dp, CP.Slate, CircleShape).clickable { onClick() },
            contentAlignment = Alignment.Center) {
            Text(if (forward) "+10" else "−10", color = CP.Peach, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable private fun Heading(t: String) { Text(t.uppercase(), color = CP.Peach.copy(alpha = .65f), fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium); Spacer(Modifier.height(10.dp)) }
    @Composable private fun Dim(t: String) { Text(t, color = CP.Peach.copy(alpha = .6f), fontSize = 14.sp) }

    @Composable
    private fun Card(content: @Composable ColumnScope.() -> Unit) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CP.Deep)
            .border(1.dp, CP.Slate, RoundedCornerShape(20.dp)).padding(18.dp), content = content)
    }

    @Composable
    private fun Primary(text: String, enabled: Boolean = true, onClick: () -> Unit) {
        Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(if (enabled) CP.Crimson else CP.Slate)
            .clickable(enabled = enabled) { onClick() }, contentAlignment = Alignment.Center) {
            Text(text, color = CP.Peach, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        }
    }

    @Composable
    private fun Ghost(text: String, onClick: () -> Unit) {
        Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).border(1.dp, CP.Slate, RoundedCornerShape(26.dp))
            .clickable { onClick() }, contentAlignment = Alignment.Center) {
            Text(text, color = CP.Peach, fontSize = 15.sp)
        }
    }

    @Composable
    private fun Option(label: String, selected: Boolean, sub: String? = null, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (selected) CP.Wine else CP.Deep)
            .border(1.dp, if (selected) CP.Peach else CP.Slate, RoundedCornerShape(16.dp))
            .clickable { onClick() }.padding(horizontal = 18.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, color = if (selected) CP.Peach else CP.Peach.copy(alpha = .8f), fontSize = 16.sp, maxLines = 1)
                if (sub != null) Text(sub, color = CP.Peach.copy(alpha = .5f), fontSize = 12.sp)
            }
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(CP.Peach))
        }
    }
}
