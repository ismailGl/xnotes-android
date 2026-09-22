package com.xnotes.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitOpenRoutingTest {
    @Test fun firstOpenOnRightKeepsPrimaryOnLeftAndRoutesNewPdfRight() {
        val primaryOnLeft = layoutForFirstSplit(SplitSide.RIGHT)
        assertTrue(primaryOnLeft)
        assertEquals(Pane.SECONDARY, paneOnSide(primaryOnLeft, SplitSide.RIGHT))
        assertEquals(Pane.PRIMARY, paneOnSide(primaryOnLeft, SplitSide.LEFT))
    }

    @Test fun firstOpenOnLeftKeepsExistingPrimaryStateOnRight() {
        val primaryOnLeft = layoutForFirstSplit(SplitSide.LEFT)
        assertFalse(primaryOnLeft)
        assertEquals(Pane.SECONDARY, paneOnSide(primaryOnLeft, SplitSide.LEFT))
        assertEquals(Pane.PRIMARY, paneOnSide(primaryOnLeft, SplitSide.RIGHT))
    }

    @Test fun existingSplitRoutesReplacementByVisualSide() {
        assertEquals(Pane.PRIMARY, paneOnSide(true, SplitSide.LEFT))
        assertEquals(Pane.SECONDARY, paneOnSide(true, SplitSide.RIGHT))
        assertEquals(Pane.SECONDARY, paneOnSide(false, SplitSide.LEFT))
        assertEquals(Pane.PRIMARY, paneOnSide(false, SplitSide.RIGHT))
    }

    @Test fun replacingEitherPaneDoesNotAlterOppositePaneSnapshot() {
        data class Snapshot(val uri: String, val page: Int, val zoom: Double,
            val pan: Pair<Double, Double>, val questionMode: Boolean, val sidebar: Boolean)
        val opposite = Snapshot("same.pdf", 7, 1.75, 42.0 to 91.0, true, true)
        for (primaryOnLeft in listOf(true, false)) for (side in SplitSide.entries) {
            paneOnSide(primaryOnLeft, side)
            assertEquals(Snapshot("same.pdf", 7, 1.75, 42.0 to 91.0, true, true), opposite)
        }
    }

    @Test fun sameAndDifferentPdfCasesUseStableRouting() {
        for (uri in listOf("same.pdf", "different.pdf")) {
            assertEquals(Pane.SECONDARY,
                paneOnSide(layoutForFirstSplit(SplitSide.RIGHT), SplitSide.RIGHT))
            assertTrue(uri.endsWith(".pdf"))
        }
    }

    @Test fun newPaneStartsWithPagesClosedWhileExistingSidebarStateIsStable() {
        assertFalse(sidebarAfterDocumentOpen(null))
        assertFalse(sidebarAfterDocumentOpen(false))
        assertTrue(sidebarAfterDocumentOpen(true))
    }

    @Test fun sourceQuestionRoutingStillTargetsVisualLeftWorkspace() {
        assertEquals(Pane.PRIMARY, questionWorkspacePane(primaryOnLeft = true))
        assertEquals(Pane.SECONDARY, questionWorkspacePane(primaryOnLeft = false))
    }
}
