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
import android.view.ViewConfiguration
import android.widget.Toast
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.rain.git.MainActivity

/** Small draggable native control; deliberately independent from the MCP server lifecycle. */
class McpOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var window: WindowManager? = null
    private var view: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!McpSettings(this).overlayEnabled || !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        if (view == null && runCatching { showOverlay() }.isFailure) stopSelf()
        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        val wm = getSystemService(WindowManager::class.java).also { window = it }
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat(); setColor(color)
        }
        fun label(value: String, size: Float) = TextView(this).apply {
            text = value; setTextColor(Color.WHITE); textSize = size; gravity = Gravity.CENTER
        }
        val status = label("MCP\n已停止", 12f).apply { setPadding(dp(10), 0, dp(10), 0) }
        val button = label("启动", 14f)
        val hide = label("×", 22f).apply { contentDescription = "隐藏 MCP 悬浮窗" }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(Color.rgb(31, 36, 48), 18).apply { setStroke(dp(1), Color.rgb(74, 83, 101)) }
            elevation = dp(5).toFloat()
            addView(status, LinearLayout.LayoutParams(dp(94), dp(48)))
            addView(button, LinearLayout.LayoutParams(dp(62), dp(48)))
            addView(hide, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.RIGHT; x = dp(12); y = dp(120)
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        status.setOnClickListener {
            attempt("打开 RainGit") {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
        }
        var initialX = 0; var initialY = 0; var touchX = 0f; var touchY = 0f; var moved = false
        status.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y; touchX = event.rawX; touchY = event.rawY; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX; val dy = event.rawY - touchY
                    if (dx * dx + dy * dy > slop * slop) moved = true
                    if (moved) {
                        val bounds = wm.currentWindowMetrics.bounds
                        params.x = (initialX - dx.toInt()).coerceIn(0, (bounds.width() - content.width).coerceAtLeast(0))
                        params.y = (initialY + dy.toInt()).coerceIn(0, (bounds.height() - content.height).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(content, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) target.performClick(); true }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        button.setOnClickListener {
            val state = McpSettings.state.value
            if (McpService.isStartingNow()) return@setOnClickListener
            if (state.running) attempt("停止 MCP") { McpService.stop(this) }
            else attempt("启动 MCP") { McpService.start(this, McpSettings(this).port) }
        }
        hide.setOnClickListener {
            McpSettings(this).overlayEnabled = false
            stopSelf()
        }
        wm.addView(content, params); view = content
        scope.launch {
            combine(McpSettings.state, McpStatus.updates(this@McpOverlayService)) { state, probe ->
                // McpStatus emits an empty default while stopped. Prefer a real running
                // listener, otherwise retain the service's explicit starting/error state.
                if (probe.running) probe else state
            }.collect {
                val summary = when {
                    it.running -> "运行中 · ${it.port}"
                    it.starting && McpService.isStartingNow() -> "启动中…"
                    it.error != null -> "启动失败"
                    else -> "已停止"
                }
                status.text = "MCP\n$summary"
                status.contentDescription = "MCP $summary，点按打开 RainGit，拖动可移动"
                button.text = if (it.running) "停止" else "启动"
                val busy = McpService.isStartingNow()
                button.isEnabled = !busy
                button.alpha = if (busy) .55f else 1f
                button.background = rounded(if (it.running) Color.rgb(145, 56, 67) else Color.rgb(51, 95, 193), 14)
            }
        }
    }

    private fun attempt(action: String, block: () -> Unit) {
        runCatching(block).onFailure {
            Toast.makeText(this, "${action}失败，请打开 RainGit 查看", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        view?.let { runCatching { window?.removeView(it) } }
        view = null; scope.cancel(); super.onDestroy()
    }
}
