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
        try { return handleCommand(intent) }
        catch (error: Throwable) {
            reportFailure("服务入口异常", error)
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return START_NOT_STICKY
        }
    }

    private fun handleCommand(intent: Intent?): Int {
        if (intent?.action == ACTION_STOP || (intent == null && server != null)) {
            stopServer(); return START_NOT_STICKY
        }
        val settings = McpSettings(this)
        val port = intent?.getIntExtra(EXTRA_PORT, settings.port) ?: settings.port
        if (server != null && activePort == port) return START_STICKY
        if (server != null) {
            server?.close(); server = null
        }
        if (McpSettings.state.value.starting) return START_STICKY
        McpSettings.update { it.copy(running = false, starting = true, error = null, port = port) }
        scope.launch {
            try {
                diagnostic("开始前台通知")
                startForegroundWithNotification(port)
                diagnostic("初始化工具注册表")
                val toolRegistry = McpTools(applicationContext, settings)
                diagnostic("创建 Socket 服务")
                val instance = McpServer(toolRegistry)
                diagnostic("绑定端口 $port")
                instance.start(port)
                diagnostic("MCP 启动成功")
                server = instance
                activePort = port
                McpSettings.update { it.copy(running = true, starting = false, port = port, error = null) }
                updateOverlay()
            } catch (e: Throwable) {
                server?.close(); server = null
                reportFailure("MCP 启动失败", e)
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun logFile(name: String) = java.io.File(java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
        android.os.Environment.DIRECTORY_DOWNLOADS), "RainGit/logs").apply { mkdirs() }, name)

    private fun diagnostic(message: String) {
        runCatching { logFile("mcp-stage.txt").appendText("${System.currentTimeMillis()} $message\n") }
    }

    private fun reportFailure(prefix: String, error: Throwable) {
        val trace = "$prefix\n" + error.stackTraceToString().takeLast(40_000)
        runCatching {
            logFile("mcp-latest.txt").writeText(trace)
            logFile("mcp-${System.currentTimeMillis()}.log").writeText(trace)
        }
        McpSettings.update { it.copy(running = false, starting = false,
            error = "$prefix：${error.javaClass.simpleName}: ${error.message}") }
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
            .setContentTitle("RainGit MCP 服务")
            .setContentText("http://127.0.0.1:$port/mcp · 仅本机访问")
            .setOngoing(true).setContentIntent(open)
            .addAction(0, "打开 RainGit", open)
            .addAction(0, "停止服务", stop)
            .build()
    }

    private fun stopServer() {
        server?.close(); server = null
        McpSettings.update { it.copy(running = false, starting = false, error = null) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateOverlay() {
        if (McpSettings(this).overlayEnabled && Settings.canDrawOverlays(this))
            runCatching { startService(Intent(this, McpOverlayService::class.java)) }
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
            if (McpSettings.state.value.running || McpSettings.state.value.starting) return
            McpSettings.update { it.copy(starting = true, error = null, port = port) }
            val intent = Intent(context, McpService::class.java).putExtra(EXTRA_PORT, port)
            try { context.startForegroundService(intent) }
            catch (error: Throwable) {
                McpSettings.update { it.copy(starting = false,
                    error = "启动请求失败：${error.message ?: error.javaClass.simpleName}") }
                throw error
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, McpService::class.java).setAction(ACTION_STOP))
        }
    }
}
