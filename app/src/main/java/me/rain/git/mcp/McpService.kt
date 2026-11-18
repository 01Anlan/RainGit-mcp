package me.rain.git.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.rain.git.MainActivity
import me.rain.git.R

class McpService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var server: McpServer? = null
    private var activePort = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runCatching {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "MCP 服务", NotificationManager.IMPORTANCE_LOW))
        }.onFailure {
            McpSettings.update { state -> state.copy(error = "通知初始化失败：${it.message}") }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || (intent == null && server != null)) {
            stopServer(); return START_NOT_STICKY
        }
        val settings = McpSettings(this)
        val port = intent?.getIntExtra(EXTRA_PORT, settings.port) ?: settings.port
        if (server != null && activePort == port) return START_STICKY
        if (server != null) stopServer()
        McpSettings.update { it.copy(starting = true, error = null, port = port) }
        scope.launch {
            try {
                startForegroundWithNotification(port)
                val instance = McpServer(McpTools(applicationContext, settings))
                instance.start(port)
                server = instance
                activePort = port
                McpSettings.update { it.copy(running = true, starting = false, port = port, error = null) }
                updateOverlay()
            } catch (e: Exception) {
                server?.close(); server = null
                McpSettings.update { it.copy(running = false, starting = false, error = e.message ?: "启动失败") }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification(port: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "MCP 服务", NotificationManager.IMPORTANCE_LOW))
        val notification = notification(port)
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1001, notification)
    }

    private fun notification(port: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, McpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_rain_mark)
            .setContentTitle("RainGit MCP 正在运行")
            .setContentText("http://127.0.0.1:$port/mcp · 仅本机访问")
            .setOngoing(true).setContentIntent(open)
            .addAction(0, "打开 RainGit", open)
            .addAction(0, "关闭服务", stop)
            .build()
    }

    private fun stopServer() {
        server?.close(); server = null
        McpSettings.update { it.copy(running = false, starting = false, error = null) }
        stopService(Intent(this, McpOverlayService::class.java))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateOverlay() {
        if (McpSettings(this).overlayEnabled && Settings.canDrawOverlays(this))
            startService(Intent(this, McpOverlayService::class.java))
    }

    override fun onDestroy() {
        server?.close(); server = null
        McpSettings.update { it.copy(running = false, starting = false) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "rain_mcp_server"
        const val ACTION_STOP = "me.rain.git.mcp.STOP"
        const val EXTRA_PORT = "port"

        fun start(context: Context, port: Int) {
            val intent = Intent(context, McpService::class.java).putExtra(EXTRA_PORT, port)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, McpService::class.java).setAction(ACTION_STOP))
        }
    }
}
