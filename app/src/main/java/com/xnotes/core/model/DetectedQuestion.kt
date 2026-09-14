package com.xnotes.core.model

data class DetectedQuestion(
    val id: String,
    val sourcePageIndex: Int,
    val crop: NormalizedRect,
    val reasons: List<String> = emptyList(),
    val accepted: Boolean = false,
) {
    val likely get() = reasons.isEmpty()
}

object QuestionOverlap {
    fun duplicate(a: NormalizedRect, b: NormalizedRect): Boolean = iou(a, b) >= 0.85
    fun possibleDuplicate(a: NormalizedRect, b: NormalizedRect): Boolean {
        val intersection = (minOf(a.right,b.right)-maxOf(a.left,b.left)).coerceAtLeast(0.0) *
            (minOf(a.bottom,b.bottom)-maxOf(a.top,b.top)).coerceAtLeast(0.0)
        val smaller = minOf((a.right-a.left)*(a.bottom-a.top),(b.right-b.left)*(b.bottom-b.top))
        return iou(a,b) >= 0.6 || intersection / smaller >= 0.8
    }
    fun iou(a: NormalizedRect, b: NormalizedRect): Double {
        val intersection = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0.0) *
            (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0.0)
        return intersection / ((a.right - a.left) * (a.bottom - a.top) +
            (b.right - b.left) * (b.bottom - b.top) - intersection)
    }
}

object ProposalGeometry {
    private const val MIN_SIZE = 0.005
    fun between(x1: Double, y1: Double, x2: Double, y2: Double): NormalizedRect? {
        val l = minOf(x1,x2).coerceIn(0.0,1.0); val t = minOf(y1,y2).coerceIn(0.0,1.0)
        val r = maxOf(x1,x2).coerceIn(0.0,1.0); val b = maxOf(y1,y2).coerceIn(0.0,1.0)
        return if (r-l < MIN_SIZE || b-t < MIN_SIZE) null else NormalizedRect(l,t,r,b)
    }
    fun move(r: NormalizedRect, dx: Double, dy: Double): NormalizedRect {
        val x = dx.coerceIn(-r.left, 1-r.right); val y = dy.coerceIn(-r.top, 1-r.bottom)
        return NormalizedRect(r.left+x,r.top+y,r.right+x,r.bottom+y)
    }
    fun resize(r: NormalizedRect, corner: Int, x: Double, y: Double): NormalizedRect {
        require(corner in 0..3)
        return NormalizedRect(
            if(corner == 0 || corner == 3) x.coerceIn(0.0,(r.right-MIN_SIZE).coerceAtLeast(0.0)) else r.left,
            if(corner == 0 || corner == 1) y.coerceIn(0.0,(r.bottom-MIN_SIZE).coerceAtLeast(0.0)) else r.top,
            if(corner == 1 || corner == 2) x.coerceIn((r.left+MIN_SIZE).coerceAtMost(1.0),1.0) else r.right,
            if(corner == 2 || corner == 3) y.coerceIn((r.top+MIN_SIZE).coerceAtMost(1.0),1.0) else r.bottom)
    }
}
