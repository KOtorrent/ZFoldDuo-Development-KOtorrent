package com.foldduo.hinge.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureApiFamiliesTest {
    @Test fun prefersNewApiThenLegacyFallback() {
        assertEquals(listOf("android.window.ScreenCaptureInternal", "android.window.ScreenCapture"), CaptureApiFamilies.names)
    }
}
