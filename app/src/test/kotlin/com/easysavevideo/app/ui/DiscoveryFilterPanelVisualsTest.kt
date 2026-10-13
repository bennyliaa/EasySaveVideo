package com.easysavevideo.app.ui

import androidx.compose.ui.graphics.Color
import com.easysavevideo.app.theme.toComposeVisuals
import com.easysavevideo.theme.EsvTheme
import dev.skinkit.contract.*
import org.junit.Assert.*
import org.junit.Test

/** Value mapping tests, not Android rendering or modal transition acceptance. */
class DiscoveryFilterPanelVisualsTest {
    @Test fun actualLightAndDarkDefaultsSeparateInlineAndFloatingRoles() {
        for (mode in listOf(ColorMode.LIGHT, ColorMode.DARK)) {
            val v = EsvTheme(VisualConfiguration(mode, 2.0)).readSnapshot().toComposeVisuals()
            val inline = discoveryFilterPanelVisuals(v, DiscoveryFilterPanelPresentation.INLINE)
            val floating = discoveryFilterPanelVisuals(v, DiscoveryFilterPanelPresentation.FLOATING)
            assertEquals(if (mode == ColorMode.DARK) Color(0xff152832) else Color(0xffffffff), inline.background)
            assertEquals(if (mode == ColorMode.DARK) Color(0xff1c3440) else Color(0xffedf4f6), floating.background)
            assertEquals(v.colors.surface, inline.background)
            assertEquals(v.colors.elevated, floating.background)
            assertEquals(16f, inline.corner.value, 0f)
            assertEquals(24f, floating.corner.value, 0f)
            assertEquals(12f, v.corner.value, 0f)
            assertNotEquals(v.corner, inline.corner)
            assertNotEquals(v.corner, floating.corner)
        }
    }

    @Test fun sourceOverridesReachBothActualRolesThroughOneDetachedSnapshot() {
        val theme = EsvTheme()
        var pins = 0
        var closes = 0
        val source = object : PinnedThemeSource by theme.provider {
            override fun pin(): ThemeReadView {
                pins++
                val view = theme.provider.pin()
                return object : ThemeReadView by view {
                    override fun getColor(token: ColorToken) = when (token) {
                        theme.surface -> ColorValue.argb(0xff173b45.toInt())
                        theme.elevated -> ColorValue.argb(0xff294955.toInt())
                        else -> view.getColor(token)
                    }
                    override fun getDimension(token: DimensionToken): DimensionValue {
                        val amount = when (token) {
                            theme.cardCorner -> 13.0
                            theme.sheetCorner -> 21.0
                            else -> null
                        }
                        return if (amount == null) view.getDimension(token)
                        else DimensionValue(amount, LogicalUnit.LENGTH, DimensionPurpose.CORNER)
                    }
                    override fun release() { closes++; view.release() }
                    override fun close() = release()
                }
            }
        }
        val frame = theme.readSnapshot(source)
        val v = frame.toComposeVisuals()
        val inline = discoveryFilterPanelVisuals(v, DiscoveryFilterPanelPresentation.INLINE)
        val floating = discoveryFilterPanelVisuals(v, DiscoveryFilterPanelPresentation.FLOATING)
        assertEquals(Color(0xff173b45), inline.background)
        assertEquals(Color(0xff294955), floating.background)
        assertEquals(13f, inline.corner.value, 0f)
        assertEquals(21f, floating.corner.value, 0f)
        assertEquals(v.cardCorner, inline.corner)
        assertEquals(v.sheetCorner, floating.corner)
        assertEquals(12f, v.corner.value, 0f)
        assertEquals(frame.snapshot.generation, v.generation)
        assertEquals(1, pins)
        assertEquals(1, closes)
        assertEquals(inline, discoveryFilterPanelVisuals(v, DiscoveryFilterPanelPresentation.INLINE))
        assertEquals(floating, discoveryFilterPanelVisuals(v, DiscoveryFilterPanelPresentation.FLOATING))
        assertEquals(1, pins)
        assertEquals(1, closes)
    }
}
