package me.rain.git.mcp

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.rain.git.MainActivity

/** Small draggable native control; deliberately independent from the MCP server lifecycle. */
class McpOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var window: WindowManager? = null
    private var view: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        if (view == null) showOverlay()
        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        val wm = getSystemService(WindowManager::class.java).also { window = it }
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val status = TextView(this).apply {
            text = "MCP 已停止"; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(8), 0)
        }
        val button = TextView(this).apply {
            text = "启动"; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.rgb(55, 103, 214)) }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(dp(4), dp(6), dp(4), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat(); setColor(Color.rgb(32, 36, 46)); setStroke(dp(1), Color.rgb(72, 79, 94))
            }
            addView(status, LinearLayout.LayoutParams(dp(130), dp(34)))
            addView(button, LinearLayout.LayoutParams(dp(64), dp(34)))
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END; x = dp(12); y = dp(120)
        }
        var initialX = 0; var initialY = 0; var touchX = 0f; var touchY = 0f; var moved = false
        content.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y; touchX = event.rawX; touchY = event.rawY; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX; val dy = event.rawY - touchY
                    if (dx * dx + dy * dy > dp(8) * dp(8)) moved = true
                    params.x = initialX - dx.toInt(); params.y = initialY + dy.toInt()
                    wm.updateViewLayout(content, params); true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                }
                else -> false
            }
        }
        button.setOnClickListener {
            val state = McpSettings.state.value
            if (state.running || state.starting) McpService.stop(this)
            else McpService.start(this, McpSettings(this).port)
        }
        wm.addView(content, params); view = content
        scope.launch {
            McpSettings.state.collectLatest {
                status.text = if (it.running) "MCP · :${it.port}" else if (it.starting) "MCP 启动中" else "MCP 已停止"
                button.text = if (it.running || it.starting) "关闭" else "启动"
            }
        }
    }

    override fun onDestroy() {
        view?.let { runCatching { window?.removeView(it) } }
        view = null; scope.cancel(); super.onDestroy()
    }
}
