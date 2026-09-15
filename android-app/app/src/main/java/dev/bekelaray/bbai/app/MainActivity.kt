package dev.bekelaray.bbai.app

import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import dev.bekelaray.bbai.app.ui.theme.BbaiTheme
import dev.bekelaray.bbai.core.io.RandomAccessReader
import dev.bekelaray.bbai.core.io.OwnedSliceReadSource
import dev.bekelaray.bbai.core.io.SliceReadSource
import dev.bekelaray.bbai.core.model.DetectionResult
import dev.bekelaray.bbai.core.model.InspectionResult
import dev.bekelaray.bbai.core.model.MetadataField
import dev.bekelaray.bbai.core.model.SortMode
import dev.bekelaray.bbai.core.model.SupportStatus
import dev.bekelaray.bbai.core.model.SwitchFileKind
import dev.bekelaray.bbai.core.model.VirtualNode
import dev.bekelaray.bbai.core.parser.SwitchInspector
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.time.Instant
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BbaiTheme {
                val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let { viewModel.onInputPicked(it) }
                }
                val openInputTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    uri?.let { viewModel.onInputDirectoryPicked(it) }
                }
                val openTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    uri?.let { viewModel.onOutputPicked(it) }
                }
                App(
                    viewModel,
                    onPickFile = { openFile.launch(arrayOf("*/*")) },
                    onPickInputTree = { openInputTree.launch(null) },
                    onPickTree = { openTree.launch(null) },
                )
            }
        }
    }
}

private const val PREFS_NAME = "bbai_prefs"
private const val PREF_LAST_INPUT = "last_input"
private const val PREF_LAST_OUTPUT = "last_output"

private object AppConfig {
    const val previewCacheLimitBytes = 128L * 1024L * 1024L
    const val exportBufferSizeBytes = 128 * 1024
    const val inputScanLimit = 2_000
    const val inputScanDepthLimit = 12
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val inspector = SwitchInspector()
    private val prefs = application.getSharedPreferences(PREFS_NAME, 0)
    private val _exportState = MutableStateFlow(ExportState())
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    var currentScreen by mutableStateOf(Screen.Home)
        private set
    var isBusy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var sessionStack by mutableStateOf(listOf<BrowserSession>())
        private set
    var selectedNode by mutableStateOf<VirtualNode?>(null)
        private set
    var selectedInspection by mutableStateOf<InspectionResult?>(null)
        private set
    var previewState by mutableStateOf<PreviewState?>(null)
        private set
    var searchQuery by mutableStateOf("")
        private set
    var sortMode by mutableStateOf(SortMode.NAME)
        private set
    var expandedPaths by mutableStateOf(setOf<String>())
        private set
    var lastInputUri by mutableStateOf(prefs.getString(PREF_LAST_INPUT, null)?.let(Uri::parse))
        private set
    var outputTreeUri by mutableStateOf(prefs.getString(PREF_LAST_OUTPUT, null)?.let(Uri::parse))
        private set
    var persistedUris by mutableStateOf(loadPersistedUris())
        private set

    private var exportJob: Job? = null

    fun onInputPicked(uri: Uri) {
        rememberInputIfPersisted(uri)
        openInput(uri)
    }

    fun onInputDirectoryPicked(uri: Uri) {
        rememberInputIfPersisted(uri)
        openInput(uri)
    }

    fun onOutputPicked(uri: Uri) {
        outputTreeUri = uri
        if (persistOutputUri(uri)) {
            prefs.edit().putString(PREF_LAST_OUTPUT, uri.toString()).apply()
            message = app.getString(R.string.message_output_saved)
        } else {
            prefs.edit().remove(PREF_LAST_OUTPUT).apply()
            message = app.getString(R.string.message_output_not_persisted)
        }
        persistedUris = loadPersistedUris()
    }

    fun reopenLastInput() {
        lastInputUri?.let(::openInput) ?: run { message = app.getString(R.string.message_no_remembered_input) }
    }

    fun navigate(screen: Screen) {
        currentScreen = screen
    }

    fun clearMessage() {
        message = null
    }

    fun updateSearchQuery(value: String) {
        searchQuery = value
    }

    fun updateSortMode(value: SortMode) {
        sortMode = value
    }

    fun toggleDirectory(path: String) {
        expandedPaths = expandedPaths.toMutableSet().also {
            if (!it.add(path)) it.remove(path)
        }
    }

