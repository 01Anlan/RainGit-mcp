package me.rain.git

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import me.rain.git.ui.RainApp
import me.rain.git.ui.RainViewModel
import me.rain.git.mcp.McpService
import me.rain.git.mcp.McpSettings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val mcp = McpSettings(this)
        if (mcp.autoStart && !McpSettings.state.value.running && !McpSettings.state.value.starting) {
            runCatching { McpService.start(this, mcp.port) }
        }
        setContent {
            val model: RainViewModel = viewModel()
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                uri?.let(model::chooseRoot)
            }
            val grantStorage = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
            RainApp(
                model = model,
                onChooseDirectory = { picker.launch(null) },
                onGrantStorage = {
                    if (!Environment.isExternalStorageManager()) {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName"))
                        grantStorage.launch(intent)
                    } else picker.launch(null)
                },
                onOpenUrl = { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            )
        }
    }
}
