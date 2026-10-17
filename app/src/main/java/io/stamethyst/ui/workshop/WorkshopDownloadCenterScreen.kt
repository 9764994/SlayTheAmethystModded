package io.stamethyst.ui.workshop

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import io.stamethyst.ui.LauncherTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.workshop.WorkshopDownloadBlocklist
import io.stamethyst.backend.workshop.WorkshopDownloadLogService
import io.stamethyst.backend.workshop.WorkshopDownloadTaskStatus
import io.stamethyst.backend.workshop.isActiveDownload
import io.stamethyst.ui.Icons
import io.stamethyst.ui.LoadingSkeletonBlock
import io.stamethyst.ui.RemoteBitmapCacheStore
import io.stamethyst.ui.icon.ArrowBack
import io.stamethyst.ui.rememberLoadingSkeletonStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext


@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun WorkshopDownloadCenterScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onPause: (WorkshopDownloadTaskUi) -> Unit = {},
    onResume: (WorkshopDownloadTaskUi) -> Unit = {},
    onCancel: (WorkshopDownloadTaskUi) -> Unit = {},
    onRetry: (WorkshopDownloadTaskUi) -> Unit = {},
) {
    val activity = LocalActivity.current
    val tasks = WorkshopDownloadCenterStore.tasks
    var selectedTaskId by remember { mutableStateOf<ULong?>(null) }
    var pendingExportTaskId by remember { mutableStateOf<ULong?>(null) }
    val exportLogLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val taskId = pendingExportTaskId
        pendingExportTaskId = null
        val task = taskId?.let(WorkshopDownloadCenterStore::find) ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            runCatching { WorkshopDownloadLogService.exportLog(requireNotNull(activity), task.toRecord(), uri) }
                .onSuccess { Toast.makeText(activity, activity?.getString(R.string.workshop_download_log_exported), Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(activity, activity?.getString(R.string.workshop_download_log_export_failed, it.message ?: it.javaClass.simpleName), Toast.LENGTH_LONG).show() }
        }
    }

    LaunchedEffect(activity) {
        val context = activity ?: return@LaunchedEffect
        while (true) {
            val loadedTasks = withContext(Dispatchers.IO) {
                WorkshopDownloadCenterStore.loadTasksWithRecovery(context)
            }
            WorkshopDownloadCenterStore.replaceInMemory(loadedTasks)
            delay(WORKSHOP_DOWNLOAD_PROGRESS_REFRESH_INTERVAL_MS)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            LauncherTopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.workshop_download_center_title))
                        Text(
                            text = stringResource(R.string.workshop_download_center_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (tasks.isEmpty()) {
                item { EmptyDownloadCenterCard(onBack = onBack) }
            } else {
                items(tasks, key = { it.publishedFileId.toString() }) { task ->
                    DownloadTaskCard(
                        task = task,
                        onClick = { selectedTaskId = task.publishedFileId },
                        onPause = { onPause(task) },
                        onResume = { onResume(task) },
                        onCancel = { onCancel(task) },
                        onRetry = { onRetry(task) },
                    )
                }
            }
        }
    }

    selectedTaskId?.let { taskId ->
        val task = tasks.firstOrNull { it.publishedFileId == taskId }
        if (task == null) {
            selectedTaskId = null
            return@let
        }
        DownloadTaskDetailDialog(
            task = task,
            onDismiss = { selectedTaskId = null },
            onPause = { onPause(task) },
            onResume = { onResume(task) },
            onCancel = {
                onCancel(task)
                selectedTaskId = null
            },
            onRetry = { onRetry(task) },
            onExportLog = {
                pendingExportTaskId = task.publishedFileId
                exportLogLauncher.launch(WorkshopDownloadLogService.fileName(task.toRecord()))
            },
            onShareLog = { activity?.shareDownloadLog(task) },
        )
    }
}

