package io.stamethyst.backend.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class LatestLogCrashDetectorTest {
    @Test
    fun detect_recognizesMtsModReportWithoutEarlierGameCrashedLine() {
        val logFile = createTempLog(
            """
            Mods in stacktrace:
             - amethyst.ai.patch.bloodstains.patch-1790300082256-09148499 (0.1.0)
            Cause:
            java.lang.NullPointerException
                at bloodstainschinese.BloodstainsChineseStringsPatch.isApplied(BloodstainsChineseStringsPatch.java:92)
                at bloodstainschinese.BloodstainsChineseStringsPatch.loadAndVerify(BloodstainsChineseStringsPatch.java:83)
            """.trimIndent()
        )

        assertEquals(
            "java.lang.NullPointerException at bloodstainschinese.BloodstainsChineseStringsPatch.isApplied(BloodstainsChineseStringsPatch.java:92)",
            LatestLogCrashDetector.detect(logFile)?.detail
        )
        assertEquals(true, LatestLogCrashDetector.isCrashMarkerLine("Mods in stacktrace:"))
        assertEquals(false, LatestLogCrashDetector.isCrashMarkerLine("java.lang.NullPointerException"))
    }

    @Test
    fun detect_returnsNull_whenOnlyExceptionStackPresentWithoutCrashMarker() {
        val logFile = createTempLog(
            """
            java.lang.NumberFormatException: null
            	at VUPShionMod.util.ShionConfig.getInt(ShionConfig.java:66)
            	at VUPShionMod.util.SaveHelper.loadSkins(SaveHelper.java:259)
            11:08:40.866 INFO saveAndContinue.SaveAndContinue> KamiShion save does NOT exist!
            """.trimIndent()
        )

        val summary = LatestLogCrashDetector.detect(logFile)

        assertNull(summary)
    }

    @Test
    fun detect_extractsExceptionDetail_afterCrashMarker() {
        val logFile = createTempLog(
            """
            11:08:40.866 INFO saveAndContinue.SaveAndContinue> KamiShion save does NOT exist!
            Game crashed.
            Cause:
            java.lang.IllegalStateException: boom
            	at example.Test.render(Test.java:42)
            """.trimIndent()
        )

        val summary = LatestLogCrashDetector.detect(logFile)

        assertEquals(
            "java.lang.IllegalStateException: boom at example.Test.render(Test.java:42)",
            summary?.detail
        )
    }

    @Test
    fun readLastNonBlankLine_returnsLastNonEmptyLine() {
        val logFile = createTempLog(
            """
            first

            second
            """.trimIndent() + "\n\n"
        )

        val lastLine = LatestLogCrashDetector.readLastNonBlankLine(logFile)

        assertEquals("second", lastLine)
    }

    private fun createTempLog(content: String): File {
        val file = Files.createTempFile("latest-log-crash-detector", ".log").toFile()
        file.writeText(content, StandardCharsets.UTF_8)
        file.deleteOnExit()
        return file
    }
}
