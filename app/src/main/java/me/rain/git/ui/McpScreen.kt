package me.rain.git.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import me.rain.git.mcp.McpOverlayService
import me.rain.git.mcp.McpService
import me.rain.git.mcp.McpSettings

@Composable
internal fun McpScreen(vm: RainViewModel) {
    val context = LocalContext.current
    val settings = remember { McpSettings(context) }
    val state by McpSettings.state.collectAsState()
    var port by remember { mutableStateOf(settings.port.toString()) }
    var overlay by remember { mutableStateOf(settings.overlayEnabled && Settings.canDrawOverlays(context)) }
    var battery by remember { mutableStateOf(isIgnoringBattery(context)) }
    var expanded by remember { mutableStateOf(false) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlay = settings.overlayEnabled && Settings.canDrawOverlays(context)
                if (overlay && !McpSettings.state.value.running)
                    runCatching { context.startService(Intent(context, McpOverlayService::class.java)) }
                battery = isIgnoringBattery(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val start: () -> Unit = {
        runCatching {
            val value = port.toInt()
            settings.port = value
            if (Build.VERSION.SDK_INT >= 33)
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            McpService.start(context, value)
        }.onFailure { vm.mcpMessage(it.message ?: "端口或启动参数无效") }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Surface(shape = RoundedCornerShape(25.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(17.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .75f)) {
                        Icon(Icons.Rounded.Hub, null, Modifier.padding(14.dp).size(28.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text("RainGit MCP", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("让 AI 直接读取、编辑和发布仓库", style = MaterialTheme.typography.bodySmall)
                    }
                    StateLabel(if (state.running) "运行中" else if (state.starting) "启动中" else "已停止")
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.running || state.starting) {
                        Button(onClick = { McpService.stop(context) }, modifier = Modifier.weight(1f).height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                            Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(7.dp)); Text("关闭服务")
                        }
                    } else {
                        Button(onClick = start, modifier = Modifier.weight(1f).height(50.dp), enabled = port.toIntOrNull() in 1024..65535) {
                            Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(7.dp)); Text("启动服务")
                        }
                    }
                    if (state.running && port.toIntOrNull() != state.port) {
                        FilledTonalButton(onClick = start, modifier = Modifier.height(50.dp)) { Text("保存并重启") }
                    }
                }
                state.error?.let {
                    Spacer(Modifier.height(10.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        SectionHeading("连接", "Streamable HTTP · 默认只允许本机客户端访问")
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("监听端口") }, singleLine = true, enabled = !state.starting,
                    supportingText = { Text("1024–65535；运行中修改后请点击保存并重启") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), leadingIcon = { Icon(Icons.Rounded.Lan, null) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("MCP 地址", style = MaterialTheme.typography.labelMedium)
                        Text("http://${state.address}:${if (state.running) state.port else port}/mcp",
                            style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    }
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("RainGit MCP", "http://${state.address}:${if (state.running) state.port else port}/mcp"))
                        vm.mcpMessage("MCP 地址已复制")
                    }) { Icon(Icons.Rounded.ContentCopy, "复制地址") }
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("允许修改与推送", fontWeight = FontWeight.SemiBold)
                        Text("关闭后只保留查询、读取和日志工具", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(settings.allowWrites, { settings.allowWrites = it; vm.mcpMessage(if (it) "修改类工具已启用" else "已切换为只读模式") })
                }
            }
        }

        SectionHeading("后台与控制", "前台服务保持 MCP 运行，以下权限均可独立设置")
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)), shape = RoundedCornerShape(22.dp)) {
            Column {
                ListItem(headlineContent = { Text("悬浮控制窗") },
                    supportingContent = { Text(if (overlay) "已开启，可拖动并快速启停 MCP" else "显示服务状态、启动和关闭按钮") },
                    leadingContent = { Icon(Icons.Rounded.PictureInPictureAlt, null) },
                    trailingContent = { Switch(overlay, { checked ->
                        if (checked && !Settings.canDrawOverlays(context)) {
                            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
                                .onFailure { vm.mcpMessage("请在系统设置中允许显示悬浮窗") }
                        } else {
                            settings.overlayEnabled = checked; overlay = checked
                            if (checked) context.startService(Intent(context, McpOverlayService::class.java))
                            else context.stopService(Intent(context, McpOverlayService::class.java))
                        }
                    }) }, colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListItem(headlineContent = { Text("后台无限制运行") },
                    supportingContent = { Text(if (battery) "系统已设置为不限制" else "将 RainGit 加入电池优化白名单") },
                    leadingContent = { Icon(Icons.Rounded.BatterySaver, null) },
                    trailingContent = { TextButton(onClick = {
                        val intent = if (!battery) Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                        else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        runCatching { context.startActivity(intent) }.onFailure {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }) { Text(if (battery) "查看" else "去授权") } }, colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
            }
        }

        SectionHeading("运行记录", "凭证、文件正文不会写入请求记录")
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("工具调用 ${state.requests} 次", style = MaterialTheme.typography.bodyMedium)
                    Text("34 个工具", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (state.lastTool != null) {
                    Spacer(Modifier.height(7.dp))
                    Text(state.lastTool.orEmpty(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    Text(state.lastResult.orEmpty(), style = MaterialTheme.typography.bodySmall,
                        color = if (state.lastResult == "成功") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                } else Text("暂无调用", Modifier.padding(top = 7.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.align(Alignment.End)) {
                    Text(if (expanded) "收起工具清单" else "查看工具清单")
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                }
                if (expanded) Text(toolNames.joinToString("\n"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private val toolNames = listOf(
    "list_repositories", "list_local_repositories", "get_repository_status", "list_files", "list_directories",
    "read_file", "search_files", "get_diff", "list_commits", "get_commit", "clone_repository", "write_file",
    "create_directory", "rename_file", "delete_file", "fetch_repository", "pull_repository", "stage_files",
    "unstage_files", "commit_changes", "push_repository", "create_branch", "checkout_branch", "create_stash",
    "apply_stash", "list_workflows", "list_workflow_runs", "get_workflow_run", "get_workflow_logs",
    "dispatch_workflow", "control_workflow_run", "list_issues", "list_pull_requests", "create_issue"
)

private fun isIgnoringBattery(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
