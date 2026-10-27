package io.stamethyst.ui.aimod

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AiInboxStoreInstrumentedTest {
    @Test fun pendingInputSurvivesReopenAndConcurrentOutputWrites() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "inbox-${UUID.randomUUID()}")
        try {
            val store = AiConversationStore(root)
            val item = AiInboxItem("input", "change direction", delivery = AiInboxDelivery.STEER)
            store.mutate("test", create = AiEditorSession("test", messages = listOf(
                AiEditorMessage(1, true, "original"), AiEditorMessage(2, false, "", streaming = true),
            ))) { it }
            store.mutate("test") { it.copy(inbox = listOf(item)) }
            store.updateMessage("test", 2) { it.copy(text = "working") }
            val reopened = AiConversationStore(root)
            assertEquals(listOf(item), reopened.load("test")!!.inbox)
            reopened.mutate("test") { it.deliverInbox(it.nextInboxItem(false)!!, "model") }
            val delivered = store.load("test")!!
            assertTrue(delivered.inbox.isEmpty())
            assertEquals("working", delivered.messages[1].text)
            assertFalse(delivered.messages[1].streaming)
            assertEquals("change direction", delivered.messages[2].text)
        } finally {
            root.deleteRecursively()
        }
    }
}