    fun goBack() {
        when (currentScreen) {
            Screen.Home -> Unit
            Screen.Browser -> {
                if (sessionStack.size > 1) {
                    sessionStack = sessionStack.dropLast(1)
                } else {
                    currentScreen = Screen.Home
                }
            }
            Screen.Detail -> currentScreen = Screen.Browser
            Screen.Preview -> currentScreen = Screen.Detail
            Screen.Settings -> currentScreen = if (sessionStack.isNotEmpty()) Screen.Browser else Screen.Home
        }
    }

    fun openNode(node: VirtualNode) {
        if (node.isDirectory) {
            toggleDirectory(node.path)
            return
        }
        selectedNode = node
        currentScreen = Screen.Detail
        loadDetail(node)
    }

    fun browseSelectedNode() {
        val node = selectedNode ?: return
        val current = sessionStack.lastOrNull() ?: return
        val detectionKind = selectedInspection?.detection?.kind ?: node.detection.kind
        if (detectionKind !in setOf(SwitchFileKind.PFS0, SwitchFileKind.NSP, SwitchFileKind.HFS0, SwitchFileKind.XCI, SwitchFileKind.EXEFS)) {
            message = app.getString(R.string.message_not_readable_archive)
            return
        }
        val factory = factoryForNode(current, node) ?: run {
            message = app.getString(R.string.message_unable_open_node)
            return
        }
        inspectAndPush(factory)
    }

    fun preparePreview() {
        val node = selectedNode ?: return
        val current = sessionStack.lastOrNull() ?: return
        val detection = selectedInspection?.detection ?: node.detection
        if (detection.kind !in setOf(SwitchFileKind.IMAGE, SwitchFileKind.AUDIO, SwitchFileKind.VIDEO)) {
            message = app.getString(R.string.message_preview_supported_only)
            return
        }
        val sourceFactory = factoryForNode(current, node) ?: run {
            message = app.getString(R.string.message_unable_read_node)
            return
        }
        viewModelScope.launch {
            isBusy = true
            try {
                if (node.size > AppConfig.previewCacheLimitBytes) {
                    message = app.getString(R.string.message_preview_large)
                    return@launch
                }
                val cached = withContext(Dispatchers.IO) {
                    materializeToCache(sourceFactory, node.path)
                }
                previewState = PreviewState(node.name, detection, Uri.fromFile(cached), node.size)
                currentScreen = Screen.Preview
            } catch (error: Exception) {
                message = app.getString(R.string.message_preview_failed, error.message ?: app.getString(R.string.message_unknown_error))
            } finally {
                isBusy = false
            }
        }
    }

