/* OpenCode V2 SessionCompactionMessage / TimelineSeparator, adapted to Compose. See NOTICE. */
package io.stamethyst.ui.aimod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.stamethyst.R
import io.stamethyst.backend.llm.AgentCompactionProgress
import io.stamethyst.ui.SimpleMarkdownContent
import kotlinx.serialization.json.Json

/** Thin centered separators, ordinary Markdown, and a quiet running row, as in the V2 timeline. */
@Composable
internal fun AiCompactionDivider(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(label, Modifier.weight(3f, fill = false), fontSize = 13.sp, letterSpacing = (-0.04).sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun AiCompactionPart(encoded: String, streaming: Boolean) {
    val event = remember(encoded) { runCatching { Json.decodeFromString<AgentCompactionProgress>(encoded) }.getOrNull() } ?: return
    // A persisted running event after process death must not spin forever.
    val status = if (event.status == "running" && !streaming) "interrupted" else event.status
    Column(Modifier.fillMaxWidth().testTag("compaction-${event.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AiCompactionDivider(stringResource(R.string.ai_compaction_started))
        if (status !in setOf("failed", "interrupted") && event.summary.isNotBlank()) {
            // Providers may fill their entire output allowance. Reuse the bounded Markdown
            // viewport for very long summaries instead of laying out one unbounded Text tree.
            if (event.summary.length > 8192) AiExpandedMarkdown(event.summary, MaterialTheme.colorScheme.onSurface)
            else SimpleMarkdownContent(event.summary, textColor = MaterialTheme.colorScheme.onSurface, textSelectable = true)
        }
        if (status == "running") {
            Row(Modifier.padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.ai_compaction_running), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val outcome = stringResource(when (status) {
                "completed" -> R.string.ai_compaction_completed
                "interrupted" -> R.string.ai_compaction_interrupted
                else -> R.string.ai_compaction_failed
            })
            val usage = if ((event.inputTokens ?: 0) + (event.outputTokens ?: 0) > 0)
                stringResource(R.string.ai_compaction_usage, formatAiTokenCount(event.inputTokens), formatAiTokenCount(event.outputTokens)) else ""
            AiCompactionDivider(listOf(outcome, usage).filter(String::isNotBlank).joinToString(" · "))
        }
        if (status == "failed" && event.error.isNotBlank()) Surface(
            shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) {
            Text(event.error, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}
