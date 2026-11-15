package com.manjaniq.service

import android.app.Notification
import android.net.VpnService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.manjaniq.MainActivity
import com.manjaniq.R
import com.manjaniq.quickconnect.TunStack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Routes the device's full traffic through a local SOCKS5 proxy.
 *
 * Established exclusively by the Quick Connect flow in the UI; the manual
 * core start buttons never reach this service and never alter system
 * traffic. The tunnel is a userspace IPv4 stack ([TunStack]) living inside
 * this process: TCP is relayed with SOCKS5 CONNECT through the proxy, DNS is
 * relayed as DNS-over-TCP, other UDP is dropped (see TunStack docs).
 */
class ManjaniqVpnService : VpnService() {

    enum class VpnState { STOPPED, STARTING, CONNECTED, STOPPING, ERROR }

    private var tunPfd: ParcelFileDescriptor? = null
    private var tunOutput: FileOutputStream? = null
    private var stack: TunStack? = null

    @Volatile
    private var pumpRunning = false
    private var pumpThread: Thread? = null

    private val tunWriteLock = Any()

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_PROXY_HOST) ?: DEFAULT_PROXY_HOST
                val port = intent.getIntExtra(EXTRA_PROXY_PORT, DEFAULT_PROXY_PORT)
                startTunnel(host, port)
            }
            ACTION_STOP -> stopTunnel()
            else -> stopTunnel()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardownTunnel()
        _state.value = VpnState.STOPPED
        super.onDestroy()
    }

    /** Called when the user revokes the VPN from system settings. */
    override fun onRevoke() {
        Log.i(TAG, "VPN revoked by the system; tearing down")
        stopTunnel()
        super.onRevoke()
    }

    // ------------------------------------------------------------- tunnel

    private fun startTunnel(proxyHost: String, proxyPort: Int) {
        _state.value = VpnState.STARTING
        promoteForeground(getString(R.string.vpn_notification_connecting))
        teardownTunnel() // idempotent: never stack two tunnels

        val established: ParcelFileDescriptor? = try {
            Builder() // inner Builder: outer receiver is this VpnService
                .setSession(getString(R.string.vpn_session_name))
                .setMtu(TUN_MTU)
                .addAddress(TUN_ADDRESS, TUN_PREFIX)
                .addRoute(DEFAULT_ROUTE, 0)
                // Blackhole IPv6: an IPv6 route with no v6 proxy support would
                // leak. Apps race v4 alongside v6 (Happy Eyeballs), so this
                // only makes them prefer the tunnelled v4 path.
                .addAddress(TUN_ADDRESS_V6, TUN_PREFIX_V6)
                .addRoute(DEFAULT_ROUTE_V6, 0)
                .addDnsServer(PRIMARY_DNS)
                .addDnsServer(SECONDARY_DNS)
                .apply {
                    // The cores live inside this app (Aether via JNI, RSTA via
                    // JNI). Excluding our own package keeps their sockets out
                    // of the tunnel — otherwise proxy traffic would re-enter
                    // the TUN and recurse into itself.
                    try {
                        addDisallowedApplication(packageName)
                    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                        Log.w(TAG, "addDisallowedApplication failed; the local proxy may loop", e)
                    }
                }
                .establish()
        } catch (t: Throwable) {
            Log.e(TAG, "establish() failed", t)
            null
        }

        if (established == null) {
            // VPN consent missing/revoked, or the system refused the request.
            Log.e(TAG, "establish() returned null — VPN consent not granted?")
            _state.value = VpnState.ERROR
            stopForegroundAndSelf()
            return
        }

        tunPfd = established
        tunOutput = FileOutputStream(established.fileDescriptor)
        stack = TunStack(
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            sendToTun = ::writeToTun,
            protectSocket = { socket -> protect(socket) },
            log = { message -> Log.i(TAG, message) },
        )

        pumpRunning = true
        val input = FileInputStream(established.fileDescriptor)
        pumpThread = thread(name = "manjaniq-tun-pump", isDaemon = true) {
            val buffer = ByteArray(TUN_READ_BUFFER)
            try {
                while (pumpRunning) {
                    val n = try {
                        input.read(buffer)
                    } catch (e: IOException) {
                        // Thrown when the fd closes during teardown.
                        break
                    }
                    if (n < 0) break
                    if (n > 0) stack?.handlePacket(buffer, n)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "TUN pump died", t)
            }
        }

        _state.value = VpnState.CONNECTED
        promoteForeground(getString(R.string.vpn_notification_running, "$proxyHost:$proxyPort"))
        Log.i(TAG, "VPN up; proxying via $proxyHost:$proxyPort")
    }

    private fun stopTunnel() {
        _state.value = VpnState.STOPPING
        teardownTunnel()
        _state.value = VpnState.STOPPED
        stopForegroundAndSelf()
    }

    private fun teardownTunnel() {
        pumpRunning = false
        try {
            tunPfd?.close() // unblocks the pump's read()
        } catch (_: Exception) {
        }
        tunPfd = null
        try {
            tunOutput?.close()
        } catch (_: IOException) {
        }
        tunOutput = null
        try {
            stack?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "stack teardown failed", t)
        }
        stack = null
        pumpThread = null
    }

    private fun writeToTun(packet: ByteArray) {
        synchronized(tunWriteLock) {
            try {
                tunOutput?.write(packet)
            } catch (_: IOException) {
                // TUN closed mid-write; teardown proceeds independently.
            }
        }
    }

    // ---------------------------------------------------------- foreground

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promoteForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ManjaniqVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_engine)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.vpn_notification_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "ManjaniqVpn"

        const val ACTION_START = "com.manjaniq.action.QUICK_CONNECT_VPN_START"
        const val ACTION_STOP = "com.manjaniq.action.QUICK_CONNECT_VPN_STOP"
        const val EXTRA_PROXY_HOST = "com.manjaniq.extra.PROXY_HOST"
        const val EXTRA_PROXY_PORT = "com.manjaniq.extra.PROXY_PORT"

        private const val CHANNEL_ID = "manjaniq_vpn"
        private const val NOTIFICATION_ID = 40444

        private const val TUN_ADDRESS = "10.111.222.2"
        private const val TUN_PREFIX = 32
        private const val TUN_ADDRESS_V6 = "fd6d:6a6e:6a69::2"
        private const val TUN_PREFIX_V6 = 128
        private const val TUN_MTU = 1500
        private const val TUN_READ_BUFFER = 32767
        private const val DEFAULT_ROUTE = "0.0.0.0"
        private const val DEFAULT_ROUTE_V6 = "::"
        private const val PRIMARY_DNS = "1.1.1.1"
        private const val SECONDARY_DNS = "8.8.8.8"

        const val DEFAULT_PROXY_HOST = "127.0.0.1"
        const val DEFAULT_PROXY_PORT = 1819

        private val _state = MutableStateFlow(VpnState.STOPPED)
        val state: StateFlow<VpnState> = _state.asStateFlow()

        fun start(context: Context, proxyHost: String, proxyPort: Int) {
            val app = context.applicationContext
            val intent = Intent(app, ManjaniqVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROXY_HOST, proxyHost)
                putExtra(EXTRA_PROXY_PORT, proxyPort)
            }
            androidx.core.content.ContextCompat.startForegroundService(app, intent)
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, ManjaniqVpnService::class.java).apply {
                action = ACTION_STOP
            }
            // The service is already foregrounded when stopping. If the app
            // is in the background (Android O+ start restrictions), a running
            // service can always be stopped directly; onDestroy tears down.
            try {
                app.startService(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "startService(ACTION_STOP) refused; stopping service directly", t)
                app.stopService(Intent(app, ManjaniqVpnService::class.java))
            }
        }
    }
}
