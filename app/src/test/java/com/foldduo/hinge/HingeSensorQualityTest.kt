package com.foldduo.hinge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class HingeSensorQualityTest {
    @Test fun recognizesCoarseHingeStates() {
        val quality = HingeSensorQuality()
        listOf(180f, 90f, 0f, 180f).forEach(quality::observe)
        assertEquals("coarse states", quality.label)
    }

    @Test fun recognizesIntermediateMeasurements() {
        val quality = HingeSensorQuality()
        listOf(180f, 175f, 160f, 145f).forEach(quality::observe)
        assertEquals("varying", quality.label)
    }

    @Test fun ignoresInvalidSensorSamples() {
        val quality = HingeSensorQuality()
        listOf(Float.NaN, -1f, 200f).forEach(quality::observe)
        assertEquals("sampling", quality.label)
    }

    @Test fun coarseSourceRecognizedBeforeThirdPostureState() {
        val quality = HingeSensorQuality()
        quality.observe(180f)
        assertFalse(quality.isCoarse)
        quality.observe(90f)
        assertTrue(quality.isCoarse)
    }

    @Test fun genuineIntermediateAnglesKeepContinuousSourceEnabled() {
        val quality = HingeSensorQuality()
        listOf(180f, 176f, 170f, 160f).forEach(quality::observe)
        assertFalse(quality.isCoarse)
    }
}
