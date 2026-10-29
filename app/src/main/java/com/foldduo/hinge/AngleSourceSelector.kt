package com.foldduo.hinge

/**
 * Selects an angle source without mixing stale public/private observations.
 * Sensor data are evaluated independently from the Android framework so
 * behavior can be regression-tested in GitHub Actions.
 */
class AngleSourceSelector(
    private val privateFreshNanos: Long = 2_000_000_000L,
) {
    private var lastPrivateTimeNanos: Long? = null

    fun onPrivate(angle: Float, timestampNanos: Long): Float? {
        if (!angle.isFinite() || angle !in 0f..180f) return null
        lastPrivateTimeNanos = timestampNanos
        return angle
    }

    fun onPublic(angle: Float, timestampNanos: Long): Float? {
        if (!angle.isFinite() || angle !in 0f..180f) return null
        val last = lastPrivateTimeNanos
        if (last != null && timestampNanos >= last && timestampNanos - last <= privateFreshNanos) return null
        return angle
    }
}
