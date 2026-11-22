package com.castpro.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.castpro.core.Cast
import org.json.JSONObject
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Listens for CastPro senders (phone / Windows) and exchanges JSON lines with them. */
class Receiver(
    private val ctx: Context,
    private val onCmd: (JSONObject) -> Unit,
    private val onLink: (Boolean) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    @Volatile private var out: PrintWriter? = null
    @Volatile private var running = false
    private var nsd: NsdManager? = null
    private var reg: NsdManager.RegistrationListener? = null

    fun start() {
        running = true
        thread(name = "castpro-rx") {
            try {
                val ss = ServerSocket().apply { reuseAddress = true; bind(java.net.InetSocketAddress(Cast.CONTROL_PORT)) }
                server = ss
                register(Cast.CONTROL_PORT)
                while (running) {
                    val s = ss.accept()
                    close(client)
                    client = s
                    thread { serve(s) }
                }
            } catch (e: Exception) { /* closed */ }
        }
    }

    private fun serve(s: Socket) {
        try {
            s.tcpNoDelay = true
            out = PrintWriter(s.getOutputStream().bufferedWriter(), true)
            main.post { onLink(true) }
            s.getInputStream().bufferedReader().forEachLine { line ->
                runCatching { JSONObject(line) }.getOrNull()?.let { main.post { onCmd(it) } }
            }
        } catch (e: Exception) {
        } finally {
            if (client === s) { out = null; main.post { onLink(false) } }
            close(s)
        }
    }

    fun send(o: JSONObject) {
        val w = out ?: return
        io.execute { try { w.println(o.toString()) } catch (e: Exception) { } }
    }

    private fun register(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "CastPro " + Build.MODEL
            serviceType = Cast.SERVICE_TYPE
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        nsd = ctx.getSystemService(NsdManager::class.java)
        nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        reg = l
    }

    fun stop() {
        running = false
        reg?.let { runCatching { nsd?.unregisterService(it) } }
        runCatching { server?.close() }
        close(client)
    }

    private fun close(s: Socket?) { try { s?.close() } catch (e: Exception) { } }

    companion object {
        fun localIp(): String = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress ?: "-"
        } catch (e: Exception) { "-" }
    }
}