    fun exportSelectedNode() {
        val targetTree = outputTreeUri
        val node = selectedNode
        val current = sessionStack.lastOrNull()
        if (targetTree == null || node == null || current == null) {
            message = app.getString(R.string.message_pick_output_dir)
            return
        }
        exportJob?.cancel()
        exportJob = viewModelScope.launch(Dispatchers.IO) {
            val detection = selectedInspection?.detection ?: node.detection
            _exportState.value = ExportState(
                running = true,
                cancelled = false,
                title = node.name,
                progress = 0f,
                copiedBytes = 0L,
                totalBytes = node.size,
                outputUri = null,
                error = null,
                logLines = listOf(logLine("Export started: ${node.path}")),
            )
            try {
                val file = ensureOutputFile(targetTree, node.path, detection.mimeType)
                val factory = factoryForNode(current, node) ?: error(app.getString(R.string.message_unable_read_node))
                copyFactoryToUri(factory, file.uri, node.size)
                _exportState.value = _exportState.value.copy(
                    running = false,
                    progress = 1f,
                    copiedBytes = node.size,
                    outputUri = file.uri,
                    logLines = _exportState.value.logLines + logLine("Export completed: ${file.uri}"),
                )
            } catch (cancelled: Exception) {
                if (cancelled is kotlinx.coroutines.CancellationException) {
                    _exportState.value = _exportState.value.copy(
                        running = false,
                        cancelled = true,
                        logLines = _exportState.value.logLines + logLine(app.getString(R.string.message_export_cancelled)),
                    )
                } else {
                    _exportState.value = _exportState.value.copy(
                        running = false,
                        error = cancelled.message ?: app.getString(R.string.message_unknown_error),
                        logLines = _exportState.value.logLines + logLine(
                            app.getString(
                                R.string.message_export_failed,
                                cancelled.message ?: app.getString(R.string.message_unknown_error),
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
    }

    fun releasePersistedUri(uri: Uri) {
        val permission = app.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        val flags = buildPermissionFlags(permission?.isReadPermission == true, permission?.isWritePermission == true)
        if (flags == 0) {
            persistedUris = loadPersistedUris()
            message = app.getString(R.string.message_no_persisted_permission)
            return
        }
        val released = runCatching {
            app.contentResolver.releasePersistableUriPermission(
                uri,
                flags,
            )
        }.isSuccess
        if (!released) {
            persistedUris = loadPersistedUris()
            message = app.getString(R.string.message_release_permission_failed)
            return
        }
        if (uri == lastInputUri) {
            lastInputUri = null
            prefs.edit().remove(PREF_LAST_INPUT).apply()
        }
        if (uri == outputTreeUri) {
            outputTreeUri = null
            prefs.edit().remove(PREF_LAST_OUTPUT).apply()
        }
        persistedUris = loadPersistedUris()
    }

    private fun openInput(uri: Uri) {
        val treeDocument = DocumentFile.fromTreeUri(app, uri)
        if (treeDocument?.isDirectory == true) {
            openInputDirectory(uri, treeDocument)
            return
        }
        val document = DocumentFile.fromSingleUri(app, uri)
        val name = document?.name ?: uri.lastPathSegment ?: "selected-file"
        val size = document?.length()?.takeIf { it >= 0 } ?: 0L
        val factory = UriReaderFactory(app, uri, name, size)
        inspectAndReplace(factory)
    }

    private fun openInputDirectory(uri: Uri, root: DocumentFile) {
        viewModelScope.launch {
            isBusy = true
            try {
                val inspection = withContext(Dispatchers.IO) { inspectDirectory(root) }
                sessionStack = listOf(BrowserSession(root.name ?: "selected-directory", null, inspection))
                expandedPaths = inspection.entries.map { it.path }.toSet()
                selectedNode = null
                selectedInspection = null
                previewState = null
                searchQuery = ""
                sortMode = SortMode.NAME
                currentScreen = Screen.Browser
            } catch (error: Exception) {
                message = app.getString(R.string.message_open_directory_failed, error.message ?: app.getString(R.string.message_unknown_error))
            } finally {
                isBusy = false
            }
        }
    }

    private fun inspectAndReplace(factory: ReaderFactory) {
        viewModelScope.launch {
            isBusy = true
            try {
                val inspection = withContext(Dispatchers.IO) { inspect(factory) }
                sessionStack = listOf(BrowserSession(factory.displayName, factory, inspection))
                expandedPaths = inspection.entries.map { it.path }.toSet()
                selectedNode = null
                selectedInspection = null
                previewState = null
                searchQuery = ""
                sortMode = SortMode.NAME
                currentScreen = Screen.Browser
            } catch (error: Exception) {
                message = app.getString(R.string.message_open_failed, error.message ?: app.getString(R.string.message_unknown_error))
            } finally {
                isBusy = false
            }
        }
    }

    private fun inspectAndPush(factory: ReaderFactory) {
        viewModelScope.launch {
            isBusy = true
            try {
                val inspection = withContext(Dispatchers.IO) { inspect(factory) }
                sessionStack = sessionStack + BrowserSession(factory.displayName, factory, inspection)
                expandedPaths = expandedPaths + inspection.entries.map { it.path }
                selectedNode = null
                selectedInspection = null
                previewState = null
                currentScreen = Screen.Browser
            } catch (error: Exception) {
                message = app.getString(R.string.message_browse_failed, error.message ?: app.getString(R.string.message_unknown_error))
            } finally {
                isBusy = false
            }
        }
    }

    private fun loadDetail(node: VirtualNode) {
        val current = sessionStack.lastOrNull() ?: return
        viewModelScope.launch {
            isBusy = true
            try {
                val inspection = withContext(Dispatchers.IO) {
                    val factory = factoryForNode(current, node) ?: error(app.getString(R.string.message_unable_read_node))
                    inspect(factory)
                }
                selectedInspection = inspection
            } catch (error: Exception) {
                selectedInspection = InspectionResult(
                    displayName = node.name,
                    size = node.size,
                    detection = node.detection,
                    supportStatus = SupportStatus.INVALID,
                    metadata = listOf(MetadataField("Status", error.message ?: app.getString(R.string.message_unknown_error))),
                )
            } finally {
                isBusy = false
            }
        }
    }

    private suspend fun inspect(factory: ReaderFactory): InspectionResult {
        val reader = factory.openReader()
        return try {
            inspector.inspect(factory.displayName, reader)
        } finally {
            reader.close()
        }
    }

    private suspend fun inspectDirectory(root: DocumentFile): InspectionResult {
        val counter = ScanCounter(AppConfig.inputScanLimit)
        val entries = scanDirectory(root, "", 0, counter)
        return InspectionResult(
            displayName = root.name ?: "selected-directory",
            size = 0L,
            detection = DetectionResult(SwitchFileKind.UNKNOWN),
            supportStatus = SupportStatus.SUPPORTED,
            metadata = listOf(
                MetadataField("Detected type", "DIRECTORY"),
                MetadataField("Source URI", root.uri.toString()),
                MetadataField("Scanned entries", (AppConfig.inputScanLimit - counter.remaining).toString()),
                MetadataField("Scan limit", AppConfig.inputScanLimit.toString()),
            ),
            entries = entries,
            warnings = if (counter.truncated) listOf("Directory scan stopped after ${AppConfig.inputScanLimit} nodes to avoid excessive memory use.") else emptyList(),
        )
    }

    private suspend fun scanDirectory(
        directory: DocumentFile,
        relativePath: String,
        depth: Int,
        counter: ScanCounter,
    ): List<VirtualNode> {
        if (depth > AppConfig.inputScanDepthLimit || counter.remaining <= 0) return emptyList()
        val children = directory.listFiles().sortedWith(compareBy<DocumentFile>({ !it.isDirectory }, { it.name.orEmpty().lowercase() }))
        return buildList {
            for (child in children) {
                if (counter.remaining <= 0) {
                    counter.truncated = true
                    break
                }
                counter.remaining -= 1
                val safeName = sanitizeRelativePath(child.name ?: "unnamed").substringAfterLast('/')
                val path = listOfNotNull(relativePath.takeIf { it.isNotBlank() }, safeName).joinToString("/")
                if (child.isDirectory) {
                    add(
                        VirtualNode(
                            name = safeName,
                            path = path,
                            isDirectory = true,
                            size = 0L,
                            offset = 0L,
                            backingUri = child.uri.toString(),
                            children = scanDirectory(child, path, depth + 1, counter),
                        ),
                    )
                } else {
                    val size = child.length().takeIf { it >= 0 } ?: 0L
                    val detection = inspector.detectByName(child.name ?: "selected-file")
                    add(
                        VirtualNode(
                            name = safeName,
                            path = path,
                            isDirectory = false,
                            size = size,
                            offset = 0L,
                            detection = detection,
                            backingUri = child.uri.toString(),
                        ),
                    )
                }
            }
        }
    }

    private suspend fun materializeToCache(factory: ReaderFactory, relativePath: String): File {
        val sanitized = sanitizeRelativePath(relativePath)
        val target = File(app.cacheDir, sanitized.replace('/', '_'))
        target.parentFile?.mkdirs()
        val reader = factory.openReader()
        try {
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(AppConfig.exportBufferSizeBytes)
                var position = 0L
                while (position < reader.size) {
                    val read = reader.readAt(position, buffer, 0, minOf(buffer.size.toLong(), reader.size - position).toInt())
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    position += read
                }
            }
            return target
        } finally {
            reader.close()
        }
    }

    private suspend fun copyFactoryToUri(factory: ReaderFactory, targetUri: Uri, totalSize: Long) {
        val reader = factory.openReader()
        try {
            app.contentResolver.openOutputStream(targetUri, "w")?.use { output ->
                val buffer = ByteArray(AppConfig.exportBufferSizeBytes)
                var copied = 0L
                var lastLoggedProgress = -1
                while (copied < reader.size) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val read = reader.readAt(copied, buffer, 0, minOf(buffer.size.toLong(), reader.size - copied).toInt())
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    val progress = if (totalSize > 0) copied.toFloat() / totalSize.toFloat() else 0f
                    _exportState.value = _exportState.value.copy(progress = progress, copiedBytes = copied)
                    val rounded = (progress * 100).roundToInt()
                    if (rounded % 10 == 0 && rounded != lastLoggedProgress) {
                        lastLoggedProgress = rounded
                        _exportState.value = _exportState.value.copy(
                            logLines = _exportState.value.logLines + logLine("Export progress: $rounded%"),
                        )
                    }
                }
            } ?: error(app.getString(R.string.internal_unable_output_stream))
        } finally {
            reader.close()
        }
    }

    private fun ensureOutputFile(treeUri: Uri, relativePath: String, mimeType: String?): DocumentFile {
        val tree = DocumentFile.fromTreeUri(app, treeUri) ?: error(app.getString(R.string.internal_output_dir_unavailable))
        val cleanPath = sanitizeRelativePath(relativePath)
        val parts = cleanPath.split('/')
        var current = tree
        parts.dropLast(1).forEach { folder ->
            val existing = current.findFile(folder)
            current = when {
                existing == null -> current.createDirectory(folder) ?: error(app.getString(R.string.internal_failed_create_folder, folder))
                existing.isDirectory -> existing
                else -> error(app.getString(R.string.internal_export_path_file_collision, folder))
            }
        }
        val filename = parts.last()
        current.findFile(filename)?.let { existing ->
            return when {
                existing.isDirectory -> error(app.getString(R.string.internal_export_path_directory_collision, filename))
                else -> existing
            }
        }
        return current.createFile(mimeType ?: "application/octet-stream", filename)
            ?: error(app.getString(R.string.internal_failed_create_output))
    }

    private fun persistInputUri(uri: Uri): Boolean {
        val persisted = runCatching {
            app.contentResolver.takePersistableUriPermission(uri, ReadPermissionFlag)
        }.isSuccess
        persistedUris = loadPersistedUris()
        return persisted
    }

    private fun persistOutputUri(uri: Uri): Boolean {
        val persisted = runCatching {
            app.contentResolver.takePersistableUriPermission(uri, ReadWritePermissionFlags)
        }.isSuccess
        persistedUris = loadPersistedUris()
        return persisted
    }

    private fun rememberInputIfPersisted(uri: Uri) {
        if (persistInputUri(uri)) {
            lastInputUri = uri
            prefs.edit().putString(PREF_LAST_INPUT, uri.toString()).apply()
        } else {
            lastInputUri = null
            prefs.edit().remove(PREF_LAST_INPUT).apply()
            if (message == null) {
                message = app.getString(R.string.message_input_not_persisted)
            }
        }
    }

    private fun loadPersistedUris(): List<Uri> =
        app.contentResolver.persistedUriPermissions.map { it.uri }.sortedBy { it.toString() }

    private fun sanitizeRelativePath(value: String): String =
        value.split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/") { segment -> segment.replace(Regex("[^A-Za-z0-9._ -]"), "_") }
            .ifBlank { "export.bin" }

    private fun logLine(message: String): String = "${Instant.now()}  $message"

    private fun factoryForNode(session: BrowserSession, node: VirtualNode): ReaderFactory? {
        node.backingUri?.let {
            return UriReaderFactory(app, Uri.parse(it), node.name, node.size)
        }
        val factory = session.factory ?: return null
        return SliceReaderFactory(factory, node.offset, node.size, node.name)
    }

    companion object {
        private const val ReadPermissionFlag = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        private const val WritePermissionFlag = android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        private const val ReadWritePermissionFlags = ReadPermissionFlag or WritePermissionFlag
    }
}

private fun buildPermissionFlags(read: Boolean, write: Boolean): Int =
    (if (read) android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
        (if (write) android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)

private data class BrowserSession(
    val label: String,
    val factory: ReaderFactory?,
    val inspection: InspectionResult,
)

private data class ScanCounter(
    var remaining: Int,
    var truncated: Boolean = false,
)

private data class PreviewState(
    val title: String,
    val detection: DetectionResult,
    val uri: Uri,
    val size: Long,
)

data class ExportState(
    val running: Boolean = false,
    val cancelled: Boolean = false,
    val title: String? = null,
    val progress: Float = 0f,
    val copiedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val outputUri: Uri? = null,
    val error: String? = null,
    val logLines: List<String> = emptyList(),
)

private enum class Screen {
    Home,
    Browser,
    Detail,
    Preview,
    Settings,
}

private interface ReaderFactory {
    val displayName: String
    suspend fun openReader(): RandomAccessReader
}

private class UriReaderFactory(
    private val application: Application,
    private val uri: Uri,
    override val displayName: String,
    private val sizeHint: Long,
) : ReaderFactory {
    override suspend fun openReader(): RandomAccessReader = AndroidUriReader(application, uri, sizeHint)
}

private class SliceReaderFactory(
    private val parent: ReaderFactory,
    private val baseOffset: Long,
    private val length: Long,
    override val displayName: String,
) : ReaderFactory {
    override suspend fun openReader(): RandomAccessReader {
        val parentReader = parent.openReader()
        return OwnedSliceReadSource(parentReader, SliceReadSource(parentReader, baseOffset, length))
    }
}

private class AndroidUriReader(
    application: Application,
    uri: Uri,
    sizeHint: Long,
) : RandomAccessReader {
    private val descriptor = application.contentResolver.openFileDescriptor(uri, "r") ?: error(application.getString(R.string.internal_unable_open_fd))
    private val channel: FileChannel = FileInputStream(descriptor.fileDescriptor).channel
    override val size: Long = sizeHint.takeIf { it > 0 } ?: descriptor.statSize.takeIf { it > 0 } ?: channel.size()

    override suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position < 0 || position >= size) return -1
        val byteBuffer = ByteBuffer.wrap(buffer, offset, length)
        return synchronized(channel) {
            channel.read(byteBuffer, position)
        }
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { descriptor.close() }
    }
}

@Composable
private fun App(
    viewModel: MainViewModel,
    onPickFile: () -> Unit,
    onPickInputTree: () -> Unit,
    onPickTree: () -> Unit,
) {
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val title = when (viewModel.currentScreen) {
        Screen.Home -> stringResource(R.string.title_home)
        Screen.Browser -> viewModel.sessionStack.lastOrNull()?.inspection?.displayName ?: stringResource(R.string.title_browser)
        Screen.Detail -> viewModel.selectedNode?.name ?: stringResource(R.string.title_details)
        Screen.Preview -> viewModel.previewState?.title ?: stringResource(R.string.title_preview)
        Screen.Settings -> stringResource(R.string.title_settings)
    }

    Scaffold(
        topBar = {
            TopBar(
                title = title,
                showBack = viewModel.currentScreen != Screen.Home,
                onBack = viewModel::goBack,
                onHome = { viewModel.navigate(Screen.Home) },
                onSettings = { viewModel.navigate(Screen.Settings) },
            )
        },
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                StatusBanner(viewModel.message, onDismiss = viewModel::clearMessage)
                ExportStatusCard(exportState, onCancel = viewModel::cancelExport)
                when (viewModel.currentScreen) {
                    Screen.Home -> HomeScreen(viewModel, onPickFile, onPickInputTree, onPickTree)
                    Screen.Browser -> BrowserScreen(viewModel)
                    Screen.Detail -> DetailScreen(viewModel)
                    Screen.Preview -> PreviewScreen(viewModel)
                    Screen.Settings -> SettingsScreen(viewModel)
                }
                if (viewModel.isBusy) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onSettings: () -> Unit,
) {
    CenterAlignedTopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (showBack) {
                IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        actions = {
            IconButton(onClick = onHome) {
                Icon(Icons.Default.Home, contentDescription = stringResource(R.string.action_home))
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.action_settings))
            }
        },
    )
}

@Composable
private fun HomeScreen(
    viewModel: MainViewModel,
    onPickFile: () -> Unit,
    onPickInputTree: () -> Unit,
    onPickTree: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.home_intro_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.home_intro_body))
                    Button(onClick = onPickFile) { Text(stringResource(R.string.button_pick_input_file)) }
                    OutlinedButton(onClick = onPickInputTree) { Text(stringResource(R.string.button_pick_input_directory)) }
                    OutlinedButton(onClick = onPickTree) { Text(stringResource(R.string.button_pick_output_directory)) }
                    OutlinedButton(onClick = viewModel::reopenLastInput, enabled = viewModel.lastInputUri != null) { Text(stringResource(R.string.button_reopen_last_input)) }
                }
            }
        }
        item {
            SummaryCard(stringResource(R.string.label_input_file), viewModel.lastInputUri?.toString() ?: stringResource(R.string.label_not_selected))
        }
        item {
            SummaryCard(stringResource(R.string.label_output_directory), viewModel.outputTreeUri?.toString() ?: stringResource(R.string.label_not_selected))
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.label_support_scope))
                    Text(stringResource(R.string.support_pfs_hfs_exefs))
                    Text(stringResource(R.string.support_input_directory))
                    Text(stringResource(R.string.support_metadata))
                    Text(stringResource(R.string.support_media_preview))
                    Text(stringResource(R.string.support_deferred_types))
                }
            }
        }
    }
}

