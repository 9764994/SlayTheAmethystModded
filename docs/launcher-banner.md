# Shared launcher banner

The launcher renders one glass banner in `LauncherContent`, outside the horizontal pager and
Navigation 3 page transitions. Pages contribute content, not separate glass surfaces.

## Motion

- Horizontal paging uses `currentPage + currentPageOffsetFraction`. Adjacent slots slide left/right
  on a full-width rail, and the glass height interpolates between their measured heights.
  Reversing or cancelling a swipe reverses the same transition; there is no half-page height snap.
- Opening a child route slides its slot into the same surface; back reverses the direction.
  Navigation and pager animations do not translate the glass itself.
- A page-level nested-scroll observer updates a continuous collapse fraction without consuming
  scroll or fling velocity. The title row, icon, subtitle and expandable controls share this
  fraction. On release, a damped spring settles a partially collapsed banner.
- Each page saves its own collapse state. Switching tabs does not recreate its banner controls.
- The four main pages share `LauncherNavigationIcons` with the bottom dock: gamepad, package,
  storefront and settings sliders. Both locations reference the exact same vectors.

## Adding a page

`LauncherBannerPage` is supplied by the dock pager and the navigation entry wrapper. Inside it:

- Use `LauncherTopAppBar` in a conventional `Scaffold.topBar`; the in-page slot reserves space
  while the actual toolbar is rendered by the shared host.
- For a richer header, use `FloatingGlassHeader` or `CollapsibleFloatingGlassHeader` with
  `LauncherBannerTitleRow`. These register their content with the host and still work independently
  in previews without the host.
- Use `launcherBannerExpandedHeight(fallback)` for an overlay header's **stable expanded** list
  spacer. Do not feed the banner's current animated height into list content padding: that creates
  a scroll/measure feedback loop and visible jumping.
- Keep essential actions in `pinnedContent` and persistent progress in `footerContent`. Put only
  optional details/search/filter controls in `expandedContent`.
- Call `LauncherBannerVisibility(false)` while an in-page full-screen media overlay is open.
  Dialogs and modal sheets already render in their own window above the banner.

The host preserves each slot's composition-local context, so navigation, theme, localization and
view-model owners remain those of the original page. Only the highest-weight visible slot exposes
accessibility actions and receives pointer input; inactive content cannot be tapped through the
transition.

Welcome/import and resource-preparation screens that have no top toolbar do not gain an artificial
banner. Full-screen media also hides it. Every regular launcher page with top chrome uses the host.

## Regression tests

- `LauncherBannerWeightTest`: continuity across the pager's current-page boundary, full-width
  horizontal translation, normalized weights and invisible non-adjacent pages.
- `LauncherBannerInstrumentedTest`: one surface, intermediate swipe height, correct actions,
  continuous collapse, stable scroll inset, real nested scrolling and child-route return state.
- `LauncherNavigationIconsTest`: the four banner/dock icon pairs use identical vector instances;
  child-page category icons remain unchanged.
- `SettingsHeaderRenderingInstrumentedTest`: actual settings navigation and visible text pixels
  after transitions settle, both before and after scrolling.
