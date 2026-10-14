package io.stamethyst.ui.settings.core

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import io.stamethyst.ui.icon.SaveImportIcons as Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import io.stamethyst.R
import io.stamethyst.ui.settings.common.HapticTextButton
import io.stamethyst.ui.settings.files.SaveArchiveResolver
import io.stamethyst.ui.settings.files.SaveImportPreview

@Composable
internal fun SaveImportConfirmationDialog(
    state: SettingsScreenViewModel.SaveImportConfirmationState,
    busy: Boolean,
    onCandidate: (Int) -> Unit,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by rememberSaveable(state.archive.path, state.candidateIndex) {
        mutableStateOf(ArrayList(state.summary.items.filter { it.change != SaveArchiveResolver.Change.UNCHANGED }.map { it.path }))
    }
    val groups = state.summary.items.groupBy { it.path.substringBefore('/') }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.save_import_confirm)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.candidates.size > 1) {
                    items(state.candidates.indices.toList(), key = { "candidate-$it" }) { index ->
                        Row(Modifier.fillMaxWidth().toggleable(
                            value = index == state.candidateIndex, enabled = !busy, role = Role.RadioButton,
                            onValueChange = { onCandidate(index) },
                        ), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = index == state.candidateIndex, onClick = null, enabled = !busy)
                            Text(state.candidates[index].prefix.ifEmpty { "/" }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Legend(Icons.Add, stringResource(R.string.save_import_added))
                        Legend(Icons.Sync, stringResource(R.string.save_import_replaced))
                        Legend(Icons.DragHandle, stringResource(R.string.save_import_same))
                    }
                }
                listOf("preferences", "runs", "saves").forEach { root ->
                    val entries = groups[root].orEmpty()
                    if (entries.isNotEmpty()) {
                        item(key = "header-$root") {
                            val title = stringResource(when(root) {
                                "preferences" -> R.string.save_import_progress
                                "runs" -> R.string.save_import_history
                                else -> R.string.save_import_active
                            })
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Icon(when(root) {
                                    "preferences" -> Icons.EmojiEvents
                                    "runs" -> Icons.History
                                    else -> Icons.HourglassEmpty
                                }, null, tint = MaterialTheme.colorScheme.primary)
                                Text(title, Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleSmall)
                                if (root != "preferences") {
                                    val added = if (root == "runs") state.summary.addedHistory else state.summary.addedActiveRuns
                                    val replaced = if (root == "runs") state.summary.replacedHistory else state.summary.replacedActiveRuns
                                    Text(stringResource(R.string.save_import_run_count, added, replaced), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(enabled = !busy && entries.any { it.change != SaveArchiveResolver.Change.UNCHANGED }, onClick = {
                                    val paths = entries.filter { it.change != SaveArchiveResolver.Change.UNCHANGED }.map { it.path }
                                    selected = ArrayList(if (selected.containsAll(paths)) selected - paths.toSet() else (selected + paths).distinct())
                                }) { Icon(Icons.CheckBox, stringResource(R.string.save_import_select_group, title)) }
                            }
                        }
                        items(entries, key = { it.path }) { entry ->
                            val enabled = !busy && entry.change != SaveArchiveResolver.Change.UNCHANGED
                            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                                Row(Modifier.fillMaxWidth().toggleable(
                                    value = entry.path in selected, enabled = enabled, role = Role.Checkbox,
                                    onValueChange = { checked -> selected = ArrayList(if (checked) selected + entry.path else selected - entry.path) },
                                ).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = entry.path in selected, onCheckedChange = null, enabled = enabled)
                                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                        Text(entry.character ?: when(entry.category) {
                                            SaveImportPreview.Category.UNLOCKS -> stringResource(R.string.save_import_unlocks)
                                            SaveImportPreview.Category.HISTORY -> stringResource(R.string.save_import_history)
                                            SaveImportPreview.Category.ACTIVE_RUN -> entry.path.substringAfterLast('/').substringBefore(".autosave")
                                            else -> entry.path.substringAfterLast('/')
                                        }, style = MaterialTheme.typography.bodyMedium)
                                        Text(entry.path.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        val ascension = entry.fields.firstOrNull { it.key.equals("ASCENSION_LEVEL", true) }
                                        if (ascension != null) Text(stringResource(R.string.save_import_ascension, ascension.before ?: "—", ascension.after ?: "—"), style = MaterialTheme.typography.bodySmall)
                                    }
                                    Icon(when(entry.change) {
                                        SaveArchiveResolver.Change.ADDED -> Icons.Add
                                        SaveArchiveResolver.Change.REPLACED -> Icons.Sync
                                        SaveArchiveResolver.Change.UNCHANGED -> Icons.DragHandle
                                    }, stringResource(when(entry.change) {
                                        SaveArchiveResolver.Change.ADDED -> R.string.save_import_added
                                        SaveArchiveResolver.Change.REPLACED -> R.string.save_import_replaced
                                        SaveArchiveResolver.Change.UNCHANGED -> R.string.save_import_same
                                    }), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { HapticTextButton(enabled = !busy && selected.isNotEmpty(), onClick = { onConfirm(selected.toSet()) }) {
            Text(stringResource(R.string.save_import_apply, selected.size))
        } },
        dismissButton = { HapticTextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun Legend(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
