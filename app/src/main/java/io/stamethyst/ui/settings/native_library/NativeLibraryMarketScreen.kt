package io.stamethyst.ui.settings.native_library

import io.stamethyst.ui.settings.baidu.*
import io.stamethyst.ui.settings.common.*
import io.stamethyst.ui.settings.core.*
import io.stamethyst.ui.settings.files.*
import io.stamethyst.ui.settings.first_run.*
import io.stamethyst.ui.settings.mobileglues.*
import io.stamethyst.ui.settings.sections.*
import io.stamethyst.ui.settings.services.*
import io.stamethyst.ui.settings.steamcloud.*

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.stamethyst.R
import io.stamethyst.backend.nativelib.NativeLibraryMarketAvailability
import io.stamethyst.backend.nativelib.NativeLibraryMarketPackageState
import io.stamethyst.navigation.currentNavigator
import io.stamethyst.ui.Icons
import io.stamethyst.ui.FloatingGlassHeader
import io.stamethyst.ui.LauncherBannerTitleRow
import io.stamethyst.ui.launcherBannerExpandedHeight
import io.stamethyst.ui.icon.ArrowBack

@Composable
fun LauncherNativeLibraryMarketScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    val uiState = viewModel.uiState
    val headerHazeState = rememberHazeState()
    val headerHeight = launcherBannerExpandedHeight(104.dp)

    LaunchedEffect(activity) {
        viewModel.onOpenNativeLibraryMarket(activity)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .hazeSource(state = headerHazeState),
            contentPadding = PaddingValues(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(headerHeight))
            }

            item {
                SettingsBusyIndicator(uiState = uiState)
            }

            item {
                SettingsSectionCard(
                    title = stringResource(R.string.settings_native_library_market_title)
                ) {
                    Text(
                        text = stringResource(R.string.settings_native_library_market_dialog_desc),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (uiState.nativeLibraryMarketLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    uiState.nativeLibraryMarketErrorText?.let { errorText ->
                        HorizontalDivider()
                        Text(
                            text = errorText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            if (
                !uiState.nativeLibraryMarketLoading &&
                uiState.nativeLibraryMarketErrorText == null &&
                uiState.nativeLibraryMarketPackages.isEmpty()
            ) {
                item {
                    SettingsSectionCard(
                        title = stringResource(R.string.settings_native_library_market_dialog_title)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_native_library_market_empty),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            items(
                count = uiState.nativeLibraryMarketPackages.size,
                key = { index -> uiState.nativeLibraryMarketPackages[index].catalogEntry.id }
            ) { index ->
                NativeLibraryMarketPackageCard(
                    packageState = uiState.nativeLibraryMarketPackages[index],
                    enabled = !uiState.busy,
                    onInstall = { packageId ->
                        viewModel.onInstallNativeLibraryPackage(activity, packageId)
                    },
                    onRemove = { packageId ->
                        viewModel.onRemoveNativeLibraryPackage(activity, packageId)
                    }
                )
            }
        }

        FloatingGlassHeader(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
            hazeState = headerHazeState,
            shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            NativeLibraryMarketHeader(
                onBack = navigator::goBack,
                onRefresh = { viewModel.refreshNativeLibraryMarket(activity) },
                refreshEnabled = !uiState.busy && !uiState.nativeLibraryMarketLoading,
            )
        }
    }
}

@Composable
private fun NativeLibraryMarketHeader(
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    refreshEnabled: Boolean,
) {
    LauncherBannerTitleRow(
        iconResId = R.drawable.ic_settings_native_library,
        title = stringResource(R.string.settings_native_library_market_title),
        subtitle = stringResource(R.string.settings_native_library_market_desc),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.ArrowBack,
                    contentDescription = stringResource(R.string.common_content_desc_back),
                )
            }
        },
    ) {
        IconButton(
            onClick = onRefresh,
            enabled = refreshEnabled,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_refresh),
                contentDescription = stringResource(R.string.common_action_refresh),
            )
        }
    }
}

@Composable
private fun NativeLibraryMarketPackageCard(
    packageState: NativeLibraryMarketPackageState,
    enabled: Boolean,
    onInstall: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val packageEntry = packageState.catalogEntry
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = packageEntry.displayName,
                style = MaterialTheme.typography.titleSmall
            )
            if (packageEntry.description.isNotBlank()) {
                Text(
                    text = packageEntry.description,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                text = stringResource(
                    R.string.settings_native_library_market_files,
                    packageEntry.files.joinToString(", ") { it.fileName }
                ),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = when (packageState.availability) {
                    NativeLibraryMarketAvailability.INSTALLED ->
                        stringResource(R.string.settings_native_library_market_status_installed)
                    NativeLibraryMarketAvailability.BUNDLED ->
                        stringResource(R.string.settings_native_library_market_status_bundled)
                    NativeLibraryMarketAvailability.NOT_INSTALLED ->
                        stringResource(R.string.settings_native_library_market_status_not_installed)
                },
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                when (packageState.availability) {
                    NativeLibraryMarketAvailability.INSTALLED -> {
                        TextButton(
                            onClick = { onRemove(packageEntry.id) },
                            enabled = enabled
                        ) {
                            Text(stringResource(R.string.common_action_remove))
                        }
                    }

                    NativeLibraryMarketAvailability.NOT_INSTALLED -> {
                        TextButton(
                            onClick = { onInstall(packageEntry.id) },
                            enabled = enabled
                        ) {
                            Text(stringResource(R.string.common_action_install))
                        }
                    }

                    NativeLibraryMarketAvailability.BUNDLED -> Unit
                }
            }
        }
    }
}