@Composable
private fun BrowserScreen(viewModel: MainViewModel) {
    val session = viewModel.sessionStack.lastOrNull() ?: return
    val visibleNodes = remember(session, viewModel.searchQuery, viewModel.sortMode, viewModel.expandedPaths) {
        flattenNodes(
            nodes = filterAndSort(session.inspection.entries, viewModel.searchQuery, viewModel.sortMode),
            expandedPaths = viewModel.expandedPaths,
        )
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.padding(16.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${session.inspection.detection.kind} · ${session.inspection.supportStatus}")
                session.inspection.metadata.take(6).forEach { Text("${it.label}: ${it.value}") }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = viewModel.searchQuery,
                onValueChange = viewModel::updateSearchQuery,
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.label_search_path)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {}),
                singleLine = true,
            )
            SortMenu(selected = viewModel.sortMode, onSelected = viewModel::updateSortMode)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(visibleNodes, key = { it.node.path }) { row ->
                BrowserNodeRow(
                    row = row,
                    onClick = { viewModel.openNode(row.node) },
                )
            }
        }
    }
}

@Composable
private fun DetailScreen(viewModel: MainViewModel) {
    val node = viewModel.selectedNode ?: return
    val inspection = viewModel.selectedInspection
    val detection = inspection?.detection ?: node.detection
    val canPreview = detection.kind in setOf(SwitchFileKind.IMAGE, SwitchFileKind.AUDIO, SwitchFileKind.VIDEO)
    val canBrowse = detection.kind in setOf(SwitchFileKind.PFS0, SwitchFileKind.NSP, SwitchFileKind.HFS0, SwitchFileKind.XCI, SwitchFileKind.EXEFS)
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SummaryCard(stringResource(R.string.label_path), node.path)
        SummaryCard(stringResource(R.string.label_size), node.size.toString())
        SummaryCard(stringResource(R.string.label_offset), "0x${node.offset.toString(16)}")
        inspection?.warnings?.forEach { WarningCard(it) }
        Card {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.label_details), style = MaterialTheme.typography.titleMedium)
                (inspection?.metadata ?: listOf(MetadataField("Status", stringResource(R.string.label_metadata_loading)))).forEach { field ->
                    Text("${field.label}: ${field.value}")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canBrowse) {
            Button(onClick = viewModel::browseSelectedNode) { Text(stringResource(R.string.button_browse_archive)) }
            }
            if (canPreview) {
            OutlinedButton(onClick = viewModel::preparePreview) { Text(stringResource(R.string.button_media_preview)) }
            }
            OutlinedButton(onClick = viewModel::exportSelectedNode) { Text(stringResource(R.string.button_export_raw)) }
        }
    }
}

