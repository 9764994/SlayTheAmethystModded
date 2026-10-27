package io.stamethyst.ui.main

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.ui.aimod.AiAgentExecutionService
import io.stamethyst.ui.LauncherNavigationRequestBus

@Composable
internal fun AgentWorkingIndicator() {
    val isRunning by AiAgentExecutionService.isRunning.collectAsState()
    val editorRoute by AiAgentExecutionService.activeEditorRoute.collectAsState()
    if (!isRunning) return

    val description = stringResource(R.string.main_agent_working)
    IconButton(
        onClick = {
            AiAgentExecutionService.activeEditorRoute.value?.let(LauncherNavigationRequestBus::requestAiEditor)
        },
        enabled = editorRoute != null,
        modifier = Modifier.size(48.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(34.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Icon(
            painter = painterResource(R.drawable.ic_agent_working),
            contentDescription = description,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}
