package io.stamethyst.config

/** Runtime-only switches: changing these does not require rebuilding the patch cache. */
enum class MtsCacheStartupOptimization(val propertyName: String) {
    CLASS_FINDER_SCAN_CACHE("amethyst.runtime_compat.class_finder_scan_cache"),
    LAZY_CUSTOM_CARD_IMAGES("amethyst.runtime_compat.lazy_custom_card_images"),
    LAZY_STARTUP_CARD_DESCRIPTIONS("amethyst.runtime_compat.lazy_startup_card_descriptions"),
    LAZY_CARD_LIBRARY_SCREEN("amethyst.runtime_compat.lazy_card_library_screen"),
    FAST_CACHE_SPLASH("amethyst.runtime_compat.fast_cache_splash");

    val preferenceKey: String get() = "mts_cache_optimization_${name.lowercase(java.util.Locale.ROOT)}"
}
