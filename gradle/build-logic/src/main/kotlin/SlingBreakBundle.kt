import org.gradle.api.GradleException
import java.io.File

/** Resolve only the deployable web runtime, never the upstream Android project or test tools. */
internal object SlingBreakBundle {
    fun resolve(source: File): File? =
        listOf(File(source, "game"), source).firstOrNull { root ->
            File(root, "index.html").isFile &&
                (File(root, "src/ui/launcher-mode.js").isFile ||
                    File(root, "launcher-mode.js").isFile)
        }

    fun require(source: File): File = resolve(source) ?: throw GradleException(
        "Sling Break source is missing the web runtime at $source; expected index.html and " +
            "src/ui/launcher-mode.js (or legacy launcher-mode.js) in game/ or the source root. " +
            "Set -PslingBreak.sourceDir=<repository-or-game-directory>."
    )
}
