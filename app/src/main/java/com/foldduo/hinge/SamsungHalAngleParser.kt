package com.foldduo.hinge

/** Samsung sensor-HAL hinge observations exposed to the shell log reader. */
data class SamsungHalAngle(val angle: Float, val sampleNanos: Long, val source: String)

object SamsungHalAngleParser {
    private val folding = Regex("""\]folding_angle ts=(\d+) ns value \[\s*(\d+)/""")
    private val lid = Regex("""\]lid_angle_fusion ts=(\d+) ns value \[\d+/\s*(\d+)/""")

    fun parse(line: String): SamsungHalAngle? {
        val match = folding.find(line) ?: lid.find(line) ?: return null
        val angle = match.groupValues[2].toFloatOrNull() ?: return null
        val timestamp = match.groupValues[1].toLongOrNull() ?: return null
        if (angle !in 0f..180f || timestamp <= 0L) return null
        return SamsungHalAngle(angle, timestamp, if (match.regex == folding) "folding_angle" else "lid_angle_fusion")
    }
}