@Composable
private fun DownloadTaskCard(
    task: WorkshopDownloadTaskUi,
    onClick: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClick = onClick),
        colors = when (task.status) {
            WorkshopDownloadTaskStatus.Failed -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            WorkshopDownloadTaskStatus.Completed -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            else -> CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WorkshopDownloadPreviewImage(
                    url = task.previewUrl,
                    contentDescription = stringResource(R.string.workshop_preview_content_description, task.title),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = task.progressSummary(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DownloadProgressIndicator(task = task)
                    Text(
                        text = task.message.ifBlank { task.status.defaultMessage() },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DownloadTaskActions(
                task = task,
                onPause = onPause,
                onResume = onResume,
                onCancel = onCancel,
                onRetry = onRetry,
                compact = true,
            )
        }
    }
}

@Composable
private fun DownloadTaskActions(
    task: WorkshopDownloadTaskUi,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    compact: Boolean,
) {
    if (task.status == WorkshopDownloadTaskStatus.Completed) return
    if (WorkshopDownloadBlocklist.isBlocked(task.publishedFileId)) {
        Row(
            modifier = if (compact) Modifier.fillMaxWidth() else Modifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                enabled = task.status != WorkshopDownloadTaskStatus.Cancelling,
                onClick = onCancel,
            ) { Text(stringResource(R.string.main_folder_dialog_cancel)) }
        }
        return
    }
    Row(
        modifier = if (compact) Modifier.fillMaxWidth() else Modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (compact) {
            when (task.status) {
                WorkshopDownloadTaskStatus.Queued,
                WorkshopDownloadTaskStatus.Resolving,
                WorkshopDownloadTaskStatus.Downloading -> OutlinedButton(onClick = onPause) { Text(stringResource(R.string.workshop_action_pause)) }
                WorkshopDownloadTaskStatus.Pausing -> OutlinedButton(enabled = false, onClick = {}) { Text(stringResource(R.string.workshop_action_pausing)) }
                WorkshopDownloadTaskStatus.Cancelling -> OutlinedButton(enabled = false, onClick = {}) { Text(stringResource(R.string.workshop_action_cancelling)) }
                WorkshopDownloadTaskStatus.Paused -> OutlinedButton(onClick = onResume) { Text(stringResource(R.string.workshop_action_continue)) }
                WorkshopDownloadTaskStatus.Failed,
                WorkshopDownloadTaskStatus.Cancelled -> OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.workshop_action_retry)) }
                WorkshopDownloadTaskStatus.Completed -> Unit
            }
            TextButton(
                enabled = task.status != WorkshopDownloadTaskStatus.Cancelling,
                onClick = onCancel,
            ) { Text(stringResource(R.string.main_folder_dialog_cancel)) }
            return@Row
        }

        when (task.status) {
            WorkshopDownloadTaskStatus.Queued,
            WorkshopDownloadTaskStatus.Resolving,
            WorkshopDownloadTaskStatus.Downloading -> DownloadDetailIconButton(
                iconResId = R.drawable.ic_pause,
                contentDescription = stringResource(R.string.workshop_action_pause),
                onClick = onPause,
            )
            WorkshopDownloadTaskStatus.Pausing -> DownloadDetailIconButton(
                iconResId = R.drawable.ic_pause,
                contentDescription = stringResource(R.string.workshop_action_pausing),
                enabled = false,
                onClick = {},
            )
            WorkshopDownloadTaskStatus.Cancelling -> DownloadDetailIconButton(
                iconResId = R.drawable.ic_delete,
                contentDescription = stringResource(R.string.workshop_action_cancelling),
                enabled = false,
                onClick = {},
            )
            WorkshopDownloadTaskStatus.Paused -> DownloadDetailIconButton(
                iconResId = R.drawable.ic_play_arrow,
                contentDescription = stringResource(R.string.workshop_action_continue),
                onClick = onResume,
            )
            WorkshopDownloadTaskStatus.Failed,
            WorkshopDownloadTaskStatus.Cancelled -> DownloadDetailIconButton(
                iconResId = R.drawable.ic_workshop_retry,
                contentDescription = stringResource(R.string.workshop_action_retry),
                onClick = onRetry,
            )
            WorkshopDownloadTaskStatus.Completed -> Unit
        }
        DownloadDetailIconButton(
            iconResId = R.drawable.ic_delete,
            contentDescription = stringResource(R.string.workshop_action_cancel_task),
            enabled = task.status != WorkshopDownloadTaskStatus.Cancelling,
            onClick = onCancel,
        )
    }
}

@Composable
private fun DownloadDetailIconButton(
    iconResId: Int,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
    ) {
        Icon(
            painter = painterResource(iconResId),
            contentDescription = contentDescription,
        )
    }
}

