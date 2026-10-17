package io.stamethyst.ui.feedback

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import io.stamethyst.ui.LauncherTopAppBar
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.getstream.chat.android.compose.ui.attachments.preview.MediaGalleryPreviewContract
import io.getstream.chat.android.compose.ui.components.messages.MessageBubble
import io.getstream.chat.android.compose.ui.theme.MediaGalleryConfig
import io.getstream.chat.android.compose.ui.theme.MediaGalleryOptionsConfig
import io.getstream.chat.android.models.Attachment
import io.getstream.chat.android.models.AttachmentType
import io.getstream.chat.android.models.Message
import io.getstream.chat.android.models.User
import io.getstream.chat.android.ui.common.images.resizing.StreamCdnImageResizing
import io.stamethyst.R
import io.stamethyst.backend.feedback.FeedbackAvatarCacheStore
import io.stamethyst.backend.feedback.FeedbackThreadAttachment
import io.stamethyst.backend.feedback.FeedbackThreadAuthorType
import io.stamethyst.backend.feedback.FeedbackThreadEvent
import io.stamethyst.backend.feedback.FeedbackThreadEventType
import io.stamethyst.navigation.currentNavigator
import io.stamethyst.ui.Icons
import io.stamethyst.ui.SimpleMarkdownContent
import io.stamethyst.ui.extractSimpleMarkdownImageUrls
import io.stamethyst.ui.icon.ArrowBack
import io.stamethyst.ui.icon.AttachFile
import io.stamethyst.ui.icon.Close
import io.stamethyst.ui.icon.Description
import io.stamethyst.ui.icon.Download
import io.stamethyst.ui.icon.Send
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherFeedbackConversationScreen(
    issueNumber: Long,
    modifier: Modifier = Modifier
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    val uriHandler = LocalUriHandler.current
    val mediaGalleryContract = remember {
        MediaGalleryPreviewContract(
            config = MediaGalleryConfig(
                isCloseVisible = true,
                isOptionsVisible = false,
                isShareVisible = false,
                isGalleryVisible = true,
                optionsConfig = MediaGalleryOptionsConfig(
                    isShowInChatVisible = false,
                    isReplyVisible = false,
                    isSaveMediaVisible = false,
                    isDeleteVisible = false
                )
            )
        )
    }
    val mediaGalleryLauncher = rememberLauncherForActivityResult(mediaGalleryContract) { }
    val viewModel: FeedbackConversationViewModel = viewModel(
        factory = FeedbackConversationViewModel.factory(issueNumber)
    )
    val uiState = viewModel.uiState
    val issueReportEvent = remember(uiState.issueNumber, uiState.events) {
        uiState.events.firstOrNull { event -> event.isIssueReportEvent(uiState.issueNumber) }
    }
    val conversationEvents = remember(uiState.issueNumber, uiState.events) {
        uiState.events.filterNot { event -> event.isIssueReportEvent(uiState.issueNumber) }
    }
    val listState = rememberLazyListState()
    var pendingStateAction by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.bind(activity)
    }

    val screenshotPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        viewModel.onScreenshotUrisPicked(activity, uris)
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                FeedbackConversationViewModel.Effect.OpenScreenshotPicker -> {
                    screenshotPicker.launch("image/*")
                }
            }
        }
    }
    LaunchedEffect(
        uiState.issueNumber,
        uiState.issueBody,
        issueReportEvent?.id,
        conversationEvents.size,
        conversationEvents.lastOrNull()?.id
    ) {
        listState.scrollToItem(2 + conversationEvents.size)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            LauncherTopAppBar(
                title = {
                    Text(
                        text = if (uiState.title.isBlank()) {
                            stringResource(R.string.feedback_issue_fallback_title, issueNumber)
                        } else {
                            uiState.title
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = navigator::goBack) {
                        Icon(
                            imageVector = Icons.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back)
                        )
                    }
                },
                actions = {
                    if (uiState.issueUrl.isNotBlank()) {
                        TextButton(onClick = { uriHandler.openUri(uiState.issueUrl) }) {
                            Text(stringResource(R.string.common_action_open_issue))
                        }
                    }
                    TextButton(onClick = { viewModel.onRefresh(activity) }) {
                        Text(stringResource(R.string.feedback_conversation_refresh))
                    }
                }
            )
        },
        bottomBar = {
            if (uiState.isFollowed) {
                FeedbackConversationComposer(
                    uiState = uiState,
                    onMessageChanged = viewModel::onMessageChanged,
                    onAddScreenshots = viewModel::onAddScreenshots,
                    onRemoveScreenshot = viewModel::onRemoveScreenshot,
                    onAttachLogsChanged = viewModel::onAttachLogsChanged,
                    onSendMessage = { viewModel.onSendMessage(activity) },
                    onRequestClose = {
                        pendingStateAction = if (uiState.isClosed) "open" else "closed"
                    }
                )
            } else {
                FeedbackConversationFollowBar(
                    uiState = uiState,
                    onFollowIssue = { viewModel.onFollowIssue(activity) }
                )
            }
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                if (uiState.busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    uiState.busyMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            item {
                FeedbackIssueSummaryBubble(
                    uiState = uiState,
                    reportEvent = issueReportEvent,
                    onOpenAttachment = { url -> uriHandler.openUri(url) },
                    onOpenImagePreview = { selectedImageUrl ->
                        val previewEvent = issueReportEvent
                            ?: buildFeedbackIssueReportFallbackEvent(uiState)
                        previewEvent
                            ?.let { event ->
                                buildFeedbackMediaGalleryInput(
                                    event = event,
                                    selectedImageUrl = selectedImageUrl
                                )
                            }
                            ?.let(mediaGalleryLauncher::launch)
                            ?: uriHandler.openUri(selectedImageUrl)
                    }
                )
            }

            items(conversationEvents, key = { it.id }) { event ->
                FeedbackConversationEventCard(
                    event = event,
                    onOpenAttachment = { url -> uriHandler.openUri(url) },
                    onOpenImagePreview = { previewEvent, selectedImageUrl ->
                        buildFeedbackMediaGalleryInput(
                            event = previewEvent,
                            selectedImageUrl = selectedImageUrl
                        )?.let(mediaGalleryLauncher::launch)
                            ?: uriHandler.openUri(selectedImageUrl)
                    }
                )
            }

            item(key = "feedback-conversation-bottom-anchor") {
                Box(modifier = Modifier.size(1.dp))
            }
        }
    }

    pendingStateAction?.let { targetState ->
        AlertDialog(
            onDismissRequest = { pendingStateAction = null },
            title = {
                Text(
                    stringResource(
                        if (targetState == "closed") {
                            R.string.feedback_conversation_close_title
                        } else {
                            R.string.feedback_conversation_reopen_title
                        }
                    )
                )
            },
            text = {
                Text(
                    stringResource(
                        if (targetState == "closed") {
                            R.string.feedback_conversation_close_confirm
                        } else {
                            R.string.feedback_conversation_reopen_confirm
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingStateAction = null
                        if (targetState == "closed") {
                            viewModel.onCloseIssue(activity)
                        } else {
                            viewModel.onReopenIssue(activity)
                        }
                    }
                ) {
                    Text(stringResource(R.string.common_action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingStateAction = null }) {
                    Text(stringResource(R.string.main_folder_dialog_cancel))
                }
            }
        )
    }
}

@Composable
private fun FeedbackIssueSummaryBubble(
    uiState: FeedbackConversationViewModel.UiState,
    reportEvent: FeedbackThreadEvent?,
    onOpenAttachment: (String) -> Unit,
    onOpenImagePreview: (String) -> Unit
) {
    val reportBody = reportEvent
        ?.body
        ?.takeIf(String::isNotBlank)
        ?: uiState.issueBody
    if (reportBody.isNotBlank() || !reportEvent?.attachments.isNullOrEmpty()) {
        FeedbackIssueReportCard(
            uiState = uiState,
            reportEvent = reportEvent,
            reportBody = reportBody,
            onOpenAttachment = onOpenAttachment,
            onOpenImagePreview = onOpenImagePreview
        )
    } else {
        FeedbackIssueStatusPill(uiState = uiState)
    }
}

@Composable
private fun FeedbackIssueStatusPill(
    uiState: FeedbackConversationViewModel.UiState
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        FeedbackIssueStatusChip(uiState = uiState)
    }
}

