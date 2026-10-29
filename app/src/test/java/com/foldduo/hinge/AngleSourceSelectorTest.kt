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
}