@Composable
private fun DownloadProgressIndicator(task: WorkshopDownloadTaskUi) {
    val percent = task.progressPercent
    val targetProgress = percent?.coerceIn(0, 100)?.div(100f)
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress ?: 0f,
        animationSpec = tween(
            durationMillis = DOWNLOAD_PROGRESS_ANIMATION_MS,
            easing = FastOutSlowInEasing,
        ),
        label = "workshopDownloadProgress",
    )
    when {
        targetProgress != null -> LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
        )
        task.status.isActiveDownload() -> LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
        )
        else -> Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun DownloadTaskDetailDialog(
    task: WorkshopDownloadTaskUi,
    onDismiss: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onExportLog: () -> Unit,
    onShareLog: () -> Unit,
) {
    var showLogActionDialog by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(task.title, modifier = Modifier.weight(1f))
                IconButton(onClick = { showLogActionDialog = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_log),
                        contentDescription = stringResource(R.string.workshop_download_log_content_description),
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    WorkshopDownloadPreviewImage(
                        url = task.previewUrl,
                        contentDescription = stringResource(R.string.workshop_preview_content_description, task.title),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(task.progressSummary(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                DownloadProgressIndicator(task = task)
                if (task.status == WorkshopDownloadTaskStatus.Paused) {
                    Text(
                        text = stringResource(R.string.workshop_resume_restart_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (task.status == WorkshopDownloadTaskStatus.Failed && task.errorMessage.isNotBlank()) {
                    DetailRow(label = stringResource(R.string.workshop_detail_error), value = task.errorMessage)
                    task.errorClass.takeIf(String::isNotBlank)?.let { DetailRow(label = stringResource(R.string.workshop_detail_type), value = it) }
                }
                DetailRow(label = "Workshop ID", value = task.publishedFileId.toString())
                task.authorName.takeIf(String::isNotBlank)?.let { DetailRow(label = stringResource(R.string.workshop_detail_author), value = it) }
                DetailRow(label = stringResource(R.string.workshop_detail_size), value = task.totalBytes?.let { formatBytes(it) } ?: formatBytes(task.fileSizeBytes))
                task.fileProgressText()?.let { DetailRow(label = stringResource(R.string.workshop_detail_file), value = it) }
                task.chunkProgressText()?.let { DetailRow(label = stringResource(R.string.workshop_detail_chunks), value = it) }
                task.description.takeIf(String::isNotBlank)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DownloadTaskActions(
                    task = task,
                    onPause = onPause,
                    onResume = onResume,
                    onCancel = onCancel,
                    onRetry = onRetry,
                    compact = false,
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_confirm)) }
            }
        },
    )
    if (showLogActionDialog) {
        AlertDialog(
            onDismissRequest = { showLogActionDialog = false },
            title = { Text(stringResource(R.string.workshop_download_center_title)) },
            text = { Text(stringResource(R.string.workshop_download_log_dialog_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showLogActionDialog = false
                    onExportLog()
                }) { Text(stringResource(R.string.workshop_action_export_log)) }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        showLogActionDialog = false
                        onShareLog()
                    }) { Text(stringResource(R.string.workshop_action_share_log)) }
                    TextButton(onClick = { showLogActionDialog = false }) { Text(stringResource(R.string.main_folder_dialog_cancel)) }
                }
            },
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WorkshopDownloadPreviewImage(url: String, contentDescription: String) {
    val context = LocalContext.current
    val imageState by produceState<DownloadPreviewImageState>(initialValue = DownloadPreviewImageState.Loading, key1 = url) {
        value = when {
            url.isBlank() -> DownloadPreviewImageState.Failed
            else -> withContext(Dispatchers.IO) {
                // Shared store keeps Steam preview hosts on the accelerated client;
                // the old private loader used a bare OkHttpClient with no timeouts.
                RemoteBitmapCacheStore.load(context.applicationContext, url)
                    ?.let(DownloadPreviewImageState::Loaded)
                    ?: DownloadPreviewImageState.Failed
            }
        }
    }
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CardDefaults.shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when (val current = imageState) {
            DownloadPreviewImageState.Loading -> LoadingSkeletonBlock(
                modifier = Modifier.fillMaxSize(),
                style = rememberLoadingSkeletonStyle("workshop_download_preview_image_skeleton"),
                shape = CardDefaults.shape,
            )
            DownloadPreviewImageState.Failed -> Text("MOD", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is DownloadPreviewImageState.Loaded -> Image(
                bitmap = current.bitmap.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun EmptyDownloadCenterCard(onBack: () -> Unit) {
    Card {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.workshop_download_empty_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = stringResource(R.string.workshop_download_empty_description),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.workshop_action_back_to_workshop)) }
        }
    }
}

private sealed interface DownloadPreviewImageState {
    data object Loading : DownloadPreviewImageState
    data object Failed : DownloadPreviewImageState
    data class Loaded(val bitmap: Bitmap) : DownloadPreviewImageState
}

@Composable
private fun WorkshopDownloadTaskStatus.defaultMessage(): String = when (this) {
    WorkshopDownloadTaskStatus.Queued -> stringResource(R.string.workshop_download_task_message_waiting)
    WorkshopDownloadTaskStatus.Resolving -> stringResource(R.string.workshop_download_task_message_resolving)
    WorkshopDownloadTaskStatus.Downloading -> stringResource(R.string.workshop_download_task_message_downloading)
    WorkshopDownloadTaskStatus.Pausing -> stringResource(R.string.workshop_download_task_message_pausing)
    WorkshopDownloadTaskStatus.Cancelling -> stringResource(R.string.workshop_download_task_message_cancelling)
    WorkshopDownloadTaskStatus.Paused -> stringResource(R.string.workshop_download_task_message_paused)
    WorkshopDownloadTaskStatus.Completed -> stringResource(R.string.workshop_download_task_message_completed)
    WorkshopDownloadTaskStatus.Failed -> stringResource(R.string.workshop_download_task_message_failed)
    WorkshopDownloadTaskStatus.Cancelled -> stringResource(R.string.workshop_download_task_message_cancelled)
}

@Composable
private fun WorkshopDownloadTaskUi.progressSummary(): String {
    val percentText = progressPercent?.let { "${it.coerceIn(0, 100)}%" }
    val bytesText = when {
        totalBytes != null && totalBytes > 0L -> "${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}"
        downloadedBytes > 0L -> formatBytes(downloadedBytes)
        fileSizeBytes > 0L -> formatBytes(fileSizeBytes)
        else -> stringResource(R.string.workshop_download_waiting_size)
    }
    val speedText = rememberDownloadSpeedText(this)
    return listOfNotNull(percentText, bytesText, speedText).joinToString(" · ")
}

@Composable
private fun rememberDownloadSpeedText(task: WorkshopDownloadTaskUi): String? {
    val speedBps = rememberDownloadSpeedBytesPerSecond(task) ?: return null
    return stringResource(R.string.workshop_download_speed_format, formatBytes(speedBps))
}

@Composable
private fun rememberDownloadSpeedBytesPerSecond(task: WorkshopDownloadTaskUi): Long? {
    val isMeasuring = task.status == WorkshopDownloadTaskStatus.Downloading ||
        task.status == WorkshopDownloadTaskStatus.Pausing
    val latestBytes by rememberUpdatedState(task.downloadedBytes)
    var speedBps by remember(task.publishedFileId) { mutableStateOf<Long?>(null) }

    LaunchedEffect(task.publishedFileId, isMeasuring) {
        if (!isMeasuring) {
            speedBps = null
            return@LaunchedEffect
        }

        var previousBytes = latestBytes
        var previousAtElapsedMs = SystemClock.elapsedRealtime()
        var lastProgressAtElapsedMs = previousAtElapsedMs
        var smoothedBps: Long? = null
        while (true) {
            delay(DOWNLOAD_SPEED_TICK_MS)
            val now = SystemClock.elapsedRealtime()
            val currentBytes = latestBytes
            val elapsedMs = now - previousAtElapsedMs
            if (elapsedMs < DOWNLOAD_SPEED_SAMPLE_MIN_MS) continue

            val deltaBytes = currentBytes - previousBytes
            if (deltaBytes < 0L) {
                previousBytes = currentBytes
                previousAtElapsedMs = now
                lastProgressAtElapsedMs = now
                smoothedBps = null
                speedBps = null
                continue
            }

            if (deltaBytes > 0L) {
                val instantBps = (deltaBytes * 1000L) / elapsedMs.coerceAtLeast(1L)
                smoothedBps = if (smoothedBps == null) {
                    instantBps
                } else {
                    ((smoothedBps * 3L) + instantBps) / 4L
                }
                lastProgressAtElapsedMs = now
            }
            if (now - lastProgressAtElapsedMs >= DOWNLOAD_SPEED_STALL_MS) {
                smoothedBps = 0L
            }
            speedBps = smoothedBps
            previousBytes = currentBytes
            previousAtElapsedMs = now
        }
    }

    return if (isMeasuring) speedBps else null
}

private fun WorkshopDownloadTaskUi.fileProgressText(): String? {
    val total = totalFiles ?: return null
    val completed = completedFiles ?: 0
    return "$completed / $total"
}

private fun WorkshopDownloadTaskUi.chunkProgressText(): String? {
    val total = totalChunks ?: return null
    val completed = completedChunks ?: 0
    return "$completed / $total"
}

@Composable
private fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return stringResource(R.string.workshop_unknown_value)
    if (bytes == 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return if (unitIndex == 0) {
        "$bytes ${units[unitIndex]}"
    } else {
        "${String.format(Locale.US, "%.1f", value)} ${units[unitIndex]}"
    }
}

private const val DOWNLOAD_PROGRESS_ANIMATION_MS = 450
private const val DOWNLOAD_SPEED_TICK_MS = 500L
private const val DOWNLOAD_SPEED_SAMPLE_MIN_MS = 350L
private const val DOWNLOAD_SPEED_STALL_MS = 2_000L

private fun Activity.shareDownloadLog(task: WorkshopDownloadTaskUi) {
    runCatching {
        val shareIntent = WorkshopDownloadLogService.prepareShareIntent(this, task.toRecord())
        startActivity(Intent.createChooser(shareIntent, getString(R.string.workshop_share_download_log_chooser_title)))
    }.onFailure {
        Toast.makeText(this, getString(R.string.workshop_share_download_log_failed, it.message ?: it.javaClass.simpleName), Toast.LENGTH_LONG).show()
    }
}