@Composable
private fun FeedbackIssueStatusChip(
    uiState: FeedbackConversationViewModel.UiState
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 1.dp
    ) {
        Text(
            text = stringResource(
                R.string.feedback_conversation_status_format,
                stringResource(
                    if (uiState.isClosed) {
                        R.string.feedback_conversation_state_closed
                    } else {
                        R.string.feedback_conversation_state_in_progress
                    }
                )
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun FeedbackIssueReportCard(
    uiState: FeedbackConversationViewModel.UiState,
    reportEvent: FeedbackThreadEvent?,
    reportBody: String,
    onOpenAttachment: (String) -> Unit,
    onOpenImagePreview: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.feedback_issue_report_card_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    reportEvent?.let { event ->
                        Text(
                            text = buildFeedbackIssueReportMeta(event),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                FeedbackIssueStatusChip(uiState = uiState)
            }
            if (reportBody.isNotBlank()) {
                SimpleMarkdownContent(
                    markdown = reportBody,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    codeContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    textSelectable = true,
                    imageShowOpenButton = false,
                    onImageClick = onOpenImagePreview
                )
            }
            reportEvent?.attachments.orEmpty().forEach { attachment ->
                FeedbackAttachmentBubble(
                    attachment = attachment,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    onOpenAttachment = onOpenAttachment,
                    onOpenImagePreview = onOpenImagePreview
                )
            }
        }
    }
}

@Composable
private fun FeedbackConversationFollowBar(
    uiState: FeedbackConversationViewModel.UiState,
    onFollowIssue: () -> Unit
) {
    Surface(shadowElevation = 6.dp, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onFollowIssue,
                enabled = !uiState.busy,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.feedback_follow_issue))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedbackConversationComposer(
    uiState: FeedbackConversationViewModel.UiState,
    onMessageChanged: (String) -> Unit,
    onAddScreenshots: () -> Unit,
    onRemoveScreenshot: (String) -> Unit,
    onAttachLogsChanged: (Boolean) -> Unit,
    onSendMessage: () -> Unit,
    onRequestClose: () -> Unit
) {
    val inputShape = RoundedCornerShape(24.dp)
    val canSend = !uiState.busy &&
        (uiState.messageText.isNotBlank() || uiState.screenshots.isNotEmpty())
    Surface(shadowElevation = 6.dp, tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (uiState.screenshots.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    uiState.screenshots.forEach { screenshot ->
                        FeedbackAttachmentPreview(
                            screenshot = screenshot,
                            enabled = !uiState.busy,
                            onRemoveScreenshot = onRemoveScreenshot
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = inputShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    TextField(
                        value = uiState.messageText,
                        onValueChange = onMessageChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp, max = 144.dp),
                        minLines = 1,
                        maxLines = 5,
                        enabled = !uiState.busy,
                        placeholder = {
                            Text(stringResource(R.string.feedback_conversation_message_placeholder))
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent
                        )
                    )
                }
                FilledIconButton(
                    onClick = onSendMessage,
                    enabled = canSend,
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(
                        imageVector = Icons.Send,
                        contentDescription = stringResource(R.string.feedback_conversation_send)
                    )
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AssistChip(
                    onClick = onAddScreenshots,
                    enabled = !uiState.busy && uiState.screenshots.size < 4,
                    modifier = Modifier.widthIn(max = 156.dp),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AttachFile,
                            contentDescription = null
                        )
                    },
                    label = {
                        Text(
                            text = stringResource(R.string.feedback_add_screenshot),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
                FilterChip(
                    selected = uiState.attachLogs,
                    onClick = { onAttachLogsChanged(!uiState.attachLogs) },
                    enabled = !uiState.busy,
                    modifier = Modifier.widthIn(max = 172.dp),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Description,
                            contentDescription = null
                        )
                    },
                    label = {
                        Text(
                            text = stringResource(R.string.feedback_conversation_attach_logs),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
                TextButton(
                    onClick = onRequestClose,
                    enabled = !uiState.busy,
                    modifier = Modifier.widthIn(min = 72.dp)
                ) {
                    Text(
                        text = stringResource(
                            if (uiState.isClosed) {
                                R.string.feedback_conversation_reopen_action
                            } else {
                                R.string.feedback_conversation_close_action
                            }
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun FeedbackAttachmentPreview(
    screenshot: FeedbackConversationViewModel.ScreenshotItem,
    enabled: Boolean,
    onRemoveScreenshot: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 6.dp, end = 4.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AttachFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = screenshot.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = screenshot.sizeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = { onRemoveScreenshot(screenshot.id) },
                enabled = enabled,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Close,
                    contentDescription = stringResource(R.string.feedback_remove)
                )
            }
        }
    }
}

@Composable
internal fun FeedbackConversationEventCard(
    event: FeedbackThreadEvent,
    onOpenAttachment: (String) -> Unit,
    onOpenImagePreview: (FeedbackThreadEvent, String) -> Unit
) {
    if (event.type == FeedbackThreadEventType.STATE_CHANGE) {
        FeedbackSystemEventPill(event = event)
        return
    }

    val isOutgoing = event.authorType == FeedbackThreadAuthorType.ME
    val containerColor = when (event.authorType) {
        FeedbackThreadAuthorType.ME -> MaterialTheme.colorScheme.primaryContainer
        FeedbackThreadAuthorType.DEVELOPER -> MaterialTheme.colorScheme.secondaryContainer
        FeedbackThreadAuthorType.OTHER -> MaterialTheme.colorScheme.surfaceContainerHigh
        FeedbackThreadAuthorType.SYSTEM -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = feedbackContentColorFor(containerColor)
    val bubbleShape = when {
        isOutgoing -> RoundedCornerShape(
            topStart = 18.dp,
            topEnd = 18.dp,
            bottomEnd = 6.dp,
            bottomStart = 18.dp
        )
        else -> RoundedCornerShape(
            topStart = 18.dp,
            topEnd = 18.dp,
            bottomEnd = 18.dp,
            bottomStart = 6.dp
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isOutgoing) {
            FeedbackAuthorAvatar(event = event)
        }
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = if (isOutgoing) {
                Alignment.CenterEnd
            } else {
                Alignment.CenterStart
            }
        ) {
            FeedbackMessageCluster(
                event = event,
                isOutgoing = isOutgoing,
                containerColor = containerColor,
                contentColor = contentColor,
                bubbleShape = bubbleShape,
                onOpenAttachment = onOpenAttachment,
                onOpenImagePreview = onOpenImagePreview
            )
        }
        if (isOutgoing) {
            FeedbackAuthorAvatar(event = event)
        }
    }
}

@Composable
private fun FeedbackMessageCluster(
    event: FeedbackThreadEvent,
    isOutgoing: Boolean,
    containerColor: Color,
    contentColor: Color,
    bubbleShape: RoundedCornerShape,
    onOpenAttachment: (String) -> Unit,
    onOpenImagePreview: (FeedbackThreadEvent, String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(0.84f),
        horizontalAlignment = if (isOutgoing) {
            Alignment.End
        } else {
            Alignment.Start
        },
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        FeedbackMessageAuthorLine(
            event = event,
            isOutgoing = isOutgoing
        )
        MessageBubble(
            color = containerColor,
            shape = bubbleShape
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (event.body.isNotBlank()) {
                        SimpleMarkdownContent(
                            markdown = event.body,
                            textColor = contentColor,
                            codeContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            textSelectable = true,
                            imageShowOpenButton = false,
                            onImageClick = { imageUrl ->
                                onOpenImagePreview(event, imageUrl)
                            }
                        )
                    }
                    event.attachments.forEach { attachment ->
                        FeedbackAttachmentBubble(
                            attachment = attachment,
                            contentColor = contentColor,
                            onOpenAttachment = onOpenAttachment,
                            onOpenImagePreview = { imageUrl ->
                                onOpenImagePreview(event, imageUrl)
                            }
                        )
                    }
                }
            }
        }
        FeedbackMessageTimestamp(
            event = event,
            isOutgoing = isOutgoing
        )
    }
}

@Composable
private fun FeedbackMessageAuthorLine(
    event: FeedbackThreadEvent,
    isOutgoing: Boolean
) {
    val metaText = event.authorDeviceLabel
        ?.takeIf(String::isNotBlank)
        ?.let { device -> "${event.authorLabel} · $device" }
        ?: event.authorLabel
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) {
            Arrangement.End
        } else {
            Arrangement.Start
        },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = metaText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (event.authorType == FeedbackThreadAuthorType.DEVELOPER) {
                FontWeight.SemiBold
            } else {
                FontWeight.Normal
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FeedbackMessageTimestamp(
    event: FeedbackThreadEvent,
    isOutgoing: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) {
            Arrangement.End
        } else {
            Arrangement.Start
        }
    ) {
        Text(
            text = formatFeedbackEventTime(event.createdAtMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
            maxLines = 1
        )
    }
}

@Composable
private fun FeedbackAttachmentBubble(
    attachment: FeedbackThreadAttachment,
    contentColor: Color,
    onOpenAttachment: (String) -> Unit,
    onOpenImagePreview: (String) -> Unit
) {
    val displayName = feedbackAttachmentDisplayName(attachment).ifBlank {
        stringResource(R.string.feedback_attachment_unnamed)
    }
    if (attachment.isImageAttachment()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SimpleMarkdownContent(
                markdown = buildFeedbackAttachmentMarkdownImage(attachment, displayName),
                textColor = contentColor,
                codeContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                imageShowOpenButton = false,
                onImageClick = onOpenImagePreview
            )
        }
    } else {
        FeedbackAttachmentBubbleAction(
            label = stringResource(R.string.feedback_attachment_download_format, displayName),
            icon = Icons.Download,
            contentColor = contentColor,
            onClick = { onOpenAttachment(attachment.url) }
        )
    }
}

@Composable
private fun FeedbackAttachmentBubbleAction(
    label: String,
    icon: ImageVector,
    contentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = contentColor.copy(alpha = 0.08f)
    ) {
        TextButton(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor.copy(alpha = 0.82f),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .weight(1f)
            )
        }
    }
}

@Composable
private fun FeedbackSystemEventPill(event: FeedbackThreadEvent) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 1.dp
        ) {
            Text(
                text = "${event.body} · ${formatFeedbackEventTime(event.createdAtMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun FeedbackAuthorAvatar(event: FeedbackThreadEvent) {
    val context = LocalContext.current.applicationContext
    val avatarUrl = event.authorAvatarUrl.orEmpty()
    val avatarBitmap by produceState<Bitmap?>(initialValue = null, avatarUrl) {
        value = if (avatarUrl.isNotBlank()) {
            withContext(Dispatchers.IO) {
                FeedbackAvatarCacheStore.load(context, avatarUrl)
            }
        } else {
            null
        }
    }
    val identityKey = event.authorIdentityKey
        ?.takeIf(String::isNotBlank)
        ?: avatarUrl.ifBlank { event.authorLabel }
    Box(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .size(36.dp)
            .clip(CircleShape)
            .background(feedbackAvatarColor(identityKey)),
        contentAlignment = Alignment.Center
    ) {
        if (avatarBitmap != null) {
            Image(
                bitmap = requireNotNull(avatarBitmap).asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = feedbackAvatarInitial(event.authorLabel),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
    }
}

@Composable
private fun formatFeedbackEventTime(timestampMs: Long): String {
    if (timestampMs <= 0L) {
        return stringResource(R.string.feedback_unknown_time)
    }
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestampMs))
}

private fun feedbackAvatarColor(identityKey: String): Color {
    val colors = listOf(
        Color(0xFF4F6BED),
        Color(0xFF007A5A),
        Color(0xFFB45F06),
        Color(0xFF8E3A59),
        Color(0xFF006D8F),
        Color(0xFF6C5DD3),
        Color(0xFF7A5C2E),
        Color(0xFF2F6F4E)
    )
    return colors[Math.floorMod(identityKey.hashCode(), colors.size)]
}

private fun feedbackAvatarInitial(authorLabel: String): String {
    val first = authorLabel.trim().firstOrNull() ?: return "?"
    return first.uppercaseChar().toString()
}

private fun FeedbackThreadAttachment.isImageAttachment(): Boolean {
    val normalizedMimeType = mimeType
        .substringBefore(';')
        .trim()
        .lowercase(Locale.ROOT)
    if (normalizedMimeType.startsWith("image/")) {
        return true
    }
    val normalizedName = name.lowercase(Locale.ROOT)
    val normalizedUrl = url
        .substringBefore('?')
        .substringBefore('#')
        .lowercase(Locale.ROOT)
    return imageAttachmentExtensions.any { extension ->
        normalizedName.endsWith(extension) || normalizedUrl.endsWith(extension)
    }
}

private fun feedbackAttachmentDisplayName(attachment: FeedbackThreadAttachment): String {
    if (attachment.name.isNotBlank()) {
        return attachment.name
    }
    return attachment.url
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('/')
        .takeIf(String::isNotBlank)
        ?: attachment.url
}

private fun buildFeedbackAttachmentMarkdownImage(
    attachment: FeedbackThreadAttachment,
    displayName: String
): String {
    return "![${escapeMarkdownImageAlt(displayName)}](${attachment.url})"
}

private fun escapeMarkdownImageAlt(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("]", "\\]")
}

private fun FeedbackThreadEvent.isIssueReportEvent(issueNumber: Long): Boolean {
    return id == feedbackIssueReportEventId(issueNumber)
}

private fun feedbackIssueReportEventId(issueNumber: Long): String {
    return "issue-$issueNumber"
}

@Composable
private fun buildFeedbackIssueReportMeta(event: FeedbackThreadEvent): String {
    val authorText = event.authorDeviceLabel
        ?.takeIf(String::isNotBlank)
        ?.let { device -> "${event.authorLabel} · $device" }
        ?: event.authorLabel
    val timeText = formatFeedbackEventTime(event.createdAtMs)
    return if (authorText.isBlank()) {
        timeText
    } else {
        "${stringResource(R.string.feedback_author_format, authorText)} · $timeText"
    }
}

private fun buildFeedbackIssueReportFallbackEvent(
    uiState: FeedbackConversationViewModel.UiState
): FeedbackThreadEvent? {
    val body = uiState.issueBody.trim()
    if (body.isBlank()) {
        return null
    }
    return FeedbackThreadEvent(
        id = feedbackIssueReportEventId(uiState.issueNumber),
        type = FeedbackThreadEventType.COMMENT,
        authorType = FeedbackThreadAuthorType.OTHER,
        authorLabel = "",
        body = body,
        createdAtMs = 0L,
        htmlUrl = uiState.issueUrl.takeIf(String::isNotBlank)
    )
}

private fun buildFeedbackMediaGalleryInput(
    event: FeedbackThreadEvent,
    selectedImageUrl: String
): MediaGalleryPreviewContract.Input? {
    val normalizedSelectedUrl = selectedImageUrl.trim()
    if (normalizedSelectedUrl.isBlank()) {
        return null
    }
    val attachments = event.attachments
        .filter { attachment -> attachment.isImageAttachment() }
        .map(::buildStreamImageAttachment)
        .toMutableList()
    extractSimpleMarkdownImageUrls(event.body).forEach { imageUrl ->
        attachments.addFeedbackImageAttachmentIfMissing(imageUrl)
    }
    if (attachments.none { it.thumbUrl == normalizedSelectedUrl || it.imageUrl == normalizedSelectedUrl }) {
        attachments.addFeedbackImageAttachmentIfMissing(normalizedSelectedUrl)
    }
    if (attachments.isEmpty()) {
        return null
    }
    val createdAt = event.createdAtMs
        .takeIf { it > 0L }
        ?.let(::Date)
    return MediaGalleryPreviewContract.Input(
        message = Message(
            id = event.id.ifBlank { "feedback-${event.createdAtMs}" },
            text = event.body,
            attachments = attachments,
            createdAt = createdAt,
            updatedAt = createdAt,
            user = User(
                id = event.authorIdentityKey
                    ?.takeIf(String::isNotBlank)
                    ?: event.authorLabel.ifBlank { "feedback-user" },
                name = event.authorLabel,
                image = event.authorAvatarUrl.orEmpty()
            )
        ),
        selectedAttachmentUrl = normalizedSelectedUrl,
        videoThumbnailsEnabled = false,
        streamCdnImageResizing = StreamCdnImageResizing.defaultStreamCdnImageResizing(),
        skipEnrichUrl = true
    )
}

private fun MutableList<Attachment>.addFeedbackImageAttachmentIfMissing(url: String) {
    val normalizedUrl = url.trim()
    if (normalizedUrl.isBlank()) {
        return
    }
    if (any { it.thumbUrl == normalizedUrl || it.imageUrl == normalizedUrl || it.assetUrl == normalizedUrl }) {
        return
    }
    add(
        buildStreamImageAttachment(
            name = feedbackAttachmentNameFromUrl(normalizedUrl),
            url = normalizedUrl,
            mimeType = ""
        )
    )
}

private fun buildStreamImageAttachment(attachment: FeedbackThreadAttachment): Attachment {
    return buildStreamImageAttachment(
        name = feedbackAttachmentDisplayName(attachment).ifBlank {
            feedbackAttachmentNameFromUrl(attachment.url)
        },
        url = attachment.url,
        mimeType = attachment.mimeType
    )
}

private fun buildStreamImageAttachment(
    name: String,
    url: String,
    mimeType: String
): Attachment {
    val normalizedUrl = url.trim()
    return Attachment(
        name = name.ifBlank { feedbackAttachmentNameFromUrl(normalizedUrl) },
        fallback = name.ifBlank { feedbackAttachmentNameFromUrl(normalizedUrl) },
        thumbUrl = normalizedUrl,
        imageUrl = normalizedUrl,
        assetUrl = normalizedUrl,
        mimeType = mimeType.ifBlank { "image/*" },
        type = AttachmentType.IMAGE
    )
}

private fun feedbackAttachmentNameFromUrl(url: String): String {
    return url
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('/')
        .takeIf(String::isNotBlank)
        ?: url
}

private val imageAttachmentExtensions = setOf(
    ".png",
    ".jpg",
    ".jpeg",
    ".webp",
    ".gif",
    ".bmp"
)

@Composable
private fun feedbackContentColorFor(containerColor: Color): Color {
    val mappedColor = contentColorFor(containerColor)
    return if (mappedColor == Color.Unspecified) {
        MaterialTheme.colorScheme.onSurface
    } else {
        mappedColor
    }
}
