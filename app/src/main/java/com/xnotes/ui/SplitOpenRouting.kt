package com.xnotes.ui

/** Visual side selected by "Open another PDF". */
enum class SplitSide { LEFT, RIGHT }

/** Keeps visual left/right routing independent from which Editor instance owns the split. */
internal fun paneOnSide(primaryOnLeft: Boolean, side: SplitSide): Pane =
    if ((side == SplitSide.LEFT) == primaryOnLeft) Pane.PRIMARY else Pane.SECONDARY

/** A first split keeps the live editor intact and opens the picked file in the other editor. */
internal fun layoutForFirstSplit(newDocumentSide: SplitSide): Boolean =
    newDocumentSide == SplitSide.RIGHT

/** A replacement retains its panel state; a newly created pane starts with every panel closed. */
internal fun sidebarAfterDocumentOpen(previous: Boolean?): Boolean = previous ?: false

/** Source questions shown on the right always route into the visual-left workspace. */
internal fun questionWorkspacePane(primaryOnLeft: Boolean): Pane =
    paneOnSide(primaryOnLeft, SplitSide.LEFT)
