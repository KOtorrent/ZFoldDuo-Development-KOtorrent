package com.foldduo.hinge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SamsungHalAngleParserTest {
    @Test fun parsesFoldingSensor() {
        val sample = SamsungHalAngleParser.parse("I sensors-hal: [0]folding_angle ts=123456789012 ns value [ 89/0]")
        assertEquals(89f, sample!!.angle)
        assertEquals(123456789012L, sample.sampleNanos)
        assertEquals("folding_angle", sample.source)
    }

    @Test fun parsesLidFusionSensor() {
        val sample = SamsungHalAngleParser.parse("I sensors-hal: [0]lid_angle_fusion ts=123456789013 ns value [2/ 82/3]")
        assertEquals(82f, sample!!.angle)
        assertEquals("lid_angle_fusion", sample.source)
    }

    @Test fun rejectsUnrelatedOrInvalidLogs() {
        assertNull(SamsungHalAngleParser.parse("I sensors-hal: accelerometer angle=99"))
        assertNull(SamsungHalAngleParser.parse("I sensors-hal: [0]folding_angle ts=123 ns value [ 999/0]"))
        assertNull(SamsungHalAngleParser.parse("I sensors-hal: [0]lid_angle_fusion ts=0 ns value [2/ 80/3]"))
    }
}
