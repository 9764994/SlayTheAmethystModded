package io.stamethyst.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val LAUNCHER_BANNER_TAG = "launcher_shared_banner"
internal object DockBannerKey

/** Page state belongs to the page, but its chrome is composed only by the outer host. */
@Stable
internal class LauncherBannerPageState {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
    var locals by mutableStateOf<CompositionLocalContext?>(null)
    var collapseOffsetPx by mutableFloatStateOf(0f)
    var collapseRangePx by mutableFloatStateOf(1f)
    var expandedHeightPx by mutableIntStateOf(0)
    var hidden by mutableStateOf(false)
    val collapseFraction: Float get() = (collapseOffsetPx / collapseRangePx).coerceIn(0f, 1f)

    suspend fun expand() {
        animate(
            initialValue = collapseOffsetPx,
            targetValue = 0f,
            animationSpec = spring(dampingRatio = 1f, stiffness = 450f),
        ) { value, _ -> collapseOffsetPx = value.coerceIn(0f, collapseRangePx) }
    }

    companion object {
        val Saver = listSaver<LauncherBannerPageState, Number>(
            save = { listOf(it.collapseOffsetPx, it.collapseRangePx, it.expandedHeightPx) },
            restore = { values ->
                LauncherBannerPageState().apply {
                    collapseOffsetPx = values[0].toFloat()
                    collapseRangePx = values[1].toFloat()
                    expandedHeightPx = values[2].toInt()
                }
            },
        )
    }
}

@Stable
internal class LauncherBannerState {
    val pages = mutableStateMapOf<Any, LauncherBannerPageState>()
}

internal val LocalLauncherBannerState = compositionLocalOf<LauncherBannerState?> { null }
internal val LocalLauncherBannerPage = compositionLocalOf<LauncherBannerPageState?> { null }
internal val LocalLauncherBannerCollapseFraction = compositionLocalOf { 0f }

/** Build the route provider once; decorating a visible entry must not rebuild every route. */
internal fun launcherBannerEntryProvider(
    dockPagerVisible: Boolean,
    dockKeys: List<NavKey>,
    provider: (NavKey) -> NavEntry<NavKey>,
): (NavKey) -> NavEntry<NavKey> = { key ->
    val entry = provider(key)
    NavEntry<NavKey>(navEntry = entry) { route: NavKey ->
        if (dockPagerVisible && route in dockKeys) {
            entry.Content()
        } else {
            LauncherBannerPage(route) { entry.Content() }
        }
    }
}

/**
 * Observe, but never consume, vertical scroll. The list and banner move on the same frame;
 * changing the banner's height never changes a list's scroll offset or its top spacer.
 */
@Composable
internal fun LauncherBannerPage(pageKey: Any, content: @Composable () -> Unit) {
    val host = LocalLauncherBannerState.current
    if (host == null) {
        content()
        return
    }
    val page = rememberSaveable(pageKey, saver = LauncherBannerPageState.Saver) { LauncherBannerPageState() }
    val density = LocalDensity.current
    val compactHeightPx = with(density) { 64.dp.toPx() } + WindowInsets.statusBars.getTop(density)
    val rangePx = maxOf(with(density) { 96.dp.toPx() }, page.expandedHeightPx - compactHeightPx)
    val scope = rememberCoroutineScope()
    val connection = remember(page, scope) {
        object : NestedScrollConnection {
            var settling: Job? = null

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y != 0f) settling?.cancel()
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val delta = consumed.y + available.y.coerceAtLeast(0f)
                page.collapseOffsetPx = (page.collapseOffsetPx - delta)
                    .coerceIn(0f, page.collapseRangePx)
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val target = if (page.collapseFraction < 0.5f) 0f else page.collapseRangePx
                settling?.cancel()
                settling = scope.launch {
                    animate(
                        initialValue = page.collapseOffsetPx,
                        targetValue = target,
                        animationSpec = spring(dampingRatio = 1f, stiffness = 450f),
                    ) { value, _ -> page.collapseOffsetPx = value.coerceIn(0f, page.collapseRangePx) }
                }
                return Velocity.Zero
            }
        }
    }
    DisposableEffect(connection) { onDispose { connection.settling?.cancel() } }
    SideEffect {
        val fraction = page.collapseFraction
        if (page.collapseRangePx != rangePx) {
            page.collapseRangePx = rangePx
            page.collapseOffsetPx = fraction * rangePx
        }
    }
    DisposableEffect(host, pageKey, page) {
        host.pages[pageKey] = page
        onDispose {
            if (host.pages[pageKey] === page) host.pages.remove(pageKey)
        }
    }
    CompositionLocalProvider(LocalLauncherBannerPage provides page) {
        Box(Modifier.fillMaxSize().nestedScroll(connection)) { content() }
    }
}

