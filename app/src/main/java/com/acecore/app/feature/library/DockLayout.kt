package com.acecore.app.feature.library

/**
 * Where the dock's tabs go, in dp. Pure arithmetic (unit-tested in DockLayoutTest).
 *
 * The shapes on the bar's top edge (notch, crest, lamp, drop) are [NOTCH_HALF] wide on each side of the selected
 * tab's centre and must stay on the straight part of the edge, clear of the rounded ends (radius [BAR_HEIGHT] / 2).
 * So the first and last tab centres must be at least `reach` from the ends; the end slots are inset by `pad` to get
 * there, and all slots stay equal. With little room the straight run before the ends ([END_FLAT]) is given up first
 * and slots may narrow to [SLOT_TIGHT]; with still less room the dock scrolls (slots of [SLOT_MIN]). On wide screens
 * (tablets, landscape) the dock is capped at [SLOT_MAX]-wide slots and centred instead of stretching edge to edge.
 */
internal data class DockLayout(val width: Float, val count: Int, val slot: Float, val pad: Float, val reach: Float) {
    val contentWidth: Float get() = slot * count + pad * 2
    /** Centre of tab [i] from the content's start edge. */
    fun centre(i: Int) = pad + slot * (i + 0.5f)
    val scrolls: Boolean get() = contentWidth > width + 0.5f

    companion object {
        const val BAR_HEIGHT = 64f
        const val BUBBLE = 48f
        const val NOTCH_HALF = BUBBLE / 2 + 18f
        const val END_FLAT = 12f
        const val SLOT_MIN = 64f
        const val SLOT_TIGHT = 56f
        const val SLOT_MAX = 96f

        fun of(available: Float, n: Int): DockLayout {
            require(n >= 1)
            val full = BAR_HEIGHT / 2 + NOTCH_HALF + END_FLAT
            // With inset end slots the content is (n - 1) slots plus a reach at each end.
            val width = minOf(available, if (n > 1) SLOT_MAX * (n - 1) + full * 2 else maxOf(SLOT_MAX, full * 2))
            fun solve(reach: Float): Pair<Float, Float> {
                val even = width / n
                if (reach <= even / 2) return even to 0f
                val slot = if (n > 1) (width - reach * 2) / (n - 1) else width
                return slot to maxOf(0f, reach - slot / 2)
            }
            var reach = full
            var (slot, pad) = solve(reach)
            if (slot < SLOT_MIN) { reach = full - END_FLAT; val s = solve(reach); slot = s.first; pad = s.second }
            if (slot < SLOT_TIGHT) { slot = SLOT_MIN; pad = maxOf(0f, reach - slot / 2) } // too many tabs: it scrolls
            return DockLayout(width, n, slot, pad, reach)
        }
    }
}
