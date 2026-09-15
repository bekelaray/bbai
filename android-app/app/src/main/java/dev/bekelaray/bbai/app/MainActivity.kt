package dev.bekelaray.bbai.app

import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
                val openTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    uri?.let { viewModel.onOutputPicked(it) }
                }
                App(viewModel, onPickFile = { openFile.launch(arrayOf("*/*")) }, onPickTree = { openTree.launch(null) })
            }
        }
    }
}

private const val PREFS_NAME = "bbai_prefs"
private const val PREF_LAST_INPUT = "last_input"
private const val PREF_LAST_OUTPUT = "last_output"
private const val PREVIEW_CACHE_LIMIT = 128L * 1024L * 1024L
private const val BUFFER_SIZE = 128 * 1024

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
        persistUri(uri)
        lastInputUri = uri
        prefs.edit().putString(PREF_LAST_INPUT, uri.toString()).apply()
        openRoot(uri)
    }

    fun onOutputPicked(uri: Uri) {
        persistUri(uri)
        outputTreeUri = uri
        prefs.edit().putString(PREF_LAST_OUTPUT, uri.toString()).apply()
        persistedUris = loadPersistedUris()
        message = "Output directory saved."
    }

    fun reopenLastInput() {
        lastInputUri?.let(::openRoot) ?: run { message = "No remembered input file." }
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
        if (detectionKind !in setOf(SwitchFileKind.PFS0, SwitchFileKind.NSP, SwitchFileKind.HFS0, SwitchFileKind.XCI)) {
            message = "This file is not a readable archive in the current build."
            return
        }
        val factory = SliceReaderFactory(current.factory, node.offset, node.size, node.name)
        inspectAndPush(factory)
    }

    fun preparePreview() {
        val node = selectedNode ?: return
        val current = sessionStack.lastOrNull() ?: return
        val detection = selectedInspection?.detection ?: node.detection
        if (detection.kind !in setOf(SwitchFileKind.IMAGE, SwitchFileKind.AUDIO, SwitchFileKind.VIDEO)) {
            message = "Preview is only available for supported media files."
            return
        }
        val sourceFactory = SliceReaderFactory(current.factory, node.offset, node.size, node.name)
        viewModelScope.launch(Dispatchers.IO) {
            isBusy = true
            try {
                if (node.size > PREVIEW_CACHE_LIMIT) {
                    message = "Preview fallback: file is large, export it instead."
                    return@launch
                }
                val cached = materializeToCache(sourceFactory, node.path)
                previewState = PreviewState(node.name, detection, Uri.fromFile(cached), node.size)
                currentScreen = Screen.Preview
            } catch (error: Exception) {
                message = "Preview failed: ${error.message ?: "unknown error"}"
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
            message = "Pick an output directory first."
            return
        }
        exportJob?.cancel()
        exportJob = viewModelScope.launch(Dispatchers.IO) {
            val detection = selectedInspection?.detection ?: node.detection
            _exportState.value = ExportState(
                running = true,
                title = node.name,
                progress = 0f,
                totalBytes = node.size,
                logLines = listOf(logLine("Export started: ${node.path}")),
            )
            try {
                val file = ensureOutputFile(targetTree, node.path, detection.mimeType)
                val factory = SliceReaderFactory(current.factory, node.offset, node.size, node.name)
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
                        logLines = _exportState.value.logLines + logLine("Export cancelled."),
                    )
                } else {
                    _exportState.value = _exportState.value.copy(
                        running = false,
                        error = cancelled.message ?: "unknown error",
                        logLines = _exportState.value.logLines + logLine("Export failed: ${cancelled.message}"),
                    )
                }
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
    }

    fun releasePersistedUri(uri: Uri) {
        runCatching {
            app.contentResolver.releasePersistableUriPermission(
                uri,
                IntentFlags,
            )
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

    private fun openRoot(uri: Uri) {
        val document = DocumentFile.fromSingleUri(app, uri)
        val name = document?.name ?: uri.lastPathSegment ?: "selected-file"
        val size = document?.length()?.takeIf { it >= 0 } ?: 0L
        val factory = UriReaderFactory(app, uri, name, size)
        inspectAndReplace(factory)
    }

    private fun inspectAndReplace(factory: ReaderFactory) {
        viewModelScope.launch(Dispatchers.IO) {
            isBusy = true
            try {
                val inspection = inspect(factory)
                sessionStack = listOf(BrowserSession(factory.displayName, factory, inspection))
                expandedPaths = inspection.entries.map { it.path }.toSet()
                searchQuery = ""
                sortMode = SortMode.NAME
                currentScreen = Screen.Browser
            } catch (error: Exception) {
                message = "Open failed: ${error.message ?: "unknown error"}"
            } finally {
                isBusy = false
            }
        }
    }

    private fun inspectAndPush(factory: ReaderFactory) {
        viewModelScope.launch(Dispatchers.IO) {
            isBusy = true
            try {
                val inspection = inspect(factory)
                sessionStack = sessionStack + BrowserSession(factory.displayName, factory, inspection)
                expandedPaths = expandedPaths + inspection.entries.map { it.path }
                currentScreen = Screen.Browser
            } catch (error: Exception) {
                message = "Browse failed: ${error.message ?: "unknown error"}"
            } finally {
                isBusy = false
            }
        }
    }

    private fun loadDetail(node: VirtualNode) {
        val current = sessionStack.lastOrNull() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            isBusy = true
            try {
                val factory = SliceReaderFactory(current.factory, node.offset, node.size, node.name)
                selectedInspection = inspect(factory)
            } catch (error: Exception) {
                selectedInspection = InspectionResult(
                    displayName = node.name,
                    size = node.size,
                    detection = node.detection,
                    supportStatus = SupportStatus.INVALID,
                    metadata = listOf(MetadataField("Status", error.message ?: "Unable to inspect entry.")),
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

    private suspend fun materializeToCache(factory: ReaderFactory, relativePath: String): File {
        val sanitized = sanitizeRelativePath(relativePath)
        val target = File(app.cacheDir, sanitized.replace('/', '_'))
        target.parentFile?.mkdirs()
        val reader = factory.openReader()
        try {
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
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
            app.contentResolver.openOutputStream(targetUri, "wt")?.use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
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
            } ?: error("Unable to open output stream.")
        } finally {
            reader.close()
        }
    }

    private fun ensureOutputFile(treeUri: Uri, relativePath: String, mimeType: String?): DocumentFile {
        val tree = DocumentFile.fromTreeUri(app, treeUri) ?: error("Output directory is unavailable.")
        val cleanPath = sanitizeRelativePath(relativePath)
        val parts = cleanPath.split('/')
        var current = tree
        parts.dropLast(1).forEach { folder ->
            current = current.findFile(folder) ?: current.createDirectory(folder) ?: error("Failed to create $folder")
        }
        val filename = parts.last()
        current.findFile(filename)?.delete()
        return current.createFile(mimeType ?: "application/octet-stream", filename)
            ?: error("Failed to create output file.")
    }

    private fun persistUri(uri: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, IntentFlags)
        }
        persistedUris = loadPersistedUris()
    }

    private fun loadPersistedUris(): List<Uri> =
        app.contentResolver.persistedUriPermissions.map { it.uri }.sortedBy { it.toString() }

    private fun sanitizeRelativePath(value: String): String =
        value.split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/") { segment -> segment.replace(Regex("[^A-Za-z0-9._ -]"), "_") }
            .ifBlank { "export.bin" }

    private fun logLine(message: String): String = "${Instant.now()}  $message"

    companion object {
        private const val IntentFlags =
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

private data class BrowserSession(
    val label: String,
    val factory: ReaderFactory,
    val inspection: InspectionResult,
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
    override suspend fun openReader(): RandomAccessReader = SliceReadSource(parent.openReader(), baseOffset, length)
}

private class AndroidUriReader(
    application: Application,
    uri: Uri,
    sizeHint: Long,
) : RandomAccessReader {
    private val descriptor = application.contentResolver.openFileDescriptor(uri, "r") ?: error("Unable to open file descriptor.")
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
private fun App(viewModel: MainViewModel, onPickFile: () -> Unit, onPickTree: () -> Unit) {
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val title = when (viewModel.currentScreen) {
        Screen.Home -> "BBAI Switch Reader"
        Screen.Browser -> viewModel.sessionStack.lastOrNull()?.inspection?.displayName ?: "Browser"
        Screen.Detail -> viewModel.selectedNode?.name ?: "Details"
        Screen.Preview -> viewModel.previewState?.title ?: "Preview"
        Screen.Settings -> "Settings"
    }

    LaunchedEffect(viewModel.message) {
        // State is rendered inline; nothing else required.
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
                    Screen.Home -> HomeScreen(viewModel, onPickFile, onPickTree)
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        },
        actions = {
            IconButton(onClick = onHome) {
                Icon(Icons.Default.Home, contentDescription = "Home")
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        },
    )
}

@Composable
private fun HomeScreen(viewModel: MainViewModel, onPickFile: () -> Unit, onPickTree: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("只读浏览合法拥有的 Switch 容器与特殊文件", style = MaterialTheme.typography.titleMedium)
                    Text("当前版本优先选择最稳、最快、最适合解包的方案：纯 Kotlin + SAF + 流式导出，不内置密钥、不联网、不执行内容。")
                    Button(onClick = onPickFile) { Text("选择输入文件") }
                    OutlinedButton(onClick = onPickTree) { Text("选择输出目录") }
                    OutlinedButton(onClick = viewModel::reopenLastInput, enabled = viewModel.lastInputUri != null) { Text("重新打开上次文件") }
                }
            }
        }
        item {
            SummaryCard("输入文件", viewModel.lastInputUri?.toString() ?: "未选择")
        }
        item {
            SummaryCard("输出目录", viewModel.outputTreeUri?.toString() ?: "未选择")
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("支持范围")
                    Text("• 真实目录读取：PFS0/NSP、HFS0/XCI 根分区")
                    Text("• 基础元数据：CNMT、NACP、NPDM、NRO、NSO、KIP")
                    Text("• 媒体预览：PNG/JPEG/WebP、MP3/WAV/OGG/FLAC、MP4/WebM（可预览时预览，否则导出回退）")
                    Text("• NCA/NCZ、RomFS、ExeFS 目前仅识别和显示不支持/需合法密钥状态")
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
                label = { Text("搜索路径") },
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
    val canBrowse = detection.kind in setOf(SwitchFileKind.PFS0, SwitchFileKind.NSP, SwitchFileKind.HFS0, SwitchFileKind.XCI)
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SummaryCard("路径", node.path)
        SummaryCard("大小", node.size.toString())
        SummaryCard("偏移", "0x${node.offset.toString(16)}")
        inspection?.warnings?.forEach { WarningCard(it) }
        Card {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("详情", style = MaterialTheme.typography.titleMedium)
                (inspection?.metadata ?: listOf(MetadataField("Status", "Loading…"))).forEach { field ->
                    Text("${field.label}: ${field.value}")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canBrowse) {
                Button(onClick = viewModel::browseSelectedNode) { Text("浏览容器") }
            }
            if (canPreview) {
                OutlinedButton(onClick = viewModel::preparePreview) { Text("媒体预览") }
            }
            OutlinedButton(onClick = viewModel::exportSelectedNode) { Text("导出原文件") }
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
        else -> Column(modifier = Modifier.padding(16.dp)) {
            Text("Preview unavailable. Export the raw file instead.")
        }
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
                    Text("合法使用边界", style = MaterialTheme.typography.titleMedium)
                    Text("• 不内置、提取、下载或绕过任何密钥/DRM")
                    Text("• 不联网，不自动寻找密钥，不执行导出内容")
                    Text("• 对加密或未实现格式仅显示状态并保留后续合法接口边界")
                }
            }
        }
        item {
            Text("已持久化的 SAF URI", style = MaterialTheme.typography.titleMedium)
        }
        items(viewModel.persistedUris, key = { it.toString() }) { uri ->
            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(uri.toString())
                    OutlinedButton(onClick = { viewModel.releasePersistedUri(uri) }) {
                        Text("释放权限")
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("后续建议补充")
                    Text("• RomFS/ExeFS 真实浏览")
                    Text("• 合法密钥接口注入与仅在用户提供密钥时的受控解密")
                    Text("• Switch 专有纹理/音频容器解码")
                    Text("• 更持久的后台任务（如 WorkManager）")
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
        OutlinedButton(onClick = { expanded = true }) { Text("排序: ${selected.name}") }
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
                Icon(Icons.Default.Close, contentDescription = "Dismiss")
            }
        }
    }
}

@Composable
private fun ExportStatusCard(state: ExportState, onCancel: () -> Unit) {
    if (!state.running && state.outputUri == null && state.error == null && state.logLines.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.title ?: "Export")
            if (state.running) {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                Text("${(state.progress * 100).roundToInt()}% · ${state.copiedBytes}/${state.totalBytes} bytes")
                OutlinedButton(onClick = onCancel) { Text("取消导出") }
            }
            state.outputUri?.let { Text("输出: $it") }
            state.error?.let { Text("错误: $it", color = MaterialTheme.colorScheme.error) }
            if (state.cancelled) {
                Text("任务已取消", color = MaterialTheme.colorScheme.error)
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
    node.detection.kind in setOf(SwitchFileKind.PFS0, SwitchFileKind.NSP, SwitchFileKind.HFS0, SwitchFileKind.XCI) -> Icons.Default.Folder
    node.detection.kind in setOf(SwitchFileKind.NCA, SwitchFileKind.NCZ) -> Icons.Default.Warning
    else -> Icons.Default.Description
}
