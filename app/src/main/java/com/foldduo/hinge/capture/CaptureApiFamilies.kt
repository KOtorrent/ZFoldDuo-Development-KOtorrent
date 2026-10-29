package com.foldduo.hinge.capture

/** Preferred Android screenshot API families; runtime reflection selects an available one. */
object CaptureApiFamilies {
    val names = listOf("android.window.ScreenCaptureInternal", "android.window.ScreenCapture")
}