@Composable
private fun PreviewScreen(viewModel: MainViewModel) {
    val preview = viewModel.previewState ?: return
    when (preview.detection.kind) {
        SwitchFileKind.IMAGE -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AsyncImage(model = preview.uri, contentDescription = preview.title, modifier = Modifier.fillMaxWidth().padding(16.dp))
            }
        }
        SwitchFileKind.AUDIO,
        SwitchFileKind.VIDEO,
        -> MediaPreview(preview.uri)
        else -> Column(modifier = Modifier.padding(16.dp)) { Text(stringResource(R.string.preview_unavailable)) }
    }
}

@Composable
private fun MediaPreview(uri: Uri) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
            }
        },
    )
}

@Composable
private fun SettingsScreen(viewModel: MainViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.legal_boundary_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.legal_boundary_1))
                    Text(stringResource(R.string.legal_boundary_2))
                    Text(stringResource(R.string.legal_boundary_3))
                }
            }
        }
        item {
            Text(stringResource(R.string.persisted_uri_title), style = MaterialTheme.typography.titleMedium)
        }
        items(viewModel.persistedUris, key = { it.toString() }) { uri ->
            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(uri.toString())
                    OutlinedButton(onClick = { viewModel.releasePersistedUri(uri) }) {
                        Text(stringResource(R.string.button_release_permission))
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.future_work_title))
                    Text(stringResource(R.string.future_romfs))
                    Text(stringResource(R.string.future_key_provider))
                    Text(stringResource(R.string.future_codecs))
                    Text(stringResource(R.string.future_workers))
                }
            }
        }
    }
}

