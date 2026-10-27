package io.stamethyst.ui.main

internal enum class ContributionAction { UPLOAD, UPGRADE, SYNCED, REMOTE_NEWER }

internal fun contributionAction(localVersion: String, sharedVersion: String?): ContributionAction {
    if (sharedVersion == null) return ContributionAction.UPLOAD
    val comparison = comparePatchVersions(localVersion, sharedVersion)
    return when {
        comparison > 0 -> ContributionAction.UPGRADE
        comparison < 0 -> ContributionAction.REMOTE_NEWER
        else -> ContributionAction.SYNCED
    }
}

internal fun comparePatchVersions(left: String, right: String): Int {
    fun tokens(value: String) = Regex("\\d+").findAll(value).mapNotNull { it.value.toLongOrNull() }.toList()
    val a = tokens(left)
    val b = tokens(right)
    for (index in 0 until maxOf(a.size, b.size)) {
        val comparison = (a.getOrNull(index) ?: 0L).compareTo(b.getOrNull(index) ?: 0L)
        if (comparison != 0) return comparison
    }
    return 0
}
