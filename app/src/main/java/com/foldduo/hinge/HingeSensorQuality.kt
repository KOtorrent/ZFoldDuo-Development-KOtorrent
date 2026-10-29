package com.foldduo.hinge

/** Distinguishes plausible continuously varying hinge readings from coarse posture states. */
class HingeSensorQuality {
    private val distinct = LinkedHashSet<Int>()
    val label: String
        get() = when {
            distinct.size < 3 -> "sampling"
            distinct.size <= 3 && distinct.all { it == 0 || it == 90 || it == 180 } -> "coarse states"
            else -> "varying"
        }

    fun observe(angle: Float) {
        if (!angle.isFinite() || angle !in 0f..180f) return
        distinct.add(kotlin.math.round(angle).toInt())
        if (distinct.size > 24) distinct.remove(distinct.first())
    }
}
