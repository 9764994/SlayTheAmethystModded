package io.stamethyst.backend.steamcloud

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SteamCloudControlStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun snapshotReadDoesNotWaitForSaveTransactionWriter() {
        val control = File(temp.root, "control-v2.json")
        val expected = SteamCloudControlStore.State(disabled = true, backgroundLaunchRequested = true)
        SteamCloudControlStore.write(control, expected)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = Executors.newSingleThreadExecutor()
        val reader = Executors.newSingleThreadExecutor()
        try {
            val transaction = writer.submit {
                SteamCloudControlStore.locked(File(temp.root, "control.lock")) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // Models publishUiState while :steamcloud still owns the transaction lock.
            assertEquals(expected, reader.submit<SteamCloudControlStore.State> {
                SteamCloudControlStore.read(control)
            }.get(1, TimeUnit.SECONDS))
            release.countDown()
            transaction.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            writer.shutdownNow()
            reader.shutdownNow()
        }
    }

    @Test fun readBeforeMigrationDoesNotCreateOrWriteFiles() {
        val control = File(temp.root, "not-created/control-v2.json")
        assertEquals(SteamCloudControlStore.State(), SteamCloudControlStore.read(control))
        assertFalse(control.parentFile.exists())
    }

    @Test fun snapshotsReflectOtherWritersWithoutProcessCache() {
        val control = File(temp.root, "control-v2.json")
        val initial = SteamCloudControlStore.State()
        SteamCloudControlStore.write(control, initial)
        assertEquals(initial, SteamCloudControlStore.read(control))
        val updated = initial.copy(disabled = true, backgroundLaunchRequested = true, pendingSteamId = "123")
        SteamCloudControlStore.write(control, updated)
        assertEquals(updated, SteamCloudControlStore.read(control))
    }

    @Test fun transactionApplyAndRollbackAlwaysPublishCompleteControlFile() {
        val control = File(temp.root, "control-v2.json")
        // Neither snapshot equals legacy/default state: an unlink gap must fail this test.
        val original = SteamCloudControlStore.State(disabled = true, pendingSteamId = "original")
        val next = original.copy(backgroundLaunchRequested = true, pendingSteamId = "next")
        SteamCloudControlStore.write(control, original)
        val staged = File(temp.root, "staged.json")
        SteamCloudControlStore.write(staged, next)
        val stop = AtomicBoolean(false)
        val ready = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val snapshots = executor.submit {
            ready.countDown()
            while (!stop.get()) {
                val observed = SteamCloudControlStore.read(control)
                check(observed == original || observed == next) { "Incomplete control snapshot: $observed" }
            }
        }
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            repeat(30) {
                val transaction = SteamCloudFileTransaction.prepare(File(temp.root, "transactions"), temp.root,
                    listOf(SteamCloudPathReplacement(staged, control)))
                transaction.apply()
                assertEquals(next, SteamCloudControlStore.read(control))
                transaction.rollback()
                assertEquals(original, SteamCloudControlStore.read(control))
            }
        } finally {
            stop.set(true)
            try { snapshots.get(5, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
        }
    }
}
