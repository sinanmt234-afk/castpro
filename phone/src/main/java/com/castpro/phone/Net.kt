package com.castpro.phone

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import com.castpro.core.Cast
import org.json.JSONObject
import java.io.FileInputStream
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.concurrent.thread

data class Tv(val name: String, val host: String, val port: Int)

/** Finds CastPro TVs on the local network. */
class Discovery(ctx: Context, private val onChange: (List<Tv>) -> Unit) {
    private val nsd = ctx.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, Tv>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var busy = false

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(i: NsdServiceInfo) { synchronized(this@Discovery) { queue.add(i) }; next() }
        override fun onServiceLost(i: NsdServiceInfo) { synchronized(this@Discovery) { found.remove(i.serviceName) }; push() }
        override fun onDiscoveryStarted(t: String) {}
        override fun onDiscoveryStopped(t: String) {}
        override fun onStartDiscoveryFailed(t: String, e: Int) {}
        override fun onStopDiscoveryFailed(t: String, e: Int) {}
    }

    fun start() { runCatching { nsd.discoverServices(Cast.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) } }
    fun stop() { runCatching { nsd.stopServiceDiscovery(listener) } }

    private fun next() {
        val i: NsdServiceInfo
        synchronized(this) {
            if (busy) return
            i = queue.removeFirstOrNull() ?: return
            busy = true
        }
        nsd.resolveService(i, object : NsdManager.ResolveListener {
            override fun onResolveFailed(s: NsdServiceInfo, e: Int) { done() }
            override fun onServiceResolved(s: NsdServiceInfo) {
                val h = s.host
                if (h is Inet4Address) synchronized(this@Discovery) { found[s.serviceName] = Tv(s.serviceName, h.hostAddress ?: "", s.port) }
                push(); done()
            }
        })
    }

    private fun done() { synchronized(this) { busy = false }; next() }
    private fun push() { val l = synchronized(this) { found.values.toList() }; main.post { onChange(l) } }
}

/** Control connection to the TV (JSON lines over TCP). */
class Client(private val onMsg: (JSONObject) -> Unit, private val onClosed: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var sock: Socket? = null
    @Volatile private var w: PrintWriter? = null

    /** result: this device's LAN address as seen by the TV, or null on failure. */
    fun connect(host: String, port: Int, result: (String?) -> Unit) {
        close()
        thread {
            var ok = false
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 4000)
                s.tcpNoDelay = true
                sock = s
                w = PrintWriter(s.getOutputStream().bufferedWriter(), true)
                ok = true
                val ip = s.localAddress.hostAddress
                main.post { result(ip) }
                s.getInputStream().bufferedReader().forEachLine { l ->
                    runCatching { JSONObject(l) }.getOrNull()?.let { main.post { onMsg(it) } }
                }
            } catch (e: Exception) {
                if (!ok) main.post { result(null) }
            } finally {
                if (ok) main.post { onClosed() }
            }
        }
    }

    fun send(o: JSONObject) { val x = w ?: return; io.execute { try { x.println(o.toString()) } catch (e: Exception) { } } }
    fun close() { runCatching { sock?.close() }; sock = null; w = null }
}

/** Tiny HTTP server (with Range support) so the TV can stream straight from this phone. */
class MediaServer(private val cr: ContentResolver) {
    @Volatile var media: Uri? = null
    @Volatile var sub: Uri? = null
    @Volatile var subMime = "application/x-subrip"
    @Volatile private var ss: ServerSocket? = null

    fun start() {
        if (ss != null) return
        thread {
            try {
                val s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(Cast.MEDIA_PORT)) }
                ss = s
                while (true) { val c = s.accept(); thread { serve(c) } }
            } catch (e: Exception) { ss = null }
        }
    }

    fun stop() { runCatching { ss?.close() }; ss = null }

    private fun serve(c: Socket) {
        try {
            c.use {
                val r = it.getInputStream().bufferedReader()
                val first = r.readLine() ?: return
                val parts = first.split(" ")
                val method = parts[0]
                val path = parts.getOrElse(1) { "/" }
                var range: String? = null
                while (true) {
                    val l = r.readLine() ?: break
                    if (l.isEmpty()) break
                    if (l.startsWith("range:", true)) range = l.substring(6).trim()
                }
                val isSub = path.startsWith("/s")
                val uri = (if (isSub) sub else media)
                val os = it.getOutputStream()
                if (uri == null) { os.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray()); return }
                cr.openFileDescriptor(uri, "r")?.use { pfd ->
                    val size = pfd.statSize
                    var start = 0L
                    var end = size - 1
                    range?.removePrefix("bytes=")?.split("-")?.let { p ->
                        p[0].toLongOrNull()?.let { v -> start = v }
                        p.getOrNull(1)?.toLongOrNull()?.let { v -> end = v }
                    }
                    end = end.coerceAtMost(size - 1)
                    val partial = range != null
                    val len = end - start + 1
                    val mime = if (isSub) subMime else (cr.getType(uri) ?: "application/octet-stream")
                    val head = StringBuilder()
                        .append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                        .append("Content-Type: $mime\r\nAccept-Ranges: bytes\r\nContent-Length: $len\r\nConnection: close\r\n")
                    if (partial) head.append("Content-Range: bytes $start-$end/$size\r\n")
                    head.append("\r\n")
                    os.write(head.toString().toByteArray())
                    if (method != "HEAD") {
                        val ch = FileInputStream(pfd.fileDescriptor).channel
                        ch.position(start)
                        val buf = ByteBuffer.allocate(64 * 1024)
                        var left = len
                        while (left > 0) {
                            buf.clear()
                            if (left < buf.capacity()) buf.limit(left.toInt())
                            val n = ch.read(buf)
                            if (n < 0) break
                            os.write(buf.array(), 0, n)
                            left -= n
                        }
                    }
                    os.flush()
                }
            }
        } catch (e: Exception) { /* client went away (normal while seeking) */ }
    }
}
