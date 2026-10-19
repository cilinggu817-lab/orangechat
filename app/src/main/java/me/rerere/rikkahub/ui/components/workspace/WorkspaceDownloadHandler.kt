package me.rerere.rikkahub.ui.components.workspace

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject

internal val LocalWorkspaceDownloadHandler = staticCompositionLocalOf<UriHandler?> { null }

@Composable
internal fun rememberWorkspaceDownloadHandler(workspaceId: String?): UriHandler {
    val context = LocalContext.current
    val fallback = LocalUriHandler.current
    val toaster = LocalToaster.current
    val repository = koinInject<WorkspaceRepository>()
    val scope = rememberCoroutineScope()
    var pendingWorkspace by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = pendingWorkspace
        val path = pendingPath
        pendingWorkspace = null
        pendingPath = null
        if (uri != null && id != null && path != null) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                            repository.exportFile(id, WorkspaceStorageArea.FILES, path, output)
                        } ?: error("无法打开保存位置")
                    }
                    toaster.show(message = "已保存 ${path.substringAfterLast('/')}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    toaster.show(message = "文件保存失败：${e.message}")
                } finally {
                    busy = false
                }
            }
        }
    }
    return remember(workspaceId, fallback, launcher) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (!WorkspaceDownloadPath.isWorkspaceLink(uri)) {
                    runCatching { fallback.openUri(uri) }.onFailure { toaster.show(message = "无法打开此链接") }
                    return
                }
                val path = WorkspaceDownloadPath.parse(uri)
                when {
                    workspaceId == null -> toaster.show(message = "此会话的助手未绑定工作区")
                    path == null -> toaster.show(message = "无效的工作区文件路径")
                    busy || pendingPath != null -> toaster.show(message = "请先完成当前文件保存")
                    else -> {
                        // Capture the workspace now: changing conversations while SAF is open must not redirect the export.
                        pendingWorkspace = workspaceId
                        pendingPath = path
                        scope.launch {
                            try {
                                // Check existence/canonical containment before creating a destination document.
                                repository.fileSize(workspaceId, WorkspaceStorageArea.FILES, path)
                                launcher.launch(path.substringAfterLast('/'))
                            } catch (e: CancellationException) {
                                pendingWorkspace = null
                                pendingPath = null
                                throw e
                            } catch (e: Exception) {
                                pendingWorkspace = null
                                pendingPath = null
                                toaster.show(message = "无法保存此文件：${e.message}")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun WorkspaceInlineDownloads(text: String) {
    val handler = LocalWorkspaceDownloadHandler.current ?: return
    val paths = remember(text) { WorkspaceDownloadPath.inlinePaths(text) }
    paths.forEach { path ->
        TextButton(onClick = { handler.openUri("sandbox:" + Uri.encode(path, "/")) }) {
            Text("保存文件 · ${path.substringAfterLast('/')}")
        }
    }
}