/** Register a slot without copying its state into a second, independently composed header. */
@Composable
internal fun RegisterLauncherBanner(content: @Composable () -> Unit) {
    val page = requireNotNull(LocalLauncherBannerPage.current)
    val latestContent = rememberUpdatedState(content)
    val locals = currentCompositionLocalContext
    val slot = remember { @Composable { latestContent.value() } }
    SideEffect { page.locals = locals }
    DisposableEffect(page, slot) {
        page.content = slot
        onDispose {
            if (page.content === slot) {
                page.content = null
                page.locals = null
            }
        }
    }
}

/** Stable expanded inset: never feed the animated height back into a scrolling list. */
@Composable
internal fun launcherBannerExpandedHeight(fallback: Dp): Dp {
    val height = LocalLauncherBannerPage.current?.expandedHeightPx ?: 0
    return if (height > 0) with(LocalDensity.current) { height.toDp() } else fallback
}

/** Full-screen media is above the page chrome, unlike ordinary in-page content. */
@Composable
internal fun LauncherBannerVisibility(visible: Boolean) {
    val page = LocalLauncherBannerPage.current ?: return
    SideEffect { page.hidden = !visible }
    DisposableEffect(page) { onDispose { page.hidden = false } }
}

internal fun bannerPageWeight(position: Float, page: Int): Float =
    (1f - abs(position - page)).coerceIn(0f, 1f)

internal fun bannerPageTranslation(position: Float, page: Int, widthPx: Int): Float =
    (page - position) * widthPx

