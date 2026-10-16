import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SlingBreakBundleTest {
    @get:Rule
    val temporary = TemporaryFolder(File("../../agent-tmp").apply { mkdirs() })

    private fun bundle(root: File, launcher: String = "src/ui/launcher-mode.js"): File {
        File(root, "index.html").apply {
            parentFile.mkdirs()
            writeText("<html></html>")
        }
        File(root, launcher).apply {
            parentFile.mkdirs()
            writeText("// launcher bridge")
        }
        return root
    }

    @Test
    fun resolvesCurrentRepositoryRuntime() {
        val source = temporary.newFolder()
        val runtime = bundle(File(source, "game"))
        File(source, "android").mkdirs()
        File(source, "tests").mkdirs()

        assertEquals(runtime, SlingBreakBundle.require(source))
    }

    @Test
    fun resolvesDirectRuntimeAndNormalizedCache() {
        val runtime = bundle(temporary.newFolder())

        assertEquals(runtime, SlingBreakBundle.require(runtime))
    }

    @Test
    fun resolvesLegacyFlatRefAndCache() {
        val runtime = bundle(temporary.newFolder(), "launcher-mode.js")

        assertEquals(runtime, SlingBreakBundle.require(runtime))
    }

    @Test
    fun prefersGameOverRootWhenBothAreValid() {
        val source = bundle(temporary.newFolder(), "launcher-mode.js")
        val runtime = bundle(File(source, "game"))

        assertEquals(runtime, SlingBreakBundle.require(source))
    }

    @Test
    fun fallsBackToValidRootWhenGameDirectoryIsIncomplete() {
        val source = bundle(temporary.newFolder(), "launcher-mode.js")
        File(source, "game/index.html").apply {
            parentFile.mkdirs()
            writeText("incomplete")
        }

        assertEquals(source, SlingBreakBundle.require(source))
    }

    @Test
    fun rejectsIndexWithoutLauncher() {
        val source = temporary.newFolder()
        File(source, "index.html").writeText("not a launcher runtime")

        assertNull(SlingBreakBundle.resolve(source))
    }

    @Test
    fun rejectsLauncherWithoutIndex() {
        val source = temporary.newFolder()
        File(source, "src/ui/launcher-mode.js").apply {
            parentFile.mkdirs()
            writeText("// bridge")
        }

        assertNull(SlingBreakBundle.resolve(source))
    }

    @Test
    fun missingRuntimeHasActionableError() {
        val source = File(temporary.root, "missing")
        try {
            SlingBreakBundle.require(source)
            throw AssertionError("Expected missing runtime to fail")
        } catch (failure: GradleException) {
            assertTrue(failure.message.orEmpty().contains("src/ui/launcher-mode.js"))
            assertTrue(failure.message.orEmpty().contains("slingBreak.sourceDir"))
        }
    }
}
