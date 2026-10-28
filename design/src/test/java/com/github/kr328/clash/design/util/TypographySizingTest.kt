package com.github.kr328.clash.design.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TypographySizingTest {
    @Test fun sakuraDefaultSizeIsSlightlySmaller() {
        val scale = typographyScale(1, false, 140)
        assertEquals(13.16f, 14f * scale, .001f)
        assertEquals(15.04f, 16f * scale, .001f)
        assertEquals(16.92f, 18f * scale, .001f)
    }

    @Test fun otherThemesKeepTheirExistingSizes() {
        for (skin in listOf(-1, 0, 2, 3)) {
            assertEquals(1f, typographyScale(skin, false, 120), .001f)
            assertEquals(1.2f, typographyScale(skin, true, 120), .001f)
        }
    }

    @Test fun sakuraStillHonorsCustomScaleAndItsLimits() {
        assertEquals(.94f, typographyScale(1, true, 100), .001f)
        assertEquals(1.128f, typographyScale(1, true, 120), .001f)
        assertEquals(.752f, typographyScale(1, true, 60), .001f)
        assertEquals(1.316f, typographyScale(1, true, 160), .001f)
    }

    @Test fun repeatedRefreshDoesNotKeepShrinkingSakuraText() {
        var base: Float? = null
        var applied: Float? = null
        var current = 14f
        repeat(100) {
            base = typographyBaseSize(base, applied, current)
            applied = base!! * typographyScale(1, false, 100)
            current = applied!!
        }
        assertEquals(13.16f, current, .001f)
    }

    @Test fun switchingAwayFromSakuraRestoresOriginalSize() {
        val applied = 14f * typographyScale(1, false, 100)
        val base = typographyBaseSize(14f, applied, applied)
        assertEquals(14f, base * typographyScale(0, false, 100), .001f)
    }

    @Test fun repeatedRefreshDoesNotCompoundScaling() {
        var base: Float? = null
        var applied: Float? = null
        var current = 14f
        repeat(100) {
            base = typographyBaseSize(base, applied, current)
            applied = base!! * 1.4f
            current = applied!!
        }
        assertEquals(19.6f, current, .001f)
    }

    @Test fun disablingOrChangingScaleUsesOriginalSize() {
        val base = typographyBaseSize(14f, 19.6f, 19.6f)
        assertEquals(14f, base, .001f)
        assertEquals(11.2f, base * .8f, .001f)
    }

    @Test fun explicitComponentSizeChangeGetsANewBaseline() {
        assertEquals(18f, typographyBaseSize(14f, 19.6f, 18f), .001f)
    }

    @Test fun floatingPointRoundingDoesNotResetBaseline() {
        assertEquals(14f, typographyBaseSize(14f, 19.6f, 19.600002f), .001f)
    }
}
