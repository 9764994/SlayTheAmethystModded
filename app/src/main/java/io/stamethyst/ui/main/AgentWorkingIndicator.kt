package io.stamethyst.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.ui.aimod.AiAgentExecutionService

@Composable
internal fun AgentWorkingIndicator() {
    val isRunning by AiAgentExecutionService.isRunning.collectAsState()
    if (!isRunning) return

    val description = stringResource(R.string.main_agent_working)
    Box(
        modifier = Modifier.size(48.dp).clearAndSetSemantics {
            contentDescription = description
        },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(34.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Icon(
            painter = painterResource(R.drawable.ic_agent_working),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}