@Composable
private fun BrowserNodeRow(row: VisibleNodeRow, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.width((row.depth * 16).dp))
            Icon(iconFor(row.node), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(row.node.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (row.node.isDirectory) row.node.path else "${row.node.detection.kind} · ${row.node.size} bytes · 0x${row.node.offset.toString(16)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SortMenu(selected: SortMode, onSelected: (SortMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(stringResource(R.string.label_sort, selected.name)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.name) },
                    onClick = {
                        expanded = false
                        onSelected(mode)
                    },
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(label: String, value: String) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value)
        }
    }
}

@Composable
private fun WarningCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text)
        }
    }
}

@Composable
private fun StatusBanner(message: String?, onDismiss: () -> Unit) {
    if (message == null) return
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Info, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(message, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_dismiss))
            }
        }
    }
}

@Composable
private fun ExportStatusCard(state: ExportState, onCancel: () -> Unit) {
    if (!state.running && state.outputUri == null && state.error == null && state.logLines.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.title ?: stringResource(R.string.label_export))
            if (state.running) {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.label_progress_bytes, (state.progress * 100).roundToInt(), state.copiedBytes, state.totalBytes))
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.button_cancel_export)) }
            }
            state.outputUri?.let { Text(stringResource(R.string.label_output_uri, it.toString())) }
            state.error?.let { Text(stringResource(R.string.label_error, it), color = MaterialTheme.colorScheme.error) }
            if (state.cancelled) {
                Text(stringResource(R.string.label_task_cancelled), color = MaterialTheme.colorScheme.error)
            }
            state.logLines.takeLast(6).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

