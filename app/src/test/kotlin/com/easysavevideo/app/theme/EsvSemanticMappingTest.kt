package com.easysavevideo.app.theme

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.easysavevideo.app.save.SaveSelectionPresentation
import com.easysavevideo.app.save.saveSelectionVisuals
import com.easysavevideo.theme.EsvTheme
import dev.skinkit.contract.*
import org.junit.Assert.*
import org.junit.Test

/** Calls real value mappers; no Android rendering, resource I/O or theme provider is added. */
class EsvSemanticMappingTest {
    @Test fun detachedDefaultsMapIntoComposeWithoutPreScalingLogicalSpOrDp() {
        val theme = EsvTheme(VisualConfiguration(ColorMode.DARK, 2.0))
        val v = theme.readSnapshot().toComposeVisuals()
        assertEquals(Color(0xff1c3440), v.colors.elevated)
        assertEquals(20f, v.sectionTextStyle.fontSize.value, 0f)
        assertEquals(28f, v.sectionTextStyle.lineHeight.value, 0f)
        assertEquals(listOf(12f, 16f, 24f), listOf(v.corner.value, v.cardCorner.value, v.sheetCorner.value))
        assertEquals(listOf(4f, 12f, 32f), listOf(v.spacingExtraSmall.value, v.spacingCompact.value, v.spacingExtraLarge.value))
    }
    @Test fun admittedSourceOverridesSurviveSinglePinSnapshotAndComposeMapping() {
        val theme = EsvTheme(); var pins = 0; var closes = 0
        val source = object : PinnedThemeSource by theme.provider {
            override fun pin(): ThemeReadView {
                pins++; val view = theme.provider.pin()
                return object : ThemeReadView by view {
                    override fun getColor(token: ColorToken) = if (token == theme.elevated)
                        ColorValue.argb(0xff294955.toInt()) else view.getColor(token)
                    override fun getTypography(token: TypographyToken) = if (token == theme.section)
                        TypographyValue(FontRef.System(), 23.0, 700, 31.0) else view.getTypography(token)
                    override fun getDimension(token: DimensionToken): DimensionValue {
                        val amount = when (token) { theme.cardCorner -> 13.0; theme.sheetCorner -> 21.0
                            theme.spacingExtraSmall -> 3.0; theme.spacingCompact -> 11.0; theme.spacingExtraLarge -> 29.0; else -> null }
                        return if (amount == null) view.getDimension(token) else DimensionValue(amount, LogicalUnit.LENGTH,
                            if (token == theme.cardCorner || token == theme.sheetCorner) DimensionPurpose.CORNER else DimensionPurpose.SPACING)
                    }
                    override fun release() { closes++; view.release() }
                    override fun close() = release()
                }
            }
        }
        val frame = theme.readSnapshot(source)
        assertEquals(1, pins); assertEquals(1, closes)
        val v = frame.toComposeVisuals()
        assertEquals(frame.snapshot.generation, v.generation)
        assertEquals(Color(0xff294955), v.colors.elevated)
        assertEquals(23f, v.sectionTextStyle.fontSize.value, 0f)
        assertEquals(31f, v.sectionTextStyle.lineHeight.value, 0f)
        assertEquals(listOf(13f, 21f, 3f, 11f, 29f), listOf(v.cardCorner.value, v.sheetCorner.value,
            v.spacingExtraSmall.value, v.spacingCompact.value, v.spacingExtraLarge.value))
        val inline = saveSelectionVisuals(v, SaveSelectionPresentation.INLINE)
        val floating = saveSelectionVisuals(v, SaveSelectionPresentation.FLOATING)
        assertEquals(v.colors.surface, inline.background); assertEquals(13f, inline.corner.value, 0f)
        assertEquals(v.spacingMedium, inline.padding)
        assertEquals(v.colors.elevated, floating.background); assertEquals(21f, floating.corner.value, 0f)
        assertEquals(29f, floating.padding.value, 0f)
        assertEquals(1, pins); assertEquals(1, closes)
    }
    @Test fun materialSlotsUseSemanticValuesRatherThanOneUniversalShape() {
        val v = EsvTheme().readSnapshot().toComposeVisuals()
        val colors = v.toMaterialColors(); val type = v.toMaterialTypography(); val shapes = v.toMaterialShapes()
        assertEquals(v.colors.elevated, colors.surfaceContainer)
        assertEquals(v.colors.elevated, colors.surfaceContainerHigh)
        assertEquals(v.colors.elevated, colors.surfaceContainerHighest)
        assertEquals(v.colors.surface, colors.surface)
        assertEquals(v.sectionTextStyle, type.titleMedium)
        assertEquals(v.titleTextStyle, type.titleLarge)
        assertEquals(12f, shapes.small.topStart.toPx(Size(200f, 200f), Density(1f)), 0f)
        assertEquals(16f, shapes.medium.topStart.toPx(Size(200f, 200f), Density(1f)), 0f)
        assertEquals(24f, shapes.large.topStart.toPx(Size(200f, 200f), Density(1f)), 0f)
    }
    @Test fun standardButtonKeepsItsOwnControlCornerAndExplicitStateVisuals() {
        val v = EsvTheme().readSnapshot().toComposeVisuals()
        assertEquals(12f, v.buttonCorner.value, 0f)
        assertNotEquals(v.cardCorner, v.buttonCorner)
        assertNotEquals(v.sheetCorner, v.buttonCorner)
        assertNotEquals(v.buttonNormal.background, v.buttonDisabled.background)
        assertNotEquals(v.buttonNormal.background, v.buttonFocused.background)
    }
}