/** One glass surface, outside both the pager and navigation transitions. */
@Composable
internal fun LauncherBannerHost(
    state: LauncherBannerState,
    hazeState: HazeState,
    activeKey: Any,
    dockKeys: List<Any>,
    pagerState: PagerState,
    navigationDepth: Int = 1,
    modifier: Modifier = Modifier,
) {
    var previousDepth by remember { mutableIntStateOf(navigationDepth) }
    val navigationDirection = remember(activeKey) { if (navigationDepth < previousDepth) -1 else 1 }
    SideEffect { previousDepth = navigationDepth }
    val transition = updateTransition(activeKey, label = "launcherBannerNavigation")
    val position = pagerState.currentPage + pagerState.currentPageOffsetFraction
    val pages = state.pages.entries.filter { it.value.content != null }
    val navigationWeights = pages.map { (pageKey, _) ->
        key(pageKey) {
            val navigationWeight by transition.animateFloat(
                transitionSpec = { tween(360) },
                label = "bannerWeight:$pageKey",
            ) { target ->
                if (target == pageKey || (target == DockBannerKey && pageKey in dockKeys)) 1f else 0f
            }
            navigationWeight
        }
    }
    val visibilities = pages.map { (pageKey, page) ->
        key(pageKey) {
            val visibility by animateFloatAsState(
                targetValue = if (page.hidden) 0f else 1f,
                animationSpec = tween(180),
                label = "bannerVisibility:$pageKey",
            )
            visibility
        }
    }
    val weights = pages.mapIndexed { index, (pageKey, _) ->
        val dockIndex = dockKeys.indexOf(pageKey)
        navigationWeights[index] * visibilities[index] *
            if (dockIndex >= 0) bannerPageWeight(position, dockIndex) else 1f
    }
    val interactiveIndex = weights.indices.maxByOrNull { weights[it] }
    // Slots slide on a full-width rail inside ONE fixed glass surface, without crossfading text.
    // Keep their compositions so search focus, menus and profile controls survive a page change.
    FrostedGlassChrome(
        modifier = modifier.fillMaxWidth().testTag(LAUNCHER_BANNER_TAG),
        hazeState = hazeState,
        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
        shadowElevation = 6.dp,
    ) {
        Layout(
            content = {
                pages.forEachIndexed { index, (pageKey, page) ->
                    key(pageKey) {
                        val interactive = index == interactiveIndex && weights[index] > 0f
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .then(if (interactive) Modifier else Modifier.clearAndSetSemantics {})
                                .pointerInput(interactive) {
                                    if (!interactive) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                awaitPointerEvent(PointerEventPass.Initial)
                                                    .changes.forEach { it.consume() }
                                            }
                                        }
                                    }
                                }
                                .onSizeChanged {
                                    if (page.collapseFraction == 0f) page.expandedHeightPx = it.height
                                },
                        ) {
                            page.locals?.let { locals ->
                                CompositionLocalProvider(locals) {
                                    CompositionLocalProvider(
                                        LocalLauncherBannerCollapseFraction provides page.collapseFraction,
                                    ) { page.content?.invoke() }
                                }
                            }
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val placeables = measurables.map {
                it.measure(constraints.copy(minHeight = 0))
            }
            val totalWeight = weights.sum()
            val height = if (totalWeight > 0f) {
                placeables.indices.sumOf { (placeables[it].height * weights[it]).toDouble() }
                    .div(totalWeight.coerceAtLeast(1f)).roundToInt()
            } else 0
            layout(constraints.maxWidth, constraints.constrainHeight(height)) {
                placeables.forEachIndexed { index, placeable ->
                    val weight = weights[index]
                    if (weight > 0f) {
                        val dockIndex = dockKeys.indexOf(pages[index].key)
                        val pagerShift = if (dockIndex >= 0) {
                            bannerPageTranslation(position, dockIndex, constraints.maxWidth)
                        } else 0f
                        val entering = activeKey == pages[index].key ||
                            (activeKey == DockBannerKey && dockIndex >= 0)
                        val navigationShift = (1f - navigationWeights[index]) * constraints.maxWidth *
                            navigationDirection * if (entering) 1f else -1f
                        placeable.placeWithLayer((pagerShift + navigationShift).roundToInt(), 0, zIndex = weight) {
                            alpha = visibilities[index]
                        }
                    }
                }
            }
        }
    }
}

/** Collapse a measured slot continuously, rather than toggling visibility at a scroll threshold. */
@Composable
internal fun BannerReveal(fraction: Float, content: @Composable () -> Unit) {
    val reveal = (1f - fraction).coerceIn(0f, 1f)
    Layout(
        modifier = Modifier.clipToBounds()
            .then(if (reveal < 0.05f) Modifier.clearAndSetSemantics {} else Modifier),
        content = content,
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val fullHeight = placeables.maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, (fullHeight * reveal).roundToInt()) {
            if (reveal > 0f) {
                placeables.forEach { placeable ->
                    placeable.placeWithLayer(0, (-8.dp.toPx() * fraction).roundToInt()) {
                        alpha = reveal * reveal
                    }
                }
            }
        }
    }
}

/** Existing Scaffold top bars use the same outer banner; their inset follows its compact row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LauncherTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val page = LocalLauncherBannerPage.current
    val content: @Composable () -> Unit = {
        TopAppBar(
            modifier = modifier,
            title = title,
            navigationIcon = navigationIcon,
            actions = actions,
            expandedHeight = lerp(80.dp, 64.dp, LocalLauncherBannerCollapseFraction.current),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
            ),
        )
    }
    if (page == null) {
        content()
    } else {
        RegisterLauncherBanner(content)
        Column {
            Spacer(Modifier.statusBarsPadding())
            Spacer(Modifier.height(lerp(80.dp, 64.dp, page.collapseFraction)))
        }
    }
}