private data class VisibleNodeRow(val node: VirtualNode, val depth: Int)

private fun flattenNodes(nodes: List<VirtualNode>, expandedPaths: Set<String>, depth: Int = 0): List<VisibleNodeRow> = buildList {
    nodes.forEach { node ->
        add(VisibleNodeRow(node, depth))
        if (node.isDirectory && node.path in expandedPaths) {
            addAll(flattenNodes(node.children, expandedPaths, depth + 1))
        }
    }
}

private fun filterAndSort(nodes: List<VirtualNode>, query: String, sortMode: SortMode): List<VirtualNode> {
    val filtered = nodes.mapNotNull { node ->
        if (node.isDirectory) {
            val children = filterAndSort(node.children, query, sortMode)
            val matchesSelf = query.isBlank() || node.path.contains(query, ignoreCase = true)
            if (children.isNotEmpty() || matchesSelf) node.copy(children = children) else null
        } else {
            if (query.isBlank() || node.path.contains(query, ignoreCase = true)) node else null
        }
    }
    val comparator = when (sortMode) {
        SortMode.NAME -> compareBy<VirtualNode> { !it.isDirectory }.thenBy { it.name.lowercase() }
        SortMode.SIZE -> compareBy<VirtualNode> { !it.isDirectory }.thenByDescending { it.size }.thenBy { it.name.lowercase() }
        SortMode.OFFSET -> compareBy<VirtualNode> { !it.isDirectory }.thenBy { it.offset }.thenBy { it.name.lowercase() }
    }
    return filtered.sortedWith(comparator).map { node ->
        if (node.isDirectory) node.copy(children = filterAndSort(node.children, query = "", sortMode = sortMode)) else node
    }
}

@Composable
private fun iconFor(node: VirtualNode) = when {
    node.isDirectory -> Icons.Default.Folder
    node.detection.kind == SwitchFileKind.IMAGE -> Icons.Default.Description
    node.detection.kind == SwitchFileKind.AUDIO -> Icons.Default.MusicNote
    node.detection.kind == SwitchFileKind.VIDEO -> Icons.Default.Movie
    node.detection.kind in setOf(SwitchFileKind.PFS0, SwitchFileKind.NSP, SwitchFileKind.HFS0, SwitchFileKind.XCI, SwitchFileKind.EXEFS) -> Icons.Default.Folder
    node.detection.kind in setOf(SwitchFileKind.NCA, SwitchFileKind.NCZ) -> Icons.Default.Warning
    else -> Icons.Default.Description
}
