package com.wsolv.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.wsolv.core.feedback.Feedback

/** Wordle tile palette, matching the canonical board colors. */
val WordleGreen = Color(0xFF6AAA64)
val WordleYellow = Color(0xFFC9B458)
val WordleGray = Color(0xFF787C7E)

/** Text drawn on top of a colored Wordle tile. */
val OnTile = Color(0xFFFFFFFF)

/**
 * Map a feedback color code to its tile background [Color].
 *
 * Any value other than [Feedback.YELLOW] or [Feedback.GREEN] (including
 * [Feedback.GRAY]) renders as the gray tile.
 */
fun tileColor(code: Int): Color =
    when (code) {
        Feedback.GREEN -> WordleGreen
        Feedback.YELLOW -> WordleYellow
        else -> WordleGray
    }
