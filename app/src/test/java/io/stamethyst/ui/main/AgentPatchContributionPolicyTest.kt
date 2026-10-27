package io.stamethyst.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentPatchContributionPolicyTest {
    @Test fun unsharedPatchCanBeUploaded() {
        assertEquals(ContributionAction.UPLOAD, contributionAction("1.0.0", null))
    }

    @Test fun newerLocalVersionCanUpgradeSharedPatch() {
        assertEquals(ContributionAction.UPGRADE, contributionAction("1.10.0", "1.9.0"))
    }

    @Test fun equalVersionIsAlreadySynced() {
        assertEquals(ContributionAction.SYNCED, contributionAction("1.0", "1.0.0"))
    }

    @Test fun olderLocalVersionCannotOverwriteNewerSharedPatch() {
        assertEquals(ContributionAction.REMOTE_NEWER, contributionAction("1.1.0", "1.2.0"))
    }
}
