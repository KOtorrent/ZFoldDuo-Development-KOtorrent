package com.foldduo.hinge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AngleSourceSelectorTest {
    @Test fun publicReadingUsedWhenPrivateUnavailable() {
        val selector = AngleSourceSelector()
        assertEquals(180f, selector.onPublic(180f, 1_000_000_000L))
        assertEquals(90f, selector.onPublic(90f, 2_000_000_000L))
    }

    @Test fun freshPrivateReadingTakesPriority() {
        val selector = AngleSourceSelector()
        assertEquals(160f, selector.onPrivate(160f, 3_000_000_000L))
        assertNull(selector.onPublic(90f, 3_100_000_000L))
        assertEquals(140f, selector.onPrivate(140f, 3_200_000_000L))
    }

    @Test fun publicReadingResumesWhenPrivateStale() {
        val selector = AngleSourceSelector()
        selector.onPrivate(150f, 1_000_000_000L)
        assertNull(selector.onPublic(90f, 3_000_000_000L))
        assertEquals(90f, selector.onPublic(90f, 3_000_000_001L))
    }

    @Test fun invalidAnglesAreRejected() {
        val selector = AngleSourceSelector()
        assertNull(selector.onPublic(Float.NaN, 1L))
        assertNull(selector.onPrivate(-20f, 1L))
        assertNull(selector.onPublic(200f, 2L))
    }

    @Test fun smoothClosingSequenceKeepsPrecisePrivateSamples() {
        val selector = AngleSourceSelector()
        val privateAngles = listOf(180f, 175f, 170f, 155f, 140f, 120f, 90f, 60f, 30f, 0f)
        for ((index, angle) in privateAngles.withIndex()) {
            val t = 1_000_000_000L + index * 100_000_000L
            assertEquals(angle, selector.onPrivate(angle, t))
            // Coarse public events must not replace a fresh private reading.
            assertNull(selector.onPublic(if (angle > 90f) 180f else 90f, t + 20_000_000L))
        }
    }

    @Test fun dropoutAllowsPublicFallbackAndPrivateRecovery() {
        val selector = AngleSourceSelector()
        assertEquals(165f, selector.onPrivate(165f, 1_000_000_000L))
        assertNull(selector.onPublic(90f, 1_300_000_000L))
        assertEquals(90f, selector.onPublic(90f, 3_500_000_000L))
        assertEquals(80f, selector.onPrivate(80f, 3_600_000_000L))
        assertNull(selector.onPublic(90f, 3_700_000_000L))
    }

    @Test fun invalidPrivateSampleDoesNotBlockPublicSensor() {
        val selector = AngleSourceSelector()
        assertNull(selector.onPrivate(Float.NaN, 100L))
        assertEquals(180f, selector.onPublic(180f, 200L))
    }

    @Test fun outOfOrderPublicSampleNeverOverridesFreshPrivateSensor() {
        val selector = AngleSourceSelector()
        selector.onPrivate(125f, 8_000_000_000L)
        // No pre-private sensor event should be accepted over a later private sample.
        assertNull(selector.onPublic(180f, 7_000_000_000L))
    }

    @Test fun privateSensorRejectsOutOfOrderAndDuplicateTimestamps() {
        val selector = AngleSourceSelector()
        assertEquals(175f, selector.onPrivate(175f, 5_000L))
        assertNull(selector.onPrivate(90f, 4_900L))
        assertNull(selector.onPrivate(90f, 5_000L))
        assertEquals(170f, selector.onPrivate(170f, 5_100L))
    }
}
