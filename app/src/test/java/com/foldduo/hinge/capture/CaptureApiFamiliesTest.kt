package com.foldduo.hinge.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureApiFamiliesTest {
    @Test fun prefersNewApiThenLegacyFallback() {
        assertEquals(listOf("android.window.ScreenCaptureInternal", "android.window.ScreenCapture"), CaptureApiFamilies.names)
    }
    @Test fun fallsBackWhenModernFamilyIsIncomplete() {
        assertEquals("android.window.ScreenCapture", CaptureApiFamilies.firstCompatible {
            it == "android.window.ScreenCapture"
        })
    }

    @Test fun rejectsWhenNoFamilyIsCompatible() {
        assertNull(CaptureApiFamilies.firstCompatible { false })
    }
}

