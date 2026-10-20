package io.stamethyst.ui.main

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.stamethyst.R
import io.stamethyst.backend.mods.AgentPatchCatalogueService
import io.stamethyst.backend.mods.AgentPatchModManager
import io.stamethyst.backend.mods.RemoteAgentPatch
import io.stamethyst.model.AgentPatchModUi
import io.stamethyst.model.ModItemUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class AgentPatchCatalogueTab { GITHUB, LOCAL }

internal fun interface AgentPatchFileAction {
    fun invoke(patch: AgentPatchModUi)
}

@Composable
internal fun AgentPatchCatalogueDialog(
    mod: ModItemUi,
    localPatches: List<AgentPatchModUi>,
    workshopId: ULong?,
    initialTab: AgentPatchCatalogueTab = AgentPatchCatalogueTab.GITHUB,
    initialLocalActionPatchId: String? = null,
    initialLocalUploadPatchId: String? = null,
    localOnly: Boolean = false,
    actionsOnly: Boolean = false,
    onDismiss: () -> Unit,
    onInstalled: () -> Unit,
    onCatalogueRefreshed: () -> Unit = {},
    onExport: AgentPatchFileAction,
    onShare: AgentPatchFileAction,
    onRename: (AgentPatchModUi, String, String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { context.getSharedPreferences("ai_patch_catalogue_upload", 0) }
    val parentModId = mod.manifestModId.ifBlank { mod.modId }
    val parentVersion = mod.version.trim()
    var selectedTab by remember { mutableStateOf(if (localOnly) AgentPatchCatalogueTab.LOCAL else initialTab) }
    var snapshot by remember(parentModId) { mutableStateOf<io.stamethyst.backend.mods.AgentPatchCatalogueSnapshot?>(null) }
    var error by remember(parentModId) { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(AgentPatchFilter.ALL) }
    var details by remember { mutableStateOf<RemoteAgentPatch?>(null) }
    var localDetails by remember { mutableStateOf<AgentPatchModUi?>(null) }
    var localActionsPatchId by remember(initialLocalActionPatchId) { mutableStateOf(initialLocalActionPatchId) }
    var showStandaloneActions by remember(initialLocalActionPatchId, actionsOnly) { mutableStateOf(actionsOnly) }
    var pendingUploadPatch by remember(initialLocalUploadPatchId, localPatches) {
        mutableStateOf(localPatches.firstOrNull { it.patchId == initialLocalUploadPatchId })
    }
    var renameTarget by remember { mutableStateOf<AgentPatchModUi?>(null) }
    var workingPatchModId by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var username by remember { mutableStateOf(prefs.getString("username", "").orEmpty()) }
    var email by remember { mutableStateOf(prefs.getString("email", "").orEmpty()) }
    var consent by remember { mutableStateOf(false) }

    LaunchedEffect(parentModId, localOnly) {
        if (localOnly) return@LaunchedEffect
        error = null
        withContext(Dispatchers.IO) {
            AgentPatchCatalogueService.readCached(context.applicationContext, parentModId)
        }?.let { snapshot = it }
        runCatching { AgentPatchCatalogueService.refresh(context.applicationContext, parentModId) }
            .onSuccess {
                snapshot = it
                if (!it.fromCache) onCatalogueRefreshed()
            }
            .onFailure { error = it.message ?: "Catalogue request failed." }
    }

    val localByModId = remember(localPatches) { localPatches.associateBy { it.patchModId } }
    val visibleRemote = remember(snapshot, filter, localByModId, parentVersion) {
        val matching = snapshot?.patches.orEmpty().asSequence()
            .map { patch -> patch to localByModId[patch.patchModId] }
            .filter { (remote, installed) ->
                when (filter) {
                    AgentPatchFilter.ALL -> true
                    AgentPatchFilter.INSTALL -> installed == null
                    AgentPatchFilter.UPDATE -> installed != null && compareVersions(remote.version, installed.version) > 0
                }
            }
            .toList()
        val (compatible, otherVersions) = matching.partition { isCompatibleWithVersion(it.first, parentVersion) }
        compatible + otherVersions
    }

    if (!actionsOnly) Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .animateContentSize(animationSpec = tween(durationMillis = 180)),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.onSurface) {
                        Text("⌘", modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = MaterialTheme.colorScheme.surface)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (localOnly) stringResource(R.string.main_mod_agent_patch_action_title)
                            else stringResource(R.string.main_mod_agent_patch_catalogue_title, mod.name),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!localOnly) {
                            Text(
                                stringResource(R.string.main_mod_agent_patch_source_github),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                HorizontalDivider()
                if (!localOnly) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { selectedTab = AgentPatchCatalogueTab.GITHUB }) {
                            Text(
                                stringResource(R.string.main_mod_agent_patch_github_tab),
                                fontWeight = FontWeight.SemiBold,
                                color = if (selectedTab == AgentPatchCatalogueTab.GITHUB) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        TextButton(onClick = { selectedTab = AgentPatchCatalogueTab.LOCAL }) {
                            Text(
                                stringResource(R.string.main_mod_agent_patch_local_tab),
                                fontWeight = FontWeight.SemiBold,
                                color = if (selectedTab == AgentPatchCatalogueTab.LOCAL) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
                if (selectedTab == AgentPatchCatalogueTab.GITHUB) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AgentPatchFilter.entries.forEach { item ->
                            FilterChip(
                                selected = filter == item,
                                onClick = { filter = item },
                                label = {
                                    Text(stringResource(when (item) {
                                        AgentPatchFilter.ALL -> R.string.main_mod_agent_patch_filter_all
                                        AgentPatchFilter.INSTALL -> R.string.main_mod_agent_patch_filter_install
                                        AgentPatchFilter.UPDATE -> R.string.main_mod_agent_patch_filter_update
                                    }))
                                },
                            )
                        }
                    }
                    if (snapshot == null && error == null) {
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            repeat(AGENT_PATCH_SKELETON_ROWS) {
                                AgentPatchSkeletonRow()
                            }
                        }
                    } else if (error != null && snapshot == null) {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            scope.launch {
                                error = null
                                runCatching { AgentPatchCatalogueService.refresh(context.applicationContext, parentModId) }
                                    .onSuccess {
                                        snapshot = it
                                        if (!it.fromCache) onCatalogueRefreshed()
                                    }
                                    .onFailure { error = it.message ?: "Catalogue request failed." }
                            }
                        }) { Text(stringResource(R.string.main_mod_agent_patch_retry)) }
                    } else if (visibleRemote.isEmpty()) {
                        Text(stringResource(R.string.main_mod_agent_patch_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f, fill = false).heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            items(visibleRemote, key = { it.first.patchModId }) { (patch, installed) ->
                                val hasUpdate = installed != null && compareVersions(patch.version, installed.version) > 0
                                RemoteAgentPatchRow(
                                    modifier = Modifier.animateItem(),
                                    patch = patch,
                                    parentVersion = parentVersion,
                                    installed = installed != null,
                                    hasUpdate = hasUpdate,
                                    working = workingPatchModId == patch.patchModId,
                                    onDetails = { details = patch },
                                    onInstall = {
                                        if (workingPatchModId != null) return@RemoteAgentPatchRow
                                        workingPatchModId = patch.patchModId
                                        scope.launch {
                                            runCatching { AgentPatchCatalogueService.install(context.applicationContext, parentModId, patch) }
                                                .onSuccess {
                                                    notice = context.getString(R.string.main_mod_agent_patch_install_success, patch.name)
                                                    onInstalled()
                                                }
                                                .onFailure { notice = context.getString(R.string.main_mod_agent_patch_install_failed, it.message.orEmpty()) }
                                            workingPatchModId = null
                                        }
                                    },
                                )
                            }
                        }
                    }
                    if (error != null && snapshot != null) {
                        Text(error.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    Text(stringResource(R.string.main_mod_agent_patch_catalogue_source), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    if (localPatches.isEmpty()) {
                        Text(stringResource(R.string.main_mod_agent_patch_local_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f, fill = false).heightIn(max = 480.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            items(localPatches, key = { it.patchId }) { patch ->
                                LocalAgentPatchRow(
                                    modifier = Modifier.animateItem(),
                                    patch = patch,
                                    onDetails = { localDetails = patch },
                                    onExport = { onExport.invoke(patch) },
                                    onShare = { onShare.invoke(patch) },
                                    onUpload = { pendingUploadPatch = patch },
                                    onRename = { renameTarget = patch },
                                    openActionsOnLoad = localActionsPatchId == patch.patchId,
                                )
                            }
                        }
                    }
                }

                notice?.let { text ->
                    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    if (showStandaloneActions) {
        val selectedPatch = localPatches.firstOrNull { it.patchId == localActionsPatchId }
        if (selectedPatch == null) {
            LocalAgentPatchChooserDialog(
                patches = localPatches,
                onSelect = { localActionsPatchId = it.patchId },
                onDismiss = onDismiss,
            )
        } else {
            LocalAgentPatchActionsDialog(
                patch = selectedPatch,
                onDismiss = onDismiss,
                onUpload = { showStandaloneActions = false; pendingUploadPatch = selectedPatch },
                onExport = { onDismiss(); onExport.invoke(selectedPatch) },
                onShare = { onDismiss(); onShare.invoke(selectedPatch) },
                onRename = { showStandaloneActions = false; renameTarget = selectedPatch },
            )
        }
    }

    if (actionsOnly && !showStandaloneActions && pendingUploadPatch == null && renameTarget == null && notice != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text(notice.orEmpty()) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_close)) } },
        )
    }

    details?.let { patch ->
        val installed = localByModId[patch.patchModId]
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text(patch.name.ifBlank { stringResource(R.string.main_mod_agent_patch_unnamed) }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${stringResource(R.string.main_mod_agent_patch_version_format, patch.version)} · ${compatibilityText(patch, parentVersion)}")
                    if (patch.authorName.isNotBlank()) {
                        Text(stringResource(R.string.main_mod_agent_patch_author, patch.authorName), style = MaterialTheme.typography.labelSmall)
                    }
                    Text(patch.description.ifBlank { stringResource(R.string.main_mod_agent_patch_description_empty) }, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.main_mod_agent_patch_catalogue_source), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (installed != null && compareVersions(patch.version, installed.version) > 0) {
                        Text(stringResource(R.string.main_mod_agent_patch_update), color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = workingPatchModId == null && (installed == null || compareVersions(patch.version, installed.version) > 0), onClick = {
                    workingPatchModId = patch.patchModId
                    scope.launch {
                        runCatching { AgentPatchCatalogueService.install(context.applicationContext, parentModId, patch) }
                            .onSuccess {
                                details = null
                                notice = context.getString(R.string.main_mod_agent_patch_install_success, patch.name)
                                onInstalled()
                            }
                            .onFailure { notice = context.getString(R.string.main_mod_agent_patch_install_failed, it.message.orEmpty()) }
                        workingPatchModId = null
                    }
                }) { Text(stringResource(if (installed != null) R.string.main_mod_agent_patch_update else R.string.main_mod_agent_patch_install)) }
            },
            dismissButton = { TextButton(onClick = { details = null }) { Text(stringResource(R.string.common_action_close)) } },
        )
    }

    localDetails?.let { patch ->
        AgentPatchDetailsDialog(
            patch = patch,
            onDismiss = { localDetails = null },
        )
    }

    pendingUploadPatch?.let { patch ->
        AlertDialog(
            onDismissRequest = {
                pendingUploadPatch = null
                if (actionsOnly) onDismiss()
            },
            title = { Text(stringResource(R.string.main_mod_agent_patch_upload_title, patch.name.ifBlank { stringResource(R.string.main_mod_agent_patch_unnamed) })) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text(stringResource(R.string.main_mod_agent_patch_upload_author_prompt), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.main_mod_agent_patch_upload_identity_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(username, {
                        username = it
                        prefs.edit().putString("username", it).apply()
                    }, label = { Text(stringResource(R.string.main_mod_agent_patch_upload_username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(email, {
                        email = it
                        prefs.edit().putString("email", it).apply()
                    }, label = { Text(stringResource(R.string.main_mod_agent_patch_upload_email)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.main_mod_agent_patch_upload_no_email), style = MaterialTheme.typography.labelSmall)
                    Text(stringResource(R.string.main_mod_agent_patch_upload_public_identity), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.main_mod_agent_patch_upload_password_warning), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.clickable { consent = !consent }) {
                        androidx.compose.material3.Checkbox(checked = consent, onCheckedChange = { consent = it })
                        Text(stringResource(R.string.main_mod_agent_patch_upload_consent), style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = workingPatchModId == null && consent && username.isNotBlank() && email.isNotBlank(), onClick = {
                    workingPatchModId = patch.patchModId
                    scope.launch {
                        val info = withContext(Dispatchers.IO) {
                            AgentPatchModManager.listPackaged(context.applicationContext, parentModId)
                                .firstOrNull { it.patchId == patch.patchId }
                        }
                        if (info == null) {
                            notice = context.getString(R.string.main_mod_agent_patch_upload_failed, "Patch file is missing.")
                            workingPatchModId = null
                            return@launch
                        }
                        runCatching {
                            AgentPatchCatalogueService.upload(
                                context.applicationContext,
                                parentModId,
                                workshopId,
                                info,
                                io.stamethyst.backend.mods.AgentPatchUploadAuthor(username, email),
                                consent,
                                listOf(mod.version),
                            )
                        }.onSuccess { result ->
                            prefs.edit().putString("username", username.trim()).putString("email", email.trim()).apply()
                            pendingUploadPatch = null
                            notice = context.getString(
                                R.string.main_mod_agent_patch_upload_success,
                                result.commitUrl,
                                result.indexing.ifBlank { "unknown" },
                                result.indexUrl,
                            )
                        }.onFailure {
                            notice = context.getString(R.string.main_mod_agent_patch_upload_failed, it.message.orEmpty())
                        }
                        workingPatchModId = null
                    }
                }) { Text(stringResource(R.string.main_mod_agent_patch_upload_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingUploadPatch = null
                    if (actionsOnly) onDismiss()
                }) { Text(stringResource(R.string.common_action_close)) }
            },
        )
    }

    renameTarget?.let { patch ->
        var newName by remember(patch.patchId) { mutableStateOf(patch.name) }
        var newDescription by remember(patch.patchId) { mutableStateOf(patch.description) }
        AlertDialog(
            onDismissRequest = {
                renameTarget = null
                if (actionsOnly) onDismiss()
            },
            title = { Text(stringResource(R.string.main_mod_agent_patch_rename_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.main_mod_agent_patch_rename_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newDescription,
                        onValueChange = { newDescription = it.take(4000) },
                        label = { Text(stringResource(R.string.main_mod_agent_patch_rename_description)) },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renameTarget = null
                        onRename(patch, newName, newDescription)
                        if (actionsOnly) onDismiss()
                    },
                    enabled = newName.trim().isNotEmpty(),
                ) {
                    Text(stringResource(R.string.main_folder_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    renameTarget = null
                    if (actionsOnly) onDismiss()
                }) { Text(stringResource(R.string.main_folder_dialog_cancel)) }
            },
        )
    }
}

private enum class AgentPatchFilter { ALL, INSTALL, UPDATE }

@Composable
internal fun AgentPatchDetailsDialog(
    patch: AgentPatchModUi,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(patch.name.ifBlank { stringResource(R.string.main_mod_agent_patch_unnamed) }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.main_mod_agent_patch_version_format, patch.version))
                Text(
                    patch.description.ifBlank { stringResource(R.string.main_mod_agent_patch_description_empty) },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (patch.enabled) {
                        stringResource(R.string.main_mod_agent_patch_installed)
                    } else {
                        stringResource(R.string.main_mod_agent_patch_local_tab)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_close)) }
        },
    )
}

private const val AGENT_PATCH_SKELETON_ROWS = 3

@Composable
private fun AgentPatchSkeletonRow() {
    val transition = rememberInfiniteTransition(label = "agent_patch_skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "agent_patch_skeleton_alpha",
    )
    val placeholder = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
    Card(
        modifier = Modifier.fillMaxWidth().height(72.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(placeholder)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(placeholder)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.78f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(placeholder)
                )
            }
            Box(
                modifier = Modifier
                    .width(70.dp)
                    .height(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(placeholder)
            )
        }
    }
}

@Composable
private fun LocalAgentPatchChooserDialog(
    patches: List<AgentPatchModUi>,
    onSelect: (AgentPatchModUi) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.main_mod_agent_patch_action_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.main_mod_agent_patch_choose_for_actions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                if (patches.isEmpty()) {
                    Text(
                        stringResource(R.string.main_mod_agent_patch_local_empty),
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(patches, key = { it.patchId }) { patch ->
                            ModActionOptionItem(
                                text = patch.name.ifBlank { stringResource(R.string.main_mod_agent_patch_unnamed) },
                                description = stringResource(R.string.main_mod_agent_patch_version_format, patch.version),
                                icon = R.drawable.ic_code,
                                enabled = true,
                                onClick = { onSelect(patch) },
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PillCancelButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_action_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteAgentPatchRow(
    modifier: Modifier = Modifier,
    patch: RemoteAgentPatch,
    parentVersion: String,
    installed: Boolean,
    hasUpdate: Boolean,
    working: Boolean,
    onDetails: () -> Unit,
    onInstall: () -> Unit,
) {
    Card(
        modifier = modifier.animateContentSize(animationSpec = tween(durationMillis = 180)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onDetails).padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text("✦", modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Column(Modifier.weight(1f)) {
                    Text(patch.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (patch.authorName.isNotBlank()) {
                        Text(patch.authorName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(stringResource(R.string.main_mod_agent_patch_version_format, patch.version), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(compatibilityText(patch, parentVersion), style = MaterialTheme.typography.labelSmall, color = if (isCompatibleWithVersion(patch, parentVersion)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Button(
                    enabled = !working && (!installed || hasUpdate),
                    onClick = onInstall,
                    modifier = Modifier.animateContentSize(animationSpec = tween(durationMillis = 180)),
                ) {
                    val label = stringResource(
                        if (hasUpdate) R.string.main_mod_agent_patch_update
                        else if (installed) R.string.main_mod_agent_patch_installed
                        else R.string.main_mod_agent_patch_install
                    )
                    Box(contentAlignment = Alignment.Center) {
                        Text(label, modifier = Modifier.alpha(if (working) 0f else 1f))
                        if (working) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
            if (hasUpdate) Text("${stringResource(R.string.main_mod_agent_patch_update)} · ${patch.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

@Composable
private fun compatibilityText(patch: RemoteAgentPatch, parentVersion: String): String {
    val versions = patch.targetVersions.takeIf { it.isNotEmpty() }?.joinToString().orEmpty().ifBlank { "*" }
    return if (isCompatibleWithVersion(patch, parentVersion)) {
        stringResource(R.string.main_mod_agent_patch_compatible, versions)
    } else {
        stringResource(R.string.main_mod_agent_patch_targets, versions)
    }
}

private fun isCompatibleWithVersion(patch: RemoteAgentPatch, parentVersion: String): Boolean =
    patch.targetVersions.isEmpty() || "*" in patch.targetVersions || parentVersion in patch.targetVersions

@Composable
private fun LocalAgentPatchRow(
    modifier: Modifier = Modifier,
    patch: AgentPatchModUi,
    onDetails: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onUpload: () -> Unit,
    onRename: () -> Unit,
    openActionsOnLoad: Boolean,
) {
    var showActions by remember(patch.patchId, openActionsOnLoad) { mutableStateOf(openActionsOnLoad) }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onDetails)) {
                Text(patch.name.ifBlank { stringResource(R.string.main_mod_agent_patch_unnamed) }, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(patch.version, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            androidx.compose.material3.IconButton(onClick = { showActions = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
        }
    }
    if (showActions) LocalAgentPatchActionsDialog(
        patch = patch,
        onDismiss = { showActions = false },
        onUpload = { showActions = false; onUpload() },
        onExport = { showActions = false; onExport() },
        onShare = { showActions = false; onShare() },
        onRename = { showActions = false; onRename() },
    )
}

@Composable
private fun LocalAgentPatchActionsDialog(
    patch: AgentPatchModUi,
    onDismiss: () -> Unit,
    onUpload: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.main_mod_agent_patch_action_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    patch.name.ifBlank { stringResource(R.string.main_mod_agent_patch_unnamed) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                HorizontalDivider()
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ModActionOptionItem(
                        text = stringResource(R.string.main_mod_agent_patch_upload),
                        description = stringResource(R.string.main_mod_agent_patch_upload_action_desc),
                        icon = R.drawable.ic_cloud_sync,
                        enabled = true,
                        onClick = onUpload,
                    )
                    ModActionOptionItem(
                        text = stringResource(R.string.main_mod_agent_patch_export),
                        description = stringResource(R.string.main_mod_agent_patch_export_action_desc),
                        icon = R.drawable.ic_workshop_download,
                        enabled = true,
                        onClick = onExport,
                    )
                    ModActionOptionItem(
                        text = stringResource(R.string.main_mod_agent_patch_share),
                        description = stringResource(R.string.main_mod_agent_patch_share_action_desc),
                        icon = R.drawable.ic_lan_room_share,
                        enabled = true,
                        onClick = onShare,
                    )
                    ModActionOptionItem(
                        text = stringResource(R.string.main_mod_agent_patch_rename),
                        description = stringResource(R.string.main_mod_agent_patch_rename_action_desc),
                        icon = R.drawable.ic_edit,
                        enabled = true,
                        onClick = onRename,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PillCancelButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_close)) }
                }
            }
        }
    }
}

private fun compareVersions(left: String, right: String): Int {
    fun tokens(value: String): List<Long> = Regex("\\d+").findAll(value).mapNotNull { it.value.toLongOrNull() }.toList()
    val a = tokens(left)
    val b = tokens(right)
    for (index in 0 until maxOf(a.size, b.size)) {
        val difference = (a.getOrNull(index) ?: 0L).compareTo(b.getOrNull(index) ?: 0L)
        if (difference != 0) return difference
    }
    return 0
}
