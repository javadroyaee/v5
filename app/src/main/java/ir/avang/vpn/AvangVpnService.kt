package ir.avang.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import hev.htproxy.TProxyService
import libv2ray.Libv2ray
import libv2ray.V2RayPoint
import libv2ray.V2RayVPNServiceSupportsSet
import java.io.File

class AvangVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var v2rayPoint: V2RayPoint? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        v2rayPoint = Libv2ray.newV2RayPoint(object : V2RayVPNServiceSupportsSet {
            override fun shutdown(): Long {
                stopVpn()
                return 0
            }
            override fun prepare(): Long = 0
            override fun protect(fd: Long): Boolean = this@AvangVpnService.protect(fd.toInt())
            override fun onEmitStatus(status: Long, msg: String?): Long = 0
            override fun setup(conf: String): Long = 0
        }, false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopVpn()
            return START_NOT_STICKY
        }
        val xrayJson = intent?.getStringExtra("XRAY_JSON")
        val serverName = intent?.getStringExtra("SERVER_NAME") ?: "Avang VPN"
        if (!xrayJson.isNullOrEmpty()) {
            startForegroundNotification(serverName)
            startXrayTunnel(xrayJson)
        }
        return START_STICKY
    }

    private fun startXrayTunnel(xrayJson: String) {
        stopTunnelOnly()
        try {
            // 1. استارت هسته Xray روی پورت 10808
            v2rayPoint?.configureFileContent = xrayJson
            v2rayPoint?.domainName = "127.0.0.1:10808"
            v2rayPoint?.runLoop(false)

            // 2. ساخت رابط TUN اندروید
            val builder = Builder()
                .setSession("Avang VPN")
                .setMtu(8500)
                .addAddress("10.0.0.2", 30)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)
                .addDisallowedApplication(packageName)

            vpnInterface = builder.establish()
            val fd = vpnInterface?.fd ?: return

            // 3. نوشتن فایل تنظیمات TProxy و اتصال واقعی TUN به Xray
            val confFile = File(cacheDir, "tproxy.conf")
            confFile.writeText(
                "tunnel:\n" +
                "  mtu: 8500\n" +
                "socks5:\n" +
                "  port: 10808\n" +
                "  address: 127.0.0.1\n" +
                "  udp: 'udp'\n"
            )
            TProxyService.TProxyStartService(confFile.absolutePath, fd)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopTunnelOnly() {
        try {
            TProxyService.TProxyStopService()
        } catch (_: Exception) {}
        try {
            if (v2rayPoint?.isRunning == true) {
                v2rayPoint?.stopLoop()
            }
        } catch (_: Exception) {}
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (_: Exception) {}
    }

    private fun stopVpn() {
        stopTunnelOnly()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("avang_vpn_ch", "Avang VPN Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification(serverName: String) {
        val notif = NotificationCompat.Builder(this, "avang_vpn_ch")
            .setContentTitle("Avang VPN • Connected")
            .setContentText("متصل به: $serverName")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
        startForeground(1, notif)
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}